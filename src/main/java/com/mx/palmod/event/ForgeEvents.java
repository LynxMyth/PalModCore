package com.mx.palmod.event;

import com.mx.palmod.Palmod;
import com.mx.palmod.ai.PalAttackOwnerTargetGoal;
import com.mx.palmod.ai.PalFollowOwnerGoal;
import com.mx.palmod.ai.PalSeekFeederGoal;
import com.mx.palmod.ai.PalTargetOwnerAttackerGoal;
import com.mx.palmod.behavior.PalBehavior;
import com.mx.palmod.behavior.PalBehaviorManager;
import com.mx.palmod.stats.PalFoodManager;
import com.mx.palmod.stats.PalStats;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

@Mod.EventBusSubscriber(modid = Palmod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeEvents {

    @SubscribeEvent
    public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity entity = event.getEntity();
        LivingEntity target = event.getNewTarget();

        if (target instanceof Player player) {
            // Check if this entity was caught and tamed by this player
            if (entity.getPersistentData().contains("PalOwner")) {
                UUID ownerId = entity.getPersistentData().getUUID("PalOwner");
                if (ownerId.equals(player.getUUID())) {
                    event.setCanceled(true); // Don't target the owner
                }
            }
        }
    }

    @SubscribeEvent
    public static void onAddReloadListeners(net.minecraftforge.event.AddReloadListenerEvent event) {
        event.addListener(com.mx.palmod.behavior.PalBehaviorManager.INSTANCE);
        event.addListener(PalFoodManager.INSTANCE);
        event.addListener(com.mx.palmod.stats.PalTradeManager.INSTANCE);
    }

    @SubscribeEvent
    public static void onLivingHurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        // A mob mid-capture (shrunk inside a wobbling sphere) is shielded from all
        // damage so the animation can resolve cleanly.
        if (victim.getPersistentData().getBoolean("PalCapturing")) { event.setCanceled(true); return; }
        // The resume burst must not re-trigger procs or re-bank itself
        if (com.mx.palmod.timestop.TimeStopManager.isApplyingBurst()) return;
        // Wild damage-immune mobs shrug off everything but their natural predator
        if (com.mx.palmod.pal.WildCatchManager.cancelIfImmune(event)) return;
        // Any landed hit suppresses a catchable wild mob's self-heal for a moment
        // (no-op unless the victim is a live wild catchable).
        com.mx.palmod.pal.WildCatchManager.stampHurt(victim);
        // Anti-loop: storm procs themselves deal lightning damage
        if (event.getSource().is(net.minecraft.world.damagesource.DamageTypes.LIGHTNING_BOLT)) return;
        if (!(event.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player)) return;
        if (event.getSource().getDirectEntity() != player) return; // direct melee only, no projectiles
        if (victim == player || victim.getPersistentData().contains("PalOwner")) return;
        // Storm bonus rides the triggering hit — a nested hurt() would be
        // swallowed by the hurt-invulnerability window this hit just opened.
        float bonus = com.mx.palmod.aura.StormAura.tryProc(player, victim);
        if (bonus > 0) {
            event.setAmount(event.getAmount() + bonus);
        }
        // ZA WARUDO: hits on frozen victims are banked (incl. the storm bonus)
        // and land in one burst when time resumes.
        if (com.mx.palmod.timestop.TimeStopManager.bank(player, victim, event.getAmount())) {
            event.setCanceled(true);
        }
    }

    // ── ZA WARUDO (time stop) hooks ─────────────────────────────────

    /** TRACK A: frozen living mobs simply don't tick. HIGHEST so the pal-stats
     *  handler below never processes a canceled tick. */
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onLivingTickFreeze(net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent event) {
        if (!event.getEntity().level().isClientSide()
                && com.mx.palmod.timestop.TimeStopManager.isFrozen(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    /** TRACK B + expiry: sweep non-living entities and end expired freezes. */
    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        net.minecraft.server.MinecraftServer server =
                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            com.mx.palmod.timestop.TimeStopManager.tick(server);
            com.mx.palmod.pal.TempBlockReverter.tick(server);
            com.mx.palmod.hunt.HuntingNightManager.tick(server);
        }
    }

    /** A stopped world must never outlive its server. */
    @SubscribeEvent
    public static void onServerStopping(net.minecraftforge.event.server.ServerStoppingEvent event) {
        com.mx.palmod.timestop.TimeStopManager.resumeAll(event.getServer());
        com.mx.palmod.hunt.HuntingNightManager.reset();
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            // Entities stranded frozen by a chunk unload restore themselves here
            com.mx.palmod.timestop.TimeStopManager.restoreIfStale(event.getEntity());
        }
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof Mob mob) {
            // Swarm clones carry their own melee goal from PalCloneGoal and must
            // NOT be wild-rolled or given pal goals — they're throwaway echoes.
            if (mob.getPersistentData().getBoolean(com.mx.palmod.ai.PalCloneGoal.KEY_CLONE)) {
                return;
            }

            PalBehavior stationBehavior = PalBehaviorManager.getBehavior(mob.getType());

            // Legacy: pre-0.9.3 workers were bound to one station block. Stations
            // are plain chests now — drop the stale binding so the pal is treated
            // like any other summoned pal.
            mob.getPersistentData().remove("WorkStationPos");

            // Special deployments (sneak-throw): rooted pals with their own goal set
            String deployMode = mob.getPersistentData().getString("DeployMode");
            if ("anchor".equals(deployMode)) {
                // Waystone anchor: actively rooted where it landed (goal flags alone
                // can't hold MoveControl-driven flyers in place)
                mob.goalSelector.addGoal(1, new com.mx.palmod.ai.PalRootGoal(mob));
                return;
            }
            if ("sentry".equals(deployMode)) {
                mob.goalSelector.addGoal(1, new com.mx.palmod.ai.PalSentryGoal(
                        mob, stationBehavior.getSentryRadius()));
                return;
            }

            // Wild-catch system: naturally spawned mobs roll catchable once and,
            // if catchable, get their wild difficulty goals (brawler/skittish/...)
            if (!mob.getPersistentData().contains("PalOwner")
                    && !mob.getPersistentData().contains("SphereUUID")) {
                com.mx.palmod.pal.WildCatchManager.onWildJoin(mob, stationBehavior);
                // Hunting Night: every non-pal mob carries a dormant predator goal
                // (top priority so it overrides fleeing) that only wakes while a hunt
                // is active — self-gating, so there is nothing to remove at dawn.
                if (mob instanceof net.minecraft.world.entity.PathfinderMob pathMob) {
                    mob.goalSelector.addGoal(0, new com.mx.palmod.ai.HuntNightAttackGoal(pathMob));
                }
            }

            if (mob.getPersistentData().contains("PalOwner")) {
                PalBehavior behavior = PalBehaviorManager.getBehavior(mob.getType());

                // Feeder-seeking goal — highest priority for all Pals
                mob.goalSelector.addGoal(0, new PalSeekFeederGoal(mob));

                // Worker pals (station_mode) do their job wherever they are and
                // haul produce to whichever Pal Work Station is nearest.
                if (behavior.isStationMode()) {
                    mob.goalSelector.addGoal(1, com.mx.palmod.ai.WorkerGoalRegistry.create(
                            behavior.getStationWorkerType(), mob, behavior));
                }

                // (sit/stand toggle removed — pals no longer sit)

                // Follow sits BELOW combat goals: a pal with a live target fights
                // first and only runs back to the owner when out of range.
                if (behavior.isFollowOwner()) {
                    mob.goalSelector.addGoal(3, new PalFollowOwnerGoal(
                        mob,
                        behavior.getFollowSpeed(),
                        behavior.getStartDistance(),
                        behavior.getStopDistance()
                    ));
                }

                if (behavior.isProtectOwner()) {
                    mob.targetSelector.addGoal(1, new PalTargetOwnerAttackerGoal(
                        mob, behavior.isGuardOnly(), behavior.getGuardLeash()));
                }

                // Guard-only pals never initiate attacks on the owner's target
                if (behavior.isAttackTarget() && !behavior.isGuardOnly()) {
                    mob.targetSelector.addGoal(2, new PalAttackOwnerTargetGoal(mob));
                }

                if (behavior.isMeleeAttack() && mob instanceof net.minecraft.world.entity.PathfinderMob pathfinderMob) {
                    mob.goalSelector.addGoal(2, new net.minecraft.world.entity.ai.goal.MeleeAttackGoal(
                        pathfinderMob,
                        behavior.getMeleeAttackSpeed(),
                        true
                    ));
                }

                // Elemental casts (lightning/fireball/earth_spike/water_burst)
                if (!behavior.getCombatAbilityType().isEmpty()) {
                    mob.goalSelector.addGoal(2, new com.mx.palmod.ai.PalCastAttackGoal(mob, behavior));
                }

                // Proactive special powers (clone, fetch, magnet, and anything a
                // third-party mod registers) inject their own goals here
                for (com.mx.palmod.pal.PalAbilityRegistry.PalAbility ability
                        : com.mx.palmod.pal.PalAbilityRegistry.applicable(behavior)) {
                    ability.onJoin(mob, behavior);
                }
            }
        }
    }

    /**
     * A short server-side cooldown after any canceled sphere-in-hand interaction.
     * A modded client whose local mobInteract returned PASS follows the interact
     * packet with a ServerboundUseItemPacket; without this the trailing packet
     * reaches Item.use() and throws/summons a second sphere.
     */
    private static void suppressFollowUpUse(Player player, net.minecraft.world.item.ItemStack held) {
        if (held.getItem() instanceof com.mx.palmod.item.PalSphereItem
                || held.getItem() instanceof com.mx.palmod.item.FilledPalSphereItem) {
            player.getCooldowns().addCooldown(held.getItem(), 10);
        }
    }

    @SubscribeEvent
    public static void onEntityInteract(net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) {
            // Mirror the throw branch's client-evaluable conditions and consume
            // the click so the client never falls through to gameMode.useItem
            // (which would send a use packet and double-throw the sphere).
            net.minecraft.world.entity.Entity clientTarget = event.getTarget();
            if (clientTarget instanceof LivingEntity && !(clientTarget instanceof Player)
                    && !(clientTarget instanceof net.minecraft.world.entity.npc.Npc)) {
                net.minecraft.world.item.ItemStack clientHeld = event.getEntity().getItemInHand(event.getHand());
                if (clientHeld.getItem() instanceof com.mx.palmod.item.PalSphereItem
                        || (clientHeld.getItem() instanceof com.mx.palmod.item.FilledPalSphereItem
                            && clientHeld.hasTag() && clientHeld.getTag().contains("CapturedEntity")
                            && !clientHeld.getTag().getBoolean("IsReleased"))) {
                    event.setCanceled(true);
                    event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                }
            }
            return;
        }
        {
            net.minecraft.world.entity.Entity entity = event.getTarget();
            Player player = event.getEntity();

            // ── Hand-feed STATION pals (they're excluded from the routing below;
            // without this the mob's own AI eats the food with no hunger gain) ──
            if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                    && entity instanceof Mob stationMob
                    && stationMob.getPersistentData().hasUUID("PalOwner")
                    && PalBehaviorManager.getBehavior(stationMob.getType()).isStationMode()
                    && stationMob.getPersistentData().getUUID("PalOwner").equals(player.getUUID())) {
                net.minecraft.world.item.ItemStack stationHeld = player.getMainHandItem();
                if (!stationHeld.isEmpty() && PalStats.getHunger(stationMob) < PalStats.MAX_HUNGER
                        && (stationHeld.isEdible()
                        || PalFoodManager.getTable(stationMob.getType()).canHandFeed(stationHeld.getItem()))) {
                    if (PalFoodManager.tryFeed(stationMob, stationHeld)) {
                        if (!player.getAbilities().instabuild) stationHeld.shrink(1);
                        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                stationMob.getName().getString() + " ate! Hunger: "
                                        + String.format("%.0f%%", PalStats.getHunger(stationMob))), true);
                        suppressFollowUpUse(player, stationHeld);
                        event.setCanceled(true);
                        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                        return;
                    }
                }
            }

            // ── Worker pals carry their OWN sphere, so there is no sphere in the
            // inventory to right-click: a plain click on the pal puts it back
            // into its sphere and hands that to the owner ──
            if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                    && !player.isShiftKeyDown()
                    && entity instanceof Mob workerMob
                    && workerMob.getPersistentData().hasUUID("PalOwner")
                    && com.mx.palmod.item.FilledPalSphereItem.carriesOwnSphere(workerMob)
                    && workerMob.getPersistentData().getUUID("PalOwner").equals(player.getUUID())
                    && player.level() instanceof net.minecraft.server.level.ServerLevel workerLevel) {
                String workerName = workerMob.getName().getString();
                net.minecraft.world.item.ItemStack sphere =
                        com.mx.palmod.item.FilledPalSphereItem.recallCarriedSphere(workerLevel, workerMob);
                if (!sphere.isEmpty()) {
                    if (!player.getInventory().add(sphere)) {
                        player.drop(sphere, false);
                    }
                    player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                            workerName + " returned to its sphere."), true);
                }
                event.setCanceled(true);
                event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                return;
            }

            if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                    && entity instanceof Mob mob && mob.getPersistentData().contains("PalOwner")
                    && mob.getPersistentData().getString("DeployMode").isEmpty()) {
                UUID ownerId = mob.getPersistentData().getUUID("PalOwner");
                if (ownerId.equals(player.getUUID())) {
                    PalBehavior interactBehavior = PalBehaviorManager.getBehavior(mob.getType());
                    net.minecraft.world.item.ItemStack held = player.getMainHandItem();

                    // ── Sneak-click branch (never falls through to sit) ──
                    if (player.isShiftKeyDown()) {
                        // Sneak + sample item on a fetcher: go get it from a chest
                        if (interactBehavior.isCanFetch() && !held.isEmpty()) {
                            mob.getPersistentData().putBoolean("PalSitting", false); // stand up and go
                            mob.getPersistentData().putString("FetchItem",
                                    net.minecraftforge.registries.ForgeRegistries.ITEMS
                                            .getKey(held.getItem()).toString());
                            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                                    mob.getName().getString() + " runs off to find "
                                            + held.getHoverName().getString() + "..."));
                            suppressFollowUpUse(player, held);
                            event.setCanceled(true);
                            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                            return;
                        }
                        // Sneak on a pouch pal: open the pouch
                        if (interactBehavior.getMobileChestSize() > 0
                                && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                            com.mx.palmod.pal.PalChest.open(serverPlayer, mob, interactBehavior.getMobileChestSize());
                            suppressFollowUpUse(player, held);
                            event.setCanceled(true);
                            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                            return;
                        }
                        // Sneak + empty hand on an XP collector: extract bottles
                        if (interactBehavior.getXpCollectRadius() > 0 && held.isEmpty()
                                && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                            com.mx.palmod.pal.PalPassives.extractXpBottles(serverPlayer, mob);
                            event.setCanceled(true);
                            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                        }
                        return;
                    }

                    // ── Plain click with food: hand-feed the pal (skip when full —
                    // don't silently eat items for nothing) ──
                    if (!held.isEmpty() && PalStats.getHunger(mob) < PalStats.MAX_HUNGER
                            && (held.isEdible()
                            || PalFoodManager.getTable(mob.getType()).canHandFeed(held.getItem()))) {
                        if (PalFoodManager.tryFeed(mob, held)) {
                            if (!player.getAbilities().instabuild) held.shrink(1);
                            if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel feedLevel) {
                                feedLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                                        mob.getX(), mob.getY() + mob.getBbHeight() * 0.7, mob.getZ(),
                                        6, 0.3, 0.3, 0.3, 0.05);
                            }
                            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                                    mob.getName().getString() + " ate! Hunger: "
                                            + String.format("%.0f%%", PalStats.getHunger(mob))), true);
                            event.setCanceled(true);
                            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                            return;
                        }
                    }

                    // ── Plain click on a rideable pal: mount up ──
                    if (interactBehavior.isRideable()) {
                        player.startRiding(mob);
                        suppressFollowUpUse(player, held);
                        event.setCanceled(true);
                        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                        return;
                    }

                    // Sit/stand was removed — a plain click with nothing else to
                    // do just cancels so it doesn't trip a vanilla mob interaction.
                    event.setCanceled(true);
                    event.setCancellationResult(net.minecraft.world.InteractionResult.sidedSuccess(event.getLevel().isClientSide()));
                }
            }

            if (event.isCanceled()) {
                suppressFollowUpUse(player, player.getItemInHand(event.getHand()));
                return;
            }

            // ── Close-range sphere throws ─────────────────────────────
            // Right-clicking with the crosshair ON a mob routes here and never
            // reaches Item.use(), so point-blank throws used to do nothing.
            // Villagers are skipped so trading still works; owned pals are
            // skipped so a mis-click never wastes a guaranteed-loss sphere.
            if (entity instanceof LivingEntity && !(entity instanceof Player)
                    && !(entity instanceof net.minecraft.world.entity.npc.Npc)
                    && !entity.getPersistentData().contains("PalOwner")) {
                net.minecraft.world.item.ItemStack stack = player.getItemInHand(event.getHand());
                if (stack.getItem() instanceof com.mx.palmod.item.PalSphereItem sphereItem) {
                    com.mx.palmod.item.PalSphereItem.throwSphere(
                            event.getLevel(), player, stack, sphereItem.getSphereLevel());
                    player.awardStat(net.minecraft.stats.Stats.ITEM_USED.get(sphereItem));
                    if (!player.getAbilities().instabuild) stack.shrink(1);
                    // Block the trailing use packet a modded client sends
                    player.getCooldowns().addCooldown(sphereItem, 10);
                    event.setCanceled(true);
                    event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                    return;
                }
                if (stack.getItem() instanceof com.mx.palmod.item.FilledPalSphereItem
                        && stack.hasTag() && stack.getTag().contains("CapturedEntity")
                        && !stack.getTag().getBoolean("IsReleased")) {
                    com.mx.palmod.item.FilledPalSphereItem.throwSummon(event.getLevel(), player, stack);
                    event.setCanceled(true);
                    event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
                    return;
                }
            }
        }
    }

    /**
     * Well-fed regeneration: an owned pal at or above the well-fed hunger
     * threshold slowly heals, faster the fuller and happier it is, paying a
     * little hunger per HP. Called only for non-deployed pals on the 20-tick
     * cadence, so the per-second rate is applied per call.
     */
    private static void tryWellFedHeal(Mob mob, PalBehavior behavior) {
        // Never heal a mob that is dead or mid-death-animation: LivingTickEvent
        // fires before tickDeath(), so healing a 0-HP pal here would revive it
        // AFTER onLivingDeath already reverted its sphere — an orphaned corpse.
        if (!mob.isAlive() || mob.isDeadOrDying()) return;
        float threshold = (float) com.mx.palmod.Config.palHealWellFedThreshold;
        float hunger = PalStats.getHunger(mob);
        if (hunger < threshold) return;
        if (mob.getHealth() >= mob.getMaxHealth()) return;

        // Per-mob override (stats.heal_per_second); < 0 = use the config default.
        float perSecond = behavior.getHealPerSecond() >= 0f
                ? behavior.getHealPerSecond() : (float) com.mx.palmod.Config.palHealPerSecond;
        if (perSecond <= 0f) return;

        // Ramp with fullness above the threshold (25% at the threshold → 100% full)
        // and with mood (50% at zero mood → 100% at max).
        float ramp = threshold >= 100f ? 1f
                : Math.min(1f, (hunger - threshold) / (100f - threshold));
        float fedFactor = 0.25f + 0.75f * ramp;
        float moodFactor = 0.5f + 0.5f * (PalStats.getMood(mob) / PalStats.MAX_MOOD);

        float heal = perSecond * fedFactor * moodFactor;
        heal = Math.min(heal, mob.getMaxHealth() - mob.getHealth());
        if (heal <= 0f) return;

        mob.setHealth(mob.getHealth() + heal);
        PalStats.modifyHunger(mob, -(heal * (float) com.mx.palmod.Config.palHealHungerCostPerHp));
        if (mob.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.HEART,
                    mob.getX(), mob.getY() + mob.getBbHeight() * 0.8, mob.getZ(),
                    1, 0.3, 0.3, 0.3, 0.0);
        }
    }

    @SubscribeEvent
    public static void onLivingTick(net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.level().isClientSide()) return;

        // Swarm clones self-destruct on their timer (the reliable backstop) and
        // keep chasing whatever their master is fighting while it lives.
        if (entity.getPersistentData().getBoolean(com.mx.palmod.ai.PalCloneGoal.KEY_CLONE)) {
            CompoundTag cd = entity.getPersistentData();
            boolean expired = entity.level().getGameTime() >= cd.getLong(com.mx.palmod.ai.PalCloneGoal.KEY_EXPIRE);
            if (expired || !cd.hasUUID(com.mx.palmod.ai.PalCloneGoal.KEY_MASTER)) {
                if (entity.level() instanceof net.minecraft.server.level.ServerLevel dsl) {
                    dsl.sendParticles(net.minecraft.core.particles.ParticleTypes.CLOUD,
                            entity.getX(), entity.getY() + entity.getBbHeight() * 0.5, entity.getZ(),
                            6, 0.2, 0.3, 0.2, 0.02);
                }
                entity.discard();
                return;
            }
            // Re-seed the target from the (loaded) master so the swarm follows
            // its enemy. A null master here means dead OR just unloaded — either
            // way the expiry timer above is the cleanup, never a premature kill.
            if (entity.level() instanceof net.minecraft.server.level.ServerLevel csl
                    && csl.getEntity(cd.getUUID(com.mx.palmod.ai.PalCloneGoal.KEY_MASTER)) instanceof Mob masterMob
                    && entity instanceof Mob cloneMob) {
                LivingEntity mt = masterMob.getTarget();
                if (mt != null && mt.isAlive()
                        && (cloneMob.getTarget() == null || !cloneMob.getTarget().isAlive())) {
                    cloneMob.setTarget(mt);
                }
            }
            return; // clones run no other pal logic
        }

        // Per-tick (steering can't run on the 20-tick cadence): ridden mounts
        if (entity instanceof Mob riddenMob && riddenMob.isVehicle()
                && riddenMob.getPersistentData().contains("PalOwner")) {
            PalBehavior rideBehavior = PalBehaviorManager.getBehavior(riddenMob.getType());
            if (rideBehavior.isRideable()) {
                com.mx.palmod.pal.PalMountHandler.steer(riddenMob, rideBehavior);
            }
        }

        if (entity.tickCount % 20 != 0) return;

        CompoundTag data = entity.getPersistentData();
        boolean isPal = data.contains("PalOwner");

        // Hunt-spawned mobs vanish once the hunt ends (or when they load after
        // dawn) — UNLESS a player has since bonded to one: a caught pal (PalOwner /
        // SphereUUID / station), or a mob tamed by vanilla / Alex's Mobs (which
        // extend TamableAnimal). Deleting a tamed pet at dawn would be data loss.
        if (data.getBoolean("HuntNightSpawned") && !isPal
                && !com.mx.palmod.hunt.HuntingNightManager.isActive()) {
            boolean tamed = entity instanceof net.minecraft.world.entity.TamableAnimal ta && ta.isTame();
            boolean palBound = data.contains("SphereUUID");
            if (!tamed && !palBound) {
                entity.discard();
                return;
            }
        }

        // Safety net: an orphaned capture (the sphere vanished mid-animation, e.g.
        // a chunk unload) must not leave a mob frozen (NoAi) forever.
        if (data.getBoolean("PalCapturing")
                && entity.level().getGameTime() > data.getLong("PalCaptureUntil")) {
            data.remove("PalCapturing");
            data.remove("PalCaptureUntil");
            if (entity instanceof Mob orphan) orphan.setNoAi(false);
        }

        // ── Hunger decay for all Pals ──────────────────────────────────
        if (isPal && entity instanceof Mob mob) {
            PalBehavior behavior = PalBehaviorManager.getBehavior(mob.getType());

            // Deployed (anchor/sentry) pals are rooted and can't reach a feeder —
            // their hunger/mood is frozen while deployed.
            boolean deployed = !mob.getPersistentData().getString("DeployMode").isEmpty();
            if (!deployed) {
                // Decay timer: 1 tick = 1/20 second; decay rate = per minute (1200 ticks)
                int decayTimer = PalStats.getDecayTimer(mob) + 1;
                // Decay interval in ticks for hunger_decay_per_minute → every 1200 ticks = 1 point if rate=1
                float decayPerTick = behavior.getHungerDecayPerMinute() / 1200f;
                // We run every 20 ticks, so per call = decayPerTick * 20
                PalStats.modifyHunger(mob, -(decayPerTick * 20f));
                PalStats.setDecayTimer(mob, decayTimer);

                // Mood decay: per Minecraft day (24000 ticks) → per 20-tick call = moodDecayPerDay/1200
                float moodDecayPerCall = behavior.getMoodDecayPerDay() / 1200f;
                PalStats.modifyMood(mob, -moodDecayPerCall);

                // Well-fed self-heal: a happy, well-fed pal slowly regenerates,
                // burning a little extra hunger. Deployed/frozen pals are excluded
                // (this whole block is gated on !deployed).
                tryWellFedHeal(mob, behavior);
            }

            // Apply speed effects based on hunger state
            PalStats.applySpeedEffects(mob);

            // Owner auras (heal, storm, ...) pulse on this same cadence
            com.mx.palmod.aura.PalAuraManager.tickAura(mob, behavior);

            // Passives: XP absorption, item magnet, greedy boom
            com.mx.palmod.pal.PalPassives.tick(mob, behavior);
        }

        // ── Wild catchable upkeep: glow, shimmer, hide/reveal, brawler buffs ──
        if (!isPal && entity instanceof Mob wildMob && data.getBoolean("PalCatchable")) {
            com.mx.palmod.pal.WildCatchManager.tickWild(wildMob);
        }

        // ── Orphan & Dynamic Ownership check ───────────────────
        if (data.contains("SphereUUID")) {
            if (!data.getString("DeployMode").isEmpty()
                    || com.mx.palmod.item.FilledPalSphereItem.carriesOwnSphere(entity)) {
                // Anchored/sentry pals stay deployed while their sphere is stashed
                // anywhere in the owner's inventory — deliberate recall brings them
                // back. Worker pals HOLD their own sphere, so there is nothing in
                // an inventory to look for: they persist until recalled or killed.
            } else {
                UUID sphereId = data.getUUID("SphereUUID");
                Player currentHolder = null;

                for (Player p : entity.level().players()) {
                    for (int i = 0; i < 9; i++) {
                        net.minecraft.world.item.ItemStack stack = p.getInventory().getItem(i);
                        if (stack.getItem() instanceof com.mx.palmod.item.FilledPalSphereItem && stack.hasTag()) {
                            if (stack.getTag().getBoolean("IsReleased") && stack.getTag().hasUUID("SphereUUID")) {
                                if (stack.getTag().getUUID("SphereUUID").equals(sphereId)) {
                                    currentHolder = p;
                                    break;
                                }
                            }
                        }
                    }
                    if (currentHolder == null) {
                        net.minecraft.world.item.ItemStack offhand = p.getOffhandItem();
                        if (offhand.getItem() instanceof com.mx.palmod.item.FilledPalSphereItem && offhand.hasTag()) {
                            if (offhand.getTag().getBoolean("IsReleased") && offhand.getTag().hasUUID("SphereUUID")) {
                                if (offhand.getTag().getUUID("SphereUUID").equals(sphereId)) {
                                    currentHolder = p;
                                }
                            }
                        }
                    }
                    if (currentHolder != null) break;
                }
                
                if (currentHolder != null) {
                    data.putUUID("PalOwner", currentHolder.getUUID());
                } else if (!entity.isVehicle()) {
                    // Never discard a mount out from under its rider mid-ocean
                    entity.discard();
                }
            }
        }
    }

    /** Pal Compass upkeep: a held compass tagged PalCompass points at the nearest catchable wild mob. */
    @SubscribeEvent
    public static void onPlayerTick(net.minecraftforge.event.TickEvent.PlayerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        if (event.side != net.minecraftforge.fml.LogicalSide.SERVER) return;
        Player player = event.player;
        if (player.tickCount % 20 != 0) return;
        if (!(player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;
        for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
            net.minecraft.world.item.ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() == net.minecraft.world.item.Items.COMPASS
                    && stack.hasTag() && stack.getTag().getBoolean("PalCompass")) {
                com.mx.palmod.pal.WildCatchManager.updatePalCompass(serverLevel, player, stack);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        Player player = event.getEntity();
        com.mx.palmod.aura.StormAura.clear(player.getUUID());
        com.mx.palmod.timestop.TimeStopManager.endForCaster(player.getUUID(), player.getServer());
        if (player.level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            for (int i = 0; i < 9; i++) {
                net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(i);
                if (stack.getItem() instanceof com.mx.palmod.item.FilledPalSphereItem && !isDeployedSphere(stack)) {
                    com.mx.palmod.item.FilledPalSphereItem.recallMob(stack, serverLevel);
                }
            }
            net.minecraft.world.item.ItemStack offhand = player.getOffhandItem();
            if (offhand.getItem() instanceof com.mx.palmod.item.FilledPalSphereItem && !isDeployedSphere(offhand)) {
                com.mx.palmod.item.FilledPalSphereItem.recallMob(offhand, serverLevel);
            }
        }
    }

    /** A released sphere whose pal is deployed (anchor/sentry) — stays out through logout. */
    private static boolean isDeployedSphere(net.minecraft.world.item.ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean("IsReleased") && !tag.getString("DeployMode").isEmpty();
    }

    @SubscribeEvent
    public static void onItemToss(net.minecraftforge.event.entity.item.ItemTossEvent event) {
        net.minecraft.world.item.ItemStack stack = event.getEntity().getItem();
        if (stack.getItem() instanceof com.mx.palmod.item.FilledPalSphereItem) {
            if (event.getPlayer().level() instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                com.mx.palmod.item.FilledPalSphereItem.recallMob(stack, serverLevel);
            }
        }
    }

    /** Swarm clones are illusions — killing one yields no loot or XP. */
    @SubscribeEvent
    public static void onCloneDrops(net.minecraftforge.event.entity.living.LivingDropsEvent event) {
        if (event.getEntity().getPersistentData().getBoolean(com.mx.palmod.ai.PalCloneGoal.KEY_CLONE)) {
            event.getDrops().clear();
        }
    }

    @SubscribeEvent
    public static void onCloneXp(net.minecraftforge.event.entity.living.LivingExperienceDropEvent event) {
        if (event.getEntity().getPersistentData().getBoolean(com.mx.palmod.ai.PalCloneGoal.KEY_CLONE)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        // A dying pal drops its pouch contents and spills its stored XP
        if (!entity.level().isClientSide() && entity instanceof Mob deadMob
                && entity.level() instanceof net.minecraft.server.level.ServerLevel deathLevel) {
            com.mx.palmod.pal.PalChest.dropContents(deathLevel, deadMob);
            int storedXp = deadMob.getPersistentData().getInt(com.mx.palmod.pal.PalPassives.KEY_STORED_XP);
            if (storedXp > 0) {
                net.minecraft.world.entity.ExperienceOrb.award(deathLevel, deadMob.position(), storedXp);
                deadMob.getPersistentData().remove(com.mx.palmod.pal.PalPassives.KEY_STORED_XP);
            }
        }

        // Track player kills for the greedy pal's mimicry (skip players, pals,
        // and synthetic mimic kills — no self-sustaining loot loops)
        if (!entity.level().isClientSide() && !(entity instanceof Player)
                && !entity.getPersistentData().contains("PalOwner")
                && !entity.getPersistentData().getBoolean("PalMimic")
                && event.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer killer) {
            com.mx.palmod.pal.KillTracker.record(killer.getUUID(), entity.getType(),
                    entity.level().getGameTime());
        }
        if (!entity.level().isClientSide() && entity.getPersistentData().contains("SphereUUID")) {
            if (entity.getPersistentData().hasUUID("PalOwner")) {
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(
                        new com.mx.palmod.api.event.PalDiedEvent(
                                entity, entity.getPersistentData().getUUID("PalOwner")));
            }
            if (com.mx.palmod.item.FilledPalSphereItem.carriesOwnSphere(entity)) {
                // The sphere travelled with the pal — it drops where the pal fell,
                // empty (a dead pal never comes back inside its sphere)
                entity.getPersistentData().remove(
                        com.mx.palmod.item.FilledPalSphereItem.KEY_CARRIED_SPHERE);
                if (entity.level() instanceof net.minecraft.server.level.ServerLevel deathLevel) {
                    net.minecraft.world.level.block.Block.popResource(deathLevel, entity.blockPosition(),
                            new net.minecraft.world.item.ItemStack(
                                    com.mx.palmod.registry.ModRegistries.PAL_SPHERE.get()));
                }
                return;
            }

            if (entity.getPersistentData().contains("PalOwner")) {
                UUID ownerId = entity.getPersistentData().getUUID("PalOwner");
                Player owner = entity.level().getPlayerByUUID(ownerId);
                if (owner != null) {
                    UUID sphereId = entity.getPersistentData().getUUID("SphereUUID");
                    
                    for (int i = 0; i < owner.getInventory().getContainerSize(); i++) {
                        net.minecraft.world.item.ItemStack stack = owner.getInventory().getItem(i);
                        if (stack.getItem() instanceof com.mx.palmod.item.FilledPalSphereItem && stack.hasTag()) {
                             if (stack.getTag().hasUUID("SphereUUID") && stack.getTag().getUUID("SphereUUID").equals(sphereId)) {
                                 owner.getInventory().setItem(i, new net.minecraft.world.item.ItemStack(com.mx.palmod.registry.ModRegistries.PAL_SPHERE.get()));
                                 break;
                             }
                        }
                    }
                }
            }
        }
    }
}
