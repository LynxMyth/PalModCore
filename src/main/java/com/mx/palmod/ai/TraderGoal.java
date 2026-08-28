package com.mx.palmod.ai;

import com.mx.palmod.stats.PalTradeManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;

/**
 * The "trader" station worker (built for the crow): the player drops payment
 * items (e.g. emeralds) on the ground near the station; the crow hops over,
 * snatches one, "trades" it for a weighted-random result from its pal_trades
 * datapack table, and carries the result back to the station.
 */
public class TraderGoal extends AbstractStationWorkerGoal {

    public TraderGoal(Mob mob, int harvestRadius, int wanderRadius) {
        super(mob, harvestRadius, wanderRadius);
    }

    @Override
    protected boolean isValidTarget(ServerLevel level, BlockPos pos) {
        return findPayment(level, pos, 2.5) != null;
    }

    @Nullable
    @Override
    protected BlockPos findTarget(ServerLevel level) {
        PalTradeManager.TradeTable table = PalTradeManager.getTable(mob.getType());
        if (table == null || table.getPayment() == null) return null;

        List<ItemEntity> payments = level.getEntitiesOfClass(ItemEntity.class,
                mob.getBoundingBox().inflate(harvestRadius),
                e -> e.isAlive() && e.getItem().is(table.getPayment())
                        && withinWorkRange(e.blockPosition()));
        return payments.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(mob)))
                .map(ItemEntity::blockPosition)
                .orElse(null);
    }

    @Override
    protected double workReachSqr() {
        return 4.0; // 2 blocks — close enough to snatch an item off the ground
    }

    @Override
    protected void doWork(ServerLevel level, BlockPos pos) {
        PalTradeManager.TradeTable table = PalTradeManager.getTable(mob.getType());
        if (table == null) return;

        ItemEntity payment = findPayment(level, pos, 2.5);
        if (payment == null) return;

        // Roll first — a broken trade table must not eat payments
        ItemStack result = table.rollResult(mob.getRandom());
        if (result.isEmpty()) return;

        // Take exactly one payment item
        ItemStack stack = payment.getItem().copy();
        stack.shrink(1);
        if (stack.isEmpty()) {
            payment.discard();
        } else {
            payment.setItem(stack);
        }

        // Carry the result home
        ItemStack leftover = pickupItem(result);
        if (!leftover.isEmpty()) {
            Block.popResource(level, pos, leftover);
        }

        level.sendParticles(ParticleTypes.ENCHANT,
                mob.getX(), mob.getY() + 0.5, mob.getZ(), 12, 0.3, 0.3, 0.3, 0.1);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.NEUTRAL, 0.7F, 1.2F);

        chargeHunger("trade", 3.0f);
    }

    @Nullable
    private ItemEntity findPayment(ServerLevel level, BlockPos pos, double range) {
        PalTradeManager.TradeTable table = PalTradeManager.getTable(mob.getType());
        if (table == null || table.getPayment() == null) return null;
        List<ItemEntity> payments = level.getEntitiesOfClass(ItemEntity.class,
                new AABB(pos).inflate(range),
                e -> e.isAlive() && e.getItem().is(table.getPayment()));
        return payments.isEmpty() ? null : payments.get(0);
    }
}
