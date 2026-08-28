package com.mx.palmod.ai;

import com.mx.palmod.behavior.PalBehavior;
import com.mx.palmod.behavior.PalBehaviorManager;
import com.mx.palmod.block.PalWorkStationBlockEntity;
import com.mx.palmod.stats.PalStats;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Template for all worker Pals (the leafcutter-ant pattern):
 *  1. Work the ground around a home spot (where the pal was summoned)
 *  2. Find a work target via {@link #findTarget}
 *  3. Do the job via {@link #doWork}, collecting produce with {@link #pickupItem}
 *  4. When the carried stack fills up (or there is nothing left to do), walk to
 *     the NEAREST Pal Work Station in range and unload there
 *  5. Idle near home when there is no work; stop entirely when starving
 *
 * Workers are not bound to any station: stations are plain chests found on
 * demand via {@link PalWorkStationBlockEntity#findNearest}. With no station in
 * range the pal simply keeps working until its arms are full, then waits.
 *
 * A new job = one subclass registered in {@link WorkerGoalRegistry} under a
 * station_mode.worker_type string.
 */
public abstract class AbstractStationWorkerGoal extends Goal {

    /** ForgeData key for the pal's work anchor (stamped where it starts working). */
    private static final String KEY_HOME = "PalWorkHome";

    protected final Mob mob;
    protected final int harvestRadius;
    protected final int wanderRadius;

    // Internal state
    @Nullable
    private BlockPos targetPos = null;
    // Held produce (single stack, max 64)
    private ItemStack heldItem = ItemStack.EMPTY;
    // Set when pickupItem() had to refuse produce — the carry must be unloaded
    private boolean mustUnload = false;
    private int tickDelay = 0;

    protected AbstractStationWorkerGoal(Mob mob, int harvestRadius, int wanderRadius) {
        this.mob = mob;
        this.harvestRadius = harvestRadius;
        this.wanderRadius = wanderRadius;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!(mob.level() instanceof ServerLevel)) return false;
        CompoundTag data = mob.getPersistentData();
        if (!data.contains("PalOwner")) return false;
        // Deployed pals (anchor/sentry) have their own rooted goal set
        if (!data.getString("DeployMode").isEmpty()) return false;
        // Fight first, work later
        if (mob.getTarget() != null && mob.getTarget().isAlive()) return false;
        // The goal stays engaged even with nothing to do: it is what keeps the
        // worker drifting around its home spot instead of wandering off on the
        // mob's own native AI.
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        // Re-engage immediately after being preempted (feeder runs etc.)
        tickDelay = 0;
        stampHome();
    }

    @Override
    public void tick() {
        tickDelay--;
        if (tickDelay > 0) {
            // Between work pulses: AM mobs (climbing ants) switch navigators and
            // drop paths constantly — re-issue the current path cheaply so the
            // worker doesn't stand around until the next 1s pulse.
            if (targetPos != null && mob.getNavigation().isDone()) {
                moveTo(targetPos, 1.0);
            }
            return;
        }
        tickDelay = 20; // Run every second

        Level level = mob.level();
        if (!(level instanceof ServerLevel serverLevel)) return;

        // Self-feed: a hungry PRODUCER eats produce stored in a nearby station
        // (keeps harvesters like the leafcutter ant running without a feeder).
        // Drain-type workers (sorter) are excluded — the station holds the
        // player's items in transit, not their own produce.
        if (selfFeedsFromStation() && PalStats.needsFood(mob) && trySelfFeed(serverLevel)) return;

        // Stop when starving (hunger < 5%) — EXCEPT self-feeding workers (the
        // harvester), which must keep working to produce their own food. A hard
        // stop deadlocks a harvester at hunger 0: it can't harvest → can't
        // self-feed → stays at 0 forever even with ripe crops in front of it.
        // It keeps going at the 0.3x starving speed until it restocks and eats
        // back up. Other workers (lumberjack/miner/sorter) rely on a feeder and
        // are correctly halted here (PalSeekFeederGoal still runs while inactive).
        if (PalStats.isInactive(mob) && !selfFeedsFromOutput()) return;

        // Step 1: Find the next work target
        if (targetPos != null && !isValidTarget(serverLevel, targetPos)) {
            targetPos = null;
        }
        if (targetPos == null) {
            targetPos = findTarget(serverLevel);
        }

        // Step 2: Unload when the arms are full, when produce got refused, or
        // when there is nothing left to harvest anyway
        if (!heldItem.isEmpty() && (mustUnload || mustUnloadNow() || carryFull() || targetPos == null)) {
            if (depositCarried(serverLevel)) return;
            // Nowhere to unload — fall through and keep working if we still can
            if (mustUnload || mustUnloadNow() || carryFull()) {
                idleAtHome();
                return;
            }
        }

        // Step 3: Work it
        if (targetPos != null) {
            moveTo(targetPos, 1.0);
            if (mob.distanceToSqr(targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5) < workReachSqr()) {
                doWork(serverLevel, targetPos);
                targetPos = null;
            }
            return;
        }

        // Nothing to do: idle CLOSE to home. The old code wandered up to
        // ±harvestRadius blocks away in a random direction and routinely got
        // stuck jittering against a wall/edge — or ramming an entity that
        // happened to stand in the roam zone. Now the worker just drifts home
        // when idle so it never strays into obstacles while waiting.
        idleAtHome();
    }

    // ──────────────────────────────────────────────────────────────
    //  Job hooks
    // ──────────────────────────────────────────────────────────────

    /** Is this previously chosen position still worth working? */
    protected abstract boolean isValidTarget(ServerLevel level, BlockPos pos);

    /**
     * Scan for the next work target near the mob, constrained by harvestRadius
     * (use {@link #withinWorkRange} to also respect the pal's wanderRadius).
     * Return null when there is nothing to do.
     */
    @Nullable
    protected abstract BlockPos findTarget(ServerLevel level);

    /** Perform the job at the target. Collect produce with {@link #pickupItem}. */
    protected abstract void doWork(ServerLevel level, BlockPos pos);

    /** Squared distance at which the mob is close enough to work the target. */
    protected double workReachSqr() {
        return 3.0;
    }

    /**
     * Producers self-feed from a station's stored produce. Workers that DRAIN
     * the station (sorter) override to false — those items belong to the player.
     */
    protected boolean selfFeedsFromStation() {
        return true;
    }

    /**
     * Force a delivery run before doing any more work, even with room left in
     * the carried stack (the sorter: each load is addressed to one container).
     */
    protected boolean mustUnloadNow() {
        return false;
    }

    /**
     * True if this worker's produce is edible for it, so it can recover from
     * starvation by working (the harvester). Such workers keep running when
     * inactive instead of deadlocking at hunger 0. Others rely on a feeder.
     */
    protected boolean selfFeedsFromOutput() {
        return false;
    }

    /**
     * Where the carried produce goes: the nearest work station with room for it.
     * Null when there is none in range — the worker then holds onto its load.
     */
    @Nullable
    protected BlockPos depositPos(ServerLevel level) {
        if (heldItem.isEmpty()) return null;
        return PalWorkStationBlockEntity.findNearest(level, mob.blockPosition(), wanderRadius,
                station -> station.canAccept(heldItem));
    }

    /**
     * Insert the carried stack at the deposit position. Default handles the
     * work station BE and any generic IItemHandler container. Returns leftover.
     */
    protected ItemStack depositInto(ServerLevel level, BlockPos pos, ItemStack stack) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof PalWorkStationBlockEntity station) {
            return station.depositItem(stack);
        }
        if (be != null) {
            var handlerOpt = be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                    .resolve();
            if (handlerOpt.isPresent()) {
                return net.minecraftforge.items.ItemHandlerHelper.insertItemStacked(handlerOpt.get(), stack, false);
            }
        }
        return stack;
    }

    /** Called when a deposit fully completes (carried stack emptied). */
    protected void onDepositComplete() {
    }

    // ──────────────────────────────────────────────────────────────
    //  Shared plumbing
    // ──────────────────────────────────────────────────────────────

    protected void moveTo(BlockPos pos, double speed) {
        mob.getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, speed);
    }

    /** The pal's work anchor: where it was standing when it started working. */
    protected BlockPos homePos() {
        CompoundTag data = mob.getPersistentData();
        if (data.contains(KEY_HOME)) {
            return BlockPos.of(data.getLong(KEY_HOME));
        }
        return mob.blockPosition();
    }

    private void stampHome() {
        CompoundTag data = mob.getPersistentData();
        if (!data.contains(KEY_HOME)) {
            data.putLong(KEY_HOME, mob.blockPosition().asLong());
        }
    }

    /** Work targets stay within wanderRadius of the pal's home spot. */
    protected boolean withinWorkRange(BlockPos pos) {
        return pos.distSqr(homePos()) <= (double) (wanderRadius * wanderRadius);
    }

    protected boolean carryFull() {
        return !heldItem.isEmpty() && heldItem.getCount() >= 64;
    }

    /**
     * Add produce to the carried stack (same-item stacking, cap 64).
     * Returns whatever could NOT be absorbed — callers should spill it into the
     * world (e.g. {@code Block.popResource}) instead of letting it vanish.
     */
    protected ItemStack pickupItem(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        if (heldItem.isEmpty()) {
            int taken = Math.min(stack.getCount(), 64);
            heldItem = stack.copyWithCount(taken);
            if (stack.getCount() > taken) {
                mustUnload = true;
                return stack.copyWithCount(stack.getCount() - taken);
            }
            return ItemStack.EMPTY;
        }
        if (ItemStack.isSameItemSameTags(heldItem, stack)) {
            int taken = Math.min(stack.getCount(), 64 - heldItem.getCount());
            heldItem.grow(taken);
            if (stack.getCount() > taken) {
                mustUnload = true;
                return stack.copyWithCount(stack.getCount() - taken);
            }
            return ItemStack.EMPTY;
        }
        // Different item — can't carry two kinds, go unload first
        mustUnload = true;
        return stack;
    }

    /** Deduct this action's hunger cost from the worker (key matches hunger_costs JSON). */
    protected void chargeHunger(String action, float defaultCost) {
        PalBehavior behavior = PalBehaviorManager.getBehavior(mob.getType());
        PalStats.modifyHunger(mob, -behavior.getHungerCost(action, defaultCost));
    }

    private void idleAtHome() {
        BlockPos home = homePos();
        double homeDistSqr = mob.distanceToSqr(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
        if (homeDistSqr > 9.0) { // strayed >3 blocks — walk back
            moveTo(home, 1.0);
        } else if (!mob.getNavigation().isInProgress()) {
            // Gentle shuffle within ~1.5 blocks of home
            double offsetX = (mob.getRandom().nextDouble() - 0.5) * 3.0;
            double offsetZ = (mob.getRandom().nextDouble() - 0.5) * 3.0;
            mob.getNavigation().moveTo(
                    home.getX() + 0.5 + offsetX,
                    mob.getY(),
                    home.getZ() + 0.5 + offsetZ, 0.6);
        }
    }

    /**
     * Eats one item from a nearby station's stored produce if it's food for this
     * mob. Returns true while the worker is walking to / eating from a station.
     */
    private boolean trySelfFeed(ServerLevel serverLevel) {
        var foodTable = com.mx.palmod.stats.PalFoodManager.getTable(mob.getType());
        java.util.function.Predicate<ItemStack> edible =
                s -> s.isEdible() || foodTable.canHandFeed(s.getItem());
        BlockPos stationPos = PalWorkStationBlockEntity.findNearest(serverLevel, mob.blockPosition(), wanderRadius,
                station -> station.findSlot(edible) >= 0);
        if (stationPos == null) return false;
        if (!(serverLevel.getBlockEntity(stationPos) instanceof PalWorkStationBlockEntity station)) return false;

        double distSqr = mob.distanceToSqr(
                stationPos.getX() + 0.5, stationPos.getY(), stationPos.getZ() + 0.5);
        if (distSqr > 4.0) {
            if (PalStats.isInactive(mob)) return false; // too weak to walk there
            moveTo(stationPos, 1.0);
            return true;
        }
        int slot = station.findSlot(edible);
        if (slot < 0) return false;
        if (com.mx.palmod.stats.PalFoodManager.tryFeed(mob, station.getItem(slot))) {
            station.extractStored(slot, 1);
            serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                    mob.getX(), mob.getY() + mob.getBbHeight(), mob.getZ(), 3, 0.2, 0.2, 0.2, 0.05);
            return true;
        }
        return false;
    }

    /**
     * Walks to the deposit target and unloads. Returns false when there is
     * nowhere to unload right now (the worker keeps carrying).
     */
    private boolean depositCarried(ServerLevel serverLevel) {
        BlockPos target = depositPos(serverLevel);
        if (target == null) return false;

        double distToTarget = mob.distanceToSqr(
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        if (distToTarget > 4.0) {
            moveTo(target, 1.0);
            return true;
        }
        // Close enough - deposit
        int before = heldItem.getCount();
        heldItem = depositInto(serverLevel, target, heldItem);
        if (!heldItem.isEmpty() && heldItem.getCount() == before) {
            // Destination rejected the whole stack (full / gone between the
            // lookup and now) — try a different station on the next pulse.
            return false;
        }
        if (heldItem.isEmpty()) {
            mustUnload = false;
            onDepositComplete();
        }
        return true;
    }
}
