package com.mx.palmod.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The "lumberjack" station worker: finds the base of a tree (a log with a log
 * above it), fells the whole connected trunk (bounded flood-fill up to logCap),
 * carries the logs back and deposits them at its work station.
 */
public class TreeFellerGoal extends AbstractStationWorkerGoal {

    /** Never fell more than this many logs from one tree (mega-tree safety cap). */
    private final int logCap;
    /** Trunk search is bounded to this distance from the base log. */
    private static final double MAX_TREE_REACH_SQR = 12 * 12;

    public TreeFellerGoal(Mob mob, int harvestRadius, int wanderRadius, int logCap) {
        super(mob, harvestRadius, wanderRadius);
        this.logCap = logCap;
    }

    @Override
    protected boolean isValidTarget(ServerLevel level, BlockPos pos) {
        return isTreeBase(level, pos);
    }

    @Nullable
    @Override
    protected BlockPos findTarget(ServerLevel level) {
        BlockPos center = mob.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - harvestRadius, center.getY() - 3, center.getZ() - harvestRadius,
                center.getX() + harvestRadius, center.getY() + 3, center.getZ() + harvestRadius)) {
            if (!withinWorkRange(pos)) continue;
            if (isTreeBase(level, pos)) {
                double d = pos.distSqr(center);
                if (d < bestDist) {
                    bestDist = d;
                    best = pos.immutable();
                }
            }
        }
        return best;
    }

    @Override
    protected double workReachSqr() {
        // Trunks are solid blocks — the mob stands beside them, not on them
        return 6.25; // 2.5 blocks
    }

    @Override
    protected void doWork(ServerLevel level, BlockPos base) {
        // Bounded flood-fill over connected logs. The 3x3x3 neighborhood follows
        // diagonal branches (acacia, large oaks).
        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        List<BlockPos> logs = new ArrayList<>();
        queue.add(base);
        visited.add(base);
        while (!queue.isEmpty() && logs.size() < logCap) {
            BlockPos p = queue.poll();
            if (!level.getBlockState(p).is(BlockTags.LOGS)) continue;
            logs.add(p);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        BlockPos n = p.offset(dx, dy, dz);
                        if (n.distSqr(base) <= MAX_TREE_REACH_SQR && visited.add(n)) {
                            queue.add(n);
                        }
                    }
                }
            }
        }
        if (logs.isEmpty()) return;

        // A real tree has natural (non-persistent) leaves touching its logs.
        // Player-built log structures don't — leave those alone.
        if (!hasNaturalLeaves(level, logs)) return;

        // One visible swing + break effect at the base, then fell the tree
        mob.swing(InteractionHand.MAIN_HAND);
        level.levelEvent(2001, base, Block.getId(level.getBlockState(base)));

        for (BlockPos p : logs) {
            BlockState state = level.getBlockState(p);
            for (ItemStack drop : Block.getDrops(state, level, p, null)) {
                if (!drop.isEmpty()) {
                    ItemStack leftover = pickupItem(drop);
                    if (!leftover.isEmpty()) {
                        Block.popResource(level, p, leftover);
                    }
                }
            }
            level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }

        // One tree felled = one chop's worth of hunger
        chargeHunger("chop", 4.0f);
    }

    private boolean hasNaturalLeaves(ServerLevel level, List<BlockPos> logs) {
        for (BlockPos p : logs) {
            for (Direction dir : Direction.values()) {
                BlockState s = level.getBlockState(p.relative(dir));
                if (s.is(BlockTags.LEAVES)
                        && (!s.hasProperty(LeavesBlock.PERSISTENT) || !s.getValue(LeavesBlock.PERSISTENT))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A log with a log above it, and no log below (so we start from the stump). */
    private boolean isTreeBase(Level level, BlockPos pos) {
        return level.getBlockState(pos).is(BlockTags.LOGS)
                && level.getBlockState(pos.above()).is(BlockTags.LOGS)
                && !level.getBlockState(pos.below()).is(BlockTags.LOGS);
    }
}
