package com.ziggfreed.common.content;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * How an id is minted from a typed name, and which ids a file may carry. Pure: the caller says which
 * ids are taken.
 *
 * <p>An id is the family's {@code Is_Like_This}: the typed name's letters and digits, accents and
 * apostrophes dropped, split into words at anything else, each word capitalised and the rest lower-cased, joined by
 * {@code _}. A name with nothing usable in it (all punctuation, or a script with no Latin letters)
 * mints from {@link #FALLBACK}. An id taken in the store gets the first free {@code _2}, {@code _3}
 * suffix. Ids are compared without regard to case, as the engine keys them.
 */
final class ContentIds {

    /** The base an empty name mints from. */
    static final String FALLBACK = "Custom";

    /** The longest id minted, before any suffix: a file name, a lang key segment and a log line all carry it. */
    static final int MAX_LENGTH = 48;

    /** What a file in the pack may be named: letters, digits, {@code _} and {@code -}, never a path. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    private static final Pattern WORD_BREAK = Pattern.compile("[^A-Za-z0-9]+");

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    /** Apostrophes (straight, curly, modifier, backtick): dropped, never a word break, so "wolf's" stays one word. */
    private static final Pattern APOSTROPHES = Pattern.compile("['`\\x{2019}\\x{02BC}]");

    private ContentIds() {
    }

    /** True when {@code id} can name a file in the pack: no separator, no dot, no traversal. */
    static boolean isSafe(@Nullable String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    /** The id {@code typedName} mints to, ignoring what is taken. */
    @Nonnull
    static String base(@Nullable String typedName) {
        String plain = typedName == null ? ""
                : APOSTROPHES.matcher(MARKS.matcher(Normalizer.normalize(typedName, Normalizer.Form.NFD)).replaceAll(""))
                        .replaceAll("");
        List<String> words = new ArrayList<>();
        for (String word : WORD_BREAK.split(plain)) {
            if (!word.isEmpty()) {
                words.add(word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        StringBuilder id = new StringBuilder();
        for (String word : words) {
            int room = MAX_LENGTH - id.length() - (id.length() == 0 ? 0 : 1);
            if (room <= 0) {
                break;
            }
            if (id.length() > 0) {
                id.append('_');
            }
            id.append(word, 0, Math.min(word.length(), room));
        }
        return id.length() == 0 ? FALLBACK : id.toString();
    }

    /**
     * The id {@code typedName} mints to that {@code taken} does not hold: the base, else the base with
     * the first free {@code _<n>} from 2.
     */
    @Nonnull
    static String mint(@Nullable String typedName, @Nonnull Predicate<String> taken) {
        String base = base(typedName);
        if (!taken.test(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String candidate = base + "_" + n;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
    }
}
