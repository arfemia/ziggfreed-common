package com.ziggfreed.common.track;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.asset.EditorPick;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardParam;
import com.ziggfreed.common.loot.reward.RewardParams;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.track.command.ZigTrackCommand;
import com.ziggfreed.common.track.price.TrackPriceAsset;
import com.ziggfreed.common.track.price.TrackPricing;

/**
 * The reward kind that moves a player's climb: {@code {"Kind": "Track", "Params": {"Track": "<Id>", "Amount": 500}}}
 * (a negative Amount costs value), or priced in minutes of play (R618): {@code {"Track": "<Id>", "Price": "Quest"}},
 * a row of the XP price table, or {@code "Minutes": 15} of its own, with an optional {@code Level} (one-off content,
 * priced at that level for everyone) or {@code MaxLevel} (repeatable content, priced at the player's level up to it)
 * over the row's. UNPREFIXED because the library owns the writer behind it, and registered once into the shared
 * vocabulary, so a quest, a board contract, an achievement, a lootable, an unlock row and a Claim all pay it. The
 * engine's own reward files pay a climb through the native {@code ZigTrack} reward Type ({@link ZigTrackReward}).
 *
 * <p>A priced reward is priced when it is earned ({@link #stamp}): the Claim keeps the amount the minutes came to at
 * the player's level then, as {@code Amount} with {@code Exact}, and pays it past every boost and bonus
 * ({@link TrackService#addExact}: the world's rules, a factor's level cap and the track's Cap still apply). Granted
 * outright, it is priced at the grant; one whose earner's climb could not be read when earned (not live) is kept
 * priced and priced when collected. A plain {@code Amount} writes through {@link TrackService#add}, so the gain seam, the cap, the
 * unlocks, the mirror and the event all apply. A gain the cap cuts to nothing, or one the player's track
 * {@code Requires} refuses, is no failure (nothing is receipted). With no live player it fails, and
 * {@link #retryCommand} answers the {@code /zigtrack add} line a Claim keeps beside the reward, which pays only where
 * this kind could not.
 */
public final class TrackRewardKind implements RewardHandler {

    /** The kind id content writes. */
    public static final String KIND = "Track";

    /** Who this registration is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    static final String PARAM_TRACK = "track";
    static final String PARAM_AMOUNT = "amount";
    /** Written by {@link #stamp}: the amount was priced and pays past every boost. */
    static final String PARAM_EXACT = "exact";

    private TrackRewardKind() {
    }

    /** Register the kind into {@code kinds}, with the parameters the Asset Editor shows for it. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND, OWNER, new TrackRewardKind());
        kinds.describe(KIND, RewardParams.of("Moves the player along a track, such as a skill's experience: a "
                        + "fixed Amount, or minutes of play priced at the player's level when earned.",
                RewardParam.text("Track", "The track, by id.").asRequired()
                        .withPick(EditorPick.list(TrackBootstrap.TRACKS_DATA_SET)),
                RewardParam.integer("Amount", "How much. A negative amount takes it away. Leave it out for a "
                        + "reward priced in minutes."),
                RewardParam.text("Price", "A row of the XP price table, by id (Quest, Board, Dungeon_Clear, ...): "
                        + "how many minutes of play it pays.").withPick(EditorPick.asset(TrackPriceAsset.class)),
                RewardParam.number("Minutes", "Minutes of play at the track's pace, over the price row's."),
                RewardParam.integer("Level", "Price the minutes at this level for everyone (one-off content), over "
                        + "the price row's."),
                RewardParam.integer("MaxLevel", "Price the minutes at the player's level, never above this one "
                        + "(repeatable content), over the price row's.")));
    }

    /** How a Track reward reads in a reward list, for {@code RewardChips.contribute}. */
    @Nonnull
    public static RewardChips.Source chips() {
        return TrackRewardKind::chipFor;
    }

    /**
     * "+500 Mining", the amount a typed number and the name the track's own, beside the track's own
     * {@code Icon} (a picture standing for the track, never an item handed over); "15 minutes of Mining" for a
     * reward priced in minutes and not yet earned; null for another kind or one nothing names.
     */
    @Nullable
    public static RewardChip chipFor(@Nonnull RewardSpec spec) {
        if (!KIND.equalsIgnoreCase(spec.kind())) {
            return null;
        }
        TrackDef def = TrackService.def(trackOf(spec));
        if (def == null) {
            return null;
        }
        Message name = TrackText.name(def.asset());
        TrackPricing.Priced priced = TrackPricing.of(spec);
        if (priced != null) {
            return priced.pays() ? RewardChip.picture(def.asset().icon(),
                    TrackText.line("reward.minutes", priced.minutes(), name), name) : null;
        }
        long amount = amountOf(spec);
        if (amount == 0) {
            return null;
        }
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

    /**
     * One Track reward priced in minutes on {@code trackId}: the price row {@code priceId} names, with whichever of
     * {@code minutes}, {@code level} and {@code maxLevel} is given over it (each null for the row's own).
     */
    @Nonnull
    public static RewardSpec priced(@Nonnull String trackId, @Nullable String priceId, @Nullable Double minutes,
            @Nullable Integer level, @Nullable Integer maxLevel) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Track", trackId);
        if (priceId != null && !priceId.isBlank()) {
            params.put("Price", priceId.trim());
        }
        if (minutes != null) {
            params.put("Minutes", Double.toString(minutes));
        }
        if (level != null) {
            params.put("Level", Integer.toString(level));
        }
        if (maxLevel != null) {
            params.put("MaxLevel", Integer.toString(maxLevel));
        }
        return RewardSpec.of(KIND, params);
    }

