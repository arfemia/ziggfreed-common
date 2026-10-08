package com.ziggfreed.common.progress;

import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.validation.Finding;

/**
 * The key a piece of content is NAMED by when its file writes none, and the one check that a name
 * resolves at all. Each kind keeps the suffixes its shipped files already use: an achievement is
 * {@code achievement.<id>.title} and {@code achievement.<id>.desc}; a quest, and a board contract
 * (which folds as a quest), {@code quest.<id>.title} and {@code quest.<id>.flavor}. A yearly copy is
 * named by its BASE id, so one line names every year's copy. The id goes in exactly as folded, which
 * is lower case for every store this library folds.
 *
 * <p>Every key is bare: a pack ships it in any {@code .lang} file of its own, and zc-core's
 * {@code ContentKeys} finds the namespace that file registered it under.
 */
public final class ConventionKeys {

    /** The code of the finding for a name that resolves in no loaded en-US catalogue. */
    public static final String UNRESOLVED_TITLE = "UNRESOLVED_TITLE";

    private static final String ACHIEVEMENT = "achievement.";
    private static final String QUEST = "quest.";

    private ConventionKeys() {
    }

    /** {@code achievement.<baseId>.title}, or null for a blank id. */
    @Nullable
    public static String achievementTitle(@Nullable String baseId) {
        return key(ACHIEVEMENT, baseId, ".title");
    }

    /** {@code achievement.<baseId>.desc}, or null for a blank id. */
    @Nullable
    public static String achievementDescription(@Nullable String baseId) {
        return key(ACHIEVEMENT, baseId, ".desc");
    }

    /** {@code quest.<id>.title}, for a quest or a board contract, or null for a blank id. */
    @Nullable
    public static String questTitle(@Nullable String questId) {
        return key(QUEST, questId, ".title");
    }

    /** {@code quest.<id>.flavor}, for a quest or a board contract, or null for a blank id. */
    @Nullable
    public static String questFlavor(@Nullable String questId) {
        return key(QUEST, questId, ".flavor");
    }

    /**
     * A WARNING when neither {@code text}'s explicit title key nor its convention key is in the loaded
     * en-US catalogue, else null. Null too while no catalogue is loaded (a unit JVM, or before the
     * language files load), when nothing can be judged. Without either key a name falls back to a plain
     * DisplayName (one language for every player), then the first step's line, then the raw key.
     */
    @Nullable
    public static Finding unresolvedTitle(@Nonnull String domain, @Nonnull String id, @Nonnull ContentText text) {
        if (LangCatalog.catalogue().isEmpty() || text.titleKeyShipped()) {
            return null;
        }
        String explicit = text.titleKey();
        String convention = text.titleConventionKey();
        String asked;
        if (explicit == null && convention == null) {
            asked = "it writes no TitleKey and has no id to be named by";
        } else if (explicit == null) {
            asked = "it writes no TitleKey and no loaded en-US .lang file ships '" + convention + "'";
        } else if (convention == null || convention.equals(explicit)) {
            asked = "no loaded en-US .lang file ships '" + explicit + "'";
        } else {
            asked = "no loaded en-US .lang file ships its TitleKey '" + explicit + "' or the convention key '"
                    + convention + "'";
        }
        return Finding.warning(domain, UNRESOLVED_TITLE, asked + ", so its name falls back to a plain "
                + "DisplayName, the first step's line or the raw key; ship "
                + (convention == null ? "a key" : "'" + convention + "'") + " in the pack's own lang file", id);
    }

    /**
     * As {@link #unresolvedTitle(String, String, ContentText)}, for an audit that has to FOLD the
     * content to read its words. The fold runs only once a catalogue is loaded, and a fold that
     * throws (or answers null) costs this one finding: an audit never throws over one malformed
     * file, and that file's other findings still report.
     */
    @Nullable
    public static Finding unresolvedTitle(@Nonnull String domain, @Nonnull String id,
            @Nonnull Supplier<ContentText> fold) {
        if (LangCatalog.catalogue().isEmpty()) {
            return null;
        }
        ContentText text;
        try {
            text = fold.get();
        } catch (Throwable foldFailed) {
            // Nothing to read, so nothing to judge; the store's own fold meets the same file.
            return null;
        }
        return text == null ? null : unresolvedTitle(domain, id, text);
    }

    @Nullable
    private static String key(@Nonnull String prefix, @Nullable String id, @Nonnull String suffix) {
        return id == null || id.isBlank() ? null : prefix + id.trim() + suffix;
    }
}
