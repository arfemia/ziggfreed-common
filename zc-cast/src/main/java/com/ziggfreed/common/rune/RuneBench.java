package com.ziggfreed.common.rune;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.abilities.window.AbilityBenchWindow;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.ItemContainerUtil;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterActionType;
import com.hypixel.hytale.server.core.inventory.container.filter.SlotFilter;
import com.hypixel.hytale.server.core.inventory.transaction.ActionType;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.MoveType;
import com.hypixel.hytale.server.core.inventory.transaction.SlotTransaction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;

/**
 * The game's own rune bench screen ({@link AbilityBenchWindow}, "Runebinder's Lattice", beside the player's
 * Runestone Storage) opened over one ability line a mod owns, instead of over the player's own ability slots.
 *
 * <p><b>The line.</b> A line is the game's bench line: a core rune in its first slot and two support runes
 * after it ({@link #LINE_WIDTH}). A consumer names each of its lines with an id of its own ({@code
 * "mymod:slot_1"}) and hands {@link #open} the core to show there. The core is the consumer's to mint each time
 * the line opens: it is placed by the server, locked in its slot (no add, no remove, no drop, so it can be
 * neither dragged out, shift-clicked out, dropped nor swapped for another core) and never written back.
 * Supports are the player's own items: they move in and out through the engine's own support filter ({@code
 * AbilitySupportSlotFilter}: a support fits when the core's keywords satisfy it, one copy per line), from the
 * Runestone Storage panel, the bag or the hotbar.
 *
 * <p><b>The screen.</b> The client draws two lines ({@link #SCREEN_LINES}). {@link #open} lays out a
 * container of {@code lines} lines (1 or 2): the consumer's line is the first, and a second line, when there
 * is one, is empty and shut (nothing can be added to it). Which layout draws cleanly is the client's to show.
 *
 * <p><b>A refused move.</b> The client predicts a move into or out of a bench slot by the game's own bench
 * rules, which let a core leave its slot and let any core or support into an empty line. When a locked slot
 * refuses, the window and the player's bag, hotbar, backpack and Runestone Storage are sent again, as the
 * engine does after it vetoes a move on the player's own bench, so a predicted move snaps back. The engine
 * answers a refused quick-move out of a locked slot with no transaction at all, which its caller then reads;
 * the bench container answers with a failed one instead, so a shift-click on the core is simply refused.
 *
 * <p><b>What a line holds.</b> The supports socketed in each line are kept in memory, per player and line id,
 * and written back on every change in the open window and again when it closes, so the open container is the
 * truth and a reopen shows what was left there. That record is transient and single-process: a restart forgets
 * it, and the supports in it with it. Nothing here persists.
 *
 * <p>Inert on a server where no mod opens a line: nothing is registered and nothing ticks. Every call that
 * touches a {@link Store} or {@link Ref} runs on that entity's world thread.
 */
public final class RuneBench {

    /** Slots in one bench line: the core, then its supports (the game's own line width). */
    public static final short LINE_WIDTH = 3;
    /** Support slots in one line. */
    public static final int SUPPORTS_PER_LINE = LINE_WIDTH - 1;
    /** Lines the game's rune screen draws. */
    public static final int SCREEN_LINES = 2;
    /** The consumer's line's core slot. */
    public static final short CORE_SLOT = 0;

    /** Player id to line id to the supports the line holds; transient and single-process (see the class doc). */
    private static final Map<UUID, Map<String, ItemStack[]>> HELD = new ConcurrentHashMap<>();

    private RuneBench() {
    }

    /** {@link #open(Ref, Store, String, ItemStack, int)} over the screen's own two lines, the second shut. */
    public static boolean open(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull String lineId, @Nullable ItemStack core) {
        return open(ref, store, lineId, core, SCREEN_LINES);
    }

