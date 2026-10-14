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
 * <p>It is an OBJECTIVE item, what the step asks the player to bring. Whoever hands it over decides
 * where it goes (zc-objectives' {@code drop/QuestItemDrops}: a kill's into the bag like a pickup, a won
 * fight's kept for the player to collect with the rest of its loot), and tells the engine how much of it
 * waits uncollected, which counts as held ({@link QuestEngine.ItemsWaiting}).
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
 */
public record QuestItemDrop(@Nonnull List<String> roles, @Nonnull List<String> encounters, double chance,
                            int count) {

    /** What the player did that may drop the item. */
    public enum Source {
        /** A kill credited to the player, named by the dead NPC's role id. */
        ROLE,
        /** A won boss fight the player was credited in, named by the fight's id. */
        ENCOUNTER
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
     * The drop as authored: a null chance reads {@link #DEFAULT_CHANCE} and a null count
     * {@link #DEFAULT_COUNT}; a null list names nothing.
     */
    @Nonnull
    public static QuestItemDrop of(@Nullable Collection<String> roles, @Nullable Collection<String> encounters,
            @Nullable Double chance, @Nullable Integer count) {
        return new QuestItemDrop(roles == null ? List.of() : new ArrayList<>(roles),
                encounters == null ? List.of() : new ArrayList<>(encounters),
                chance == null ? DEFAULT_CHANCE : chance.doubleValue(),
                count == null ? DEFAULT_COUNT : count.intValue());
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
