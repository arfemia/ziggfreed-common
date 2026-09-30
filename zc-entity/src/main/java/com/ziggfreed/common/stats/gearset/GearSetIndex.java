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
 */
public final class GearSetIndex {

    private static final Comparator<GearSetAsset> BY_ID =
            Comparator.comparing(GearSetAsset::getId, String.CASE_INSENSITIVE_ORDER);

    /** No sets at all. */
    public static final GearSetIndex EMPTY = new GearSetIndex(List.of(), Set.of());

    private final Map<String, List<GearSetAsset>> byItem;
    private final Set<String> effectIds;

    private GearSetIndex(@Nonnull List<GearSetAsset> enabledSorted, @Nonnull Set<String> effectIds) {
        this.effectIds = Collections.unmodifiableSet(new LinkedHashSet<>(effectIds));
        Map<String, List<GearSetAsset>> index = new LinkedHashMap<>();
        for (GearSetAsset set : enabledSorted) {
            for (String member : set.memberIds()) {
                index.computeIfAbsent(member, k -> new ArrayList<>()).add(set);
            }
        }
        for (Map.Entry<String, List<GearSetAsset>> e : index.entrySet()) {
            e.setValue(Collections.unmodifiableList(e.getValue()));
        }
        this.byItem = Collections.unmodifiableMap(index);
    }

    /**
     * The index over every enabled set in {@code all}, ordered by id so a walk is the same twice,
     * plus the effect ids of every set in {@code all}, enabled or not.
     */
    @Nonnull
    public static GearSetIndex of(@Nonnull Collection<GearSetAsset> all) {
        List<GearSetAsset> sorted = new ArrayList<>();
        for (GearSetAsset set : all) {
            if (set != null && set.getId() != null) {
                sorted.add(set);
            }
        }
        sorted.sort(BY_ID);
        List<GearSetAsset> enabled = new ArrayList<>();
        Set<String> effects = new LinkedHashSet<>();
        for (GearSetAsset set : sorted) {
            if (set.isEnabled()) {
                enabled.add(set);
            }
            for (GearSetAsset.Tier tier : set.tiers()) {
                String effect = tier.effectId();
                if (effect != null) {
                    effects.add(effect);
                }
            }
        }
        return new GearSetIndex(enabled, effects);
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
     * Every effect id any tier of any folded set holds, a DISABLED set's included, distinct and in
     * set-id order: half of what a hydrate answers for, since it cannot trust memory. A set deleted
     * from the fold is not here; the player's saved record ({@link GearSetLooksComponent}) is the
     * other half, which names its look.
     */
    @Nonnull
    public Set<String> allEffectIds() {
        return effectIds;
    }
}
