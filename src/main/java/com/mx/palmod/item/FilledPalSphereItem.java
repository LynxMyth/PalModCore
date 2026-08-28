package com.mx.palmod.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import com.mx.palmod.registry.ModRegistries;

import java.util.Optional;
import java.util.UUID;

public class FilledPalSphereItem extends Item {

    /** Ticks a summoning throw stays "in flight" — blocks re-throws of the same sphere. */
    private static final int SUMMON_LOCK_TICKS = 60;

    public FilledPalSphereItem(Properties pProperties) {
        super(pProperties);
    }

    /** Hotbar popup / inventory name shows the captured mob and whether it's out. */
    @Override
    public net.minecraft.network.chat.Component getName(ItemStack pStack) {
        CompoundTag tag = pStack.getTag();
        if (tag != null && tag.contains("CapturedEntity")) {
            CompoundTag entityData = tag.getCompound("CapturedEntity");
            net.minecraft.network.chat.Component mobName = null;
            if (entityData.contains("CustomName", 8)) {
                try {
                    mobName = net.minecraft.network.chat.Component.Serializer
                            .fromJson(entityData.getString("CustomName"));
                } catch (Exception ignored) {
                }
            }
            if (mobName == null) {
                Optional<EntityType<?>> type = EntityType.by(entityData);
                if (type.isPresent()) {
                    mobName = type.get().getDescription();
                }
            }
            if (mobName != null) {
                boolean released = tag.getBoolean("IsReleased");
                return net.minecraft.network.chat.Component.translatable(
                        released ? "item.palmod.filled_pal_sphere.released"
                                 : "item.palmod.filled_pal_sphere.inside", mobName)
                        .withStyle(released ? net.minecraft.ChatFormatting.GRAY
                                            : net.minecraft.ChatFormatting.AQUA);
            }
        }
        return super.getName(pStack);
    }

    /** Glint only while the pal is INSIDE the sphere (third state: released = no glint). */
    @Override
    public boolean isFoil(ItemStack pStack) {
        CompoundTag tag = pStack.getTag();
        return tag != null && tag.contains("CapturedEntity") && !tag.getBoolean("IsReleased");
    }