    /**
     * Open the game's rune screen for the player at {@code ref} over line {@code lineId}: {@code core} locked in
     * its first slot (null leaves it empty, so no support fits), the supports the line holds behind it, laid out
     * in {@code lines} lines (clamped to 1 or 2; the second one shut). On the player's world thread. False when
     * {@code ref} is not a player or the page manager refused; the line's supports stay held either way.
     */
    public static boolean open(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull String lineId, @Nullable ItemStack core, int lines) {
        PlayerRef playerRef = PlayerAccess.playerRef(store, ref);
        Player player = store.getComponent(ref, Player.getComponentType());
        if (playerRef == null || player == null) {
            SafeLog.fine("[runes] a rune bench was asked for by an entity that is not a player");
            return false;
        }
        UUID playerId = playerRef.getUuid();
        LineContainer container = build(core, supports(playerId, lineId), lines);
        AbilityBenchWindow window = new AbilityBenchWindow(container);
        container.onRefused = () -> resync(ref, store, player, window);
        EventRegistration<Void, ItemContainer.ItemContainerChangeEvent> changes =
                container.registerChangeEvent(event -> keep(playerId, lineId, container));
        window.registerCloseEvent(event -> {
            keep(playerId, lineId, container);
            changes.unregister();
            container.onRefused = null;
        });
        boolean opened;
        try {
            opened = player.getPageManager().setPageWithWindows(ref, store, Page.Bench, true, window);
        } catch (Throwable t) {
            SafeLog.warn("[runes] the rune bench for line '" + lineId + "' failed to open", t);
            opened = false;
        }
        if (!opened) {
            changes.unregister();
            container.onRefused = null;
        }
        return opened;
    }

    /**
     * The bench container {@link #open} shows, without opening it: {@code lines} lines (clamped to 1 or 2) laid
     * out with the engine's own ability filters, {@code core} placed in {@link #CORE_SLOT} and locked (add,
     * remove and drop all refused), the given supports placed behind it as they are (a support the core no
     * longer fits stays, idle), and every slot of a second line refusing any add.
     */
    @Nonnull
    public static SimpleItemContainer lineContainer(@Nullable ItemStack core, @Nullable List<ItemStack> supports,
            int lines) {
        return build(core, supports, lines);
    }

    /**
     * The supports line {@code lineId} holds for {@code playerId}, in slot order with an empty slot as null, as
     * of the last change in an open window or its close; an empty list for a line never opened.
     */
    @Nonnull
    public static List<ItemStack> supports(@Nonnull UUID playerId, @Nonnull String lineId) {
        Map<String, ItemStack[]> lines = HELD.get(playerId);
        ItemStack[] held = lines == null ? null : lines.get(lineId);
        if (held == null) {
            return List.of();
        }
        List<ItemStack> out = new ArrayList<>(held.length);
        Collections.addAll(out, held);
        return Collections.unmodifiableList(out);
    }

    @Nonnull
    private static LineContainer build(@Nullable ItemStack core, @Nullable List<ItemStack> supports, int lines) {
        int shown = Math.max(1, Math.min(SCREEN_LINES, lines));
        LineContainer container = new LineContainer((short) (LINE_WIDTH * shown));
        ItemContainerUtil.trySetAbilityFilters(container, LINE_WIDTH);
        if (!ItemStack.isEmpty(core)) {
            container.setItemStackForSlot(CORE_SLOT, core, false);
        }
        if (supports != null) {
            for (int i = 0; i < SUPPORTS_PER_LINE && i < supports.size(); i++) {
                ItemStack support = supports.get(i);
                if (!ItemStack.isEmpty(support)) {
                    container.setItemStackForSlot((short) (CORE_SLOT + 1 + i), support, false);
                }
            }
        }
        container.setSlotFilter(FilterActionType.ADD, CORE_SLOT, SlotFilter.DENY);
        container.setSlotFilter(FilterActionType.REMOVE, CORE_SLOT, SlotFilter.DENY);
        container.setSlotFilter(FilterActionType.DROP, CORE_SLOT, SlotFilter.DENY);
        for (short slot = LINE_WIDTH; slot < container.getCapacity(); slot++) {
            container.setSlotFilter(FilterActionType.ADD, slot, SlotFilter.DENY);
        }
        return container;
    }

