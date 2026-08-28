package com.mx.palmod.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * The "miner" station worker: finds ore blocks (a configurable block tag,
 * default forge:ores) in radius, breaks them, and hauls the drops back to
 * its work station. Built for creatures that eat rock for a living
 * (alexscaves:rocky_roller); works for any pathfinding mob.
 */
public class OreMinerGoal extends AbstractStationWorkerGoal {

    private final TagKey<Block> oreTag;

    public OreMinerGoal(Mob mob, int harvestRadius, int wanderRadius, String oreTagId) {
        super(mob, harvestRadius, wanderRadius);
        this.oreTag = TagKey.create(Registries.BLOCK, new ResourceLocation(oreTagId));
    }

    @Override
    protected boolean isValidTarget(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(oreTag) && isReachable(level, pos);
    }

    /**
     * Only mine ore with at least one open face — fully buried ore would pin
     * the mob in place forever trying to path to something it can't reach.
     */
    private boolean isReachable(ServerLevel level, BlockPos pos) {
        for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
            BlockPos side = pos.relative(dir);
            if (level.getBlockState(side).getCollisionShape(level, side).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    @Override
    protected BlockPos findTarget(ServerLevel level) {
        BlockPos center = mob.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - harvestRadius, center.getY() - 4, center.getZ() - harvestRadius,
                center.getX() + harvestRadius, center.getY() + 4, center.getZ() + harvestRadius)) {
            if (!withinWorkRange(pos)) continue;
            if (level.getBlockState(pos).is(oreTag) && isReachable(level, pos)) {
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
        // Ores are solid blocks — mine from beside them
        return 6.25; // 2.5 blocks
    }

    @Override
    protected void doWork(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(oreTag)) return;

        mob.swing(InteractionHand.MAIN_HAND);
        level.levelEvent(2001, pos, Block.getId(state));

        for (ItemStack drop : Block.getDrops(state, level, pos, null)) {
            if (!drop.isEmpty()) {
                ItemStack leftover = pickupItem(drop);
                if (!leftover.isEmpty()) {
                    Block.popResource(level, pos, leftover);
                }
            }
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);

        chargeHunger("mine", 3.0f);
    }
}