    @Override
    public void appendHoverText(ItemStack pStack, @org.jetbrains.annotations.Nullable Level pLevel, java.util.List<net.minecraft.network.chat.Component> pTooltipComponents, net.minecraft.world.item.TooltipFlag pIsAdvanced) {
        super.appendHoverText(pStack, pLevel, pTooltipComponents, pIsAdvanced);
        CompoundTag tag = pStack.getTag();
        if (tag != null) {
            boolean isReleased = tag.getBoolean("IsReleased");
            pTooltipComponents.add(net.minecraft.network.chat.Component.literal(isReleased ? "[Status: Released]" : "[Status: Inside]").withStyle(isReleased ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.YELLOW));
        }
        if (tag != null && tag.contains("CapturedEntity")) {
            CompoundTag entityData = tag.getCompound("CapturedEntity");
            Optional<EntityType<?>> optionalType = EntityType.by(entityData);
            if (optionalType.isPresent()) {
                EntityType<?> type = optionalType.get();
                pTooltipComponents.add(net.minecraft.network.chat.Component.translatable("tooltip.palmod.captured_entity", type.getDescription()).withStyle(net.minecraft.ChatFormatting.AQUA));
                
                if (entityData.contains("CustomName", 8)) {
                    String customNameJson = entityData.getString("CustomName");
                    try {
                        net.minecraft.network.chat.Component customName = net.minecraft.network.chat.Component.Serializer.fromJson(customNameJson);
                        if (customName != null) {
                            pTooltipComponents.add(net.minecraft.network.chat.Component.translatable("tooltip.palmod.custom_name", customName).withStyle(net.minecraft.ChatFormatting.ITALIC, net.minecraft.ChatFormatting.GRAY));
                        }
                    } catch (Exception e) {
                        // ignore
                    }
                }
                
                if (entityData.contains("Health", 99)) {
                    float health = entityData.getFloat("Health");
                    pTooltipComponents.add(net.minecraft.network.chat.Component.translatable("tooltip.palmod.health", String.format("%.1f", health)).withStyle(net.minecraft.ChatFormatting.RED));
                }

                // Show hunger & mood from PersistentData embedded inside entity NBT
                if (entityData.contains("ForgeCaps") || entityData.contains("ForgeData")) {
                    CompoundTag forgeData = entityData.contains("ForgeData") ? entityData.getCompound("ForgeData") : entityData.getCompound("ForgeCaps");
                    if (forgeData.contains("PalHunger")) {
                        float hunger = forgeData.getFloat("PalHunger");
                        net.minecraft.ChatFormatting hungerColor = hunger < 5 ? net.minecraft.ChatFormatting.DARK_RED
                                : hunger < 30 ? net.minecraft.ChatFormatting.RED : hunger < 80 ? net.minecraft.ChatFormatting.YELLOW : net.minecraft.ChatFormatting.GREEN;
                        pTooltipComponents.add(net.minecraft.network.chat.Component.literal(String.format("Hunger: %.0f%%", hunger)).withStyle(hungerColor));
                    }
                    if (forgeData.contains("PalMood")) {
                        float mood = forgeData.getFloat("PalMood");
                        net.minecraft.ChatFormatting moodColor = mood < 20 ? net.minecraft.ChatFormatting.DARK_RED : mood < 50 ? net.minecraft.ChatFormatting.YELLOW : net.minecraft.ChatFormatting.AQUA;
                        pTooltipComponents.add(net.minecraft.network.chat.Component.literal(String.format("Mood: %.0f%%", mood)).withStyle(moodColor));
                    }
                }
            }
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext pContext) {
        Level level = pContext.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }

        ItemStack itemStack = pContext.getItemInHand();
        CompoundTag tag = itemStack.getTag();
        if (tag != null && tag.contains("CapturedEntity")) {
            if (tag.getBoolean("IsReleased")) {
                // Released sphere click on a block = recall (+ warp for beacons).
                // Time stop is no longer a manual sneak trigger — it fires on
                // summon, so the player picks the moment by throwing the sphere.
                Player p = pContext.getPlayer();
                recallMob(itemStack, serverLevel,
                        p instanceof net.minecraft.server.level.ServerPlayer sp ? sp : null);
                return InteractionResult.SUCCESS;
            }

            // Inside-pal: summon-throw right here. A modded client
            // consumes the click in its own useOn, so the server-side use()
            // fall-through this used to rely on never happens there.
            if (pContext.getPlayer() != null) {
                throwSummon(serverLevel, pContext.getPlayer(), itemStack);
                return InteractionResult.SUCCESS;
            }
        }

        return InteractionResult.PASS; // Allow use() to handle throwing
    }

    @Override
    public net.minecraft.world.InteractionResultHolder<ItemStack> use(Level pLevel, Player pPlayer, net.minecraft.world.InteractionHand pUsedHand) {
        ItemStack itemStack = pPlayer.getItemInHand(pUsedHand);
        CompoundTag tag = itemStack.getTag();
        if (tag != null && tag.contains("CapturedEntity")) {
            if (tag.getBoolean("IsReleased")) {
                if (!pLevel.isClientSide && pLevel instanceof ServerLevel serverLevel) {
                    // Right-click a released sphere = recall (+ warp for beacons).
                    // Time stop now fires on summon, not via a manual trigger.
                    recallMob(itemStack, serverLevel,
                            pPlayer instanceof net.minecraft.server.level.ServerPlayer sp ? sp : null);
                }
                return net.minecraft.world.InteractionResultHolder.sidedSuccess(itemStack, pLevel.isClientSide());
            } else {
                throwSummon(pLevel, pPlayer, itemStack);
                return net.minecraft.world.InteractionResultHolder.sidedSuccess(itemStack, pLevel.isClientSide());
            }
        }
        return net.minecraft.world.InteractionResultHolder.pass(itemStack);
    }

