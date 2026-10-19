package com.ziggfreed.common.track;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.track.command.ZigTrackCommand;

/**
 * The reward kind that moves a player's climb: {@code {"Kind": "Track", "Params": {"Track": "<Id>", "Amount": 500}}}
 * (a negative Amount costs value). UNPREFIXED because the library owns the writer behind it, and registered once
 * into the shared vocabulary, so a quest, a board contract, an achievement, a lootable, an unlock row and a
 * Claim all pay it. The engine's own reward files pay a climb through the native {@code ZigTrack} reward Type
 * ({@link ZigTrackReward}).
 *
 * <p>It writes through {@link TrackService#add}, so the gain seam, the cap, the unlocks, the mirror and the
 * event all apply. A gain the cap cuts to nothing, or one the player's track {@code Requires} refuses, is no
 * failure (nothing is receipted). With no live player it fails, and {@link #retryCommand} answers the
 * {@code /zigtrack add} line a Claim keeps beside the reward, which pays only where this kind could not.
 */
public final class TrackRewardKind implements RewardHandler {

    /** The kind id content writes. */
    public static final String KIND = "Track";

    /** Who this registration is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    static final String PARAM_TRACK = "track";
    static final String PARAM_AMOUNT = "amount";

    private TrackRewardKind() {
    }

    /** Register the kind into {@code kinds}. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND, OWNER, new TrackRewardKind());
    }

    /** How a Track reward reads in a reward list, for {@code RewardChips.contribute}. */
    @Nonnull
    public static RewardChips.Source chips() {
        return TrackRewardKind::chipFor;
    }

    /**
     * "+500 Mining", the amount a typed number and the name the track's own, beside the track's own
     * {@code Icon} (a picture standing for the track, never an item handed over); null for another kind or one
     * nothing names.
     */
    @Nullable
    public static RewardChip chipFor(@Nonnull RewardSpec spec) {
        if (!KIND.equalsIgnoreCase(spec.kind())) {
            return null;
        }
        long amount = amountOf(spec);
        TrackDef def = TrackService.def(trackOf(spec));
        if (def == null || amount == 0) {
            return null;
        }
        Message name = TrackText.name(def.asset());
        return RewardChip.picture(def.asset().icon(), TrackText.line(amount > 0 ? "reward.gain" : "reward.loss",
                amount, name), name);
    }

    /**
     * One Track reward moving {@code trackId} by {@code amount}, for a payout written in Java (the native
     * {@code ZigTrack} reward, an objective's {@code ZigTrack} completion): the one place its params are spelt.
     */
    @Nonnull
    public static RewardSpec spec(@Nonnull String trackId, long amount) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Track", trackId);
        params.put("Amount", Long.toString(amount));
        return RewardSpec.of(KIND, params);
    }

    /** Which track {@code spec} moves, trimmed; empty when it names none. */
    @Nonnull
    public static String trackOf(@Nonnull RewardSpec spec) {
        return spec.paramOr(PARAM_TRACK, "").trim();
    }

    /** How much it moves, whole; 0 when unauthored or unreadable. */
    public static long amountOf(@Nonnull RewardSpec spec) {
        return spec.longParam(PARAM_AMOUNT, 0L);
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
        grant(spec, subject, KIND, ignored -> {
        });
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId) throws Exception {
        grant(spec, subject, sourceId, ignored -> {
        });
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId,
            @Nonnull Consumer<RewardSpec> receipt) throws Exception {
        String trackId = trackOf(spec);
        long amount = amountOf(spec);
        if (trackId.isEmpty() || amount == 0) {
            throw new IllegalStateException("a reward of kind '" + KIND + "' needs a 'Track' and a whole, non-zero "
                    + "'Amount'");
        }
        TrackService.Result result = TrackService.add(TrackKey.player(subject.id(), trackId), amount, sourceId);
        switch (result.status()) {
            case CHANGED -> receipt.accept(spec);
            case UNCHANGED, LOCKED -> {
                // At the cap, or a track this player does not meet: paid as authored, nothing moved.
            }
            case UNKNOWN -> throw new IllegalStateException("'" + trackId + "' is no track this server has "
                    + "switched on with a loaded curve");
            case NOT_LIVE -> throw new IllegalStateException("no live player to move the track '" + trackId
                    + "' for");
            default -> throw new IllegalStateException("the track '" + trackId + "' could not be moved ("
                    + result.status().name().toLowerCase(Locale.ROOT) + ")");
        }
    }

    @Override
    @Nullable
    public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId) {
        TrackDef def = TrackService.def(trackOf(spec));
        long amount = amountOf(spec);
        return def == null || amount == 0 ? null : line(def.id(), Long.toString(amount), subject.name());
    }

    /**
     * The retry line with the amount left as {@code {amount}}, so a Claim scaled before it pays moves the climb
     * by the share it shows. A gain only: a loss is null, so it replays whole.
     */
    @Override
    @Nullable
    public String scalableRetryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
            @Nonnull String sourceId) {
        TrackDef def = TrackService.def(trackOf(spec));
        return def == null || amountOf(spec) <= 0 ? null
                : line(def.id(), InstanceReward.AMOUNT_PLACEHOLDER, subject.name());
    }

    /** The one spelling of the console line, {@code amount} as given (a number, or a placeholder filled later). */
    @Nonnull
    static String line(@Nonnull String trackId, @Nonnull String amount, @Nonnull String player) {
        return ZigTrackCommand.FAMILY + " " + ZigTrackCommand.ADD + " --player=" + player + " --track=" + trackId
                + " --amount=" + amount;
    }
}
