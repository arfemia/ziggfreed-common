package com.ziggfreed.common.quest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A quest step's item drop: the item the step asks for (its {@code Target}) drops for a player only
 * while that step is theirs and still wants it, from the creatures and the boss fights the step
 * names. A player without the step gets nothing, and one who already has what the step owes stops
 * getting it. It drops only on a {@code TURN_IN} or a {@code PICKUP_ITEM} step
 * ({@link QuestEngine.ItemDropDue#dropsOn}). {@link QuestEngine#itemDropsDue} decides who is owed what;
 * whoever hands the item over rolls {@link #chance()} once per owed step and gives at most
 * {@link #count()}.
 *
 * <p>It is an OBJECTIVE item, what the step asks the player to bring. How it reaches the player is the
 * step's {@link #delivery()} ({@link Delivery}; unauthored, a won fight's waits in the fight's spoils and a
 * kill's goes into the bag with a Claim behind it), carried out by whoever hands it over (zc-objectives'
 * {@code drop/QuestItemDrops}), which tells the engine how much of it waits uncollected, counted as held
 * ({@link QuestEngine.ItemsWaiting}).
 *
 * <p>Two sources, told apart by what the player did, so one kill never rolls twice:
 * <ul>
 *   <li>{@link Source#ROLE}: a kill of an NPC whose role id is in {@link #roles()}, for the player
 *   credited with the kill (the {@code KILL_ENTITY} moment's player);</li>
 *   <li>{@link Source#ENCOUNTER}: a won boss fight whose id is in {@link #encounters()} (the id an
 *   {@code ENCOUNTER_DEFEATED} step names: the encounter script id for a run, the role id for an
 *   encounter rule's credit), for every participant the fight credited.</li>
 * </ul>
 *
 * <p>Ids compare whole, ignoring case.
 *
 * @param roles      NPC role ids whose kill can drop the item; immutable, blanks dropped, never null
 * @param encounters boss fight ids whose win can drop the item; immutable, blanks dropped, never null
 * @param chance     the chance from 0 to 1 that the drop happens, rolled once per eligible step (each
 *                   player's, at each kill or win, that still wants the item); held inside 0..1
 * @param count      how many one drop hands over, at least 1; never more than the step still wants
 * @param delivery   how the item reaches the player, or null for the source's own default
 *                   ({@link #deliveryFor})
 */
public record QuestItemDrop(@Nonnull List<String> roles, @Nonnull List<String> encounters, double chance,
                            int count, @Nullable Delivery delivery) {

    /** What the player did that may drop the item. */
    public enum Source {
        /** A kill credited to the player, named by the dead NPC's role id. */
        ROLE,
        /** A won boss fight the player was credited in, named by the fight's id. */
        ENCOUNTER
    }

    /**
     * How a dropped quest item reaches the player: a closed vocabulary, the four words the codec reads (the
     * maintainer's explicit ask for a mode, R438, R440). Whatever a player is owed is never lost to a
     * delivery that cannot be made: what has nowhere else to go waits in the step's Claim, except that
     * {@link #BAG} drops what the bag cannot take and says so.
     */
    public enum Delivery {
        /**
         * The fight's spoils: the item waits in the step's Claim, paid for the fight, so a won boss fight that
         * leaves a spoils chest offers it there; a fight that leaves none, or a kill, keeps it under Claims.
         * The default for a won fight.
         */
        SPOILS("Spoils"),
        /**
         * Into the bag like a pickup; what the bag cannot take tops up the step's Claim. The default for a
         * kill.
         */
        BAG_THEN_CLAIM("BagThenClaim"),
        /** Into the bag like a pickup; what the bag cannot take is not handed over, and the player is told. */
        BAG("Bag"),
        /**
         * Spilled on the ground where the creature or the boss fell, the way the base game drops a kill's
         * loot: one stack for each player owed it, owned by nobody, so anyone nearby can pick it up. With no
         * place to spill it, it waits in the step's Claim.
         */
        GROUND("Ground");

        private final String word;

        Delivery(@Nonnull String word) {
            this.word = word;
        }

        /** The word a file writes for this delivery. */
        @Nonnull
        public String word() {
            return word;
        }

        /** The delivery {@code word} names, any case, or null for a word that names none. */
        @Nullable
        public static Delivery byWord(@Nullable String word) {
            if (word == null) {
                return null;
            }
            String trimmed = word.trim();
            for (Delivery delivery : values()) {
                if (delivery.word.equalsIgnoreCase(trimmed)) {
                    return delivery;
                }
            }
            return null;
        }

        /** What an unauthored delivery reads as for {@code source}: a won fight's spoils, a kill's bag. */
        @Nonnull
        public static Delivery defaultFor(@Nonnull Source source) {
            return source == Source.ENCOUNTER ? SPOILS : BAG_THEN_CLAIM;
        }
    }

    /** The chance an unauthored {@code Chance} reads as: every eligible player gets it. */
    public static final double DEFAULT_CHANCE = 1.0;

    /** How many one drop hands over when {@code Count} is unauthored. */
    public static final int DEFAULT_COUNT = 1;

    public QuestItemDrop {
        roles = named(roles);
        encounters = named(encounters);
        chance = Double.isNaN(chance) ? 0.0 : Math.max(0.0, Math.min(1.0, chance));
        count = Math.max(1, count);
    }

    /**
     * The drop as authored, its delivery the source's default: a null chance reads {@link #DEFAULT_CHANCE} and
     * a null count {@link #DEFAULT_COUNT}; a null list names nothing.
     */
    @Nonnull
    public static QuestItemDrop of(@Nullable Collection<String> roles, @Nullable Collection<String> encounters,
            @Nullable Double chance, @Nullable Integer count) {
        return of(roles, encounters, chance, count, null);
    }

    /**
     * The drop as authored: a null chance reads {@link #DEFAULT_CHANCE}, a null count {@link #DEFAULT_COUNT}
     * and a null delivery the source's default ({@link #deliveryFor}); a null list names nothing.
     */
    @Nonnull
    public static QuestItemDrop of(@Nullable Collection<String> roles, @Nullable Collection<String> encounters,
            @Nullable Double chance, @Nullable Integer count, @Nullable Delivery delivery) {
        return new QuestItemDrop(roles == null ? List.of() : new ArrayList<>(roles),
                encounters == null ? List.of() : new ArrayList<>(encounters),
                chance == null ? DEFAULT_CHANCE : chance.doubleValue(),
                count == null ? DEFAULT_COUNT : count.intValue(), delivery);
    }

    /**
     * How the item reaches a player owed it from {@code source}: the authored {@link #delivery()}, else
     * {@link Delivery#defaultFor} that source.
     */
    @Nonnull
    public Delivery deliveryFor(@Nonnull Source source) {
        return delivery != null ? delivery : Delivery.defaultFor(source);
    }

    /** Does this drop name anywhere it drops from? One that names nothing never drops. */
    public boolean namesSource() {
        return !roles.isEmpty() || !encounters.isEmpty();
    }

    /** Does {@code id}, of this kind of source, drop the item? A blank id drops nothing. */
    public boolean dropsFrom(@Nonnull Source source, @Nullable String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        String wanted = id.trim();
        for (String named : source == Source.ROLE ? roles : encounters) {
            if (named.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Does a roll of {@code sample} (uniform in {@code [0, 1)}) land the drop? A chance of 1 always
     * lands, one of 0 never does.
     */
    public boolean lands(double sample) {
        return sample < chance;
    }

    /** The one spelling a source is indexed under: its kind and its id, trimmed and case folded. */
    @Nonnull
    static String sourceKey(@Nonnull Source source, @Nonnull String id) {
        return source.name() + ':' + id.trim().toLowerCase(Locale.ROOT);
    }

    /** Every source a step of {@code catalogue} drops its item from, as {@link #sourceKey} spells it. */
    @Nonnull
    static Set<String> sourceKeys(@Nonnull Collection<Quest> catalogue) {
        Set<String> keys = new HashSet<>();
        for (Quest quest : catalogue) {
            for (QuestItemDrop drop : quest.stepDrops().values()) {
                for (String role : drop.roles()) {
                    keys.add(sourceKey(Source.ROLE, role));
                }
                for (String encounter : drop.encounters()) {
                    keys.add(sourceKey(Source.ENCOUNTER, encounter));
                }
            }
        }
        return Set.copyOf(keys);
    }

    @Nonnull
    private static List<String> named(@Nullable List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim());
            }
        }
        return List.copyOf(out);
    }
}