    /**
     * Launches a summoning throw. Shared by use() and the close-range path
     * (right-clicking directly on a mob routes to EntityInteract, never use()).
     * A per-sphere in-flight lock stops rapid re-throws from summoning the same
     * pal multiple times; the lock is cleared when the projectile lands and
     * self-expires after {@link #SUMMON_LOCK_TICKS} in case it never does.
     */
    public static void throwSummon(Level pLevel, Player pPlayer, ItemStack itemStack) {
        CompoundTag tag = itemStack.getTag();
        if (tag == null || !tag.contains("CapturedEntity") || tag.getBoolean("IsReleased")) return;
        if (!pLevel.isClientSide) {
            long now = pLevel.getGameTime();
            if (tag.getLong("SummonLockUntil") > now) {
                return; // a summoning throw is already in flight
            }
            tag.putLong("SummonLockUntil", now + SUMMON_LOCK_TICKS);
            pPlayer.getCooldowns().addCooldown(itemStack.getItem(), 10);

            com.mx.palmod.entity.PalSphereProjectile projectile = new com.mx.palmod.entity.PalSphereProjectile(pLevel, pPlayer);
            projectile.setItem(itemStack);
            projectile.setSummoning(true, tag.getUUID("SphereUUID"));
            // Sneak-throw triggers the pal's special deploy mode (anchor/sentry)
            if (pPlayer.isShiftKeyDown()) {
                CompoundTag entityData = tag.getCompound("CapturedEntity");
                Optional<EntityType<?>> optionalType = EntityType.by(entityData);
                if (optionalType.isPresent()) {
                    projectile.setDeployMode(com.mx.palmod.behavior.PalBehaviorManager
                            .getBehavior(optionalType.get()).getDeployMode());
                }
            }
            projectile.shootFromRotation(pPlayer, pPlayer.getXRot(), pPlayer.getYRot(), 0.0F, 1.5F, 1.0F);
            pLevel.addFreshEntity(projectile);
            pLevel.playSound(null, pPlayer.getX(), pPlayer.getY(), pPlayer.getZ(), net.minecraft.sounds.SoundEvents.SNOWBALL_THROW, net.minecraft.sounds.SoundSource.NEUTRAL, 0.5F, 0.4F / (pLevel.getRandom().nextFloat() * 0.4F + 0.8F));
        }
    }

