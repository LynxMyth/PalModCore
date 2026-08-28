package com.mx.palmod.ai;

import com.mx.palmod.block.PalFeederBlockEntity;
import com.mx.palmod.block.PalWorkStationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The "sorter" station worker (built for the raccoon): a work station is an
 * INPUT buffer (hopper-feed it mixed items); the raccoon grabs a stack out of
 * the nearest one and carries it to a nearby container that already holds the
 * same item — a living item-sorting system. Items no container "wants" stay in
 * the station.
 */
public class SorterGoal extends AbstractStationWorkerGoal {

    /** How many items the raccoon carries per trip. */
    private static final int CARRY_SIZE = 16;

    @Nullable
    private BlockPos destination = null;

    public SorterGoal(Mob mob, int harvestRadius, int wanderRadius) {
        super(mob, harvestRadius, wanderRadius);
    }

    @Override
    protected boolean isValidTarget(ServerLevel level, BlockPos pos) {
        return sortableSlot(level, pos) >= 0;
    }

    @Nullable
    @Override
    protected BlockPos findTarget(ServerLevel level) {
        // The work target is the nearest station holding something sortable
        return PalWorkStationBlockEntity.findNearest(level, mob.blockPosition(), wanderRadius,
                station -> sortableSlot(level, station.getBlockPos()) >= 0);
    }

    @Override
    protected void doWork(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof PalWorkStationBlockEntity station)) return;

        int slot = sortableSlot(level, pos);
        if (slot < 0) return;

        BlockPos dest = findDestinationFor(scanContainers(level, pos), pos, station.getItem(slot));
        if (dest == null) return;

        ItemStack taken = station.extractStored(slot, CARRY_SIZE);
        if (taken.isEmpty()) return;

        ItemStack leftover = pickupItem(taken);
        if (!leftover.isEmpty()) {
            // Arms already full of something else — put it straight back
            station.depositItem(leftover);
            return;
        }
        this.destination = dest;
        chargeHunger("sort", 1.0f);
    }

    @Override
    protected boolean selfFeedsFromStation() {
        // A station is our INPUT buffer — full means there's work to do, and our
        // load belongs to the player, so never self-feed from it
        return false;
    }

    /** A picked-up load is always addressed to one container — deliver it now. */
    @Override
    protected boolean mustUnloadNow() {
        return destination != null;
    }

    @Nullable
    @Override
    protected BlockPos depositPos(ServerLevel level) {
        return destination != null ? destination : super.depositPos(level);
    }

    @Override
    protected void onDepositComplete() {
        destination = null;
    }

    // ──────────────────────────────────────────────────────────────

    /** First slot in the station whose contents some nearby container wants. */
    private int sortableSlot(ServerLevel level, BlockPos stationPos) {
        if (!(level.getBlockEntity(stationPos) instanceof PalWorkStationBlockEntity station)) return -1;
        if (station.isEmpty()) return -1;
        List<Target> containers = scanContainers(level, stationPos);
        if (containers.isEmpty()) return -1;
        return station.findSlot(stack -> findDestinationFor(containers, stationPos, stack) != null);
    }

    /** A candidate destination container found by {@link #scanContainers}. */
    private record Target(BlockPos pos, IItemHandler handler) {}

    /**
     * Every container in range that could receive sorted items. Scanned once per
     * probe — the per-slot destination test then runs against this list instead
     * of re-walking the whole box for all 27 station slots.
     */
    private List<Target> scanContainers(ServerLevel level, BlockPos stationPos) {
        List<Target> found = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                stationPos.getX() - harvestRadius, stationPos.getY() - 2, stationPos.getZ() - harvestRadius,
                stationPos.getX() + harvestRadius, stationPos.getY() + 2, stationPos.getZ() + harvestRadius)) {
            // Skip the source station and the drain hopper directly below it —
            // the hopper always holds the same item and would swallow everything
            if (pos.equals(stationPos) || pos.equals(stationPos.below())) continue;
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null || be instanceof PalWorkStationBlockEntity || be instanceof PalFeederBlockEntity) continue;
            IItemHandler handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
            if (handler == null) continue;
            found.add(new Target(pos.immutable(), handler));
        }
        return found;
    }

    /**
     * Finds the nearest scanned container that already holds the same item and
     * has room — "put it where its kind already lives".
     */
    @Nullable
    private BlockPos findDestinationFor(List<Target> containers, BlockPos stationPos, ItemStack item) {
        if (item.isEmpty()) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Target target : containers) {
            double d = target.pos().distSqr(stationPos);
            if (d >= bestDist) continue;
            IItemHandler handler = target.handler();
            boolean containsSame = false;
            for (int i = 0; i < handler.getSlots(); i++) {
                if (ItemStack.isSameItemSameTags(handler.getStackInSlot(i), item)) {
                    containsSame = true;
                    break;
                }
            }
            if (!containsSame) continue;
            // Must actually have room for at least one item
            if (!ItemHandlerHelper.insertItemStacked(handler, item.copyWithCount(1), true).isEmpty()) continue;
            bestDist = d;
            best = target.pos();
        }
        return best;
    }
}
