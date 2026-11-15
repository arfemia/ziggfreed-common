package com.ziggfreed.common.authoring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.match.MatchKey;
import com.ziggfreed.common.match.MatchSets;

/**
 * What a wizard's picker offers: a {@link Source} of ids, read each time the step paints, so a pack loaded since or
 * a kind a mod registered late is on the next list. A picker shows as a dropdown on its step, narrowed by the step's
 * search row and capped at {@link #LIMIT} entries, with the value already chosen always kept and a blank first
 * entry for none. Pick, never type, where a list exists.
 */
public final class Pickers {

    /** The most entries one picker lists at once; the search row narrows a longer list. */
    public static final int LIMIT = 100;

    /** One picker's ids. Called on the world thread each time its step paints; never null. */
    @FunctionalInterface
    public interface Source {

        @Nonnull
        Collection<String> ids();
    }

    private Pickers() {
    }

    /** Every item on this server but the engine's placeholder for an unknown one. */
    @Nonnull
    public static Source items() {
        return () -> {
            List<String> ids = new ArrayList<>();
            for (Map.Entry<String, Item> e : Item.getAssetMap().getAssetMap().entrySet()) {
                String id = e.getKey();
                if (id != null && !id.isBlank() && e.getValue() != Item.UNKNOWN) {
                    ids.add(id);
                }
            }
            return ids;
        };
    }

    /** Every block family on this server (zc-world's, through the shared matcher's seam). */
    @Nonnull
    public static Source blockFamilies() {
        return () -> MatchSets.ids(MatchKey.Form.BLOCK_FAMILY);
    }

    /** Every native NPC group on this server. */
    @Nonnull
    public static Source npcGroups() {
        return () -> MatchSets.ids(MatchKey.Form.NPC_GROUP);
    }

    /** Every item tag word any item on this server carries. */
    @Nonnull
    public static Source itemTags() {
        return () -> MatchSets.ids(MatchKey.Form.ITEM_TAG);
    }

    /** A kind list from a registry (the objective kinds, the reward kinds), read fresh each paint. */
    @Nonnull
    public static Source of(@Nonnull Supplier<? extends Collection<String>> ids) {
        return () -> {
            Collection<String> all = ids.get();
            return all == null ? List.of() : all;
        };
    }

    /** A fixed list. */
    @Nonnull
    public static Source of(@Nonnull String... ids) {
        List<String> fixed = List.of(ids);
        return () -> fixed;
    }

    /**
     * The dropdown's entries for {@code ids} under the search text {@code filter}: a blank first entry (none), then
     * every id holding {@code filter} (any case), sorted without regard to case, at most {@code limit} of them,
     * with {@code current} kept whether it matches or not. Pure.
     */
    @Nonnull
    static List<String> entries(@Nullable Collection<String> ids, @Nullable String filter, @Nullable String current,
            int limit) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        Set<String> sorted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isBlank() && (needle.isEmpty() || id.toLowerCase(Locale.ROOT).contains(needle))) {
                    sorted.add(id);
                }
            }
        }
        List<String> out = new ArrayList<>();
        out.add("");
        for (String id : sorted) {
            if (out.size() > Math.max(1, limit)) {
                break;
            }
            out.add(id);
        }
        // The dropdown shows a value only when an entry spells it exactly, so the chosen one stays, at the top.
        String kept = current == null || current.isBlank() ? null : current.trim();
        if (kept != null && !out.contains(kept)) {
            out.add(1, kept);
        }
        return out;
    }
}
