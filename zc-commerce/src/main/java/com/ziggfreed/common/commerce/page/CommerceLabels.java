package com.ziggfreed.common.commerce.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardSlotAsset;
import com.ziggfreed.common.commerce.fold.BoardAssetSpec;
import com.ziggfreed.common.commerce.fold.CommerceCatalogs;
import com.ziggfreed.common.i18n.ContentI18n;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.I18nModuleContentI18n;
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
 *   <li>the AUTHORED key, from the {@code Text} group beside the band or the category in the file
 *       that declares it - which is how a pack inventing its own band supplies its own word without
 *       anybody writing Java;</li>
 *   <li>the CONVENTION key, when a consumer ships one: a mod that already has
 *       {@code board.grade.veteran} in its own lang file keeps it, and its namespace is whichever
 *       {@link ContentKeys} fill claims the key;</li>
 *   <li>this library's own SHIPPED DEFAULT for the common bands and shelves, under its own
 *       {@code ziggfreedcommon.commerce} namespace, so a bare server with authored content reads in
 *       words rather than in keys;</li>
 *   <li>the raw id, which is an untranslated word a player can still read - and the visible sign
 *       that a band nobody named is being printed.</li>
 * </ol>
 *
 * <p>Rung 2 is asked BEFORE rung 3 deliberately: a consumer's own word for a band has to outrank a
 * generic one this library guessed at, and asking by existence is the only attribution a folded
 * asset leaves available. That is also why this module's own catalogue is probed directly here
 * rather than registered as a {@link ContentKeys} fill - a fill registered by the library would sit
 * in the same queue as its consumers and, since the library loads FIRST, would answer ahead of every
 * one of them.
 */
public final class CommerceLabels {

    /** The convention key a contract's grade is looked up under, plus the band's own word. */
    public static final String GRADE_PREFIX = "board.grade.";

    /** The convention key a shelf's category is looked up under, plus the category's own word. */
    public static final String CATEGORY_PREFIX = "shop.category.";

    /** This module's own chrome namespace: the {@code ziggfreedcommon.commerce.lang} filename plus a dot. */
    public static final String CHROME_PREFIX = "ziggfreedcommon.commerce.";

    /** What this module itself ships, asked only after every consumer has passed. */
    private static final ContentI18n CHROME = new I18nModuleContentI18n(CHROME_PREFIX);

    private CommerceLabels() {
    }

    // ==================== the two labels ====================

    /**
     * What a contract's grade reads as on {@code board}: the band's authored {@code Text}, else the
     * ladder above. {@code gradeId} is the band's own word, already normalized.
     */
    @Nonnull
    public static Message grade(@Nullable BoardAsset board, @Nonnull String gradeId,
            @Nullable CommerceText.ArgResolver resolver) {
        BoardSlotAsset[] slots = board == null ? null : board.slotsOrEmpty();
        return label(gradeTextOf(slots, gradeId), GRADE_PREFIX + gradeId, gradeId, resolver);
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

    /** What a shelf reads as on {@code shop}: the category's authored {@code Text}, else the ladder. */
    @Nonnull
    public static Message category(@Nullable StorefrontAsset shop, @Nonnull String categoryId,
            @Nullable CommerceText.ArgResolver resolver) {
        return label(shop == null ? null : shop.categoryText(categoryId),
                CATEGORY_PREFIX + categoryId, categoryId, resolver);
    }

    // ==================== the ladder itself ====================

    /**
     * Which key a label should actually be emitted under, or null when nothing on this server ships
     * one and the raw id is all there is. The unprefixed form means "a consumer claims this, let
     * {@link ContentKeys} say whose"; the prefixed form is this module's own shipped default, which
     * no fill claims and which therefore passes through {@link ContentKeys} exactly as written.
     */
    @Nullable
    public static String labelKey(@Nonnull String conventionKey) {
        if (conventionKey.isBlank()) {
            return null;
        }
        if (ContentKeys.known(conventionKey)) {
            return conventionKey;
        }
        return chromeShips(conventionKey) ? CHROME_PREFIX + conventionKey : null;
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

    private static boolean chromeShips(@Nonnull String conventionKey) {
        try {
            return CHROME.hasKey(conventionKey);
        } catch (Throwable noCatalogueYet) {
            return false;
        }
    }

    // ==================== reading the authored group ====================

    /**
     * The {@code Text} a board wrote beside {@code gradeId}, or null when it named the band without
     * naming it in words. Matched however either was capitalized, exactly as a slot's band is matched
     * against a contract's.
     *
     * <p>Takes the SLOTS rather than the board, because that is the whole of what this reads and it
     * keeps the lookup assertable with no asset store standing.
     */
    @Nullable
    public static ContentTextAsset gradeTextOf(@Nullable BoardSlotAsset[] slots,
            @Nonnull String gradeId) {
        if (slots == null || gradeId.isEmpty()) {
            return null;
        }
        for (BoardSlotAsset slot : slots) {
            if (slot != null && gradeId.equals(slot.label())) {
                return slot.getText();
            }
        }
        return null;
    }

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
