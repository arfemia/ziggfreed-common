package com.ziggfreed.common.instance.dungeon.layout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The one fold every id, role, tag and door kind in a layout goes through, so each is matched without regard to
 * case: trimmed and lower-cased, a blank one dropped. Lists keep their first-written order, never a hash order, so
 * a seed walks them the same way in every JVM.
 */
public final class Words {

    private Words() {
    }

    /** {@code word} trimmed and lower-cased, or null when blank. */
    @Nullable
    public static String fold(@Nullable String word) {
        return word == null || word.isBlank() ? null : word.trim().toLowerCase(Locale.ROOT);
    }

    /** {@code word} trimmed but not folded (an engine asset id keeps its spelling), or null when blank. */
    @Nullable
    public static String trimmed(@Nullable String word) {
        return word == null || word.isBlank() ? null : word.trim();
    }

    /** Every word folded, blanks and repeats dropped, in the order written. */
    @Nonnull
    public static List<String> foldAll(@Nullable Collection<String> words) {
        if (words == null || words.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String word : words) {
            String folded = fold(word);
            if (folded != null) {
                out.add(folded);
            }
        }
        return List.copyOf(out);
    }

    /** As {@link #foldAll(Collection)} over an array. */
    @Nonnull
    public static List<String> foldAll(@Nullable String[] words) {
        if (words == null || words.length == 0) {
            return List.of();
        }
        List<String> list = new ArrayList<>(words.length);
        for (String word : words) {
            list.add(word);
        }
        return foldAll(list);
    }
}
