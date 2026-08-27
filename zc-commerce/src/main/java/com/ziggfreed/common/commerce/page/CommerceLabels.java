package com.ziggfreed.common.commerce.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.commerce.fold.BoardAssetSpec;
import com.ziggfreed.common.commerce.fold.CommerceCatalogs;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.asset.ContentTextAsset;
import com.ziggfreed.common.shop.asset.StorefrontAsset;

/**
 * What a difficulty BAND and a shelf CATEGORY are called, on one ladder both screens read.
 *
 * <p>Both are the same shape of problem: the content invents a free word ({@code training},
 * {@code featured}), a screen has to print it, and the word itself is not something a player should
 * read in English on a Russian client. Two surfaces already synthesized a key from that word
 * ({@code board.grade.<id>}, {@code shop.category.<id>}) and handed it straight to a client, so a
 * band nobody had written a translation for rendered as the key itself.
 *
 * <h2>The ladder</h2>
 *
 * <ol>
 *   <li>the AUTHORED key, from the board's own {@code Grades} entry for the band or the storefront's
 *       {@code Categories} entry for the shelf - which is how a pack inventing its own band or shelf
 *       supplies its own word without anybody writing Java;</li>
 *   <li>the CONVENTION key, when the server's loaded catalogue ships one under any namespace: a mod
 *       that already has {@code board.grade.veteran} in its own lang file keeps it, and this
 *       library's own shipped defaults for the common bands and shelves (its
 *       {@code ziggfreedcommon.commerce} namespace) sit in that same catalogue, so a bare server
 *       with authored content reads in words rather than in keys. Which namespace answers is
 *       {@link ContentKeys}'s one deterministic rule;</li>
 *   <li>the raw id, which is an untranslated word a player can still read - and the visible sign
 *       that a band nobody named is being printed.</li>
 * </ol>
 */
public final class CommerceLabels {

    /** The convention key a contract's grade is looked up under, plus the band's own word. */
    public static final String GRADE_PREFIX = "board.grade.";

    /** The convention key a shelf's category is looked up under, plus the category's own word. */
    public static final String CATEGORY_PREFIX = "shop.category.";

    private CommerceLabels() {
    }

    // ==================== the two labels ====================

    /**
     * What a contract's grade reads as on {@code board}: the band's authored {@code Grades} entry,
     * else the ladder above. {@code gradeId} is the band's own word, already normalized.
     */
    @Nonnull
    public static Message grade(@Nullable BoardAsset board, @Nonnull String gradeId,
            @Nullable CommerceText.ArgResolver resolver) {
        return label(board == null ? null : board.gradeText(gradeId), GRADE_PREFIX + gradeId, gradeId, resolver);
    }

    /**
     * {@link #grade(BoardAsset, String, CommerceText.ArgResolver)} for a surface holding only the
     * board's ID - the objective book, a quest log, anything reading a contract away from its board.
     * Guarded: a catalogue that is not up yet costs the authored rung, never the label.
     */
    @Nonnull
    public static Message gradeOn(@Nullable String boardId, @Nonnull String gradeId,
            @Nullable CommerceText.ArgResolver resolver) {
        return grade(boardOf(boardId), CommerceText.normalize(gradeId), resolver);
    }

    /**
     * What a shelf reads as on {@code shop}: the storefront's own {@code Categories} entry for the
     * shelf, else the ladder above.
     */
    @Nonnull
    public static Message category(@Nullable StorefrontAsset shop, @Nonnull String categoryId,
            @Nullable CommerceText.ArgResolver resolver) {
        return label(shop == null ? null : shop.categoryText(categoryId),
                CATEGORY_PREFIX + categoryId, categoryId, resolver);
    }

    // ==================== the ladder itself ====================

    /**
     * Which key a label should actually be emitted under, or null when nothing on this server ships
     * one and the raw id is all there is. Always the unprefixed convention key, meaning "some loaded
     * catalogue ships this, let {@link ContentKeys} say which" - this module's own shipped defaults
     * are one such catalogue ({@code ziggfreedcommon.commerce.lang}) and are found the same way a
     * consumer's word is.
     */
    @Nullable
    public static String labelKey(@Nonnull String conventionKey) {
        if (conventionKey.isBlank()) {
            return null;
        }
        return ContentKeys.known(conventionKey) ? conventionKey : null;
    }

    /**
     * The authored group first, then {@link #labelKey}, then the id itself.
     *
     * <p>The authored rung runs through {@link CommerceText#title} rather than reading the key
     * directly, so a band or a shelf gets the same {@code DisplayName} fallback and the same
     * {@code TextArgs} binding every other authored title on these screens gets.
     */
    @Nonnull
    private static Message label(@Nullable ContentTextAsset authored, @Nonnull String conventionKey,
            @Nonnull String id, @Nullable CommerceText.ArgResolver resolver) {
        String key = labelKey(conventionKey);
        Message fallback = key == null ? Msg.raw(id) : ContentKeys.tr(key);
        return CommerceText.title(authored, resolver, fallback);
    }

    // ==================== resolving a board by id ====================

    @Nullable
    private static BoardAsset boardOf(@Nullable String boardId) {
        if (boardId == null || boardId.isBlank()) {
            return null;
        }
        try {
            BoardAssetSpec spec = CommerceCatalogs.boards().board(boardId);
            return spec == null ? null : spec.asset();
        } catch (Throwable notLoadedYet) {
            return null;
        }
    }
}
