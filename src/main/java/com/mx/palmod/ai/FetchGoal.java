package com.mx.palmod.ai;

import com.mx.palmod.stats.PalStats;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.EnumSet;

/**
 * Fetch: the owner sneak-clicks the pal while holding a SAMPLE item; the pal
 * runs to the nearest container (within fetchRadius of the owner) holding that
 * item, grabs up to a stack, and delivers it into the owner's inventory.
 *
 * The request is stored in the pal's ForgeData as "FetchItem" (item id).
 */
public class FetchGoal extends Goal {

    private final Mob mob;
    private final int fetchRadius;

    @Nullable
    private BlockPos sourceChest = null;
    private ItemStack carried = ItemStack.EMPTY;
    private int tickDelay = 0;

    public FetchGoal(Mob mob, int fetchRadius) {
        this.mob = mob;
        this.fetchRadius = fetchRadius;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return mob.getPersistentData().contains("FetchItem") && !PalStats.isInactive(mob);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse() || !carried.isEmpty();
    }

    @Override
    public void stop() {
        sourceChest = null;
    }

    @Override
    public void tick() {
        if (--tickDelay > 0) return;
        tickDelay = 10;

        if (!(mob.level() instanceof ServerLevel level)) return;
        Player owner = ownerOf(level);
        if (owner == null) {
            abort(null);
            return;
        }

        // Phase 2: carrying — bring it home
        if (!carried.isEmpty()) {
            if (mob.distanceToSqr(owner) > 6.25) {
                mob.getNavigation().moveTo(owner, 1.15);
                return;
            }
            ItemHandlerHelper.giveItemToPlayer(owner, carried);
            owner.sendSystemMessage(Component.literal(
                    mob.getName().getString() + " fetched " + carried.getCount() + "x "
                            + carried.getHoverName().getString() + "!"));
            carried = ItemStack.EMPTY;
            mob.getPersistentData().remove("FetchItem");
            return;
        }

        Item wanted = requestedItem();
        if (wanted == null) {
            abort(owner);
            return;
        }

        // Phase 1: find and raid the chest
        if (sourceChest == null || !containerHas(level, sourceChest, wanted)) {
            sourceChest = findChestWith(level, owner.blockPosition(), wanted);
            if (sourceChest == null) {
                owner.sendSystemMessage(Component.literal(
                        mob.getName().getString() + " couldn't find any "
                                + new ItemStack(wanted).getHoverName().getString() + " nearby."));
                abort(owner);
                return;
            }
        }

        double dist = mob.distanceToSqr(sourceChest.getX() + 0.5, sourceChest.getY(), sourceChest.getZ() + 0.5);
        if (dist > 4.0) {
            mob.getNavigation().moveTo(sourceChest.getX() + 0.5, sourceChest.getY(), sourceChest.getZ() + 0.5, 1.15);
            return;
        }

        // At the chest: extract up to one stack of the wanted item
        BlockEntity be = level.getBlockEntity(sourceChest);
        if (be == null) return;
        IItemHandler handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
        if (handler == null) return;

        int wantedCount = wanted.getMaxStackSize();
        for (int slot = 0; slot < handler.getSlots() && wantedCount > 0; slot++) {
            ItemStack inSlot = handler.getStackInSlot(slot);
            if (!inSlot.is(wanted)) continue;
            // Never merge stacks with different NBT (potions, enchanted books...)
            if (!carried.isEmpty() && !ItemStack.isSameItemSameTags(carried, inSlot)) continue;
            ItemStack taken = handler.extractItem(slot, wantedCount, false);
            if (!taken.isEmpty()) {
                if (carried.isEmpty()) {
                    carried = taken;
                } else {
                    carried.grow(taken.getCount());
                }
                wantedCount -= taken.getCount();
            }
        }
        if (carried.isEmpty()) {
            sourceChest = null; // stale — rescan next tick
        }
    }

    // ──────────────────────────────────────────────────────────────

    @Nullable
    private Item requestedItem() {
        String id = mob.getPersistentData().getString("FetchItem");
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
    }

    @Nullable
    private Player ownerOf(ServerLevel level) {
        if (!mob.getPersistentData().hasUUID("PalOwner")) return null;
        return level.getPlayerByUUID(mob.getPersistentData().getUUID("PalOwner"));
    }

    private void abort(@Nullable Player owner) {
        mob.getPersistentData().remove("FetchItem");
        if (!carried.isEmpty() && owner != null) {
            ItemHandlerHelper.giveItemToPlayer(owner, carried);
            carried = ItemStack.EMPTY;
        }
        sourceChest = null;
    }

    private boolean containerHas(ServerLevel level, BlockPos pos, Item item) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return false;
        IItemHandler handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
        if (handler == null) return false;
        for (int i = 0; i < handler.getSlots(); i++) {
            if (handler.getStackInSlot(i).is(item)) return true;
        }
        return false;
    }

    @Nullable
    private BlockPos findChestWith(ServerLevel level, BlockPos center, Item item) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                center.getX() - fetchRadius, center.getY() - 3, center.getZ() - fetchRadius,
                center.getX() + fetchRadius, center.getY() + 3, center.getZ() + fetchRadius)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null || be instanceof com.mx.palmod.block.PalFeederBlockEntity) continue;
            if (!containerHas(level, pos, item)) continue;
            double d = pos.distSqr(center);
            if (d < bestDist) {
                bestDist = d;
                best = pos.immutable();
            }
        }
        return best;
    }
}
