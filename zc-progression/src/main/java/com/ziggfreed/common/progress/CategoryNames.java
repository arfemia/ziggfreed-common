package com.ziggfreed.common.progress;

import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.asset.CategoryPresentationAsset;

/**
 * What a category and a subcategory are CALLED, and the accent a category is marked in: the one
 * reading every listing surface asks, so a tile, a section head, a breadcrumb and a filter all name a
 * group the same way and in the player's own language.
 *
 * <p><b>The ladder.</b> A key is used only when the loaded catalogue ships it ({@link ContentKeys},
 * namespace-free, so a pack's own lang file supplies a key under its own file name):
 * <ol>
 *   <li>a category: its file's {@code TitleKey}, then the convention key for its kind
 *       ({@code achievement.category.<category>} or {@code quest.category.<category>});</li>
 *   <li>a subcategory: the category's {@code TitleKey} plus {@code .<subcategory>}, then the
 *       convention {@code achievement.category.<category>.<subcategory>}, then a key the caller knows
 *       names it (an event's own calendar name, for a category whose subcategories are events);</li>
 *   <li>failing every key, the id itself tidied into words ({@code boss_fights} reads "Boss Fights"):
 *       an untranslated word a player can read beats a raw key they cannot.</li>
 * </ol>
 * Ids are matched in lower case, the form every category id compares in, so {@code Spring_Fair}
 * filed under {@code Festivals} reads the key {@code achievement.category.festivals.spring_fair}.
 *
 * <p>Takes the shared {@link CategoryPresentationAsset} so either engine's category file can be
 * handed in; the convention prefix is the only thing that differs between the two kinds.
 */
public final class CategoryNames {

    /** The convention key prefix an achievement category (and its subcategories) is named under. */
    public static final String ACHIEVEMENT_PREFIX = "achievement.category.";

    /** The convention key prefix a quest category is named under. */
    public static final String QUEST_PREFIX = "quest.category.";

    /**
     * The colours a category no file gives an accent is marked in, one per category by a stable hash
     * of its id. They are the text colours of the shared UI kit's tones (active, done, available,
     * waiting, danger), so every one already reads against the kit's row surface; the gold reserved
     * for points and Collect and the grey that reads as locked are left out.
     */
    public static final List<String> PALETTE = List.of("#7a9cc6", "#7fc893", "#d9a75e", "#b3a0d1", "#e08080");

    private CategoryNames() {
    }

    /**
     * An achievement category's name: its {@code TitleKey}, else {@code achievement.category.<category>},
     * else the id tidied. {@code category} is the word content filed itself under, never blank (the
     * group of content with no category has a line of its own on every surface).
     */
    @Nonnull
    public static Message achievementCategory(@Nonnull String category, @Nullable CategoryPresentationAsset a) {
        return categoryName(category, a, ACHIEVEMENT_PREFIX);
    }

    /** A quest category's name: its {@code TitleKey}, else {@code quest.category.<category>}, else the id tidied. */
    @Nonnull
    public static Message questCategory(@Nonnull String category, @Nullable CategoryPresentationAsset q) {
        return categoryName(category, q, QUEST_PREFIX);
    }

    /**
     * A subcategory's name: the category's {@code TitleKey} plus {@code .<sub>}, else
     * {@code achievement.category.<category>.<sub>}, else the id tidied.
     */
    @Nonnull
    public static Message achievementSubcategory(@Nonnull String category, @Nonnull String sub,
            @Nullable CategoryPresentationAsset a) {
        return achievementSubcategory(category, sub, a, null);
    }

    /**
     * A subcategory's name, with one more rung before the tidied id: {@code fallbackKey}, a key the
     * caller already knows names this group. For a category whose subcategories are calendar events
     * ({@code SubcategoryEvents}), pass the event's own title key, so the group reads the name its
     * calendar file already ships in every language and needs no key of its own. A key written for
     * the subcategory itself still outranks it.
     */
    @Nonnull
    public static Message achievementSubcategory(@Nonnull String category, @Nonnull String sub,
            @Nullable CategoryPresentationAsset a, @Nullable String fallbackKey) {
        String subId = canonical(sub);
        String titleKey = a == null ? null : a.getTitleKey();
        String key = firstShipped(
                titleKey == null ? null : titleKey + '.' + subId,
                ACHIEVEMENT_PREFIX + canonical(category) + '.' + subId,
                fallbackKey);
        return key != null ? ContentKeys.tr(key) : Msg.raw(humanize(sub));
    }

    /**
     * The colour a category is marked in, as a lower-case {@code #rrggbb}: the authored one when it is
     * a {@code #rrggbb}, else a steady {@link #PALETTE} colour chosen by the category id, so one
     * category is one colour on every row, tile and boot. A surface still holds the answer to its own
     * contrast floor when it paints it.
     */
    @Nonnull
    public static String accent(@Nonnull String category, @Nullable String authoredHex) {
        String authored = CategoryPresentationAsset.hexColor(authoredHex);
        if (authored != null) {
            return authored;
        }
        return PALETTE.get(Math.floorMod(canonical(category).hashCode(), PALETTE.size()));
    }

    /** {@code boss_fights} read as {@code Boss Fights}: separators become spaces, words open big. */
    @Nonnull
    public static String humanize(@Nonnull String id) {
        StringBuilder out = new StringBuilder(id.length());
        boolean opening = true;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '_' || c == '-' || c == '.' || Character.isWhitespace(c)) {
                out.append(' ');
                opening = true;
            } else {
                out.append(opening ? Character.toUpperCase(c) : c);
                opening = false;
            }
        }
        return out.toString().trim();
    }

    @Nonnull
    private static Message categoryName(@Nonnull String category, @Nullable CategoryPresentationAsset a,
            @Nonnull String conventionPrefix) {
        String key = firstShipped(a == null ? null : a.getTitleKey(), conventionPrefix + canonical(category));
        return key != null ? ContentKeys.tr(key) : Msg.raw(humanize(category));
    }

    /** The first key the loaded catalogue ships, or null when none of them is; a null entry is skipped. */
    @Nullable
    private static String firstShipped(@Nonnull String... keys) {
        for (String key : keys) {
            if (key != null && !key.isBlank() && ContentKeys.known(key)) {
                return key.trim();
            }
        }
        return null;
    }

    @Nonnull
    private static String canonical(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
