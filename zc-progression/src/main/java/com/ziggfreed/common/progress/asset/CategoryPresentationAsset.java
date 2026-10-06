package com.ziggfreed.common.progress.asset;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * The leaves EVERY category file carries, whichever engine's content it groups: where the category
 * sorts, what illustrates it, what it is called and the accent colour it is drawn in.
 *
 * <pre>{@code
 * { "Order": 10, "Icon": "Weapon_Longsword_Iron", "TitleKey": "yourmod.category.combat",
 *   "Accent": "#c0504d" }
 * }</pre>
 *
 * <p><b>Why a shared base rather than two similar codecs.</b> Both lifecycle engines file content
 * under a free category word, and one listing surface draws both kinds of category. Declaring these
 * leaves ONCE and appending them into each category type keeps the spellings, the documentation and
 * the accent's reading from drifting apart; a type adds its own leaves on top.
 *
 * <p>Every leaf is optional and {@code appendInherited}: an absent one means "leave it as it was", so
 * a pack that only wants a different icon ships a file with nothing but {@code Icon}, and a file with
 * a {@code Parent} restates only what it changes. Ids are the FILE name, lower-cased at decode by each
 * concrete type, so {@code Combat.json} describes the category content writes as {@code combat}.
 */
public abstract class CategoryPresentationAsset {

    @Nullable protected Integer order;
    @Nullable protected String icon;
    @Nullable protected String titleKey;
    @Nullable protected String accent;

    /**
     * Register the four shared leaves on {@code builder}. Every category type's codec starts from this
     * call, which is what keeps the field names and their meaning the same for every kind of content.
     */
    @Nonnull
    protected static <T extends CategoryPresentationAsset, S extends BuilderCodec.BuilderBase<T, S>> S appendLeaves(
            @Nonnull S builder) {
        return builder
                .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                        (o, v) -> o.order = v, o -> o.order, (o, p) -> o.order = p.order)
                .documentation("Where this category sits among the others, lowest first. It is a sort key rather "
                        + "than an index, so leave gaps (0, 10, 20) and a later category slots between two without "
                        + "renumbering the rest. Unauthored sorts after every category that named one.")
                .add()
                .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                        (o, v) -> o.icon = v, o -> o.icon, (o, p) -> o.icon = p.icon)
                .metadata(EditorSchema.assetRef(Item.class))
                .documentation("An item id standing for the whole category: the picture on its tile, and the one "
                        + "shown for content in it that illustrated itself with nothing of its own.")
                .add()
                .appendInherited(new KeyedCodec<>("TitleKey", Codec.STRING, false),
                        (o, v) -> o.titleKey = v, o -> o.titleKey, (o, p) -> o.titleKey = p.titleKey)
                .documentation("The translation key this category is called by, so every player reads it in their "
                        + "own language. Unauthored, a surface reads the convention key for its kind of content "
                        + "(achievement.category.<id> or quest.category.<id>), and failing that the id itself, "
                        + "tidied into words. A subcategory reads this key plus .<subcategory> first.")
                .add()
                .appendInherited(new KeyedCodec<>("Accent", Codec.STRING, false),
                        (o, v) -> o.accent = v, o -> o.accent, (o, p) -> o.accent = p.accent)
                .documentation("The colour this category's strip and tile are marked in, written #rrggbb. A surface "
                        + "keeps it readable against its own background and uses its own accent when it is not. "
                        + "Unauthored, or written any other way, the category takes a steady colour of its own "
                        + "from the shared palette.")
                .add();
    }

    protected CategoryPresentationAsset() {
    }

    /** The lower-cased category name this presentation applies to. */
    public abstract String getId();

    /** Sort key among categories, or null to sort after every ordered one. */
    @Nullable
    public Integer getOrder() {
        return order;
    }

    /** Sort key, or {@link Integer#MAX_VALUE} when the file named none. */
    public int orderOrLast() {
        return order == null ? Integer.MAX_VALUE : order;
    }

    /** The item id that illustrates this category, or null. */
    @Nullable
    public String getIcon() {
        return icon == null || icon.isBlank() ? null : icon.trim();
    }

    /** The translation key a surface labels this category with, or null. */
    @Nullable
    public String getTitleKey() {
        return titleKey == null || titleKey.isBlank() ? null : titleKey.trim();
    }

    /** The authored accent as a lower-case {@code #rrggbb}, or null when unauthored or malformed. */
    @Nullable
    public String getAccent() {
        return hexColor(accent);
    }

    /**
     * {@code written} as a lower-case {@code #rrggbb}, or null when it is anything else: a colour
     * name, a short or an alpha form, a missing {@code #}. Surrounding spaces are forgiven. One
     * reading for every accent, so a malformed one is unauthored everywhere rather than pushed to a
     * client that would refuse it.
     */
    @Nullable
    public static String hexColor(@Nullable String written) {
        if (written == null) {
            return null;
        }
        String hex = written.trim();
        if (hex.length() != 7 || hex.charAt(0) != '#') {
            return null;
        }
        for (int i = 1; i < hex.length(); i++) {
            if (!isAsciiHexDigit(hex.charAt(i))) {
                return null;
            }
        }
        return hex.toLowerCase(Locale.ROOT);
    }

    /** ASCII only: {@code Character.digit} would also take other scripts' digits, which no client reads. */
    private static boolean isAsciiHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
