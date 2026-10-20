package com.ziggfreed.common.track.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.factor.FactorNames;
import com.ziggfreed.common.grant.Grant;
import com.ziggfreed.common.grant.StatGrant;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.Msg;

/**
 * What a pick's grants give, as the lines a tree page lists: a {@code Stat} grant as its amount and its stat's name
 * ("+2 Health", "+5% Stamina"), the stat named the way a requirement names it ({@code hytale:stat}'s own names, so a
 * mod naming its stat there names it here too), else by its id; a kind that names itself in the tree's lang file
 * ({@code grant.kind.<kind>}) by that line; any other kind by one plain line. Grant kinds carry no wording of their
 * own yet, so this is where they read.
 */
final class TreeGrants {

    /** The factor whose names name a stat. */
    private static final String STAT_FACTOR = "hytale:stat";

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
        Message stat = statName(grant.stat());
        if (grant.add() != 0.0) {
            out.add(TreeText.line(grant.add() > 0 ? "grant.stat.more" : "grant.stat.less", Math.abs(grant.add()), stat));
        }
        if (grant.percent() != 0.0) {
            out.add(TreeText.line(grant.percent() > 0 ? "grant.stat.more.percent" : "grant.stat.less.percent",
                    Math.abs(grant.percent()), stat));
        }
        return out;
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
