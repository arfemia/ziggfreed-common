package com.ziggfreed.common.shop.asset;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The one walk over storefront {@code Includes}, shared by the page's view and the audit so the two agree
 * on what a storefront lists and on what a loop is.
 *
 * <p>A storefront lists its own offers, then each storefront it includes, in authored order, reaching
 * through: a storefront an included one includes is listed too. Each storefront is listed once, so two
 * routes to one stall list it once and a loop is cut where it comes back round. An include nothing defines
 * adds nothing. Whether an included storefront is switched on is not asked: a switched-off stall still
 * supplies the storefronts that include it.
 */
public final class StorefrontIncludes {

    private StorefrontIncludes() {
    }

    /**
     * Every storefront a page for {@code storefrontId} lists from, ids lower-cased: the storefront itself
     * first (whether or not anything defines it, as the page has always read its own id), then each defined
     * storefront its {@code Includes} reach, depth first in authored order, each once.
     *
     * @param resolve the storefront an id names, or null when nothing defines it
     */
    @Nonnull
    public static List<String> chain(@Nonnull String storefrontId,
            @Nonnull Function<String, StorefrontAsset> resolve) {
        String start = normalize(storefrontId);
        if (start.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(start);
        out.add(start);
        walk(resolve.apply(start), resolve, seen, out);
        return List.copyOf(out);
    }

    /**
     * The loop {@code storefrontId}'s {@code Includes} lead back into it by, as the ids from the storefront
     * round to itself again ({@code a, b, a}); empty when nothing it reaches includes it.
     */
    @Nonnull
    public static List<String> loopFrom(@Nonnull String storefrontId,
            @Nonnull Function<String, StorefrontAsset> resolve) {
        String start = normalize(storefrontId);
        List<String> path = new ArrayList<>();
        path.add(start);
        return reaches(start, start, resolve, path, new HashSet<>()) ? List.copyOf(path) : List.of();
    }

    private static void walk(@Nullable StorefrontAsset storefront,
            @Nonnull Function<String, StorefrontAsset> resolve, @Nonnull Set<String> seen,
            @Nonnull List<String> out) {
        if (storefront == null) {
            return;
        }
        for (String included : storefront.includeIds()) {
            StorefrontAsset next = resolve.apply(included);
            if (next != null && seen.add(included)) {
                out.add(included);
                walk(next, resolve, seen, out);
            }
        }
    }

    private static boolean reaches(@Nonnull String at, @Nonnull String target,
            @Nonnull Function<String, StorefrontAsset> resolve, @Nonnull List<String> path,
            @Nonnull Set<String> visited) {
        StorefrontAsset storefront = resolve.apply(at);
        if (storefront == null) {
            return false;
        }
        for (String next : storefront.includeIds()) {
            if (next.equals(target)) {
                path.add(next);
                return true;
            }
            if (visited.add(next)) {
                path.add(next);
                if (reaches(next, target, resolve, path, visited)) {
                    return true;
                }
                path.remove(path.size() - 1);
            }
        }
        return false;
    }

    @Nonnull
    private static String normalize(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