    @Override
    public void inventoryTick(ItemStack pStack, Level pLevel, Entity pEntity, int pSlotId, boolean pIsSelected) {
        if (pLevel.isClientSide || !(pEntity instanceof Player player)) return;
        
        CompoundTag tag = pStack.getTag();
        if (tag != null && tag.getBoolean("IsReleased")) {
            // Anchored/sentry pals stay deployed no matter where the sphere is
            // stashed — deliberate recall (or death) is the only way back.
            String deployMode = tag.getString("DeployMode");
            if ("anchor".equals(deployMode) || "sentry".equals(deployMode)) return;

            boolean inHotbar = false;
            for (int i = 0; i < 9; i++) {
                if (player.getInventory().getItem(i) == pStack) {
                    inHotbar = true;
                    break;
                }
            }
            if (player.getOffhandItem() == pStack) inHotbar = true;

            if (!inHotbar) {
                if (pLevel instanceof ServerLevel serverLevel) {
                    recallMob(pStack, serverLevel);
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Worker pals: the sphere travels WITH the pal
    // ──────────────────────────────────────────────────────────────

    /**
     * ForgeData key holding a worker pal's own sphere NBT. A station_mode pal
     * takes its sphere with it when summoned (the item leaves the player's
     * inventory), so there is nothing to right-click to recall it — you
     * right-click the PAL instead, and it hands the sphere back. Such pals are
     * therefore exempt from the orphan rule, exactly like deployed ones.
     */
    public static final String KEY_CARRIED_SPHERE = "PalCarriedSphere";

    /** True if this pal is holding its own sphere rather than the player. */
    public static boolean carriesOwnSphere(Entity pal) {
        return pal.getPersistentData().contains(KEY_CARRIED_SPHERE);
    }

    /**
     * Stamps the sphere onto a freshly summoned worker pal. The caller consumes
     * the item afterwards — from here on the pal IS the sphere.
     */
    public static void attachCarriedSphere(Entity pal, CompoundTag sphereTag) {
        CompoundTag carried = sphereTag.copy();
        carried.putBoolean("IsReleased", false);
        carried.remove("EntityUUID");
        carried.remove("SummonLockUntil");
        pal.getPersistentData().put(KEY_CARRIED_SPHERE, carried);
    }

    /**
     * Puts a carried-sphere pal back into its sphere and returns the item.
     * Returns EMPTY if the pal isn't carrying one.
     */
    public static ItemStack recallCarriedSphere(ServerLevel pLevel, Entity pal) {
        CompoundTag data = pal.getPersistentData();
        if (!data.contains(KEY_CARRIED_SPHERE)) return ItemStack.EMPTY;
        CompoundTag tag = data.getCompound(KEY_CARRIED_SPHERE).copy();
        // Drop the marker BEFORE snapshotting, or every recall/summon cycle
        // would nest one more copy of the sphere tag inside CapturedEntity
        data.remove(KEY_CARRIED_SPHERE);

        CompoundTag entityData = new CompoundTag();
        if (pal.saveAsPassenger(entityData)) {
            tag.put("CapturedEntity", entityData);
        }
        tag.putBoolean("IsReleased", false);
        tag.remove("EntityUUID");
        tag.remove("DeployMode");
        tag.remove("AnchorPos");
        tag.remove("AnchorDim");

        pLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF,
                pal.getX(), pal.getY() + 1, pal.getZ(), 10, 0.5, 0.5, 0.5, 0.1);
        pLevel.playSound(null, pal.getX(), pal.getY(), pal.getZ(),
                net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
        if (pal instanceof LivingEntity livingPal && data.hasUUID("PalOwner")) {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                    new com.mx.palmod.api.event.PalRecalledEvent(
                            livingPal, data.getUUID("PalOwner")));
        }
        pal.discard();

        ItemStack sphere = new ItemStack(com.mx.palmod.registry.ModRegistries.FILLED_PAL_SPHERE.get());
        sphere.setTag(tag);
        return sphere;
    }

    /** Auto-recall (inventory shuffle, item toss, logout) — never warps the player. */
    public static void recallMob(ItemStack pStack, ServerLevel pLevel) {
        recallMob(pStack, pLevel, null);
    }

    /**
     * Recalls the summoned pal into its sphere. When triggered deliberately by a
     * player (use/useOn) AND the sphere warps (anchor deploy or WarpTether catch),
     * the player is teleported to the pal's position first.
     */
    public static void recallMob(ItemStack pStack, ServerLevel pLevel, @org.jetbrains.annotations.Nullable net.minecraft.server.level.ServerPlayer trigger) {
        CompoundTag tag = pStack.getTag();
        if (tag != null && tag.getBoolean("IsReleased")) {
            if (tag.contains("EntityUUID")) {
                UUID entityUUID = tag.getUUID("EntityUUID");
                Entity mob = pLevel.getEntity(entityUUID);
                if (mob != null && mob.isAlive()) {
                    // Warp before the pal disappears (deliberate recall only, same
                    // dimension). Prefer the stored AnchorPos — the pal may have
                    // drifted or be hovering mid-air.
                    if (trigger != null && shouldWarpOnRecall(tag) && trigger.level() == pLevel) {
                        BlockPos warpPos = tag.contains("AnchorPos")
                                ? BlockPos.of(tag.getLong("AnchorPos")) : mob.blockPosition();
                        safeTeleport(trigger, pLevel, warpPos);
                    }
                    CompoundTag entityData = new CompoundTag();
                    if (mob.saveAsPassenger(entityData)) {
                        tag.put("CapturedEntity", entityData);
                    }
                    pLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.POOF, mob.getX(), mob.getY() + 1, mob.getZ(), 10, 0.5, 0.5, 0.5, 0.1);
                    pLevel.playSound(null, mob.getX(), mob.getY(), mob.getZ(), net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT, net.minecraft.sounds.SoundSource.NEUTRAL, 1.0F, 1.0F);
                    if (mob instanceof net.minecraft.world.entity.LivingEntity livingMob
                            && livingMob.getPersistentData().hasUUID("PalOwner")) {
                        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                                new com.mx.palmod.api.event.PalRecalledEvent(
                                        livingMob, livingMob.getPersistentData().getUUID("PalOwner")));
                    }
                    mob.discard();
                } else if (!tag.getString("DeployMode").isEmpty()) {
                    // Deployed pal in an UNLOADED chunk (the waystone's core use
                    // case): warp the player to the anchor and keep the sphere
                    // released — standing there loads the chunk, and a second
                    // click completes the recall. Never flip IsReleased here or
                    // a re-summon would duplicate the pal.
                    // AnchorDim guard: never warp to overworld coordinates while
                    // standing in the nether (or any dimension mismatch)
                    if (trigger != null && shouldWarpOnRecall(tag) && trigger.level() == pLevel
                            && tag.contains("AnchorPos")
                            && tag.getString("AnchorDim").equals(pLevel.dimension().location().toString())) {
                        safeTeleport(trigger, pLevel, BlockPos.of(tag.getLong("AnchorPos")));
                    }
                    return;
                }
            }
            tag.putBoolean("IsReleased", false);
            // Deploy state is chosen per-throw; clear it on recall
            tag.remove("DeployMode");
            tag.remove("AnchorPos");
            tag.remove("AnchorDim");
        }
    }

    private static boolean shouldWarpOnRecall(CompoundTag tag) {
        return "anchor".equals(tag.getString("DeployMode")) || tag.getBoolean("WarpOnRecall");
    }

    /**
     * Teleports the player to a standable spot at/near pos. If no safe spot is
     * found the warp is skipped (the recall still completes) so the player can
     * never be put inside a wall.
     */
    private static void safeTeleport(net.minecraft.server.level.ServerPlayer player, ServerLevel level, BlockPos pos) {
        for (int dy : new int[]{0, 1, -1, 2}) {
            if (tryTeleportAt(player, level, pos.offset(0, dy, 0))) return;
        }
        // Hovering pal: scan down for the ground beneath the anchor
        BlockPos cursor = pos.below(2);
        for (int i = 0; i < 6; i++) {
            if (tryTeleportAt(player, level, cursor)) return;
            cursor = cursor.below();
        }
        // No standable spot found — skip the warp, the recall still completes
    }

    private static boolean tryTeleportAt(net.minecraft.server.level.ServerPlayer player, ServerLevel level, BlockPos feet) {
        boolean feetFree = level.getBlockState(feet).getCollisionShape(level, feet).isEmpty();
        boolean headFree = level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
        boolean groundSolid = !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty();
        if (!feetFree || !headFree || !groundSolid) return false;
        player.teleportTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL,
                feet.getX() + 0.5, feet.getY() + 1.0, feet.getZ() + 0.5, 20, 0.4, 0.6, 0.4, 0.05);
        level.playSound(null, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5,
                net.minecraft.sounds.SoundEvents.ENDERMAN_TELEPORT,
                net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.2F);
        return true;
    }
}
