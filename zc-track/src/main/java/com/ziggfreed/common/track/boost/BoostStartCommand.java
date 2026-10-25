package com.ziggfreed.common.track.boost;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.util.MessageUtil;
import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.util.SafeLog;

/**
 * The server owner's command a boost start runs ({@code BoostSettings.OnStart}): every start any mod makes through
 * {@link Boosts} (a player's, a reward's, an admin's, a console's), never a carried-over run or a standing boost. Runs
 * as the console through {@link CommandRunner}, after the start is kept and before it is announced, from any thread
 * (the engine's command manager runs the line on its own pool); a line that fails reports itself and the start
 * stands. Every value a placeholder takes from outside (a player's name, a boost's words or id) is one line of plain
 * text: whitespace collapsed to one space, control characters dropped, and the substitution one pass, so a value is
 * never read as a placeholder of its own.
 */
final class BoostStartCommand {

    private BoostStartCommand() {
    }

    /**
     * Run the start command for {@code run} of {@code boost}, when it is on for {@code scope}. Never throws.
     *
     * @return true when the command was handed over for a server boost whose command takes the place of the library's
     *         line to everyone ({@code OnStart.ReplacesAnnouncement}); false otherwise, a failed line included, so
     *         the caller then sends that line itself
     */
    static boolean run(@Nonnull BoostAsset boost, @Nonnull BoostRun run, @Nonnull BoostScope scope,
            @Nullable UUID starter) {
        try {
            BoostSettingsAsset settings = BoostSettingsConfig.getInstance().settings();
            if (!settings.startCommandOn()) {
                return false;
            }
            boolean server = scope == BoostScope.SERVER;
            if (server ? !settings.startCommandOnServer() : !settings.startCommandOnPersonal()) {
                return false;
            }
            boolean ran = CommandRunner.run(settings.startCommand(),
                    placeholders(boost, run, server, nameOf(starter, run)),
                    failure -> SafeLog.warn("[boost] the start command did not run: " + failure));
            return ran && server && settings.startCommandReplacesAnnouncement();
        } catch (Throwable t) {
            SafeLog.warn("[boost] the start command did not run: " + t.getMessage());
            return false;
        }
    }

    /** What the command's placeholders read for one start. */
    @Nonnull
    static Map<String, String> placeholders(@Nonnull BoostAsset boost, @Nonnull BoostRun run, boolean server,
            @Nonnull String player) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", oneLine(player));
        values.put("multiplier", multiplier(run.percent()));
        values.put("target", oneLine(plain(boost, run)));
        values.put("duration", Long.toString(Math.max(0L, run.endsAt() - run.startedAt()) / 60_000L));
        values.put("scope", server ? "Global" : "Personal");
        values.put("boost", oneLine(boost.getId()));
        return values;
    }

    /**
     * {@code text} as one line of plain text for a command: every run of whitespace (a newline and a tab among them)
     * one space, every other control or format character dropped, trimmed. Pure.
     */
    @Nonnull
    static String oneLine(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = out.length() > 0;
                continue;
            }
            int type = Character.getType(c);
            if (Character.isISOControl(c) || type == Character.FORMAT || type == Character.PRIVATE_USE
                    || type == Character.UNASSIGNED) {
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * A start's multiplier as a command reads it: {@code 1 + percent / 100}, up to three decimals, trailing zeros
     * dropped but one kept ({@code 1.5}, {@code 2.0}, {@code 1.125}).
     */
    @Nonnull
    static String multiplier(double percent) {
        String s = String.format(Locale.ROOT, "%.3f", 1.0 + percent / 100.0);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s + "0" : s;
    }

    /** What the start lifts, in the server's own language. */
    @Nonnull
    private static String plain(@Nonnull BoostAsset boost, @Nonnull BoostRun run) {
        try {
            String text = MessageUtil.formatMessageToPlainString(
                    BoostText.target(boost, run.track()).getFormattedMessage());
            return text == null ? "" : text;
        } catch (Throwable t) {
            return run.track() != null ? run.track() : boost.getId();
        }
    }

    /** The starter's name while they are online, else what the start says started it. */
    @Nonnull
    private static String nameOf(@Nullable UUID starter, @Nonnull BoostRun run) {
        if (starter != null) {
            try {
                Universe universe = Universe.get();
                PlayerRef player = universe == null ? null : universe.getPlayer(starter);
                if (player != null && player.getUsername() != null) {
                    return player.getUsername();
                }
            } catch (Throwable ignored) {
                // An unreadable roster falls back to what the start says started it.
            }
        }
        return run.by();
    }
}