    /**
     * The one Track reward a native file's leaves make (the {@code ZigTrack} reward file, an objective's
     * {@code ZigTrack} completion): priced in minutes when it names a {@code price} or {@code minutes}
     * ({@link #priced}), else its fixed {@code amount} ({@link #spec}); null when it pays nothing (no price and an
     * amount of 0).
     */
    @Nullable
    public static RewardSpec of(@Nonnull String trackId, long amount, @Nullable String price, @Nullable Double minutes,
            @Nullable Integer level, @Nullable Integer maxLevel) {
        if ((price != null && !price.isBlank()) || minutes != null) {
            return priced(trackId, price, minutes, level, maxLevel);
        }
        return amount == 0L ? null : spec(trackId, amount);
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

    /** Does {@code spec} pay past every boost (a priced reward, stamped)? */
    public static boolean isExact(@Nonnull RewardSpec spec) {
        return spec.flagParam(PARAM_EXACT, false);
    }

    /**
     * A reward priced in minutes, priced for the earner now: the amount it came to as {@code Amount}, with
     * {@code Exact}, the price leaves gone. The reward as it was when it is no priced one, or its earner's climb
     * cannot be read here (priced again at the grant).
     */
    @Override
    @Nonnull
    public RewardSpec stamp(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
        TrackPricing.Priced priced = TrackPricing.of(spec);
        String trackId = trackOf(spec);
        if (priced == null || trackId.isEmpty()) {
            return spec;
        }
        long amount = TrackPricing.priceFor(subject.id(), trackId, priced);
        return amount == TrackPricing.UNPRICED ? spec : stamped(spec, amount);
    }

    /** {@code spec} carrying {@code amount} as a priced, exact amount, its price leaves dropped. */
    @Nonnull
    static RewardSpec stamped(@Nonnull RewardSpec spec, long amount) {
        Map<String, String> params = new LinkedHashMap<>(spec.params());
        params.remove(TrackPricing.PARAM_PRICE);
        params.remove(TrackPricing.PARAM_MINUTES);
        params.remove(TrackPricing.PARAM_LEVEL);
        params.remove(TrackPricing.PARAM_MAX_LEVEL);
        params.put(PARAM_AMOUNT, Long.toString(Math.max(0L, amount)));
        params.put(PARAM_EXACT, "true");
        return RewardSpec.of(spec.kind(), params).withRequires(spec.requires()).withOrigin(spec.origin());
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
        RewardSpec paying = TrackPricing.isPriced(spec) ? stamp(spec, subject) : spec;
        if (TrackPricing.isPriced(paying)) {
            throw new IllegalStateException("no live player to price the track '" + trackId + "' for");
        }
        long amount = amountOf(paying);
        if (trackId.isEmpty() || amount == 0) {
            throw new IllegalStateException("a reward of kind '" + KIND + "' needs a 'Track' and a whole, non-zero "
                    + "'Amount' or a price that comes to one");
        }
        TrackKey key = TrackKey.player(subject.id(), trackId);
        TrackService.Result result = isExact(paying) ? TrackService.addExact(key, amount, sourceId)
                : TrackService.add(key, amount, sourceId);
        switch (result.status()) {
            case CHANGED -> receipt.accept(paying);
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

    /**
     * The {@code /zigtrack add} line, with {@code --exact} for a priced amount; for a reward priced in minutes its
     * earner's climb could not price when earned (a player not live then), the line carrying the price itself, which
     * {@code /zigtrack add} prices when it runs, at the player's level then. Null for a track not on this server or an
     * amount of 0.
     */
    @Override
    @Nullable
    public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId) {
        TrackDef def = TrackService.def(trackOf(spec));
        if (def == null) {
            return null;
        }
        if (TrackPricing.isPriced(spec)) {
            return pricedLine(def.id(), subject.name(), spec);
        }
        long amount = amountOf(spec);
        return amount == 0 ? null : line(def.id(), Long.toString(amount), subject.name(), isExact(spec));
    }

    /** The {@code /zigtrack add} line pricing {@code spec}'s minutes when it runs: its price leaves as written. */
    @Nonnull
    static String pricedLine(@Nonnull String trackId, @Nonnull String player, @Nonnull RewardSpec spec) {
        StringBuilder line = new StringBuilder(ZigTrackCommand.FAMILY + " " + ZigTrackCommand.ADD + " --player="
                + player + " --track=" + trackId);
        for (String param : List.of(TrackPricing.PARAM_PRICE, TrackPricing.PARAM_MINUTES, TrackPricing.PARAM_LEVEL,
                TrackPricing.PARAM_MAX_LEVEL)) {
            String value = spec.param(param);
            if (value != null && !value.isBlank()) {
                line.append(" --").append(param).append('=').append(value.trim());
            }
        }
        return line.toString();
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
        return def == null || amountOf(spec) <= 0 || TrackPricing.isPriced(spec) ? null
                : line(def.id(), InstanceReward.AMOUNT_PLACEHOLDER, subject.name(), isExact(spec));
    }

    /** The one spelling of the console line, {@code amount} as given (a number, or a placeholder filled later). */
    @Nonnull
    static String line(@Nonnull String trackId, @Nonnull String amount, @Nonnull String player, boolean exact) {
        return ZigTrackCommand.FAMILY + " " + ZigTrackCommand.ADD + " --player=" + player + " --track=" + trackId
                + " --amount=" + amount + (exact ? " --exact" : "");
    }
}
