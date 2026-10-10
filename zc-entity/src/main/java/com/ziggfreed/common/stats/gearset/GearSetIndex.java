package com.ziggfreed.common.stats.gearset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.stats.EquippedSnapshot;

/**
 * The folded gear sets turned around: item id to the sets it belongs to, so a recompute asks only
 * about the sets the player's worn items could complete instead of walking every set on the server.
 * Pure and immutable; built from the folded config and dropped when the content changes.
 *
 * <p>Only ENABLED sets are indexed: a disabled set is not a candidate for anybody. Its modifier keys
 * are still swept off a player (the next recompute sweeps what it wrote, and the once-per-login read
 * sweeps whatever is actually present), and its look is still the engine's to take off:
 * {@link #allEffectIds} names the effects of EVERY folded set, disabled ones included, because an
 * {@code Infinite} look is saved with the player, so a set switched off while its wearer was offline
 * would otherwise keep its look on them for good. A set DELETED outright is gone from the fold and
 * names nothing here; its look is still swept at login, because the hydrate also answers for every
 * look the player's saved record ({@link GearSetLooksComponent}) says the engine asked for.
 *
 * <p>The sets here are the JOINED ones ({@link GearSetJoins}): a file naming another set in
 * {@code Joins} is folded into that set and is no set of its own, so an item both list belongs to one
 * set, and every reader of a set (the recompute, its notices, a page) asks {@link #set} for the set as
 * it applies, never the raw store, whose answer for a joined set lacks its layers' tiers.
 */
public final class GearSetIndex {

    /** Set files in id order without regard to case: the order every walk here and the fold use. */
    static final Comparator<GearSetAsset> BY_ID =
            Comparator.comparing(GearSetAsset::getId, String.CASE_INSENSITIVE_ORDER);

    /** No sets at all. */
    public static final GearSetIndex EMPTY = new GearSetIndex(List.of(), Set.of());

    private final Map<String, List<GearSetAsset>> byItem;
    private final Map<String, GearSetAsset> bySet;
    private final Set<String> effectIds;

    private GearSetIndex(@Nonnull List<GearSetAsset> joinedSorted, @Nonnull Set<String> effectIds) {
        this.effectIds = Collections.unmodifiableSet(new LinkedHashSet<>(effectIds));
        Map<String, List<GearSetAsset>> index = new LinkedHashMap<>();
        Map<String, GearSetAsset> sets = new LinkedHashMap<>();
        for (GearSetAsset set : joinedSorted) {
            sets.put(GearSetKeys.setId(set.getId()), set);
            if (!set.isEnabled()) {
                continue;
            }
            for (String member : set.memberIds()) {
                index.computeIfAbsent(member, k -> new ArrayList<>()).add(set);
            }
        }
        for (Map.Entry<String, List<GearSetAsset>> e : index.entrySet()) {
            e.setValue(Collections.unmodifiableList(e.getValue()));
        }
        this.byItem = Collections.unmodifiableMap(index);
        this.bySet = Collections.unmodifiableMap(sets);
    }

    /**
     * The index over every enabled set in {@code all} with its layers folded in
     * ({@link GearSetJoins#fold}), ordered by id so a walk is the same twice, plus the effect ids
     * (looks and badges) of every file in {@code all}, enabled or not, a layer's included.
     */
    @Nonnull
    public static GearSetIndex of(@Nonnull Collection<GearSetAsset> all) {
        List<GearSetAsset> files = new ArrayList<>();
        for (GearSetAsset set : all) {
            if (set != null && set.getId() != null) {
                files.add(set);
            }
        }
        files.sort(BY_ID);
        Set<String> effects = new LinkedHashSet<>();
        for (GearSetAsset file : files) {
            for (GearSetAsset.Tier tier : file.tiers()) {
                String effect = tier.effectId();
                if (effect != null) {
                    effects.add(effect);
                }
                String badge = tier.badgeId();
                if (badge != null) {
                    effects.add(badge);
                }
            }
        }
        List<GearSetAsset> joined = new ArrayList<>(GearSetJoins.fold(files).sets());
        joined.sort(BY_ID);
        return new GearSetIndex(joined, effects);
    }

    /**
     * The set {@code id} names as it applies, its layers folded in, matched without regard to case
     * and answered whether it is switched on or not; null for an id no set answers to, a layer's
     * included (a layer is part of the set it joins, never a set of its own).
     */
    @Nullable
    public GearSetAsset set(@Nullable String id) {
        String key = EquippedSnapshot.lower(id);
        return key == null ? null : bySet.get(key);
    }

    /** The sets {@code itemId} belongs to, matched without regard to case; empty for a loose item. */
    @Nonnull
    public List<GearSetAsset> setsFor(@Nullable String itemId) {
        String key = EquippedSnapshot.lower(itemId);
        List<GearSetAsset> found = key == null ? null : byItem.get(key);
        return found == null ? List.of() : found;
    }

    /**
     * The distinct sets any item in {@code snapshot} belongs to, in id order: one lookup per item
     * the player has on, never a walk over every set on the server.
     */
    @Nonnull
    public List<GearSetAsset> candidates(@Nonnull EquippedSnapshot snapshot) {
        Set<GearSetAsset> found = new LinkedHashSet<>();
        for (String itemId : snapshot.distinctItemIds()) {
            found.addAll(setsFor(itemId));
        }
        if (found.size() < 2) {
            return List.copyOf(found);
        }
        List<GearSetAsset> ordered = new ArrayList<>(found);
        ordered.sort(BY_ID);
        return ordered;
    }

    /**
     * Every effect id any tier of any folded set holds, its {@code Effect} and its {@code Badge}, a
     * DISABLED set's included, distinct and in set-id order: half of what a hydrate answers for, since
     * it cannot trust memory. A set deleted from the fold is not here; the player's saved record
     * ({@link GearSetLooksComponent}) is the other half, which names its look. The default tiles
     * ({@link GearSetBadges#DEFAULT_TILES}) are no set's, so the hydrate adds them itself.
     */
    @Nonnull
    public Set<String> allEffectIds() {
        return effectIds;
    }
}
