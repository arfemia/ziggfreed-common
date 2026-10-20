package com.ziggfreed.common.track.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.factor.FactorNames;
import com.ziggfreed.common.grant.Grant;
import com.ziggfreed.common.grant.StatGrant;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.Msg;

/**
 * What a pick's grants give, as the lines a tree page lists. A {@code Stat} grant's amount reads on the stat's own
 * item-tooltip row, the native {@code client.itemTooltip.stats.<StatId>} label the client puts on an item's stat
 * modifier ("Fire Resistance: {value}%"), with the signed amount as its {@code {value}} ("Fire Resistance: +5%"), so
 * the tree page and an item's tooltip name a stat the same way, unit included, and a mod names its own stat for both
 * by shipping that one key in its {@code client.lang}. A stat no shipped row names (the engine's own Health, Stamina,
 * Mana, whose rows only the client carries) reads in the same shape from the name a requirement gives it
 * ({@code hytale:stat}'s own names), else its id. A percent grant (a share of the stat's base) reads on the same row
 * or name, its value the signed percent ("Health: +10%"). A kind that names itself in the tree's lang file ({@code grant.kind.<kind>}) reads that line;
 * any other kind one plain line. Grant kinds carry no wording of their own yet, so this is where they read.
 */
final class TreeGrants {

    /** The factor whose names name a stat. */
    private static final String STAT_FACTOR = "hytale:stat";

    /** The native item-tooltip row of a stat, its id appended: the client's own label convention. */
    private static final String TOOLTIP_ROW = "client.itemTooltip.stats.";

    /** The row's one placeholder, which the client fills with the modifier's amount on an item. */
    private static final String ROW_VALUE = "value";

    private TreeGrants() {
    }

    /** {@code grants} as lines, in order; empty for none. */
    @Nonnull
    static List<Message> lines(@Nonnull List<Grant> grants) {
        List<Message> out = new ArrayList<>();
        for (Grant grant : grants) {
            if (grant instanceof StatGrant stat) {
                out.addAll(statLines(stat));
            } else if (grant != null) {
                out.add(kindLine(grant.kind()));
            }
        }
        return out;
    }

    /** {@code lines} joined into one, each pair through {@code header.list}; null for none. */
    @Nullable
    static Message joined(@Nonnull List<Message> lines) {
        if (lines.isEmpty()) {
            return null;
        }
        Message out = lines.get(0);
        for (int i = 1; i < lines.size(); i++) {
            out = TreeText.line("header.list", out, lines.get(i));
        }
        return out;
    }

    /** A stat grant's flat part and its percent part, each a line when it moves the stat. */
    @Nonnull
    private static List<Message> statLines(@Nonnull StatGrant grant) {
        List<Message> out = new ArrayList<>(2);
        if (grant.add() != 0.0) {
            out.add(statLine(grant.stat(), TreeText.line(grant.add() > 0 ? "grant.amount.more" : "grant.amount.less",
                    Math.abs(grant.add()))));
        }
        if (grant.percent() != 0.0) {
            out.add(statLine(grant.stat(), TreeText.line(grant.percent() > 0
                    ? "grant.amount.more.percent" : "grant.amount.less.percent", Math.abs(grant.percent()))));
        }
        return out;
    }

    /**
     * A signed amount on a stat, flat or percent: the stat's tooltip row when one is shipped, the amount as its value,
     * else the same shape over the stat's name.
     */
    @Nonnull
    private static Message statLine(@Nullable String statId, @Nonnull Message amount) {
        String row = tooltipRow(statId);
        if (row != null && LangCatalog.has(row)) {
            return Msg.keyNamed(row, Map.of(ROW_VALUE, amount));
        }
        return TreeText.line("grant.stat.row", statName(statId), amount);
    }

    /** The full key of {@code statId}'s native tooltip row, or null for no stat. */
    @Nullable
    private static String tooltipRow(@Nullable String statId) {
        return statId == null || statId.isBlank() ? null : TOOLTIP_ROW + statId.trim();
    }

    /** What a stat is called where a requirement names it, else its id. */
    @Nonnull
    private static Message statName(@Nullable String statId) {
        if (statId == null) {
            return Msg.raw("");
        }
        Message named = FactorNames.name(STAT_FACTOR, statId);
        return named != null ? named : Msg.raw(statId);
    }

    /** A grant of another kind: its own line when the tree's lang file ships one, else the plain one. */
    @Nonnull
    private static Message kindLine(@Nonnull String kind) {
        String key = TreeText.PREFIX + "grant.kind." + kind.trim().toLowerCase(Locale.ROOT);
        return LangCatalog.has(key) ? Msg.key(key) : TreeText.line("grant.other");
    }
}
