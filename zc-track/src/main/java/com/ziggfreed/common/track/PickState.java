package com.ziggfreed.common.track;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.track.asset.PrestigeBuild;

/**
 * The picks made on one climb, immutable: what {@link TrackRecord#picks} keeps. Owed picks are never stored: they
 * are the slots the climb has opened minus the ones made here.
 *
 * @param slots      per choice slot ({@code <pick>/<group>/<level>}), the options chosen there, in the order chosen
 * @param takes      per pick bought outright (a points-style node, a trainer's free pick), how many times
 * @param remembered per slot, what a prestige under {@code Remember} put aside: re-levelling that slot offers it
 *                   again preselected
 */
public record PickState(@Nonnull Map<String, List<String>> slots, @Nonnull Map<String, Integer> takes,
                        @Nonnull Map<String, List<String>> remembered) {

    /** No pick made. */
    public static final PickState EMPTY = new PickState(Map.of(), Map.of(), Map.of());

    /** Between a slot key's parts. */
    public static final char SLOT_SEPARATOR = '/';

    public PickState {
        slots = choices(slots);
        takes = counts(takes);
        remembered = choices(remembered);
    }

    /** Has nothing been picked or remembered? */
    public boolean isEmpty() {
        return slots.isEmpty() && takes.isEmpty() && remembered.isEmpty();
    }

    /** The options chosen at {@code slot}, empty when it is not made. */
    @Nonnull
    public List<String> chosen(@Nonnull String slot) {
        List<String> held = slots.get(TrackKey.normalize(slot));
        return held == null ? List.of() : held;
    }

    /** What a {@code Remember} prestige put aside at {@code slot}, empty when nothing. */
    @Nonnull
    public List<String> rememberedAt(@Nonnull String slot) {
        List<String> held = remembered.get(TrackKey.normalize(slot));
        return held == null ? List.of() : held;
    }

    /** How many times the pick {@code node} was bought. */
    public int takesOf(@Nonnull String node) {
        Integer held = takes.get(TrackKey.normalize(node));
        return held == null ? 0 : held;
    }

    /** How many slots of the choice {@code pick} are made (each one a choice its Cost was paid for). */
    public int slotsMade(@Nonnull String pick) {
        String own = TrackKey.normalize(pick) + SLOT_SEPARATOR;
        int n = 0;
        for (String slot : slots.keySet()) {
            if (slot.startsWith(own)) {
                n++;
            }
        }
        return n;
    }

    /** How many picks are made here in all: every slot made and every take. */
    public int size() {
        int n = slots.size();
        for (int count : takes.values()) {
            n += count;
        }
        return n;
    }

    /**
     * How many times {@code pick} was taken: every slot whose choice holds it (an option id), every slot of it made
     * (a choice's own id), and every time it was bought (a pick's id). What {@code ziggfreedcommon:track_pick} reads.
     */
    public int count(@Nullable String pick) {
        String id = TrackKey.normalize(pick);
        if (id.isEmpty()) {
            return 0;
        }
        int n = takesOf(id);
        String own = id + SLOT_SEPARATOR;
        for (Map.Entry<String, List<String>> slot : slots.entrySet()) {
            if (slot.getKey().startsWith(own)) {
                n++;
            }
            for (String option : slot.getValue()) {
                if (option.equals(id)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** The slot key of {@code pick}'s group {@code group} at {@code level}: {@code <pick>/<group>/<level>}. */
    @Nonnull
    public static String slotKey(@Nonnull String pick, @Nonnull String group, int level) {
        return TrackKey.normalize(pick) + SLOT_SEPARATOR + TrackKey.normalize(group) + SLOT_SEPARATOR + level;
    }

    /** This state with {@code options} put back aside at {@code slot} (an undo giving back what a prestige kept). */
    @Nonnull
    public PickState withRemembered(@Nonnull String slot, @Nonnull List<String> options) {
        if (options.isEmpty()) {
            return this;
        }
        Map<String, List<String>> next = new HashMap<>(remembered);
        next.put(TrackKey.normalize(slot), options);
        return new PickState(slots, takes, next);
    }

    /** This state with {@code options} chosen at {@code slot} (none: the slot unmade), its remembered pick spent. */
    @Nonnull
    public PickState withChoice(@Nonnull String slot, @Nonnull List<String> options) {
        String key = TrackKey.normalize(slot);
        Map<String, List<String>> next = new HashMap<>(slots);
        if (options.isEmpty()) {
            next.remove(key);
        } else {
            next.put(key, options);
        }
        Map<String, List<String>> kept = remembered;
        if (!options.isEmpty() && remembered.containsKey(key)) {
            kept = new HashMap<>(remembered);
            kept.remove(key);
        }
        return new PickState(next, takes, kept);
    }

    /** This state with {@code node} bought {@code delta} more times (fewer when negative, never below none). */
    @Nonnull
    public PickState withTakes(@Nonnull String node, int delta) {
        String key = TrackKey.normalize(node);
        Map<String, Integer> next = new HashMap<>(takes);
        int now = Math.max(0, takesOf(key) + delta);
        if (now == 0) {
            next.remove(key);
        } else {
            next.put(key, now);
        }
        return new PickState(slots, next, remembered);
    }

    /** Every slot and every bought pick cleared (a respec); what a prestige put aside stays. */
    @Nonnull
    public PickState cleared() {
        return new PickState(Map.of(), Map.of(), remembered);
    }

    /**
     * This state after a prestige under {@code build} (R641). A pick {@code kept} answers true for (by its id: a
     * trainer's pick, one whose file keeps it through a prestige) stays exactly as it is under every build; the
     * others follow it. {@code Keep} changes nothing; {@code Remember} puts each of their slots aside, so it
     * re-offers its old choice preselected as it is re-levelled, and drops their takes, whose points come back as the
     * player re-levels; {@code Clear} clears their slots, takes and what an earlier prestige put aside.
     */
    @Nonnull
    public PickState afterPrestige(@Nonnull PrestigeBuild build, @Nonnull Predicate<String> kept) {
        if (build == PrestigeBuild.KEEP) {
            return this;
        }
        boolean remember = build == PrestigeBuild.REMEMBER;
        Map<String, List<String>> nextSlots = new HashMap<>();
        Map<String, List<String>> aside = new HashMap<>();
        remembered.forEach((slot, options) -> {
            if (remember || kept.test(pickOf(slot))) {
                aside.put(slot, options);
            }
        });
        slots.forEach((slot, options) -> {
            if (kept.test(pickOf(slot))) {
                nextSlots.put(slot, options);
            } else if (remember) {
                aside.put(slot, options);
            }
        });
        Map<String, Integer> nextTakes = new HashMap<>();
        takes.forEach((node, count) -> {
            if (kept.test(node)) {
                nextTakes.put(node, count);
            }
        });
        return new PickState(nextSlots, nextTakes, aside);
    }

    /** The pick a slot key ({@code <pick>/<group>/<level>}) belongs to. */
    @Nonnull
    static String pickOf(@Nonnull String slot) {
        int cut = slot.indexOf(SLOT_SEPARATOR);
        return cut < 0 ? slot : slot.substring(0, cut);
    }

    /** Comma-joined, as a save keeps a slot's choice. */
    @Nonnull
    public static String join(@Nonnull List<String> options) {
        return String.join(",", options);
    }

    /** A saved slot's choice as its options. */
    @Nonnull
    public static List<String> split(@Nullable String saved) {
        if (saved == null || saved.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : saved.split(",")) {
            String id = TrackKey.normalize(part);
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }

    @Nonnull
    private static Map<String, List<String>> choices(@Nullable Map<String, List<String>> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> out = new HashMap<>();
        for (Map.Entry<String, List<String>> e : raw.entrySet()) {
            String key = TrackKey.normalize(e.getKey());
            if (key.isEmpty() || e.getValue() == null) {
                continue;
            }
            Set<String> ids = new LinkedHashSet<>();
            for (String option : e.getValue()) {
                String id = TrackKey.normalize(option);
                if (!id.isEmpty()) {
                    ids.add(id);
                }
            }
            if (!ids.isEmpty()) {
                out.put(key, List.copyOf(ids));
            }
        }
        return Map.copyOf(out);
    }

    @Nonnull
    private static Map<String, Integer> counts(@Nullable Map<String, Integer> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, Integer> e : raw.entrySet()) {
            String key = TrackKey.normalize(e.getKey());
            if (!key.isEmpty() && e.getValue() != null && e.getValue() > 0) {
                out.merge(key, e.getValue(), Integer::sum);
            }
        }
        return Map.copyOf(out);
    }
}
