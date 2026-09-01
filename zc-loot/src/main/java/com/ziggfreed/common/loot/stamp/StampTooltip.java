package com.ziggfreed.common.loot.stamp;

import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemUtility;
import com.hypixel.hytale.server.core.asset.type.item.config.metadata.ItemDisplayMetadata;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.Msg;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

/**
 * How a stamped item READS: its own prose, an "Enhancements" heading, and one line per stat.
 *
 * <p>This is the ONE renderer, here rather than in a consumer, because an item's description slot
 * holds exactly one thing - two mods each writing "the" enhancement tooltip would be two mods
 * fighting over the same field, and the loser's stats would silently stop being visible. Vocabulary
 * still belongs to whoever owns the stat ids; that reaches each line through {@link
 * StatNamerRegistry} and nothing else here knows what any stat means.
 *
 * <p><b>The item's NAME is never touched</b> unless a caller explicitly passes one. An enhanced item
 * is the item it always was, with its stats spelled out underneath: no tier word, no quality word,
 * no rename.
 *
 * <p><b>This surface has no markup parser</b>, which is the reason for the base-description gate
 * below. A {@code <color>} or {@code <b>} tag reaching it renders as literal visible text at the
 * player, so an item whose own description carries markup gets no base prose at all rather than a
 * tooltip full of angle brackets.
 */
public final class StampTooltip {

    /** The heading above the stat lines. */
    private static final String HEADER_KEY = "header";

    /** The lang namespace: the basename of the shipped .lang file. */
    private static final String PREFIX = "ziggfreedcommon.stamp";

    private static final Pattern MARKUP = Pattern.compile("<[^>]*>");

    private StampTooltip() {
    }


    /**
     * {@link #apply} against the server's own loaded language catalogue - the form every caller
     * wants. The explicit-predicate form below exists so the gate is testable without a server.
     */
    @Nonnull
    public static ItemStack apply(@Nonnull ItemStack stack, @Nonnull Map<String, Double> entries,
            @Nullable Message nameOverride) {
        return apply(stack, entries, nameOverride, LangCatalog::has,
                key -> hasMarkup(LangCatalog.value(key)));
    }

    /**
     * Write the display metadata for {@code entries} onto a COPY of {@code stack}.
     *
     * <p>{@code nameOverride} is null for the ordinary case (the item keeps its own name); a caller
     * with a genuine flavour identity - a named enchant, a forged heirloom - passes its own
     * client-resolved name. Call AFTER the stats are stamped, so the returned stack carries both
     * documents.
     */
    @Nonnull
    public static ItemStack apply(@Nonnull ItemStack stack, @Nonnull Map<String, Double> entries,
            @Nullable Message nameOverride, @Nonnull Predicate<String> keyExists,
            @Nonnull Predicate<String> keyValueHasMarkup) {
        Item baseItem = stack.getItem();
        String baseKey = baseDescriptionKey(hasGeneratedToolStats(baseItem), stack.getItemId(),
                baseItem.getDescriptionTranslationKey(), keyExists, keyValueHasMarkup);
        Message base;
        if (baseKey == null) {
            base = null;
        } else if (hasGeneratedToolStats(baseItem)) {
            base = Msg.key(baseKey);
        } else {
            base = baseItem.getDescriptionTranslationMessage();
        }
        return stack.withMetadata(ItemDisplayMetadata.KEYED_CODEC,
                new ItemDisplayMetadata(nameOverride, descriptionFor(base, entries)));
    }

    /**
     * The composed description: the base prose, a blank line, the heading, then one line per stat.
     *
     * <p>A null base leads with the heading instead, so a descriptionless item reads as an
     * enhancement list rather than as an empty line followed by one. No entries returns the base
     * unchanged, or an empty message when there was none - never a bare heading over nothing.
     *
     * <p>Pure, so the whole shape is testable without an item.
     */
    @Nonnull
    public static Message descriptionFor(@Nullable Message baseDescription,
            @Nonnull Map<String, Double> entries) {
        Message result = baseDescription;
        boolean first = true;
        for (Map.Entry<String, Double> summed : entries.entrySet()) {
            if (summed.getKey() == null || summed.getValue() == null) {
                continue;
            }
            Message line = StatNamerRegistry.name(summed.getKey(), summed.getValue());
            if (first) {
                result = baseDescription != null
                        ? Msg.join(baseDescription, Msg.raw("\n\n"), Msg.tr(PREFIX, HEADER_KEY),
                                Msg.raw("\n"), line)
                        : Msg.join(Msg.tr(PREFIX, HEADER_KEY), Msg.raw("\n"), line);
                first = false;
            } else {
                result = Msg.join(result, Msg.raw("\n"), line);
            }
        }
        return result != null ? result : Msg.raw("");
    }

    /**
     * Which description key to nest as the base line, or null for none.
     *
     * <p>Three cases. A tool whose own description is GENERATED (it authors native utility stat
     * modifiers, so its plain description is markup plus a stat row) nests the authored, markup-free
     * {@code .description.base} prose instead. Any other item nests its own description key, unless
     * that key's value carries markup, which this surface cannot render. And every candidate is
     * checked for existence first, so a descriptionless item yields null rather than a phantom line
     * that would print its own key at the player.
     *
     * <p>Pure - unit tested.
     */
    @Nullable
    static String baseDescriptionKey(boolean toolStatsTag, @Nonnull String itemId,
            @Nonnull String ownDescriptionKey, @Nonnull Predicate<String> keyExists,
            @Nonnull Predicate<String> keyValueHasMarkup) {
        if (toolStatsTag) {
            String key = "server.items." + itemId + ".description.base";
            return keyExists.test(key) ? key : null;
        }
        if (!keyExists.test(ownDescriptionKey)) {
            return null;
        }
        if (keyValueHasMarkup.test(ownDescriptionKey)) {
            return null;
        }
        return ownDescriptionKey;
    }

    /** True when {@code value} carries a markup tag this surface would print literally. */
    public static boolean hasMarkup(@Nullable String value) {
        return value != null && MARKUP.matcher(value).find();
    }

    /**
     * True when this item's plain description is generator-owned, i.e. it authors native utility
     * stat modifiers, so something rewrote its description into markup plus a stat row.
     */
    private static boolean hasGeneratedToolStats(@Nonnull Item baseItem) {
        ItemUtility utility = baseItem.getUtility();
        Int2ObjectMap<StaticModifier[]> mods = utility != null ? utility.getStatModifiers() : null;
        return mods != null && !mods.isEmpty();
    }
}
