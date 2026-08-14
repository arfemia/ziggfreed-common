package com.ziggfreed.common.commerce.command;

import java.awt.Color;
import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * Every line this command family says, and the two rules it says them by.
 *
 * <p><b>A sentence is a KEY; an id or a number is a raw argument.</b> The words belong to whoever is
 * reading them, and they are resolved on that person's own client, so nothing here builds a sentence
 * out of English fragments. An asset id, a player name and a count are DATA - they read the same in
 * every language, and a translated one would stop naming the thing an author has to go and edit.
 *
 * <p><b>Keys live in {@code ziggfreedcommon.commerce.admin.lang}</b>, so the in-file key drops the
 * {@code ziggfreedcommon.commerce.admin.} segment the filename already carries. The DESCRIPTION
 * strings a command and its arguments are constructed with are keys too: the engine resolves a
 * command description through its own localization module, so a plain English one renders to the
 * reader as the raw text nobody translated.
 */
public final class CommerceAdminMessages {

    /** The key family every line here resolves under (the shipped file name, minus {@code .lang}). */
    public static final String PREFIX = "ziggfreedcommon.commerce.admin.";

    /** What a command or one of its arguments is FOR, resolved by the engine's own help. */
    @Nonnull
    public static String desc(@Nonnull String what) {
        return PREFIX + "desc." + what;
    }

    private static final Color HEADING = new Color(0xFFCC66);
    private static final Color DETAIL = new Color(0xAAAAAA);
    private static final Color BAD = new Color(0xFF5555);
    private static final Color GOOD = new Color(0x77DD77);

    private CommerceAdminMessages() {
    }

    // ==================== saying it ====================

    /** A heading line: what the rows under it are. */
    public static void heading(@Nonnull CommandContext ctx, @Nonnull String key,
            @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(HEADING));
    }

    /** A row, or anything else that is detail rather than an answer. */
    public static void detail(@Nonnull CommandContext ctx, @Nonnull String key,
            @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(DETAIL));
    }

    /** Something was done. */
    public static void done(@Nonnull CommandContext ctx, @Nonnull String key,
            @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(GOOD));
    }

    /** Something was refused, and why. */
    public static void refused(@Nonnull CommandContext ctx, @Nonnull String key,
            @Nonnull Object... args) {
        ctx.sendMessage(Msg.key(PREFIX + key, args).color(BAD));
    }

    // ==================== findings ====================

    /** How many findings are worth showing before the rest are left to the server log. */
    private static final int MAX_SHOWN = 20;

    /**
     * Report an audit: the counts first, then each finding, then how many were left out.
     *
     * <p>A finding's own MESSAGE is written for whoever authored the content, names files and ids,
     * and is not translated - the same choice every content validator in this library already made.
     * The sentence around it is.
     */
    public static void findings(@Nonnull CommandContext ctx, @Nonnull List<Finding> findings) {
        if (findings.isEmpty()) {
            done(ctx, "validate.clean");
            return;
        }
        heading(ctx, "validate.counts", count(findings, Severity.ERROR),
                count(findings, Severity.WARNING), count(findings, Severity.INFO));
        int shown = 0;
        for (Finding finding : findings) {
            if (shown++ >= MAX_SHOWN) {
                detail(ctx, "more", findings.size() - MAX_SHOWN);
                return;
            }
            ctx.sendMessage(Msg.key(PREFIX + "finding", finding.severity().name(), finding.code(),
                    finding.sourceId(), finding.message()).color(colorOf(finding.severity())));
        }
    }

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

    // ==================== small shared answers ====================

    /** The one spelling of "nothing on this server defines that wallet". */
    public static void unknownCurrency(@Nonnull CommandContext ctx, @Nonnull String currencyId) {
        refused(ctx, "wallet.unknown", currencyId);
    }
}
