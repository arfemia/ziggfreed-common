package com.ziggfreed.common.objectives.book;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import com.ziggfreed.common.ui.kit.Stat;
import com.ziggfreed.common.ui.kit.StatPainter;

/**
 * The shell's header band as a tab paints it: the one-sentence subtitle under the page title and the three
 * stat blocks at the right. The title itself is the shell's (it is painted before the rail, so a consumer's
 * white-label branding wins over it). Writes into the context's current builder, so it works in a build and in
 * a partial update alike.
 */
public final class BookHeader {

    /** The subtitle label under the title. */
    public static final String SUBTITLE = "#PageSubtitle";

    @Nonnull private final BookContext ctx;

    BookHeader(@Nonnull BookContext ctx) {
        this.ctx = ctx;
    }

    /** The selector of stat block {@code index} ({@code 0} to {@link LedgerLayout#STATS} - 1). */
    @Nonnull
    public static String statSelector(int index) {
        return "#Stat" + index;
    }

    /** The subtitle, or null to hide it. */
    public void subtitle(@Nullable Message line) {
        UICommandBuilder cmd = ctx.cmd();
        cmd.set(SUBTITLE + ".Visible", line != null);
        if (line != null) {
            cmd.set(SUBTITLE + ".TextSpans", line);
        }
    }

    /** Stat block {@code index}, or null to hide it; an index outside the band is ignored. */
    public void stat(int index, @Nullable Stat stat) {
        if (index < 0 || index >= LedgerLayout.STATS) {
            return;
        }
        String selector = statSelector(index);
        UICommandBuilder cmd = ctx.cmd();
        cmd.set(selector + ".Visible", stat != null);
        if (stat != null) {
            StatPainter.paint(cmd, selector, stat);
        }
    }

    /** The stats in order from the left, hiding every block past the last one given. */
    public void stats(@Nonnull List<Stat> stats) {
        for (int i = 0; i < LedgerLayout.STATS; i++) {
            stat(i, i < stats.size() ? stats.get(i) : null);
        }
    }
}
