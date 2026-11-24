package com.ziggfreed.common.track.lamp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackReading;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.price.TrackPricing;
import com.ziggfreed.common.util.SafeLog;

/**
 * Everything a lamp does to a player: which tracks it may go to for them right now ({@link #choices}), what each
 * would pay (priced in minutes at the player's level on that track, R618), and using it ({@link #rub}), which takes
 * one lamp of those numbers from the bag BEFORE paying, then pays each pick past every boost
 * ({@link TrackService#addExact}). World thread: it reads and writes the player's store.
 */
public final class TrackLamps {

    /** What a lamp's pay is labelled with. */
    public static final String SOURCE = "lamp";

    /** One track a lamp may go to, and what it pays there now. */
    public record Choice(@Nonnull TrackDef def, long pays) {
    }

    /** How a use ended. */
    public enum Outcome {
        /** The lamp is spent and every pick was paid. */
        PAID,
        /** No lamp with these numbers is in the bag any more; nothing changed. */
        NO_LAMP,
        /** The picks are not as many different tracks as the lamp asks for. */
        WRONG_COUNT,
        /** A pick is not one this lamp offers the player right now. */
        NOT_CHOOSABLE,
        /** No player behind the reference. */
        NO_PLAYER
    }

    /** A use's outcome, and what each pick paid, by track, in the order picked (empty unless any was). */
    public record Rub(@Nonnull Outcome outcome, @Nonnull Map<TrackDef, Long> paid) {

        public Rub {
            paid = Collections.unmodifiableMap(new LinkedHashMap<>(paid));
        }

        @Nonnull
        static Rub of(@Nonnull Outcome outcome) {
            return new Rub(outcome, Map.of());
        }
    }

    private TrackLamps() {
    }

    /**
     * The tracks {@code lamp} may go to for the player at {@code ref} right now, in the order their files sort, each
     * with what it pays: a usable track it names (its {@code Tracks}, else its {@code Category}'s, else every track),
     * not a derived one, the player standing at its {@code MinLevel} or past it, an exact gain landing on it now
     * ({@link TrackService#paysExactly}: its {@code Requires} met, no factor stopping it, its world paying, below every
     * cap), and a price above 0 there (a curve with a Pace).
     */
    @Nonnull
    public static List<Choice> choices(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull UUID player, @Nonnull TrackLamp lamp) {
        List<Choice> out = new ArrayList<>();
        TrackPricing.Priced priced = lamp.priced();
        if (!priced.pays()) {
            return out;
        }
        for (TrackDef def : pool(lamp)) {
            try {
                TrackKey key = TrackKey.player(player, def.id());
                TrackReading reading = TrackService.read(key);
                if (reading == null || reading.level() < lamp.minLevel() || !TrackService.paysExactly(key)) {
                    continue;
                }
                long pays = TrackPricing.price(def, priced, reading.level());
                if (pays > 0L) {
                    out.add(new Choice(def, pays));
                }
            } catch (Throwable t) {
                SafeLog.fine("[track] a lamp could not weigh the track '" + def.id() + "': " + t.getMessage());
            }
        }
        return out;
    }

    /**
     * The usable, not derived tracks {@code lamp} names, in the order the tracks sort: its {@code Tracks}, else every
     * track of its {@code Category} (several split by commas, {@code First,Second}), else every track.
     */
    @Nonnull
    static List<TrackDef> pool(@Nonnull TrackLamp lamp) {
        Set<String> named = folded(lamp.tracks());
        Set<String> categories = lamp.category() == null ? Set.of() : folded(List.of(lamp.category().split(",")));
        List<TrackDef> out = new ArrayList<>();
        for (TrackDef def : TrackService.defs()) {
            if (def.asset().isDerived()) {
                continue;
            }
            if (!named.isEmpty()) {
                if (named.contains(def.id())) {
                    out.add(def);
                }
                continue;
            }
            String own = def.asset().category();
            if (categories.isEmpty() || (own != null && categories.contains(own.toLowerCase(Locale.ROOT)))) {
                out.add(def);
            }
        }
        return out;
    }

