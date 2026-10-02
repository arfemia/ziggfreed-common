package com.ziggfreed.common.command;

import java.awt.Color;
import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * How an admin family's {@code validate} verb answers whoever asked: the counts first, then each
 * finding, then how many were left to the server log.
 *
 * <p>The words are the family's own: every line resolves under the family's key {@code prefix}
 * ({@code validate.clean}, {@code validate.counts}, {@code finding}, {@code more}), so each family's
 * lang file says what was audited. A finding's own message is written for whoever authored the
 * content, names files and ids, and is not translated, the same choice every content validator in
 * this library made; the sentence around it is.
 */
public final class FindingsReply {

    /** How many findings are worth showing before the rest are left to the server log. */
    public static final int MAX_SHOWN = 20;

    private static final Color HEADING = new Color(0xFFCC66);
    private static final Color DETAIL = new Color(0xAAAAAA);
    private static final Color BAD = new Color(0xFF5555);
    private static final Color GOOD = new Color(0x77DD77);

    private FindingsReply() {
    }

    /** Report {@code findings} to {@code ctx}, every line under the family key {@code prefix}. */
    public static void send(@Nonnull CommandContext ctx, @Nonnull String prefix, @Nonnull List<Finding> findings) {
        if (findings.isEmpty()) {
            ctx.sendMessage(Msg.key(prefix + "validate.clean").color(GOOD));
            return;
        }
        ctx.sendMessage(Msg.key(prefix + "validate.counts", count(findings, Severity.ERROR),
                count(findings, Severity.WARNING), count(findings, Severity.INFO)).color(HEADING));
        int shown = 0;
        for (Finding finding : findings) {
            if (shown++ >= MAX_SHOWN) {
                ctx.sendMessage(Msg.key(prefix + "more", findings.size() - MAX_SHOWN).color(DETAIL));
                return;
            }
            ctx.sendMessage(Msg.key(prefix + "finding", finding.severity().name(), finding.code(),
                    finding.sourceId(), finding.message()).color(colorOf(finding.severity())));
        }
    }

    /** A count binds as a long, the type every family's reply has always sent it as. */
    private static long count(@Nonnull List<Finding> findings, @Nonnull Severity severity) {
        return findings.stream().filter(f -> f.severity() == severity).count();
    }

    @Nonnull
    private static Color colorOf(@Nonnull Severity severity) {
        if (severity == Severity.ERROR) {
            return BAD;
        }
        return severity == Severity.WARNING ? HEADING : DETAIL;
    }
}
