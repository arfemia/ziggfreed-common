package com.ziggfreed.common.track;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.track.asset.PrestigeBuild;

/**
 * The picks made on one climb, immutable: what {@link TrackRecord#picks} keeps. Owed picks are never stored: they
 * are the slots the climb has opened minus the ones made here.
 *
 * @param slots           per choice slot ({@code <pick>/<group>/<level>}), the options chosen there, in the order
 *                        chosen
 * @param takes           per pick bought outright (a points-style node, a trainer's free pick), how many times
 * @param remembered      per slot, what a prestige under {@code Remember} put aside: re-levelling that slot offers it
 *                        again preselected
 * @param order           every choice and take in the order it was made, oldest first: a slot key (it holds
 *                        {@link #SLOT_SEPARATOR}) once for each slot made, a pick id once per take (a pick taken
 *                        three times is in it three times). What a Remember prestige puts its takes aside by and what
 *                        a saved build replays, choices and takes interleaved as they were made; always agrees with
 *                        {@code slots} and {@code takes} (a save from before it was kept reads its slots first, by
 *                        level, then its takes in id order)
 * @param rememberedTakes the takes a prestige under {@code Remember} put aside, oldest first, re-spent in this order
 *                        as points come back (R641)
 * @param loadouts        the saved builds, by slot (1 and up): what a swap puts back
 * @param activeLoadout   the saved build the picks in use came from (0 for none): a swap to another build first writes
 *                        the picks in use back over it, every saved pick they lack kept ({@link Loadout#writtenOver})
 */
