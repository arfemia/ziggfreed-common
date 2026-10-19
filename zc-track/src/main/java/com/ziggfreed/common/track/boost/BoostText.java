package com.ziggfreed.common.track.boost;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.asset.TrackCategoryAsset;
import com.ziggfreed.common.track.asset.TrackCategoryConfig;

/**
 * Every word a boost says: its lines are keys in {@code ziggfreedcommon.boost.lang}, a boost is called by its own
 * authored {@code Text.TitleKey} (else the plain word for its scope), and what it lifts by the tracks and
 * categories it names. A number binds typed, so the reader's client writes its digits.
 */
public final class BoostText {

    /** The key family every line here resolves under. */
    public static final String PREFIX = "ziggfreedcommon.boost.";

    private BoostText() {
    }

    /** One of this module's boost lines, its arguments bound as given. */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }

    /** What {@code boost} is called: its {@code Text.TitleKey}, else "Boost" or "Server boost". */
    @Nonnull
    public static Message name(@Nonnull BoostAsset boost) {
        String key = boost.titleKey();
        if (key != null) {
            return ContentKeys.tr(key);
        }
        return line(scopeNameKey(boost));
    }

    /** The key, under {@link #PREFIX}, of the plain word for {@code boost}'s scope: "Boost" or "Server boost". */
    @Nonnull
    private static String scopeNameKey(@Nonnull BoostAsset boost) {
        return boost.scope() == BoostScope.SERVER ? "name.server" : "name.personal";
    }

    /**
     * What {@code boost} lifts: every track, or the first track or category it names (by its own name), with
     * "and more" when it names several.
     */
    @Nonnull
    public static Message target(@Nonnull BoostAsset boost) {
        if (boost.liftsAll()) {
            return line("target.all");
        }
        List<String> tracks = boost.trackIds();
        List<String> categories = boost.categoryIds();
        Message first;
        if (!tracks.isEmpty()) {
            Message named = TrackText.nameOf(tracks.get(0));
            first = named == null ? Msg.raw(tracks.get(0)) : named;
        } else if (!categories.isEmpty()) {
            first = categoryName(categories.get(0));
        } else {
            return line("target.none");
        }
        return tracks.size() + categories.size() > 1 ? line("target.more", first) : first;
    }

    /** A category's own name, else its id. */
    @Nonnull
    private static Message categoryName(@Nonnull String categoryId) {
        TrackCategoryAsset category = TrackCategoryConfig.getInstance().category(categoryId);
        String key = category == null ? null : category.getTitleKey();
        return key == null || key.isBlank() ? Msg.raw(categoryId) : ContentKeys.tr(key);
    }

    /**
     * A length of time, the way a player reads one: hours and minutes, minutes and seconds, or seconds, a part
     * that is zero left off (30 minutes reads as 30m, never 30m 0s).
     */
    @Nonnull
    public static Message duration(long ms) {
        long seconds = Math.max(0L, (ms + 999L) / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long rest = seconds % 60L;
        if (hours > 0L) {
            return minutes == 0L ? line("time.hours_only", hours) : line("time.hours", hours, minutes);
        }
        if (minutes > 0L) {
            return rest == 0L ? line("time.minutes_only", minutes) : line("time.minutes", minutes, rest);
        }
        return line("time.seconds", seconds);
    }

    /** A percent as a typed number: whole when it is whole, else as written. */
    @Nonnull
    public static Number percentArg(double percent) {
        return percent == Math.rint(percent) && Math.abs(percent) < 1e15 ? (Number) (long) percent : (Number) percent;
    }

    /** The line a player reads when {@code run} of {@code boost} starts: theirs, or every player's for a server one. */
    @Nonnull
    public static Message started(@Nonnull BoostAsset boost, @Nonnull BoostRun run) {
        String key = boost.scope() == BoostScope.SERVER ? "started.server" : "started.personal";
        return line(key, name(boost), percentArg(run.percent()), target(boost),
                duration(run.endsAt() - run.startedAt()));
    }

    /** How a {@code Boost} reward reads: the percent, what it lifts and for how long. */
    @Nonnull
    public static Message rewardLine(@Nonnull BoostAsset boost, double percent, long seconds) {
        return line("reward.line", percentArg(percent), target(boost), duration(seconds * 1000L));
    }

    /** Why {@code start} did not start, as the player who asked reads it; null for one that started. */
    @Nullable
    public static Message refusal(@Nonnull Boosts.Start start) {
        BoostAsset boost = start.boost();
        Message name = boost == null ? line("name.personal") : name(boost);
        return switch (start.outcome()) {
            case STARTED -> null;
            case UNKNOWN -> line("refused.unknown");
            case STANDING -> line("refused.standing", name);
            case UNTIMED -> line("refused.untimed", name);
            case OUT_OF_SEASON -> line("refused.season", name);
            case LOCKED -> line("refused.locked", name);
            case NOT_LIVE -> line("refused.not_live");
            case COOLDOWN -> line("refused.cooldown", name, duration(start.waitMs()));
            case STACK_FULL -> line("refused.stack", name);
            case PERSONAL_FULL -> line("refused.personal_full", start.limit());
            case SERVER_FULL -> line("refused.server_full", start.limit());
        };
    }

    /** The line a player reads when a boost reward could not start yet and waits as a Claim. */
    @Nonnull
    public static Message waits(@Nonnull BoostAsset boost) {
        return line("waits", name(boost));
    }

    /**
     * A Claim title argument naming {@code boost} as {@link #name} does: its title key, else the plain word for its
     * scope, either one a key resolved on the reader's own client ({@link Claim#keyArg}), never its file id.
     */
    @Nonnull
    public static String claimNameArg(@Nonnull BoostAsset boost) {
        String key = boost.titleKey();
        return Claim.keyArg(key != null ? key : PREFIX + scopeNameKey(boost));
    }
}
