package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One choice group's road: the offer at each of its slots, a function of the files alone (R724), so the same files
 * offer the same road to every player on every server, and nothing is left to luck at the moment a player levels
 * (R653). Pure: an offer reads the road's pool in its authored order, its core, its deck, its pinned levels and the
 * slot's place on the road, never a player, a server, a seed or a hash, so the next slots can be read ahead and a
 * respec reopens the same offers. Built per fold and walked once, slot by slot, as far as anyone asks (the walk
 * carries what each option was last offered, which the deck's bag and drought rules and the rotation read).
 *
 * <p><b>A pinned level</b> (the pick's {@code Offers}, as the fold hands it: the pinned options this group lists and
 * keeps live) offers the core and those options, whatever the deck would draw; the walk counts them as offered, so the
 * levels after it read on from what was really shown. A level pinned to no option (an empty list, which is what a
 * group listing none of a level's live pinned options is handed) is not pinned on this road and keeps its drawn offer.
 *
 * <p><b>The draw.</b> Without a deck every option open at the level is offered. With one, an offer is the core and
 * {@code Sizes} minus one more: first the {@code Reserved} room of each Draw word (one more at its {@code Extra}
 * levels), then ordinary options. An option whose Bag word reads n is due once in each run of n slots, at the place
 * its {@code Turn} names (unauthored, the options of one Bag word take turns in the order the group lists them), and
 * comes due at once when it has gone {@code NoDrought} slots unoffered; an option naming no Bag word may come at any
 * slot. Overdue options go first, then due ones, then the steady ones, then the rest to fill; within each, the one
 * offered longest ago first, then the group's authored order, so the options rotate. No slot offers exactly the set
 * the slot before it offered while another option could take a place. A foe level offers the core and
 * {@code Foes.Offer} options of the foe Draw word, which no other level offers.
 */
public final class PickRoad {

    /** The furthest slot a road walks; an ask past it answers nothing. */
    public static final int MAX_SLOTS = 10_000;

    /** What a deck asks of a draw, as {@link PickDeckAsset#rules} folds it (every word lower-cased). */
    public record Deck(@Nonnull TreeMap<Integer, Integer> sizes, @Nonnull Map<String, Integer> reserved,
                       @Nonnull Map<String, Integer> extra, @Nonnull Map<String, Integer> bag,
                       @Nonnull Map<String, Integer> noDrought, @Nonnull Set<Integer> foeLevels, int foeOffer,
                       @Nullable String foeDraw) {

        public Deck {
            sizes = new TreeMap<>(sizes);
            reserved = Map.copyOf(reserved);
            extra = Map.copyOf(extra);
            bag = Map.copyOf(bag);
            noDrought = Map.copyOf(noDrought);
            foeLevels = Set.copyOf(foeLevels);
            foeOffer = Math.max(1, foeOffer);
        }

        /** How many an offer holds at {@code level}, the core included. */
        public int sizeAt(int level) {
            if (sizes.isEmpty()) {
                return PickDeckAsset.DEFAULT_SIZE;
            }
            Map.Entry<Integer, Integer> at = sizes.floorEntry(level);
            return at != null ? at.getValue() : sizes.firstEntry().getValue();
        }
    }

    /**
     * One option the road may offer: its id, its Draw and Bag words (lower-cased), its level window and its authored
     * {@code Turn} (counted from 1; 0 when unauthored, which takes its place among its Bag word's options).
     */
    public record Candidate(@Nonnull String id, @Nonnull String draw, @Nullable String bag, int from, int to,
                            int turn) {

        public Candidate {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(draw, "draw");
            turn = Math.max(0, turn);
        }

        boolean openAt(int level) {
            return level >= from && level <= to;
        }
    }

    /** The slot levels a road walks, ascending, between two levels (both in). */
    @FunctionalInterface
    public interface Levels {
        @Nonnull
        List<Integer> between(int fromLevel, int toLevel);
    }

    @Nonnull private final List<Candidate> pool;
    @Nullable private final String core;
    @Nullable private final Deck deck;
    @Nonnull private final Map<Integer, List<String>> pinned;
    @Nonnull private final Levels levels;
    /** Each option's place in the authored order (the pool's). */
    @Nonnull private final Map<String, Integer> order = new HashMap<>();
    /** Each Bag option's place within a run of its Bag word's slots, from 0 (its Turn, else its turn in order). */
    @Nonnull private final Map<String, Integer> place = new HashMap<>();

    // The walk so far: guarded by this road.
    private final Map<Integer, List<String>> offers = new HashMap<>();
    private final Map<String, Integer> lastSlot = new HashMap<>();
    private int walkedTo;
    private int slotIndex;
    @Nullable private Set<String> previous;

    /**
     * @param pool   the options it draws from, in authored order (the core may be among them)
     * @param core   the option at every slot, or null
     * @param deck   the draw rules, or null to offer every open option
     * @param pinned the levels whose offer is written out (the pick's {@code Offers}), each to its option ids
     *               (lower-cased); an empty list pins nothing, so that level keeps its drawn offer
     * @param levels the group's slot levels
     */
    public PickRoad(@Nonnull List<Candidate> pool, @Nullable String core, @Nullable Deck deck,
            @Nonnull Map<Integer, List<String>> pinned, @Nonnull Levels levels) {
        this.pool = List.copyOf(pool);
        this.core = core;
        this.deck = deck;
        Map<Integer, List<String>> pins = new HashMap<>();
        pinned.forEach((level, ids) -> {
            if (level != null && ids != null && !ids.isEmpty()) {
                pins.put(level, List.copyOf(ids));
            }
        });
        this.pinned = Map.copyOf(pins);
        this.levels = levels;
        Map<String, Integer> perWord = new HashMap<>();
        for (int i = 0; i < this.pool.size(); i++) {
            Candidate c = this.pool.get(i);
            if (order.putIfAbsent(c.id(), i) != null || c.bag() == null || c.id().equals(core)) {
                continue;
            }
            int nth = perWord.merge(c.bag(), 1, Integer::sum) - 1;
            place.put(c.id(), c.turn() > 0 ? c.turn() - 1 : nth);
        }
    }

    /** The options offered at {@code level}, the core first; empty when the group opens no slot there. */
    @Nonnull
    public synchronized List<String> offerAt(int level) {
        walkTo(level);
        List<String> offer = offers.get(level);
        return offer == null ? List.of() : offer;
    }

    private void walkTo(int level) {
        if (level <= walkedTo) {
            return;
        }
        for (int slot : levels.between(walkedTo + 1, level)) {
            if (slotIndex >= MAX_SLOTS) {
                break;
            }
            offers.put(slot, draw(slot, slotIndex));
            slotIndex++;
        }
        walkedTo = level;
    }

    /** The offer at the slot at {@code level}, the {@code index}th of the road. */
    @Nonnull
    private List<String> draw(int level, int index) {
        List<String> pin = pinned.get(level);
        List<String> picked = new ArrayList<>();
        if (pin != null) {
            for (String id : pin) {
                if (!id.equals(core) && order.containsKey(id) && !picked.contains(id)) {
                    picked.add(id);
                }
            }
            previous = new HashSet<>(picked);
        } else if (deck == null) {
            for (Candidate c : pool) {
                if (!c.id().equals(core) && c.openAt(level)) {
                    picked.add(c.id());
                }
            }
        } else {
            drawFromDeck(deck, level, index, picked);
            previous = new HashSet<>(picked);
        }
        List<String> offer = new ArrayList<>();
        if (core != null) {
            offer.add(core);
        }
        for (Candidate c : pool) {
            if (picked.contains(c.id()) && !offer.contains(c.id())) {
                offer.add(c.id());
            }
        }
        for (String id : offer) {
            lastSlot.put(id, index);
        }
        return List.copyOf(offer);
    }

    /** {@code deck}'s draw at the slot at {@code level}, into {@code picked}. */
    private void drawFromDeck(@Nonnull Deck deck, int level, int index, @Nonnull List<String> picked) {
        List<Candidate> open = new ArrayList<>();
        for (Candidate c : pool) {
            if (!c.id().equals(core) && c.openAt(level)) {
                open.add(c);
            }
        }
        if (deck.foeDraw() != null && deck.foeLevels().contains(level)) {
            pick(deck.foeOffer(), byDraw(open, deck.foeDraw()), index, picked);
            return;
        }
        List<Candidate> ordinary = new ArrayList<>();
        for (Candidate c : open) {
            if (!c.draw().equals(deck.foeDraw())) {
                ordinary.add(c);
            }
        }
        int room = Math.max(0, deck.sizeAt(level) - (core == null ? 0 : 1));
        Set<String> words = new TreeSet<>(deck.reserved().keySet());
        words.addAll(deck.extra().keySet());
        for (String word : words) {
            int every = deck.extra().getOrDefault(word, 0);
            int n = deck.reserved().getOrDefault(word, 0) + (every > 0 && level % every == 0 ? 1 : 0);
            room -= pick(Math.min(n, room), byDraw(ordinary, word), index, picked);
        }
        List<Candidate> regular = new ArrayList<>();
        for (Candidate c : ordinary) {
            if (!words.contains(c.draw())) {
                regular.add(c);
            }
        }
        room -= pick(room, regular, index, picked);
        pick(room, ordinary, index, picked);
        avoidRepeat(ordinary, index, picked);
    }

    /** Add up to {@code n} of {@code candidates} (not already picked) to {@code picked}, best first; how many. */
    private int pick(int n, @Nonnull List<Candidate> candidates, int index, @Nonnull List<String> picked) {
        if (n <= 0) {
            return 0;
        }
        int added = 0;
        for (Candidate c : ranked(candidates, index, picked)) {
            if (added >= n) {
                break;
            }
            picked.add(c.id());
            added++;
        }
        return added;
    }

    /**
     * {@code candidates} not yet picked, best first: overdue, then due at this slot, then the steady ones, then the
     * rest; within each tier the one offered longest ago first, then the authored order.
     */
    @Nonnull
    private List<Candidate> ranked(@Nonnull List<Candidate> candidates, int index, @Nonnull List<String> picked) {
        record Ranked(Candidate c, int tier, long since, int order) {
        }
        List<Ranked> out = new ArrayList<>();
        for (Candidate c : candidates) {
            if (picked.contains(c.id())) {
                continue;
            }
            Integer every = c.bag() == null || deck == null ? null : deck.bag().get(c.bag());
            Integer drought = c.bag() == null || deck == null ? null : deck.noDrought().get(c.bag());
            Integer last = lastSlot.get(c.id());
            long since = last == null ? index + 1L : index - (long) last;
            int tier;
            if (every == null || every <= 1) {
                tier = 2;
            } else if (drought != null && since >= drought) {
                tier = 0;
            } else if (Math.floorMod(place.getOrDefault(c.id(), 0), every) == index % every) {
                tier = 1;
            } else {
                tier = 3;
            }
            out.add(new Ranked(c, tier, since, order.getOrDefault(c.id(), Integer.MAX_VALUE)));
        }
        out.sort(Comparator.comparingInt(Ranked::tier)
                .thenComparing(Comparator.comparingLong(Ranked::since).reversed())
                .thenComparingInt(Ranked::order));
        List<Candidate> ranked = new ArrayList<>(out.size());
        for (Ranked r : out) {
            ranked.add(r.c());
        }
        return ranked;
    }

    /** When {@code picked} is exactly the slot before's set, give its last place to the best option left out. */
    private void avoidRepeat(@Nonnull List<Candidate> candidates, int index, @Nonnull List<String> picked) {
        if (previous == null || picked.isEmpty() || !previous.equals(new LinkedHashSet<>(picked))) {
            return;
        }
        List<Candidate> left = ranked(candidates, index, picked);
        if (!left.isEmpty()) {
            picked.set(picked.size() - 1, left.get(0).id());
        }
    }

    @Nonnull
    private static List<Candidate> byDraw(@Nonnull List<Candidate> candidates, @Nonnull String draw) {
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c.draw().equals(draw)) {
                out.add(c);
            }
        }
        return out;
    }
}