    /** Record the support slots of the open container as what the line holds. */
    private static void keep(@Nonnull UUID playerId, @Nonnull String lineId, @Nonnull ItemContainer container) {
        ItemStack[] held = new ItemStack[SUPPORTS_PER_LINE];
        boolean any = false;
        for (int i = 0; i < SUPPORTS_PER_LINE; i++) {
            ItemStack stack = container.getItemStack((short) (CORE_SLOT + 1 + i));
            if (!ItemStack.isEmpty(stack)) {
                held[i] = stack;
                any = true;
            }
        }
        Map<String, ItemStack[]> lines = HELD.computeIfAbsent(playerId, id -> new ConcurrentHashMap<>());
        if (any) {
            lines.put(lineId, held);
        } else {
            lines.remove(lineId);
        }
    }

    /** The player's sections a move out of or into the bench can touch, sent again after a refused one. */
    @Nonnull
    private static List<ComponentType<EntityStore, ? extends InventoryComponent>> resyncedSections() {
        return List.of(InventoryComponent.Hotbar.getComponentType(), InventoryComponent.Storage.getComponentType(),
                InventoryComponent.Backpack.getComponentType(), InventoryComponent.RuneBag.getComponentType());
    }

    /** Send the bench window and the player's own sections again, so a move the client predicted snaps back. */
    private static void resync(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Player player, @Nonnull AbilityBenchWindow window) {
        try {
            player.getWindowManager().markWindowChanged(window.getId());
            for (ComponentType<EntityStore, ? extends InventoryComponent> type : resyncedSections()) {
                InventoryComponent section = store.getComponent(ref, type);
                if (section != null) {
                    section.markDirty();
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("[runes] a refused bench move could not resync the client: " + t.getMessage());
        }
    }

    /**
     * The bench's container: the engine's own, plus two answers to a refusal from a locked slot (the core slot,
     * and every slot of a shut second line). It resyncs the client ({@link #onRefused}), and a quick-move out
     * of a locked slot answers with a failed transaction where the engine's own answer is null.
     */
    private static final class LineContainer extends SimpleItemContainer {

        /** Set while the window is open; run on the world thread whenever a locked slot refuses. */
        @Nullable
        private volatile Runnable onRefused;

        LineContainer(short capacity) {
            super(capacity);
        }

        @Override
        protected boolean cantAddToSlot(short slot, ItemStack itemStack, ItemStack slotItemStack) {
            return refusedAt(slot, super.cantAddToSlot(slot, itemStack, slotItemStack));
        }

        @Override
        protected boolean cantRemoveFromSlot(short slot) {
            return refusedAt(slot, super.cantRemoveFromSlot(slot));
        }

        @Override
        protected boolean cantDropFromSlot(short slot) {
            return refusedAt(slot, super.cantDropFromSlot(slot));
        }

        @Override
        protected MoveTransaction<ItemStackTransaction> internal_moveItemStackFromSlot(short slot, int quantity,
                @Nonnull ItemContainer containerTo, boolean allOrNothing, boolean filter) {
            if (filter && cantRemoveFromSlot(slot)) {
                return refusedMove(slot, containerTo);
            }
            return super.internal_moveItemStackFromSlot(slot, quantity, containerTo, allOrNothing, filter);
        }

        @Override
        protected MoveTransaction<ItemStackTransaction> internal_moveItemStackFromSlot(short slot,
                @Nonnull ItemContainer containerTo, boolean allOrNothing, boolean filter) {
            if (filter && cantRemoveFromSlot(slot)) {
                return refusedMove(slot, containerTo);
            }
            return super.internal_moveItemStackFromSlot(slot, containerTo, allOrNothing, filter);
        }

        private boolean refusedAt(short slot, boolean refused) {
            Runnable resync = onRefused;
            if (refused && resync != null && (slot == CORE_SLOT || slot >= LINE_WIDTH)) {
                resync.run();
            }
            return refused;
        }

        @Nonnull
        private MoveTransaction<ItemStackTransaction> refusedMove(short slot, @Nonnull ItemContainer containerTo) {
            ItemStack held = getItemStack(slot);
            SlotTransaction remove = new SlotTransaction(false, ActionType.REMOVE, slot, held, held, null,
                    false, false, true);
            return new MoveTransaction<>(false, remove, MoveType.MOVE_FROM_SELF, containerTo,
                    ItemStackTransaction.FAILED_ADD);
        }
    }
}
