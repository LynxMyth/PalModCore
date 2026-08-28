package com.mx.palmod.block;

import com.mx.palmod.registry.ModRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.InvWrapper;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * The Pal Work Station: a plain 27-slot chest that worker pals haul their
 * produce to. Craftable, breakable, hopper-friendly, and bound to NOTHING —
 * any worker pal picks the nearest one at deposit time.
 *
 * A static per-dimension index of loaded stations backs {@link #findNearest}
 * so workers never have to brute-force scan for one. Entries are added in
 * {@link #onLoad()} and dropped in {@link #setRemoved()} (which fires on chunk
 * unload too — an unloaded station is unreachable anyway).
 */
public class PalWorkStationBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer {

    public static final int SLOTS = 27;
    private static final int[] ALL_SLOTS = IntStream.range(0, SLOTS).toArray();

    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private final LazyOptional<IItemHandler> lazyItemHandler = LazyOptional.of(() -> new InvWrapper(this));

    // Loaded-station index (server side only)
    private static final Map<ResourceKey<Level>, Set<BlockPos>> INDEX = new ConcurrentHashMap<>();

    public PalWorkStationBlockEntity(BlockPos pPos, BlockState pState) {
        super(ModRegistries.PAL_WORK_STATION_BLOCK_ENTITY.get(), pPos, pState);
    }

    // ──────────────────────────────────────────────────────────────
    //  Station lookup
    // ──────────────────────────────────────────────────────────────

    private static Set<BlockPos> index(ResourceKey<Level> dimension) {
        return INDEX.computeIfAbsent(dimension, k -> ConcurrentHashMap.newKeySet());
    }

    /**
     * Nearest loaded work station to {@code from} within {@code maxDist} blocks
     * that passes {@code filter} (null = any). Returns null when there is none.
     */
    @Nullable
    public static BlockPos findNearest(Level level, BlockPos from, double maxDist,
                                       @Nullable Predicate<PalWorkStationBlockEntity> filter) {
        Set<BlockPos> stations = INDEX.get(level.dimension());
        if (stations == null || stations.isEmpty()) return null;
        double bestDist = maxDist * maxDist;
        BlockPos best = null;
        for (BlockPos pos : stations) {
            double d = pos.distSqr(from);
            if (d > bestDist) continue;
            // The index can go stale (a removal we never saw) — always confirm
            if (!level.isLoaded(pos)) continue;
            if (!(level.getBlockEntity(pos) instanceof PalWorkStationBlockEntity station)) continue;
            if (filter != null && !filter.test(station)) continue;
            bestDist = d;
            best = pos;
        }
        return best;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && !level.isClientSide) {
            index(level.dimension()).add(worldPosition.immutable());
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        lazyItemHandler.invalidate();
        if (level != null && !level.isClientSide) {
            index(level.dimension()).remove(worldPosition);
        }
    }

    // ──────────────────────────────────────────────────────────────
    //  Worker-facing helpers
    // ──────────────────────────────────────────────────────────────

    /**
     * Insert a stack anywhere it fits (merge first, then empty slots).
     * Returns the leftover that could not fit.
     */
    public ItemStack depositItem(ItemStack stack) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack remaining = stack.copy();
        for (int pass = 0; pass < 2 && !remaining.isEmpty(); pass++) {
            for (int i = 0; i < SLOTS && !remaining.isEmpty(); i++) {
                ItemStack slot = items.get(i);
                if (pass == 0) {
                    if (slot.isEmpty() || !ItemStack.isSameItemSameTags(slot, remaining)) continue;
                    int space = Math.min(slot.getMaxStackSize(), getMaxStackSize()) - slot.getCount();
                    int moved = Math.min(space, remaining.getCount());
                    if (moved <= 0) continue;
                    slot.grow(moved);
                    remaining.shrink(moved);
                    setChanged();
                } else if (slot.isEmpty()) {
                    int moved = Math.min(remaining.getCount(),
                            Math.min(remaining.getMaxStackSize(), getMaxStackSize()));
                    items.set(i, remaining.copyWithCount(moved));
                    remaining.shrink(moved);
                    setChanged();
                }
            }
        }
        return remaining;
    }

    /** True if at least one of {@code stack} fits. */
    public boolean canAccept(ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (int i = 0; i < SLOTS; i++) {
            ItemStack slot = items.get(i);
            if (slot.isEmpty()) return true;
            if (ItemStack.isSameItemSameTags(slot, stack)
                    && slot.getCount() < Math.min(slot.getMaxStackSize(), getMaxStackSize())) {
                return true;
            }
        }
        return false;
    }

    /** No room left for anything at all. */
    public boolean isFull() {
        for (int i = 0; i < SLOTS; i++) {
            ItemStack slot = items.get(i);
            if (slot.isEmpty()) return false;
            if (slot.getCount() < Math.min(slot.getMaxStackSize(), getMaxStackSize())) return false;
        }
        return true;
    }

    /** First slot holding an item matching {@code test}, or -1. */
    public int findSlot(Predicate<ItemStack> test) {
        for (int i = 0; i < SLOTS; i++) {
            ItemStack slot = items.get(i);
            if (!slot.isEmpty() && test.test(slot)) return i;
        }
        return -1;
    }

    /** Contents of the first non-empty slot (live reference — read only). */
    public ItemStack getStoredItem() {
        int slot = findSlot(s -> true);
        return slot < 0 ? ItemStack.EMPTY : items.get(slot);
    }

    /** Take up to {@code count} items out of one slot. */
    public ItemStack extractStored(int slot, int count) {
        if (slot < 0 || slot >= SLOTS) return ItemStack.EMPTY;
        return removeItem(slot, count);
    }

    // ──────────────────────────────────────────────────────────────
    //  Container
    // ──────────────────────────────────────────────────────────────

    @Override
    public int getContainerSize() {
        return SLOTS;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int pSlot) {
        return items.get(pSlot);
    }

    @Override
    public ItemStack removeItem(int pSlot, int pAmount) {
        ItemStack taken = ContainerHelper.removeItem(items, pSlot, pAmount);
        if (!taken.isEmpty()) setChanged();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int pSlot) {
        return ContainerHelper.takeItem(items, pSlot);
    }

    @Override
    public void setItem(int pSlot, ItemStack pStack) {
        items.set(pSlot, pStack);
        if (pStack.getCount() > getMaxStackSize()) {
            pStack.setCount(getMaxStackSize());
        }
        setChanged();
    }

    @Override
    public boolean stillValid(Player pPlayer) {
        return Container.stillValidBlockEntity(this, pPlayer);
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    // Hoppers may push in / pull out through every face
    @Override
    public int[] getSlotsForFace(Direction pSide) {
        return ALL_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int pIndex, ItemStack pStack, @Nullable Direction pDirection) {
        return true;
    }

    @Override
    public boolean canTakeItemThroughFace(int pIndex, ItemStack pStack, Direction pDirection) {
        return true;
    }

    // ──────────────────────────────────────────────────────────────
    //  Menu / persistence / caps
    // ──────────────────────────────────────────────────────────────

    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.palmod.pal_work_station");
    }

    @Override
    protected AbstractContainerMenu createMenu(int pContainerId, Inventory pInventory) {
        return ChestMenu.threeRows(pContainerId, pInventory, this);
    }

    @Override
    protected void saveAdditional(CompoundTag pTag) {
        super.saveAdditional(pTag);
        ContainerHelper.saveAllItems(pTag, items);
    }

    @Override
    public void load(CompoundTag pTag) {
        super.load(pTag);
        items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(pTag, items);
        migrateLegacy(pTag);
    }

    /**
     * Pre-0.9.3 stations were single-slot and held the bound worker's sphere
     * NBT. Fold both into the new chest so existing worlds keep their produce
     * and get the bound pal back as an ordinary filled sphere.
     */
    private void migrateLegacy(CompoundTag pTag) {
        if (pTag.contains("Inventory")) {
            ListTag legacyItems = pTag.getCompound("Inventory").getList("Items", 10);
            for (int i = 0; i < legacyItems.size(); i++) {
                depositItem(ItemStack.of(legacyItems.getCompound(i)));
            }
        }
        if (pTag.contains("SphereNbt")) {
            CompoundTag sphereNbt = pTag.getCompound("SphereNbt");
            if (sphereNbt.contains("CapturedEntity")) {
                CompoundTag returned = sphereNbt.copy();
                returned.putBoolean("IsReleased", false);
                returned.remove("EntityUUID");
                ItemStack sphere = new ItemStack(ModRegistries.FILLED_PAL_SPHERE.get());
                sphere.setTag(returned);
                depositItem(sphere);
            }
        }
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        if (cap == ForgeCapabilities.ITEM_HANDLER) {
            return lazyItemHandler.cast();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        lazyItemHandler.invalidate();
    }
}
