package com.ziggfreed.common.instance.claims;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.event.EventRegistration;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.Window;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * One player's look into a spoils chest: the game's own chest window ({@code ContainerWindow} on the {@code Bench}
 * page, as the engine's {@code /inventory see} builds one for a container that is no block), holding that player's
 * items from the fight's Claims and nothing else. The window is a view, the Claims stay the truth: an item leaves a
 * Claim only when it leaves the window, one that comes back to the window (a move the bag could not finish) goes
 * back into its Claim, and whatever is still in the window when it closes simply stays the Claim, under Claims.
 *
 * <p><b>The rules it keeps.</b> Items only go out ({@code ALLOW_OUTPUT_ONLY}), into the bag: a drop from the window
 * to the ground is refused ({@link SpoilsChestSystems.NoDrop}), so nothing it holds can end up owner-less. What left
 * is read by item and amount after every change, never by slot (the window's sort moves slots), and taken out of the
 * fight's Claims through the store's own take and put-back, oldest Claim first. What left is announced as collected
 * from each Claim it came out of ({@link ClaimGrants#announceCollected}, a {@code ClaimCollectedEvent}, so a step
 * counting the item's arrival counts it), once: an item that comes back and leaves again is not announced twice. One
 * window per player at a time; a second use of a chest while one is open does nothing. A disconnect closes the
 * window like a close does.
 *
 * <p>World thread: the window is built and opened there, and the engine changes the container and closes the window
 * there too.
 */
final class SpoilsWindow {

    /** The most slots one window shows, seven rows; items past them stay under Claims. */
    static final int MAX_SLOTS = 63;

    /** A chest window's row. */
    private static final int ROW = 9;

    /** The open window of each player. */
    private static final Map<UUID, SpoilsWindow> OPEN = new ConcurrentHashMap<>();

    /** The item ids (lower-cased) already warned about as unknown here, one warning each. */
    private static final Set<String> WARNED_ITEMS = ConcurrentHashMap.newKeySet();

    /** One item amount taken out of one Claim: what the Claim held when it was taken, and the amount. */
    record Taken(@Nonnull Claim claim, @Nonnull InstanceReward reward) {
    }

    @Nonnull private final UUID player;
    /** The player who opened the window, in the chest's world: who a collection is announced for. */
    @Nonnull private final Ref<EntityStore> ref;
    @Nonnull private final String fight;
    @Nonnull private final ClaimStore claims;
    @Nonnull private final ItemContainer container;
    @Nonnull private final ContainerWindow window;
    /** What the window was given of each item (by lower-cased id): the most the Claims ever count in it. */
    @Nonnull private final Map<String, Integer> given;
    /** How much of each item the Claims still count as in the window. */
    @Nonnull private final Map<String, Integer> shown;
    /** Each item's first reward as its Claim held it, the shape an item going back takes. */
    @Nonnull private final Map<String, InstanceReward> templates;
    /** The Claim each item was last taken out of, where an item coming back goes. */
    @Nonnull private final Map<String, Claim> homes = new LinkedHashMap<>();
    /** How much of each item came back after its leaving was announced: its next leaving is not announced again. */
    @Nonnull private final Map<String, Integer> returned = new LinkedHashMap<>();
    @Nullable private EventRegistration<?, ?> change;
    private boolean closed;

    private SpoilsWindow(@Nonnull UUID player, @Nonnull Ref<EntityStore> ref, @Nonnull String fight,
            @Nonnull ClaimStore claims, @Nonnull ItemContainer container, @Nonnull Map<String, Integer> given,
            @Nonnull Map<String, InstanceReward> templates) {
        this.player = player;
        this.ref = ref;
        this.fight = fight;
        this.claims = claims;
        this.container = container;
        this.window = new ContainerWindow(container);
        this.given = Map.copyOf(given);
        this.shown = new LinkedHashMap<>(given);
        this.templates = Map.copyOf(templates);
    }

    /** Whether {@code player} has a spoils window open; a window the engine no longer holds reads as closed. */
    static boolean isOpen(@Nonnull UUID player, @Nonnull Player component) {
        SpoilsWindow open = OPEN.get(player);
        if (open == null) {
            return false;
        }
        int id = open.window.getId();
        Window held = id > 0 ? component.getWindowManager().getWindow(id) : null;
        if (held == open.window) {
            return true;
        }
        open.release();
        return false;
    }

    /** The id of {@code player}'s open spoils window, the section id a drop from it names; null when none is open. */
    @Nullable
    static Integer windowIdOf(@Nullable UUID player) {
        SpoilsWindow open = player == null ? null : OPEN.get(player);
        return open == null || open.window.getId() <= 0 ? null : open.window.getId();
    }

    /**
     * Open the window of {@code player}'s items from {@code fight}'s Claims. Answers whether it opened; false when
     * the Claims hold no item this server knows, or the engine would not open it (the Claims untouched either way).
     */
    static boolean open(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull Player component,
            @Nonnull UUID player, @Nonnull String fight, @Nonnull ClaimStore claims) {
        Map<String, Integer> given = new LinkedHashMap<>();
        Map<String, InstanceReward> templates = new LinkedHashMap<>();
        List<ItemStack> stacks = stacks(claims.list(player), fight, given, templates);
        if (stacks.isEmpty()) {
            return false;
        }
        int capacity = Math.min(MAX_SLOTS, Math.max(ROW, (stacks.size() + ROW - 1) / ROW * ROW));
        SimpleItemContainer container = new SimpleItemContainer((short) capacity);
        // The slots are filled before anything listens and before the filter shuts the way in.
        for (int slot = 0; slot < stacks.size(); slot++) {
            container.setItemStackForSlot((short) slot, stacks.get(slot));
        }
        container.setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);
        SpoilsWindow session = new SpoilsWindow(player, ref, fight, claims, container, given, templates);
        session.change = container.registerChangeEvent(event -> session.reconcile());
        session.window.registerCloseEvent(event -> session.onClose());
        OPEN.put(player, session);
        boolean opened = false;
        try {
            opened = component.getPageManager().setPageWithWindows(ref, store, Page.Bench, true, session.window);
        } finally {
            if (!opened) {
                session.release();
            }
        }
        if (opened) {
            SafeLog.info(SpoilsChests.LOG + "window open run=" + fight + " player=" + player + " stacks="
                    + stacks.size());
        }
        return opened;
    }

    // ==================== the write-back ====================

    /**
     * Square the Claims with the window, item by item: what left since the last look comes out of the Claims and is
     * announced as collected from each Claim it came out of, what came back goes back into the Claim it left. Never
     * more than the window was given counts, so an item that found its way in some other way is never written to a
     * Claim. Never throws.
     */
    private void reconcile() {
        try {
            Map<String, Integer> now = totals(container);
            for (Map.Entry<String, Integer> entry : shown.entrySet()) {
                String item = entry.getKey();
                int held = Math.min(now.getOrDefault(item, 0), given.getOrDefault(item, 0));
                int counted = entry.getValue();
                if (held < counted) {
                    List<Taken> taken = new ArrayList<>();
                    int missing = takeItems(claims, player, fight, item, counted - held, homes, taken);
                    if (missing > 0) {
                        SafeLog.warn(SpoilsChests.LOG + "the window of run=" + fight + " gave out " + missing + " "
                                + item + " more than its Claims held");
                    }
                    int again = Math.min(counted - held, returned.getOrDefault(item, 0));
                    if (again > 0) {
                        returned.merge(item, -again, Integer::sum);
                    }
                    announce(taken, again);
                } else if (held > counted && putBack(item, held - counted)) {
                    returned.merge(item, held - counted, Integer::sum);
                }
                entry.setValue(held);
            }
        } catch (Throwable t) {
            SafeLog.warn(SpoilsChests.LOG + "a spoils window could not square its Claims", t);
        }
    }

    /**
     * Announce {@code taken} as collected, Claim by Claim in the order it came out, leaving out its first
     * {@code already} (an amount that came back after its leaving was announced, leaving again).
     */
    private void announce(@Nonnull List<Taken> taken, int already) {
        int skip = already;
        for (Taken each : taken) {
            int quantity = each.reward().quantity();
            int fresh = quantity - Math.min(skip, quantity);
            skip -= quantity - fresh;
            if (fresh > 0) {
                ClaimGrants.announceCollected(player, ref, each.claim(), List.of(each.reward().withQuantity(fresh)));
            }
        }
    }

    /**
     * Return {@code quantity} of {@code item}, back in the window, to the Claim it was last taken out of; whether it
     * went back (false for an item the window never gave out).
     */
    private boolean putBack(@Nonnull String item, int quantity) {
        Claim home = homes.get(item);
        InstanceReward template = templates.get(item);
        if (home == null || template == null) {
            SafeLog.warn(SpoilsChests.LOG + "the window of run=" + fight + " got back " + quantity + " " + item
                    + " it never gave out");
            return false;
        }
        claims.putBack(player, home.withRewards(List.of(template.withQuantity(quantity))));
        return true;
    }

    /** The window closed (by the player, a disconnect, a world change): square what is left, then let go. */
    private void onClose() {
        reconcile();
        release();
    }

    /** Stop listening and forget the window; whatever it still held stays in the Claims, which never lost it. */
    private void release() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (change != null) {
                change.unregister();
            }
        } catch (Throwable t) {
            SafeLog.fine(SpoilsChests.LOG + "a spoils window's listener could not be dropped: " + t.getMessage());
        }
        OPEN.remove(player, this);
    }

    // ==================== the pieces ====================

    /**
     * The stacks a window shows of {@code fight}'s item rewards in {@code held} (oldest Claim first), each item's
     * total split at its own stack size, at most {@link #MAX_SLOTS}; {@code given} receives how much of each item (by
     * lower-cased id) went in, and {@code templates} each item's first reward. An item this server does not know stays
     * in its Claim, unshown.
     */
    @Nonnull
    private static List<ItemStack> stacks(@Nonnull List<Claim> held, @Nonnull String fight,
            @Nonnull Map<String, Integer> given, @Nonnull Map<String, InstanceReward> templates) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Claim claim : held) {
            if (!claim.fromFight(fight)) {
                continue;
            }
            for (InstanceReward reward : claim.rewards()) {
                if (reward.isItem()) {
                    String key = reward.id().toLowerCase(Locale.ROOT);
                    totals.merge(key, reward.quantity(), Integer::sum);
                    templates.putIfAbsent(key, reward);
                }
            }
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            String id = templates.get(entry.getKey()).id();
            Item item = Item.getAssetMap().getAsset(id);
            if (item == null) {
                if (WARNED_ITEMS.add(entry.getKey())) {
                    SafeLog.warn(SpoilsChests.LOG + "the item '" + id + "' is not on this server, so a spoils window "
                            + "leaves it in its Claim");
                }
                continue;
            }
            int max = Math.max(1, item.getMaxStack());
            int left = entry.getValue();
            while (left > 0 && stacks.size() < MAX_SLOTS) {
                int amount = Math.min(max, left);
                stacks.add(new ItemStack(id, amount));
                given.merge(entry.getKey(), amount, Integer::sum);
                left -= amount;
            }
        }
        return stacks;
    }

    /** How much of each item (by lower-cased id) {@code container} holds now. */
    @Nonnull
    private static Map<String, Integer> totals(@Nonnull ItemContainer container) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        container.forEach((slot, stack) -> {
            if (!ItemStack.isEmpty(stack) && stack.getItemId() != null) {
                totals.merge(stack.getItemId().toLowerCase(Locale.ROOT), stack.getQuantity(), Integer::sum);
            }
        });
        return totals;
    }

    /**
     * Take {@code quantity} of the item {@code itemId} out of the Claims {@code fight} paid {@code player}, oldest
     * first, each through the store's own take and put-back (a Claim left with nothing is not put back), noting in
     * {@code homes} the Claim the item last came out of and in {@code taken} each amount taken, by the Claim it came
     * out of. Answers how much could not be found.
     */
    static int takeItems(@Nonnull ClaimStore claims, @Nonnull UUID player, @Nonnull String fight,
            @Nonnull String itemId, int quantity, @Nonnull Map<String, Claim> homes, @Nonnull List<Taken> taken) {
        int left = quantity;
        for (Claim listed : claims.list(player)) {
            if (left <= 0) {
                break;
            }
            if (!listed.fromFight(fight) || held(listed, itemId) <= 0) {
                continue;
            }
            Claim claim = claims.take(player, listed.id());
            if (claim == null) {
                continue;
            }
            homes.put(itemId.toLowerCase(Locale.ROOT), claim);
            List<InstanceReward> kept = new ArrayList<>(claim.rewards().size());
            for (InstanceReward reward : claim.rewards()) {
                if (left > 0 && reward.isItem() && reward.id().equalsIgnoreCase(itemId)) {
                    int used = Math.min(left, reward.quantity());
                    left -= used;
                    if (used > 0) {
                        taken.add(new Taken(claim, reward.withQuantity(used)));
                    }
                    if (reward.quantity() > used) {
                        kept.add(reward.withQuantity(reward.quantity() - used));
                    }
                } else {
                    kept.add(reward);
                }
            }
            claims.putBack(player, claim.withRewards(kept));
        }
        return Math.max(0, left);
    }

    /** How much of the item {@code itemId} {@code claim} holds. */
    private static int held(@Nonnull Claim claim, @Nonnull String itemId) {
        int held = 0;
        for (InstanceReward reward : claim.rewards()) {
            if (reward.isItem() && reward.id().equalsIgnoreCase(itemId)) {
                held += reward.quantity();
            }
        }
        return held;
    }
}