public record PickState(@Nonnull Map<String, List<String>> slots, @Nonnull Map<String, Integer> takes,
                        @Nonnull Map<String, List<String>> remembered, @Nonnull List<String> order,
                        @Nonnull List<String> rememberedTakes, @Nonnull Map<Integer, Loadout> loadouts,
                        int activeLoadout) {

    /** No pick made. */
    public static final PickState EMPTY = new PickState(Map.of(), Map.of(), Map.of());

    /** Between a slot key's parts. */
    public static final char SLOT_SEPARATOR = '/';

    /**
     * One saved build: the choices made at slots, and every choice and take in the order it was made. A build never
     * holds a pick that stays in use (a trainer's, or a priced one): those stay in use through every swap.
     *
     * @param slots per choice slot, the options chosen there
     * @param order every choice and take, oldest first, as {@link PickState#order} keeps them: a slot key once for
     *              each slot in {@code slots}, a pick id once per take (repeated for each further rank)
     */
    public record Loadout(@Nonnull Map<String, List<String>> slots, @Nonnull List<String> order) {

        /** A build holding nothing. */
        public static final Loadout EMPTY = new Loadout(Map.of(), List.of());

        public Loadout {
            slots = choices(slots);
            order = reconciled(order, slots, null);
        }

        /** Does it hold nothing? */
        public boolean isEmpty() {
            return order.isEmpty();
        }

        /** How many picks it holds: every slot and every take. */
        public int size() {
            return order.size();
        }

        /** Its takes alone, oldest first, a pick repeated for each further rank. */
        @Nonnull
        public List<String> takes() {
            List<String> out = new ArrayList<>();
            for (String step : order) {
                if (!isSlotKey(step)) {
                    out.add(step);
                }
            }
            return List.copyOf(out);
        }

        /**
         * This build (the picks in use) written back over {@code saved} (the build they were loaded from), so a
         * swap away never loses a saved pick: every pick {@code saved} holds stays in its place, whether it was
         * loaded or not (a lower climb, points not yet back, a pick switched off), a slot this build also makes
         * holding this build's choice; then every pick this build holds beyond {@code saved}, in the order it was
         * made. Equal to {@code saved} when nothing was picked since the load. A choice node ({@code Node}) holds
         * one face, and a reload takes the first face it meets: so where this build holds another face of a saved
         * pick's node and not the saved pick (the saved face undone and the other taken, or never loaded), the newer
         * face's picks, in the order made, stand in the saved face's place and the saved face goes.
         *
         * @param otherFaces per pick id (lower-cased), the other faces of its node on the track; empty (or null) for
         *                   a pick in no node
         */
        @Nonnull
        public Loadout writtenOver(@Nonnull Loadout saved, @Nonnull Function<String, List<String>> otherFaces) {
            Set<String> inUse = picksIn(order);
            Set<String> savedPicks = picksIn(saved.order());
            Map<String, List<String>> nextSlots = new HashMap<>();
            Map<String, Integer> savedTakes = new HashMap<>();
            Set<String> placedFaces = new HashSet<>();
            List<String> next = new ArrayList<>();
            for (String step : saved.order()) {
                List<String> newer = newerFaces(pickOf(step), otherFaces, inUse, savedPicks);
                if (!newer.isEmpty()) {
                    // The saved face gives way: the newer face's picks stand where it stood (once, at its first
                    // place), counted as saved so the walk below does not add them again.
                    for (String face : newer) {
                        if (!placedFaces.add(face)) {
                            continue;
                        }
                        for (String own : order) {
                            if (!pickOf(own).equals(face)) {
                                continue;
                            }
                            if (isSlotKey(own)) {
                                nextSlots.put(own, slots.get(own));
                            } else {
                                savedTakes.merge(own, 1, Integer::sum);
                            }
                            next.add(own);
                        }
                    }
                    continue;
                }
                if (isSlotKey(step)) {
                    List<String> mine = slots.get(step);
                    nextSlots.put(step, mine != null ? mine : saved.slots().get(step));
                } else {
                    savedTakes.merge(step, 1, Integer::sum);
                }
                next.add(step);
            }
            // A take of the build in use matches one saved (the first as many as were saved); the rest are new.
            Map<String, Integer> seen = new HashMap<>();
            for (String step : order) {
                if (isSlotKey(step)) {
                    if (!nextSlots.containsKey(step)) {
                        nextSlots.put(step, slots.get(step));
                        next.add(step);
                    }
                } else if (seen.merge(step, 1, Integer::sum) > savedTakes.getOrDefault(step, 0)) {
                    next.add(step);
                }
            }
            return new Loadout(nextSlots, next);
        }
    }

    public PickState {
        slots = choices(slots);
        takes = counts(takes);
        remembered = choices(remembered);
        order = reconciled(order, slots, takes);
        rememberedTakes = ids(rememberedTakes);
        loadouts = builds(loadouts);
        activeLoadout = Math.max(0, activeLoadout);
    }

    /** The picks made, with no take order, nothing remembered in order and no saved build. */
    public PickState(@Nonnull Map<String, List<String>> slots, @Nonnull Map<String, Integer> takes,
            @Nonnull Map<String, List<String>> remembered) {
        this(slots, takes, remembered, List.of(), List.of(), Map.of(), 0);
    }

    /** Has nothing been picked, remembered or saved? */
    public boolean isEmpty() {
        return slots.isEmpty() && takes.isEmpty() && remembered.isEmpty() && rememberedTakes.isEmpty()
                && loadouts.isEmpty() && activeLoadout == 0;
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

    /** The saved build in {@code slot}, {@link Loadout#EMPTY} when none is saved there. */
    @Nonnull
    public Loadout loadout(int slot) {
        Loadout held = loadouts.get(slot);
        return held == null ? Loadout.EMPTY : held;
    }

    /** The slot key of {@code pick}'s group {@code group} at {@code level}: {@code <pick>/<group>/<level>}. */
    @Nonnull
    public static String slotKey(@Nonnull String pick, @Nonnull String group, int level) {
        return TrackKey.normalize(pick) + SLOT_SEPARATOR + TrackKey.normalize(group) + SLOT_SEPARATOR + level;
    }

    /** Is an {@link #order} entry a slot key (a choice made there) rather than a pick id (a take)? */
    public static boolean isSlotKey(@Nullable String step) {
        return step != null && step.indexOf(SLOT_SEPARATOR) >= 0;
    }

    /** This state with {@code options} put back aside at {@code slot} (an undo giving back what a prestige kept). */
    @Nonnull
    public PickState withRemembered(@Nonnull String slot, @Nonnull List<String> options) {
        if (options.isEmpty()) {
            return this;
        }
        Map<String, List<String>> next = new HashMap<>(remembered);
        next.put(TrackKey.normalize(slot), options);
        return new PickState(slots, takes, next, order, rememberedTakes, loadouts, activeLoadout);
    }

    /**
     * This state with {@code options} chosen at {@code slot} (none: the slot unmade), its remembered pick spent: a
     * slot newly made joins the end of {@link #order}, and one unmade leaves it.
     */
    @Nonnull
    public PickState withChoice(@Nonnull String slot, @Nonnull List<String> options) {
        String key = TrackKey.normalize(slot);
        Map<String, List<String>> next = new HashMap<>(slots);
        List<String> sequence = new ArrayList<>(order);
        if (options.isEmpty()) {
            next.remove(key);
            sequence.remove(key);
        } else {
            if (!slots.containsKey(key)) {
                sequence.add(key);
            }
            next.put(key, options);
        }
        Map<String, List<String>> kept = remembered;
        if (!options.isEmpty() && remembered.containsKey(key)) {
            kept = new HashMap<>(remembered);
            kept.remove(key);
        }
        return new PickState(next, takes, kept, sequence, rememberedTakes, loadouts, activeLoadout);
    }

    /**
     * This state with {@code node} bought {@code delta} more times (fewer when negative, never below none): a take
     * joins the end of {@link #order}, and a take given back leaves it from the newest end.
     */
    @Nonnull
    public PickState withTakes(@Nonnull String node, int delta) {
        String key = TrackKey.normalize(node);
        Map<String, Integer> next = new HashMap<>(takes);
        int was = takesOf(key);
        int now = Math.max(0, was + delta);
        if (now == 0) {
            next.remove(key);
        } else {
            next.put(key, now);
        }
        List<String> sequence = new ArrayList<>(order);
        for (int i = was; i < now; i++) {
            sequence.add(key);
        }
        for (int i = now; i < was; i++) {
            sequence.remove(sequence.lastIndexOf(key));
        }
        return new PickState(slots, next, remembered, sequence, rememberedTakes, loadouts, activeLoadout);
    }

    /** This state with {@code takes} as the takes a Remember prestige keeps aside, in order. */
    @Nonnull
    public PickState withRememberedTakes(@Nonnull List<String> takes) {
        return new PickState(slots, this.takes, remembered, order, takes, loadouts, activeLoadout);
    }

    /**
     * This state with the oldest remembered take of {@code node} gone: that rank is held again, bought afresh (a
     * priced pick, which a re-spend never buys, comes back only this way). Unchanged when none is remembered.
     */
    @Nonnull
    public PickState withoutRememberedTake(@Nonnull String node) {
        int at = rememberedTakes.indexOf(TrackKey.normalize(node));
        if (at < 0) {
            return this;
        }
        List<String> next = new ArrayList<>(rememberedTakes);
        next.remove(at);
        return withRememberedTakes(next);
    }

    /** This state with {@code node} put back at the head of the remembered takes (an undo of a re-spent take). */
    @Nonnull
    public PickState withRememberedTakeFirst(@Nonnull String node) {
        List<String> next = new ArrayList<>();
        next.add(TrackKey.normalize(node));
        next.addAll(rememberedTakes);
        return withRememberedTakes(next);
    }

    /** This state with {@code build} saved in {@code slot} (an empty build clears it). */
    @Nonnull
    public PickState withLoadout(int slot, @Nonnull Loadout build) {
        Map<Integer, Loadout> next = new HashMap<>(loadouts);
        if (build.isEmpty()) {
            next.remove(slot);
        } else {
            next.put(slot, build);
        }
        return new PickState(slots, takes, remembered, order, rememberedTakes, next, activeLoadout);
    }

    /** This state with the picks in use known as coming from the saved build {@code slot} (0 for none). */
    @Nonnull
    public PickState withActiveLoadout(int slot) {
        return new PickState(slots, takes, remembered, order, rememberedTakes, loadouts, slot);
    }

    /**
     * The picks in use as a saved build: every slot and every take, in the order they were made, of a pick
     * {@code kept} answers false for (a trainer's pick, or a priced one, stays in use through every swap and is never
     * saved).
     */
    @Nonnull
    public Loadout snapshot(@Nonnull Predicate<String> kept) {
        Map<String, List<String>> savedSlots = new HashMap<>();
        slots.forEach((slot, options) -> {
            if (!kept.test(pickOf(slot))) {
                savedSlots.put(slot, options);
            }
        });
        List<String> savedOrder = new ArrayList<>();
        for (String step : order) {
            if (!kept.test(isSlotKey(step) ? pickOf(step) : step)) {
                savedOrder.add(step);
            }
        }
        return new Loadout(savedSlots, savedOrder);
    }

    /**
     * This state holding only the picks {@code kept} answers true for (what a swap starts from before it puts a
     * build back, and what a respec leaves: the trainer-taught); what a prestige put aside, every saved build and the
     * build in use stay.
     */
    @Nonnull
    public PickState keptOnly(@Nonnull Predicate<String> kept) {
        Map<String, List<String>> nextSlots = new HashMap<>();
        slots.forEach((slot, options) -> {
            if (kept.test(pickOf(slot))) {
                nextSlots.put(slot, options);
            }
        });
        Map<String, Integer> nextTakes = new HashMap<>();
        takes.forEach((node, count) -> {
            if (kept.test(node)) {
                nextTakes.put(node, count);
            }
        });
        return new PickState(nextSlots, nextTakes, remembered, order, rememberedTakes, loadouts, activeLoadout);
    }

    /**
     * This state after a prestige under {@code build} (R641). A pick {@code kept} answers true for (by its id: one
     * whose kind the track's {@code Prestige.Resets} keeps, R852) stays exactly as it is under every build; the
     * others follow it. {@code Keep} changes nothing; {@code Remember} puts each of their slots aside, so it
     * re-offers its old choice preselected as it is re-levelled, and puts their takes aside in the order they were
     * made, ahead of any an earlier prestige put aside and not yet re-spent, so they are re-spent in that order as
     * points come back; {@code Clear} clears their slots, takes and what an earlier prestige put aside. Saved builds
     * stay under every build; under {@code Remember} and {@code Clear} the picks in use no longer count as one.
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
        List<String> asideTakes = new ArrayList<>();
        if (remember) {
            for (String step : order) {
                if (!isSlotKey(step) && !kept.test(step)) {
                    asideTakes.add(step);
                }
            }
            asideTakes.addAll(rememberedTakes);
        }
        return new PickState(nextSlots, nextTakes, aside, order, asideTakes, loadouts, 0);
    }

    /** The pick a slot key ({@code <pick>/<group>/<level>}) belongs to. */
    @Nonnull
    static String pickOf(@Nonnull String slot) {
        int cut = slot.indexOf(SLOT_SEPARATOR);
        return cut < 0 ? slot : slot.substring(0, cut);
    }

    /** The picks an {@link #order} holds, each once: a slot's pick, a take's id. */
    @Nonnull
    private static Set<String> picksIn(@Nonnull List<String> order) {
        Set<String> out = new HashSet<>();
        for (String step : order) {
            out.add(pickOf(step));
        }
        return out;
    }

    /**
     * The faces of {@code pick}'s node the build in use holds instead of it: each other face {@code inUse} holds and
     * {@code savedPicks} (the saved build's) does not; none when {@code inUse} holds {@code pick} itself, or it is in
     * no node.
     */
    @Nonnull
    private static List<String> newerFaces(@Nonnull String pick, @Nonnull Function<String, List<String>> otherFaces,
            @Nonnull Set<String> inUse, @Nonnull Set<String> savedPicks) {
        if (inUse.contains(pick)) {
            return List.of();
        }
        List<String> faces = otherFaces.apply(pick);
        if (faces == null || faces.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String face : faces) {
            String id = TrackKey.normalize(face);
            if (inUse.contains(id) && !savedPicks.contains(id)) {
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

    /** {@code raw} lower-cased in order, blanks left out, repeats kept. */
    @Nonnull
    private static List<String> ids(@Nullable List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (String id : raw) {
            String key = TrackKey.normalize(id);
            if (!key.isEmpty()) {
                out.add(key);
            }
        }
        return List.copyOf(out);
    }

    /**
     * {@code raw} made to agree with {@code slots} and {@code takes}: each slot made kept once in its place (one
     * not made left out), each pick kept in its place as often as it is taken (a surplus left out from the newest
     * end); a slot the order does not carry (a save from before it was kept) goes first, lowest level first, and a
     * take it does not carry goes after it, in id order. With {@code takes} null (a saved build, which keeps no
     * counts) every pick id in {@code raw} is a take.
     */
    @Nonnull
    private static List<String> reconciled(@Nullable List<String> raw, @Nonnull Map<String, List<String>> slots,
            @Nullable Map<String, Integer> takes) {
        if (slots.isEmpty() && (takes == null ? raw == null || raw.isEmpty() : takes.isEmpty())) {
            return List.of();
        }
        Map<String, Integer> left = takes == null ? null : new TreeMap<>(takes);
        Set<String> placed = new HashSet<>();
        List<String> kept = new ArrayList<>();
        for (String id : ids(raw)) {
            if (isSlotKey(id)) {
                if (slots.containsKey(id) && placed.add(id)) {
                    kept.add(id);
                }
            } else if (left == null) {
                kept.add(id);
            } else {
                Integer room = left.get(id);
                if (room != null && room > 0) {
                    kept.add(id);
                    left.put(id, room - 1);
                }
            }
        }
        List<String> out = new ArrayList<>();
        slots.keySet().stream()
                .filter(slot -> !placed.contains(slot))
                .sorted(Comparator.<String>comparingInt(PickState::levelOf).thenComparing(Comparator.naturalOrder()))
                .forEach(out::add);
        out.addAll(kept);
        if (left != null) {
            left.forEach((id, room) -> {
                for (int i = 0; i < room; i++) {
                    out.add(id);
                }
            });
        }
        return List.copyOf(out);
    }

    /** The level a slot key ({@code <pick>/<group>/<level>}) opens at, or the most there is when unreadable. */
    private static int levelOf(@Nonnull String slot) {
        try {
            return Integer.parseInt(slot.substring(slot.lastIndexOf(SLOT_SEPARATOR) + 1));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    @Nonnull
    private static Map<Integer, Loadout> builds(@Nullable Map<Integer, Loadout> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Loadout> out = new HashMap<>();
        raw.forEach((slot, build) -> {
            if (slot != null && slot >= 1 && build != null && !build.isEmpty()) {
                out.put(slot, build);
            }
        });
        return Map.copyOf(out);
    }
}
