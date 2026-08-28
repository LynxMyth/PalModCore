package com.mx.palmod.entity;

import com.mx.palmod.enchantment.FollowingEnchantment;
import com.mx.palmod.enchantment.InfiniteEnchantment;
import com.mx.palmod.enchantment.LevelingEnchantment;
import com.mx.palmod.registry.ModRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Mob;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class PalSphereProjectile extends ThrowableItemProjectile {

    // ── Enchantment data (set from item before launch) ──────────────────
    private int sphereLevel   = 1;
    private int infiniteLevel  = 0;
    private int followingLevel = 0;
    private int levelingLevel  = 0;
    private int warpTetherLevel = 0;

    // Deploy mode for summoning throws ("" = normal combat summon)
    private String deployMode = "";

    // Following state
    private boolean isHoming   = false; // activates after first block-hit
    private static final int HOMING_MAX_TICKS = 60; // give up after 3s
    private int homingTicks    = 0;

    // Summoning state
    private boolean isSummoning = false;
    private UUID summonSphereUUID = null;

    // ── Capture animation (Pokéball-style: shrink-in → wobble → resolve) ──
    // Synced to clients so the render hook can scale the captured mob; the
    // client reads these off the (auto-synced) projectile.
    private static final EntityDataAccessor<Integer> DATA_CAPTURE_MOB_ID =
            SynchedEntityData.defineId(PalSphereProjectile.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> DATA_CAPTURE_PROGRESS =
            SynchedEntityData.defineId(PalSphereProjectile.class, EntityDataSerializers.FLOAT);

    private static final int SHRINK_TICKS  = 8;   // mob shrinks into the ball
    private static final int RELEASE_TICKS = 8;   // (fail) mob grows back out

    // capturePhase: 0 none, 1 shrinking, 2 wobbling, 3 releasing (fail)
    private int capturePhase = 0;
    private int captureTicks = 0;
    private int wobbleTicksTotal = 0;      // 0.3s per 10% catch chance
    private int nextWobbleAt = 0;
    private boolean captureSuccess = false;
    private int captureEffectiveLevel = 1;
    private int captureMobId = -1;
    private boolean wasTargetNoAi = false;
    private double baseX, baseY, baseZ;
    private double hopOffset = 0, hopVel = 0;
    // Set on load if the world saved mid-capture — the ball resolves to a harmless
    // drop next tick (the frozen mob is recovered by the ForgeEvents safety net).
    private boolean captureGhost = false;

    // ── Constructors ─────────────────────────────────────────────────────

    public PalSphereProjectile(EntityType<? extends ThrowableItemProjectile> pEntityType, Level pLevel) {
        super(pEntityType, pLevel);
    }

    public PalSphereProjectile(Level pLevel, LivingEntity pShooter) {
        super(ModRegistries.PAL_SPHERE_PROJECTILE.get(), pShooter, pLevel);
    }

    public PalSphereProjectile(Level pLevel, double pX, double pY, double pZ) {
        super(ModRegistries.PAL_SPHERE_PROJECTILE.get(), pX, pY, pZ, pLevel);
    }

    @Override
    protected Item getDefaultItem() {
        return ModRegistries.PAL_SPHERE.get();
    }

    // ── Setup from item ───────────────────────────────────────────────────

    public void setSphereLevel(int level) {
        this.sphereLevel = level;
    }

    public int getWarpTetherLevel() {
        return warpTetherLevel;
    }

    /** Read enchantment levels from the thrown item stack. */
    public void setEnchantments(ItemStack stack) {
        this.infiniteLevel  = EnchantmentHelper.getItemEnchantmentLevel(ModRegistries.ENCHANT_INFINITE.get(), stack);
        this.followingLevel = EnchantmentHelper.getItemEnchantmentLevel(ModRegistries.ENCHANT_FOLLOWING.get(), stack);
        this.levelingLevel  = EnchantmentHelper.getItemEnchantmentLevel(ModRegistries.ENCHANT_LEVELING.get(), stack);
        this.warpTetherLevel = EnchantmentHelper.getItemEnchantmentLevel(ModRegistries.ENCHANT_WARP_TETHER.get(), stack);
    }

    public void setSummoning(boolean isSummoning, UUID sphereUUID) {
        this.isSummoning = isSummoning;
        this.summonSphereUUID = sphereUUID;
    }

    /** Special deploy mode for this summoning throw ("anchor"/"sentry"; "" = combat). */
    public void setDeployMode(String deployMode) {
        this.deployMode = deployMode == null ? "" : deployMode;
    }

    // ── NBT persistence (so enchant data survives chunk reload) ─────────

    @Override
    public void addAdditionalSaveData(CompoundTag pTag) {
        super.addAdditionalSaveData(pTag);
        pTag.putInt("SphereLevel",    sphereLevel);
        pTag.putInt("InfiniteLevel",  infiniteLevel);
        pTag.putInt("FollowingLevel", followingLevel);
        pTag.putInt("LevelingLevel",  levelingLevel);
        pTag.putInt("WarpTetherLevel", warpTetherLevel);
        pTag.putString("DeployMode",  deployMode);
        pTag.putBoolean("IsHoming",   isHoming);
        pTag.putInt("HomingTicks",    homingTicks);
        pTag.putBoolean("IsSummoning", isSummoning);
        if (summonSphereUUID != null) {
            pTag.putUUID("SummonSphereUUID", summonSphereUUID);
        }
        // A capture in progress can't be meaningfully resumed across a reload
        // (entity ids aren't stable); mark it so tick() cleans up the ghost.
        if (capturePhase != 0) {
            pTag.putBoolean("PalCaptureGhost", true);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag pTag) {
        super.readAdditionalSaveData(pTag);
        sphereLevel   = pTag.getInt("SphereLevel");
        infiniteLevel  = pTag.getInt("InfiniteLevel");
        followingLevel = pTag.getInt("FollowingLevel");
        levelingLevel  = pTag.getInt("LevelingLevel");
        warpTetherLevel = pTag.getInt("WarpTetherLevel");
        deployMode     = pTag.getString("DeployMode");
        isHoming       = pTag.getBoolean("IsHoming");
        homingTicks    = pTag.getInt("HomingTicks");
        isSummoning    = pTag.getBoolean("IsSummoning");
        if (pTag.contains("SummonSphereUUID")) {
            summonSphereUUID = pTag.getUUID("SummonSphereUUID");
        }
        captureGhost = pTag.getBoolean("PalCaptureGhost");
    }

    // ── Synced capture state (read client-side by the render hook) ────────

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_CAPTURE_MOB_ID, -1);
        this.entityData.define(DATA_CAPTURE_PROGRESS, 0.0f);
    }

    /** Entity id of the mob currently shrinking into this ball, or -1. */
    public int getCaptureMobId() { return this.entityData.get(DATA_CAPTURE_MOB_ID); }

    /** 0 = full size, 1 = fully inside the ball. Client scale = 1 − this. */
    public float getCaptureProgress() { return this.entityData.get(DATA_CAPTURE_PROGRESS); }

    // ── Tick (Following homing logic) ─────────────────────────────────────

    @Override
    public void tick() {
        // Ghost left by a save mid-capture: the frozen mob is recovered by the
        // ForgeEvents safety net; just drop the (empty) sphere and remove the ball.
        if (captureGhost) {
            captureGhost = false;
            if (!level().isClientSide) {
                ItemStack s = getItem().copy();
                s.setCount(1);
                dropItem(s, this);
                discard();
            }
            return;
        }

        // Once a mob is being captured the ball freezes in place and runs the
        // capture animation instead of normal projectile physics (both sides —
        // the client reads the synced mob id so it stops flying too).
        if (capturePhase != 0 || this.entityData.get(DATA_CAPTURE_MOB_ID) >= 0) {
            tickCapture();
            return;
        }

        super.tick();

        // A capture that just BEGAN inside super.tick() (onHitEntity → beginCapture)
        // must not also run ZW-intercept/homing this tick — that could discard the
        // ball and strand the frozen mob.
        if (capturePhase != 0) return;

        // A wild time-stopper (ZA WARUDO) freezes catching spheres in its radius
        if (!level().isClientSide && !isSummoning && (tickCount & 1) == 0
                && com.mx.palmod.pal.WildCatchManager.tryZaWarudoIntercept(this)) {
            return;
        }

        if (!level().isClientSide && isHoming && followingLevel > 0) {
            homingTicks++;
            if (homingTicks > HOMING_MAX_TICKS) {
                discard();
                return;
            }

            double radius = FollowingEnchantment.getHomingRadius(followingLevel);
            LivingEntity nearest = findNearestTarget(radius);
            if (nearest != null) {
                // Steer towards the nearest target
                Vec3 toTarget = nearest.position().add(0, nearest.getBbHeight() * 0.5, 0)
                        .subtract(this.position()).normalize().scale(0.5);
                this.setDeltaMovement(this.getDeltaMovement().add(toTarget));
            } else {
                // Nothing in range — remove after timeout
                if (homingTicks > 20) discard();
            }
        }
    }

    private LivingEntity findNearestTarget(double radius) {
        AABB box = this.getBoundingBox().inflate(radius);
        // Never home onto mobs the sphere would be wasted on
        List<LivingEntity> candidates = level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != getOwner() && !(e instanceof Player) && !e.getPersistentData().contains("PalOwner")
                        && !com.mx.palmod.pal.WildCatchManager.isUncatchable(e)
                        && !e.getPersistentData().getBoolean(com.mx.palmod.pal.WildCatchManager.KEY_HIDDEN));
        return candidates.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(this)))
                .orElse(null);
    }

    // ── Hit: Entity ───────────────────────────────────────────────────────

    @Override
    protected void onHitEntity(EntityHitResult pResult) {
        if (isSummoning) {
            handleSummoning(pResult.getLocation());
            return;
        }
        super.onHitEntity(pResult);
        if (this.level().isClientSide || !(pResult.getEntity() instanceof LivingEntity target)) return;
        if (target instanceof Player) return;

        // Villagers (and wandering traders) can never be caught
        if (target instanceof net.minecraft.world.entity.npc.Npc) {
            com.mx.palmod.pal.WildCatchManager.message(getOwner(), "Villagers cannot be caught!");
            spawnSmokeAt(target);
            discardOrReturn(false);
            return;
        }

        if (target.getPersistentData().contains("PalOwner")) {
            // Already tamed — can't catch
            spawnSmokeAt(target);
            discardOrReturn(false);
            return;
        }

        // Already shrinking inside another ball — a second sphere just bounces off.
        if (target.getPersistentData().getBoolean("PalCapturing")) {
            spawnSmokeAt(target);
            discardOrReturn(false);
            return;
        }

        // ── Wild-catch gating ────────────────────────────────────────────
        // Hidden predators can't be tagged — the sphere passes through empty air
        // (a miss, so the Infinite enchant may still return it)
        if (target.getPersistentData().getBoolean(com.mx.palmod.pal.WildCatchManager.KEY_HIDDEN)) {
            com.mx.palmod.pal.WildCatchManager.message(getOwner(), "It slipped away before the sphere landed...");
            spawnSmokeAt(target);
            discardOrReturn(false);
            return;
        }
        // Only mobs that rolled catchable (enchanted shimmer) can be caught;
        // a sphere wasted on anything else is simply lost — no Infinite return.
        if (com.mx.palmod.pal.WildCatchManager.isUncatchable(target)) {
            com.mx.palmod.pal.WildCatchManager.message(getOwner(), "No enchanted shimmer — this one can't be caught!");
            spawnSmokeAt(target);
            this.discard();
            return;
        }
        // Wild mobs answer a direct sphere hit with their signature defense move —
        // unless a pal's time stop has them frozen (ZA WARUDO beats ZA WARUDO)
        com.mx.palmod.behavior.PalBehavior wildBehavior =
                com.mx.palmod.behavior.PalBehaviorManager.getBehavior(target.getType());
        if (target instanceof net.minecraft.world.entity.Mob targetMob
                && com.mx.palmod.pal.WildCatchManager.hasWildPower(target)
                && !com.mx.palmod.timestop.TimeStopManager.isFrozen(target)
                && this.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            com.mx.palmod.pal.WildDefenseRegistry.WildDefense defense =
                    com.mx.palmod.pal.WildDefenseRegistry.get(wildBehavior.getWildType());
            if (defense != null && defense.onSphereHit(targetMob, wildBehavior, this, serverLevel)) {
                discardOrReturn(false);
                return;
            }
        }

        // ── Fraction-based catch curve ──────────────────────────────────────
        // catchChance = lowHpMaxRate · weaken^p · catchRateMultiplier
        //   hpFrac    = current / ACTUAL max  (what the player sees on the health bar)
        //   weaken    = clamp((1 − hpFrac) / (1 − fullRateFrac), 0, 1)  → 1 at low HP
        //   p         = (1 + K·log2(baseMaxHealth)) / ballPower
        //   ballPower = 1 + perLevel·(effectiveLevel − 1)
        // A tankier mob (bigger baseMaxHealth) gets a larger p, so weaken^p is strictly
        // smaller at every mid-HP point → always harder — yet every mob converges to
        // lowHpMaxRate at/below fullRateFrac and to ~0 at full HP, and nothing is ever a
        // hard 0% once weakened. baseMaxHealth is the wild-toughness-STRIPPED health so
        // the 4.5x fight buff never compounds catch difficulty; the fraction uses the
        // ACTUAL (possibly buffed) max so "under ~5% of the health bar" reads correctly.
        float baseMaxHealth   = com.mx.palmod.pal.WildCatchManager.getCatchMaxHealth(target);
        float actualMaxHealth = target.getMaxHealth();
        float currentHealth   = target.getHealth();
        float hpFrac = actualMaxHealth > 0f
                ? Math.max(0f, Math.min(1f, currentHealth / actualMaxHealth)) : 0f;

        // Effective level including Leveling enchant bonus
        int effectiveLevel = sphereLevel + LevelingEnchantment.getLevelBonus(levelingLevel);

        double log2MaxHp = Math.log(Math.max(2f, baseMaxHealth)) / Math.log(2.0);
        double pRaw = 1.0 + com.mx.palmod.Config.catchToughnessWeight * log2MaxHp;
        double ballPower = 1.0 + com.mx.palmod.Config.catchBallPowerPerLevel * (effectiveLevel - 1);
        double p = pRaw / Math.max(1.0e-4, ballPower);

        double fullRateFrac = com.mx.palmod.Config.catchFullRateHpFraction;
        double weaken = (1.0 - hpFrac) / Math.max(1.0e-4, 1.0 - fullRateFrac);
        weaken = Math.max(0.0, Math.min(1.0, weaken));

        double chance = com.mx.palmod.Config.catchLowHpMaxRate
                * Math.pow(weaken, p)
                * com.mx.palmod.Config.catchRateMultiplier;
        float catchChance = (float) Math.max(0.0, Math.min(1.0, chance));

        boolean catchRolled = this.random.nextFloat() <= catchChance;
        boolean catchVetoed = catchRolled && getOwner() instanceof Player catcher
                && net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                        new com.mx.palmod.api.event.PalCaughtEvent(target, catcher.getUUID(), getItem(), effectiveLevel));

        // The outcome is decided now but REVEALED after the shrink-in + wobble
        // animation (0.3s of wobble per 10% catch chance). Success → the mob is
        // stored and a filled sphere pops out; failure → the mob grows back and
        // the empty sphere drops (see resolveCapture*).
        beginCapture(target, catchChance, catchRolled && !catchVetoed, effectiveLevel);
    }

    // ── Capture animation ─────────────────────────────────────────────────

    /** Suck the mob into the ball and start the wobble timer. */
    private void beginCapture(LivingEntity target, float catchChance, boolean success, int effectiveLevel) {
        this.captureMobId = target.getId();
        this.captureSuccess = success;
        this.captureEffectiveLevel = effectiveLevel;
        this.wobbleTicksTotal = Math.max(6, Math.round(catchChance * 60f)); // 6 ticks (0.3s) per 10%
        this.capturePhase = 1;
        this.captureTicks = 0;
        // Anchor the ball AT the mob (not the throw origin — the projectile hits on
        // its first tick before its own position updates), so the wobble and the
        // filled sphere happen where the mob was, not at the player's feet.
        this.baseX = target.getX();
        this.baseY = target.getY();
        this.baseZ = target.getZ();
        this.setPos(baseX, baseY, baseZ);
        this.setDeltaMovement(Vec3.ZERO);
        this.setNoGravity(true);
        this.entityData.set(DATA_CAPTURE_MOB_ID, target.getId());
        this.entityData.set(DATA_CAPTURE_PROGRESS, 0f);

        if (target instanceof Mob mob) {
            this.wasTargetNoAi = mob.isNoAi();
            mob.setNoAi(true);
            mob.getNavigation().stop();
        }
        target.setDeltaMovement(Vec3.ZERO);
        // Frozen + shielded from damage while inside the ball (see ForgeEvents).
        target.getPersistentData().putBoolean("PalCapturing", true);
        target.getPersistentData().putLong("PalCaptureUntil",
                level().getGameTime() + SHRINK_TICKS + wobbleTicksTotal + RELEASE_TICKS + 40L);

        if (level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.PORTAL,
                    target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
                    24, 0.3, 0.4, 0.3, 0.4);
            sl.playSound(null, baseX, baseY, baseZ,
                    SoundEvents.ENDER_EYE_LAUNCH, SoundSource.NEUTRAL, 0.7F, 1.4F);
        }
    }

    private void tickCapture() {
        // Client side: just hold position (server-synced) and let the render hook
        // read the synced progress — no server logic here.
        if (level().isClientSide) return;

        LivingEntity target = (captureMobId >= 0 && level().getEntity(captureMobId) instanceof LivingEntity le)
                ? le : null;

        // Keep the captured mob pinned; if it vanished (e.g. /kill), the catch fails.
        if (capturePhase != 3) {
            if (target == null || !target.isAlive()) {
                resolveFailure(null);
                return;
            }
            target.setDeltaMovement(Vec3.ZERO);
            if (target instanceof Mob m) m.getNavigation().stop();
        }

        captureTicks++;
        switch (capturePhase) {
            case 1 -> { // shrinking in
                this.entityData.set(DATA_CAPTURE_PROGRESS, Math.min(1f, (float) captureTicks / SHRINK_TICKS));
                if (captureTicks >= SHRINK_TICKS) {
                    this.entityData.set(DATA_CAPTURE_PROGRESS, 1f);
                    capturePhase = 2;
                    captureTicks = 0;
                    nextWobbleAt = 8;
                }
            }
            case 2 -> { // wobbling (struggle)
                if (captureTicks >= nextWobbleAt) {
                    nextWobbleAt = captureTicks + 14;
                    doWobble();
                }
                if (captureTicks >= wobbleTicksTotal) {
                    if (captureSuccess) { resolveSuccess(target); return; }
                    capturePhase = 3;
                    captureTicks = 0;
                }
            }
            case 3 -> { // releasing (fail): mob grows back out
                this.entityData.set(DATA_CAPTURE_PROGRESS, Math.max(0f, 1f - (float) captureTicks / RELEASE_TICKS));
                if (captureTicks >= RELEASE_TICKS) { resolveFailure(target); return; }
            }
        }

        // Bounce/hop offset so the ball visibly wobbles on the ground.
        hopOffset += hopVel;
        hopVel -= 0.06;
        if (hopOffset <= 0) { hopOffset = 0; hopVel = 0; }
        this.setPos(baseX, baseY + hopOffset, baseZ);
    }

    private void doWobble() {
        hopVel = 0.22;
        if (level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.CRIT, baseX, baseY + 0.2, baseZ, 5, 0.15, 0.1, 0.15, 0.05);
            sl.playSound(null, baseX, baseY, baseZ,
                    SoundEvents.WOODEN_BUTTON_CLICK_ON, SoundSource.NEUTRAL, 0.8F, 0.7F);
        }
    }

    /** Timer done, catch succeeded: store the mob and pop the filled sphere out. */
    private void resolveSuccess(LivingEntity target) {
        this.entityData.set(DATA_CAPTURE_MOB_ID, -1);
        if (target != null) {
            target.getPersistentData().remove("PalCapturing");
            target.getPersistentData().remove("PalCaptureUntil");
            if (target instanceof Mob m) m.setNoAi(wasTargetNoAi);
            // The caught pal keeps its full wild stats — heal it to its (buffed) max
            // so its max HP equals the mob's and it comes out at full health.
            target.setHealth(target.getMaxHealth());
            CompoundTag entityData = new CompoundTag();
            if (target.saveAsPassenger(entityData)) {
                ItemStack filledSphere = new ItemStack(ModRegistries.FILLED_PAL_SPHERE.get());
                CompoundTag tag = filledSphere.getOrCreateTag();
                tag.put("CapturedEntity", entityData);
                tag.putUUID("SphereUUID", UUID.randomUUID());
                tag.putBoolean("IsReleased", false);
                tag.putInt("SphereLevel", captureEffectiveLevel);
                if (warpTetherLevel > 0) tag.putBoolean("WarpOnRecall", true);

                ItemEntity ie = new ItemEntity(level(), baseX, baseY + 0.3, baseZ, filledSphere);
                ie.setDeltaMovement(0, 0.35, 0); // the ball "pops up" then the item drops
                ie.setPickUpDelay(10);
                level().addFreshEntity(ie);
                target.discard();

                // Infinite: the thrown ball itself is returned (100%) on a successful
                // catch, so it can be reused — the pal is in the filled sphere, the
                // empty ball comes back to the catcher.
                if (infiniteLevel > 0 && getOwner() instanceof Player player) {
                    ItemStack ball = getItem().copy();
                    ball.setCount(1);
                    if (!player.getInventory().add(ball)) {
                        level().addFreshEntity(new ItemEntity(level(), baseX, baseY + 0.3, baseZ, ball));
                    }
                }
            }
        }
        if (level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.HAPPY_VILLAGER, baseX, baseY + 0.5, baseZ, 20, 0.4, 0.4, 0.4, 0.15);
            sl.sendParticles(ParticleTypes.END_ROD, baseX, baseY + 0.5, baseZ, 12, 0.2, 0.3, 0.2, 0.08);
            sl.playSound(null, baseX, baseY, baseZ, SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 1.0F, 1.2F);
        }
        capturePhase = 0;
        this.discard();
    }

    /** Timer done, catch failed: mob is already grown back; drop the empty sphere. */
    private void resolveFailure(LivingEntity target) {
        this.entityData.set(DATA_CAPTURE_MOB_ID, -1);
        if (target != null) {
            target.getPersistentData().remove("PalCapturing");
            target.getPersistentData().remove("PalCaptureUntil");
            if (target instanceof Mob m) m.setNoAi(wasTargetNoAi);
        }
        if (level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.SMOKE, baseX, baseY + 0.4, baseZ, 12, 0.3, 0.3, 0.3, 0.02);
            sl.playSound(null, baseX, baseY, baseZ, SoundEvents.GLASS_BREAK, SoundSource.NEUTRAL, 0.9F, 1.0F);
        }
        // Empty sphere drops to the ground; 50% chance it's destroyed (5% with Infinite).
        float destroyChance = infiniteLevel > 0 ? 0.05F : 0.5F;
        if (this.random.nextFloat() >= destroyChance) {
            ItemStack empty = getItem().copy();
            empty.setCount(1);
            ItemEntity ie = new ItemEntity(level(), baseX, baseY + 0.2, baseZ, empty);
            ie.setDeltaMovement(0, 0.2, 0);
            ie.setPickUpDelay(10);
            level().addFreshEntity(ie);
        }
        capturePhase = 0;
        this.discard();
    }

    // ── Hit: Block ────────────────────────────────────────────────────────

    @Override
    protected void onHit(HitResult pResult) {
        if (isSummoning) {
            handleSummoning(pResult.getLocation());
            return;
        }
        // Dispatch manually instead of super.onHit(): ThrowableProjectile.onHit()
        // force-discards the projectile right after the hit, which would kill a
        // capture the instant it begins. onHitEntity fully owns the entity path —
        // it either discards on a miss or starts a capture that keeps the ball alive.
        HitResult.Type type = pResult.getType();
        if (type == HitResult.Type.ENTITY) {
            this.onHitEntity((EntityHitResult) pResult);
            return;
        }
        if (this.level().isClientSide) return;
        if (type == HitResult.Type.BLOCK) {
            if (followingLevel > 0 && !isHoming) {
                // Activate homing mode instead of discarding
                isHoming = true;
                homingTicks = 0;
                // Slow down the sphere so it can steer
                this.setDeltaMovement(this.getDeltaMovement().scale(0.3));
                return; // don't discard yet
            }
            if (this.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.ITEM_SNOWBALL,
                        this.getX(), this.getY(), this.getZ(), 8, 0.0, 0.0, 0.0, 0.0);
            }
            discardOrReturn(false);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Discard the sphere, or return it to the owner if Infinite enchant fires. */
    private void discardOrReturn(boolean wasCatchFail) {
        if (infiniteLevel > 0) {
            float returnChance = InfiniteEnchantment.getReturnChance(infiniteLevel);
            if (this.random.nextFloat() <= returnChance) {
                returnSphereToOwner();
                return;
            }
        }
        this.discard();
    }

    private void returnSphereToOwner() {
        if (getOwner() instanceof Player player) {
            ItemStack sphere = getItem();
            if (!sphere.isEmpty()) {
                ItemStack toReturn = sphere.copy();
                toReturn.setCount(1);
                if (!player.getInventory().add(toReturn)) {
                    dropItem(toReturn, player);
                }
                if (this.level() instanceof ServerLevel sl) {
                    sl.sendParticles(ParticleTypes.ENCHANT,
                            this.getX(), this.getY(), this.getZ(), 8, 0.3, 0.3, 0.3, 0.05);
                }
            }
        }
        this.discard();
    }

    private void spawnSmokeAt(LivingEntity target) {
        if (this.level() instanceof ServerLevel sl) {
            sl.sendParticles(ParticleTypes.SMOKE,
                    target.getX(), target.getY() + 1, target.getZ(), 10, 0.5, 0.5, 0.5, 0.1);
            sl.playSound(null, target.getX(), target.getY(), target.getZ(),
                    SoundEvents.GLASS_BREAK, SoundSource.NEUTRAL, 1.0F, 1.0F);
        }
    }

    private void dropItem(ItemStack stack, net.minecraft.world.entity.Entity near) {
        ItemEntity ie = new ItemEntity(level(), near.getX(), near.getY(), near.getZ(), stack);
        level().addFreshEntity(ie);
    }

    private void handleSummoning(Vec3 location) {
        if (this.level().isClientSide || !(this.level() instanceof ServerLevel serverLevel)) return;
        if (!(getOwner() instanceof Player player)) {
            this.discard();
            return;
        }
        
        ItemStack filledSphere = null;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.getItem() == ModRegistries.FILLED_PAL_SPHERE.get()) {
                CompoundTag tag = stack.getTag();
                if (tag != null && tag.contains("SphereUUID") && tag.getUUID("SphereUUID").equals(summonSphereUUID)) {
                    filledSphere = stack;
                    break;
                }
            }
        }
        if (filledSphere == null) {
            ItemStack offhand = player.getOffhandItem();
            if (offhand.getItem() == ModRegistries.FILLED_PAL_SPHERE.get()) {
                CompoundTag tag = offhand.getTag();
                if (tag != null && tag.contains("SphereUUID") && tag.getUUID("SphereUUID").equals(summonSphereUUID)) {
                    filledSphere = offhand;
                }
            }
        }
        
        if (filledSphere != null) {
            CompoundTag tag = filledSphere.getTag();
            // The throw landed — release the anti-double-summon lock
            tag.remove("SummonLockUntil");
            CompoundTag entityData = tag.getCompound("CapturedEntity");
            java.util.Optional<EntityType<?>> optionalType = EntityType.by(entityData);
            if (optionalType.isPresent()) {
                EntityType<?> entityType = optionalType.get();
                com.mx.palmod.behavior.PalBehavior summonBehavior =
                        com.mx.palmod.behavior.PalBehaviorManager.getBehavior(entityType);
                // A registered power (warp_beacon built in) may root a plain summon
                // into a deploy mode of its own (e.g. anchored as a waystone).
                if (deployMode.isEmpty()) {
                    for (com.mx.palmod.pal.PalAbilityRegistry.PalAbility ability
                            : com.mx.palmod.pal.PalAbilityRegistry.applicable(summonBehavior)) {
                        java.util.Optional<String> forced = ability.forcedDeployMode(summonBehavior);
                        if (forced.isPresent()) {
                            deployMode = forced.get();
                            break;
                        }
                    }
                }
                net.minecraft.world.entity.Entity entity = entityType.create(serverLevel);
                if (entity instanceof LivingEntity livingEntity) {
                    // Scrub Alex's Mobs hive-AI state: EntityLeafcutterAnt's flagless
                    // ReturnToHiveGoal fires on hasLeaf()/HivePos and walks the pal
                    // into an anthill (deleting it from the world) — an ant caught
                    // mid-forage would abandon its owner and vanish.
                    entityData = entityData.copy();
                    entityData.remove("Leaf");
                    entityData.remove("HivePos");
                    entityData.remove("CannotEnterHiveTicks");
                    livingEntity.load(entityData);
                    livingEntity.setUUID(UUID.randomUUID());
                    // Legacy: a pre-0.9.3 station pal still carries WorkStationPos in
                    // its saved ForgeData — stale here, and it would get the pal
                    // auto-discarded by the orphan check
                    livingEntity.getPersistentData().remove("WorkStationPos");
                    // A worker re-anchors its work area wherever it is thrown
                    livingEntity.getPersistentData().remove("PalWorkHome");
                    livingEntity.setYRot(player.getYRot());
                    // Impact points sit on block faces — find a collision-free spot
                    com.mx.palmod.pal.SafeSpawn.place(serverLevel, livingEntity, location, player);
                    if (livingEntity.getCustomName() == null) {
                        livingEntity.setCustomName(entityType.getDescription().copy());
                    }
                    livingEntity.setCustomNameVisible(true);
                    livingEntity.getPersistentData().putUUID("PalOwner", player.getUUID());
                    livingEntity.getPersistentData().putUUID("SphereUUID", summonSphereUUID);
                    if (!deployMode.isEmpty()) {
                        // Special deployment: the pal roots where it landed. AnchorPos
                        // is also stored on the sphere so recall-warp works even when
                        // the pal's chunk is unloaded.
                        long anchorPos = livingEntity.blockPosition().asLong();
                        livingEntity.getPersistentData().putString("DeployMode", deployMode);
                        livingEntity.getPersistentData().putLong("AnchorPos", anchorPos);
                        if ("anchor".equals(deployMode)) {
                            livingEntity.getPersistentData().putBoolean("PalSitting", true);
                        }
                        tag.putString("DeployMode", deployMode);
                        tag.putLong("AnchorPos", anchorPos);
                        tag.putString("AnchorDim", serverLevel.dimension().location().toString());
                    } else {
                        // Normal summon: scrub any deployment state left over from a
                        // previous anchor/sentry cycle (it rides along in ForgeData)
                        livingEntity.getPersistentData().remove("DeployMode");
                        livingEntity.getPersistentData().remove("AnchorPos");
                        livingEntity.getPersistentData().putBoolean("PalSitting", false);
                        tag.remove("DeployMode");
                        tag.remove("AnchorPos");
                        tag.remove("AnchorDim");
                    }
                    if (livingEntity instanceof net.minecraft.world.entity.Mob m) {
                        m.setPersistenceRequired();
                    }
                    serverLevel.addFreshEntity(livingEntity);
                    tag.putBoolean("IsReleased", true);
                    tag.putUUID("EntityUUID", livingEntity.getUUID());

                    if (summonBehavior.isStationMode()) {
                        // Worker pals take their sphere with them: the item leaves
                        // the player's inventory (creative included — keeping a
                        // usable copy would duplicate the pal) and only comes back
                        // by right-clicking the pal. That is what exempts them from
                        // the orphan rule so a base keeps running unattended.
                        com.mx.palmod.item.FilledPalSphereItem.attachCarriedSphere(livingEntity, tag);
                        filledSphere.shrink(1);
                    }

                    // Summon-time powers (warp_beacon, time_stop.on_summon, and
                    // anything a third-party mod registers) react to the fresh summon
                    if (livingEntity instanceof net.minecraft.world.entity.Mob summonedPal
                            && player instanceof net.minecraft.server.level.ServerPlayer serverOwner) {
                        for (com.mx.palmod.pal.PalAbilityRegistry.PalAbility ability
                                : com.mx.palmod.pal.PalAbilityRegistry.applicable(summonBehavior)) {
                            ability.onSummon(summonedPal, summonBehavior, serverOwner, serverLevel, tag);
                        }
                    }

                    serverLevel.sendParticles(ParticleTypes.POOF, location.x, location.y + 1, location.z, 15, 0.5, 0.5, 0.5, 0.1);
                    serverLevel.playSound(null, location.x, location.y, location.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 1.0F, 1.0F);

                    net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                            new com.mx.palmod.api.event.PalSummonedEvent(livingEntity, player.getUUID()));
                    if (!deployMode.isEmpty()) {
                        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                                new com.mx.palmod.api.event.PalDeployedEvent(livingEntity, player.getUUID(), deployMode));
                    }
                }
            }
        }
        this.discard();
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}

