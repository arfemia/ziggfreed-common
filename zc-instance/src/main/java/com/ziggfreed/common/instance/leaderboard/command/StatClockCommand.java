package com.ziggfreed.common.instance.leaderboard.command;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.ziggfreed.common.instance.leaderboard.statboard.StatClock;
import com.ziggfreed.common.instance.leaderboard.statboard.StatPeriod;
import com.ziggfreed.common.instance.leaderboard.statboard.StatPeriodConfig;

/**
 * Move the stat board's clock ({@link StatClock}) by an offset, to see a week or a month turn over
 * without waiting: {@code 7d}, {@code 12h}, {@code 30m}, a minus sign to go back, {@code 0} to put it
 * back. It answers with the bucket each period's tab now reads. Held in memory only, so a restart is
 * back on the server's time. Usable from the console.
 */
final class StatClockCommand extends AbstractAsyncCommand {

    /** One whole number and one unit: days, hours or minutes. */
    private static final Pattern OFFSET = Pattern.compile("([+-]?)(\\d{1,6})([dhm])");

    private final RequiredArg<String> offsetArg;

    StatClockCommand() {
        super(LeaderboardCommandLine.CLOCK, LeaderboardCommandMessages.desc(LeaderboardCommandLine.CLOCK));
        // An owner verb, not a player's: the family's adventurer group does not reach it.
        setPermissionGroups(HytalePermissionsProvider.GROUP_WORLD_EDITOR);
        this.offsetArg = withRequiredArg(LeaderboardCommandLine.ARG_OFFSET,
                LeaderboardCommandMessages.desc("arg.offset"), ArgTypes.STRING);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        String authored = offsetArg.get(ctx);
        Duration by = parse(authored);
        if (by == null) {
            LeaderboardCommandMessages.refused(ctx, "clock.bad_offset", authored == null ? "" : authored.trim());
            return CompletableFuture.completedFuture(null);
        }
        StatClock.setOffset(by);
        if (by.isZero()) {
            LeaderboardCommandMessages.heading(ctx, "clock.reset");
        } else {
            LeaderboardCommandMessages.heading(ctx, "clock.set", authored.trim());
        }
        Instant now = StatClock.now();
        for (StatPeriod period : StatPeriodConfig.getInstance().periods()) {
            String bucket = period.readKey(now);
            if (bucket == null) {
                LeaderboardCommandMessages.detail(ctx, "clock.window.none", period.id());
            } else {
                LeaderboardCommandMessages.detail(ctx, "clock.window", period.id(), bucket);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    /** The offset {@code authored} names, or null when it names none. */
    @Nullable
    static Duration parse(@Nullable String authored) {
        if (authored == null) {
            return null;
        }
        String trimmed = authored.trim().toLowerCase(Locale.ROOT);
        if (trimmed.equals("0")) {
            return Duration.ZERO;
        }
        Matcher m = OFFSET.matcher(trimmed);
        if (!m.matches()) {
            return null;
        }
        long amount = Long.parseLong(m.group(2));
        Duration unit = switch (m.group(3)) {
            case "d" -> Duration.ofDays(amount);
            case "h" -> Duration.ofHours(amount);
            default -> Duration.ofMinutes(amount);
        };
        return "-".equals(m.group(1)) ? unit.negated() : unit;
    }
}
