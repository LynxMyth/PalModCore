package com.mx.palmod.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The "harvester" station worker (originally built for the leafcutter ant):
 * finds mature crops in radius, harvests them (crop resets to age 0),
 * and deposits the produce at its work station.
 */
public class LeafcutterHarvesterGoal extends AbstractStationWorkerGoal {

    public LeafcutterHarvesterGoal(Mob mob, int harvestRadius, int wanderRadius) {
        super(mob, harvestRadius, wanderRadius);
    }

    /** Crops it harvests are food for it — lets a starving ant work its way back up. */
    @Override
    protected boolean selfFeedsFromOutput() {
        return true;
    }

    @Override
    protected boolean isValidTarget(ServerLevel level, BlockPos pos) {
        return isRipeCrop(level, pos);
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
            // Constrain within wander radius of station too
            if (!withinWorkRange(pos)) continue;
            if (isRipeCrop(level, pos)) {
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
    protected void doWork(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (!(block instanceof CropBlock cropBlock)) return;

        // Harvest: collect drops and reset the crop to age 0
        List<ItemStack> drops = Block.getDrops(state, level, pos, null);
        level.setBlock(pos, cropBlock.getStateForAge(0), 3);
        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                ItemStack leftover = pickupItem(drop);
                if (!leftover.isEmpty()) {
                    Block.popResource(level, pos, leftover);
                }
            }
        }

        // Deduct hunger cost for harvesting
        chargeHunger("harvest", 2.0f);
    }

    private boolean isRipeCrop(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof CropBlock crop && crop.isMaxAge(state);
    }
}