    /** {@code ids} trimmed and lower-cased, blanks left out. */
    @Nonnull
    private static Set<String> folded(@Nonnull List<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            String clean = id == null ? "" : id.trim();
            if (!clean.isEmpty()) {
                out.add(clean.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** How many picks the player must make: the lamp's, or every choice when fewer are open (never a choice nobody can make). */
    public static int required(@Nonnull TrackLamp lamp, int choices) {
        return Math.min(lamp.picks(), Math.max(0, choices));
    }

    /**
     * Use one lamp of {@code lamp}'s numbers on {@code chosen} (track ids): the choice is read again here, never trusted
     * from the page (exactly {@link #required} different tracks, each a choice now), then one such lamp must still be
     * in the bag and is taken BEFORE anything is paid, so a lamp that went elsewhere pays nothing; nothing is taken
     * when the choice is refused. Each pick pays what it is priced at now. World thread.
     */
    @Nonnull
    public static Rub rub(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull UUID player,
            @Nonnull TrackLamp lamp, @Nonnull List<String> chosen) {
        Player entity = store.getComponent(ref, Player.getComponentType());
        if (entity == null) {
            return Rub.of(Outcome.NO_PLAYER);
        }
        List<Choice> choices = choices(store, ref, player, lamp);
        int required = required(lamp, choices.size());
        Map<String, Choice> picked = new LinkedHashMap<>();
        for (String id : chosen) {
            Choice match = id == null ? null : matchOf(choices, id);
            if (match == null) {
                return Rub.of(Outcome.NOT_CHOOSABLE);
            }
            picked.put(match.def().id(), match);
        }
        if (required <= 0 || picked.size() != required || picked.size() != chosen.size()) {
            return Rub.of(Outcome.WRONG_COUNT);
        }
        if (!takeOne(entity, lamp)) {
            return Rub.of(Outcome.NO_LAMP);
        }
        Map<TrackDef, Long> paid = new LinkedHashMap<>();
        for (Choice choice : picked.values()) {
            try {
                TrackService.Result result = TrackService.addExact(TrackKey.player(player, choice.def().id()),
                        choice.pays(), SOURCE);
                if (result.moved()) {
                    paid.put(choice.def(), choice.pays());
                }
            } catch (Throwable t) {
                SafeLog.warn("[track] a lamp could not pay the track '" + choice.def().id() + "': " + t.getMessage());
            }
        }
        return new Rub(Outcome.PAID, paid);
    }

    @Nullable
    private static Choice matchOf(@Nonnull List<Choice> choices, @Nonnull String id) {
        String folded = id.trim().toLowerCase(Locale.ROOT);
        for (Choice choice : choices) {
            if (choice.def().id().equals(folded)) {
                return choice;
            }
        }
        return null;
    }

    /**
     * Hand {@code subject} {@code quantity} lamps of {@code lamp}'s numbers (one stack at most), the way any pickup
     * lands: where their pickup settings route it, the rest at their feet. How many landed; 0 when none could.
     */
    public static int give(@Nonnull Subject subject, @Nonnull TrackLamp lamp, int quantity, @Nonnull String source) {
        ItemStack stack = TrackLamp.mint(lamp, Math.max(1, Math.min(quantity, maxStack())));
        int landed = 0;
        for (int count : LootRewardKinds.handToPlayer(subject, source, SafeLog::warn).build()
                .spill(List.of(stack)).values()) {
            landed += count;
        }
        return landed;
    }

    /** How many lamps one stack holds, as the lamp item's file says; 1 when it cannot be read. */
    private static int maxStack() {
        try {
            Item item = new ItemStack(TrackLamp.ITEM_ID, 1).getItem();
            return item == null ? 1 : Math.max(1, item.getMaxStack());
        } catch (Throwable unreadable) {
            return 1;
        }
    }

    /** Does the player still carry a lamp of {@code lamp}'s numbers? */
    public static boolean holds(@Nonnull Player player, @Nonnull TrackLamp lamp) {
        return slotOf(PlayerAccess.combinedBackpackStorageHotbar(player), lamp) >= 0;
    }

    /** Take one lamp of {@code lamp}'s numbers from the bag; false when none is there. */
    private static boolean takeOne(@Nonnull Player player, @Nonnull TrackLamp lamp) {
        CombinedItemContainer bag = PlayerAccess.combinedBackpackStorageHotbar(player);
        short slot = slotOf(bag, lamp);
        return slot >= 0 && bag.removeItemStackFromSlot(slot, 1).succeeded();
    }

    /** The first slot holding a lamp of {@code lamp}'s numbers, or -1. */
    private static short slotOf(@Nullable CombinedItemContainer bag, @Nonnull TrackLamp lamp) {
        if (bag == null) {
            return -1;
        }
        short capacity = bag.getCapacity();
        for (short slot = 0; slot < capacity; slot++) {
            if (lamp.equals(TrackLamp.of(bag.getItemStack(slot)))) {
                return slot;
            }
        }
        return -1;
    }
}
