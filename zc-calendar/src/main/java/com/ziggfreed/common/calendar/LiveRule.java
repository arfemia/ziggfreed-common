package com.ziggfreed.common.calendar;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A calendar event that is live by other events: {@code "Live": {"AnyOf": [...], "AllOf": [...], "NoneOf":
 * [...]}}, each list naming event ids. It holds while ANY of {@code AnyOf} is live (an empty AnyOf asks nothing),
 * ALL of {@code AllOf} are, and NONE of {@code NoneOf} is. A member the server lacks or has switched off reads as
 * not live, as every other gate reads an absent event. Pure: the caller answers each member.
 *
 * <p>An event whose Live leads round a loop back to an event on its own path (itself included) is refused: it is
 * never live, however its members read, and the content audit names the loop. The check walks every member
 * whatever it reads, so whether an event is refused never depends on what happens to be live.
 *
 * @param anyOf  lower-cased ids, any of which is live
 * @param allOf  lower-cased ids, all of which are live
 * @param noneOf lower-cased ids, none of which is live
 */
public record LiveRule(@Nonnull List<String> anyOf, @Nonnull List<String> allOf, @Nonnull List<String> noneOf) {

    public LiveRule {
        anyOf = List.copyOf(anyOf);
        allOf = List.copyOf(allOf);
        noneOf = List.copyOf(noneOf);
    }

    /** The three lists as authored: trimmed, lower-cased, blanks and repeats dropped; a null list is empty. */
    @Nonnull
    public static LiveRule of(@Nullable String[] anyOf, @Nullable String[] allOf, @Nullable String[] noneOf) {
        return new LiveRule(ids(anyOf), ids(allOf), ids(noneOf));
    }

    /** Does it name no event at all? Such an event is never live. */
    public boolean isEmpty() {
        return anyOf.isEmpty() && allOf.isEmpty() && noneOf.isEmpty();
    }

    /** Every event it names, each once, AnyOf first, then AllOf, then NoneOf. */
    @Nonnull
    public List<String> members() {
        Set<String> out = new LinkedHashSet<>(anyOf);
        out.addAll(allOf);
        out.addAll(noneOf);
        return List.copyOf(out);
    }

    /** Does it hold, each member answered by {@code live}: any of AnyOf (none asked when empty), all of AllOf, none of NoneOf? */
    public boolean holds(@Nonnull Predicate<String> live) {
        if (isEmpty()) {
            return false;
        }
        if (!anyOf.isEmpty() && anyOf.stream().noneMatch(live)) {
            return false;
        }
        return allOf.stream().allMatch(live) && noneOf.stream().noneMatch(live);
    }

    /**
     * The loop {@code id}'s Live leads round, as the ids from the first one met twice back to it ({@code [a, b, a]});
     * null when it leads round none. {@code rules} answers an event's rule, null for one that is not live by other
     * events (dated, following the moon, absent or switched off), which ends a path. Walks every member.
     */
    @Nullable
    public static List<String> loopFrom(@Nonnull String id, @Nonnull Function<String, LiveRule> rules) {
        return walk(id.trim().toLowerCase(Locale.ROOT), rules, new ArrayList<>(), new LinkedHashSet<>());
    }

    /** Is {@code id} refused: does its Live lead round a loop ({@link #loopFrom})? */
    public static boolean refused(@Nonnull String id, @Nonnull Function<String, LiveRule> rules) {
        return loopFrom(id, rules) != null;
    }

    /**
     * Depth first from {@code id} along {@code path}; {@code cleared} holds ids already walked without finding a
     * loop, so a member named twice is walked once.
     */
    @Nullable
    private static List<String> walk(@Nonnull String id, @Nonnull Function<String, LiveRule> rules,
            @Nonnull List<String> path, @Nonnull Set<String> cleared) {
        int seen = path.indexOf(id);
        if (seen >= 0) {
            List<String> loop = new ArrayList<>(path.subList(seen, path.size()));
            loop.add(id);
            return List.copyOf(loop);
        }
        if (cleared.contains(id)) {
            return null;
        }
        LiveRule rule = rules.apply(id);
        if (rule == null) {
            cleared.add(id);
            return null;
        }
        path.add(id);
        for (String member : rule.members()) {
            List<String> loop = walk(member, rules, path, cleared);
            if (loop != null) {
                return loop;
            }
        }
        path.remove(path.size() - 1);
        cleared.add(id);
        return null;
    }

    @Nonnull
    private static List<String> ids(@Nullable String[] authored) {
        if (authored == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String id : authored) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(out);
    }
}
