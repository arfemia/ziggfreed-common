package com.ziggfreed.common.track.pick;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.asset.EditorPick;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardParam;
import com.ziggfreed.common.loot.reward.RewardParams;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.track.TrackBootstrap;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.command.ZigTrackCommand;

/**
 * The reward kind that teaches a pick: {@code {"Kind": "Pick", "Params": {"Track": "<Id>", "Pick": "<Id>"}}}. UNPREFIXED
 * because the library owns the teach behind it, and registered once into the shared vocabulary beside the Track kind
 * ({@code TrackRewardKind}), so a quest, a board contract, an achievement, a shop entry, a lootable and a track's
 * unlock row all pay it, each parking it as a Claim (M428): the pick is taught when the player collects it, never
 * before. What a key is given by (a slot that opens on owning a pick, a pick written {@code TaughtOnly}).
 *
 * <p>It teaches through {@link PickService#teach}: past the level the pick opens at and wherever it is taught, its
 * price never charged, every other rule asked (its rank, its choice node, its {@code Requires}, its prerequisites, its
 * points). A pick already held as often as it may be is no failure (nothing is receipted), so a Claim collected twice
 * gives nothing twice. With no live player, or a pick this player cannot be taught now, it fails, and
 * {@link #retryCommand} answers the {@code /zigtrack pick --pick} line a Claim keeps beside the reward.
 */
public final class PickRewardKind implements RewardHandler {

    /** The kind id content writes. */
    public static final String KIND = "Pick";

    /** Who this registration is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    static final String PARAM_TRACK = "track";
    static final String PARAM_PICK = "pick";

    private PickRewardKind() {
    }

    /** Register the kind into {@code kinds}, with the parameters the Asset Editor shows for it. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND, OWNER, new PickRewardKind());
        kinds.describe(KIND, RewardParams.of("Teaches the player a pick when they collect it, free and past its "
                        + "level, as an admin's teach does.",
                RewardParam.text("Track", "The track the pick sits on, by id.").asRequired()
                        .withPick(EditorPick.list(TrackBootstrap.TRACKS_DATA_SET)),
                RewardParam.text("Pick", "The pick taught, by id: one bought outright.").asRequired()
                        .withPick(EditorPick.asset(TrackPickAsset.class))));
    }

    /** How a Pick reward reads in a reward list, for {@code RewardChips.contribute}. */
    @Nonnull
    public static RewardChips.Source chips() {
        return PickRewardKind::chipFor;
    }

    /** "Learn <pick>" beside the pick's own {@code Icon}; null for another kind or a pick this server does not have. */
    @Nullable
    public static RewardChip chipFor(@Nonnull RewardSpec spec) {
        if (!KIND.equalsIgnoreCase(spec.kind())) {
            return null;
        }
        TrackPicks.PickDef pick = TrackPicks.pick(pickOf(spec));
        if (pick == null) {
            return null;
        }
        Message name = TrackText.pickName(pick.id());
        return RewardChip.picture(pick.asset().icon(), TrackText.line("reward.pick", name), name);
    }

    /**
     * One Pick reward teaching {@code pickId} on {@code trackId}, for a payout written in Java: the one place its
     * params are spelt.
     */
    @Nonnull
    public static RewardSpec spec(@Nonnull String trackId, @Nonnull String pickId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("Track", trackId);
        params.put("Pick", pickId);
        return RewardSpec.of(KIND, params);
    }

    /** Which track {@code spec} names, trimmed; empty when none. */
    @Nonnull
    public static String trackOf(@Nonnull RewardSpec spec) {
        return spec.paramOr(PARAM_TRACK, "").trim();
    }

    /** Which pick {@code spec} teaches, trimmed; empty when none. */
    @Nonnull
    public static String pickOf(@Nonnull RewardSpec spec) {
        return spec.paramOr(PARAM_PICK, "").trim();
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
        String pickId = pickOf(spec);
        if (trackId.isEmpty() || pickId.isEmpty()) {
            throw new IllegalStateException("a reward of kind '" + KIND + "' needs a 'Track' and a 'Pick'");
        }
        PickService.Result result = PickService.teach(subject.id(), trackId, pickId);
        switch (result.status()) {
            case DONE -> receipt.accept(spec);
            case MAXED -> {
                // Held as often as it may be already: paid as authored, nothing taught twice.
            }
            case NOT_LIVE -> throw new IllegalStateException("no live player to teach the pick '" + pickId
                    + "' on '" + trackId + "' to");
            case UNKNOWN -> throw new IllegalStateException("'" + pickId + "' is no pick bought outright on the "
                    + "switched-on track '" + trackId + "'");
            default -> throw new IllegalStateException("the pick '" + pickId + "' on '" + trackId + "' cannot be "
                    + "taught now (" + result.status().name().toLowerCase(Locale.ROOT) + ")");
        }
    }

    @Override
    @Nullable
    public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId) {
        TrackDef def = TrackService.def(trackOf(spec));
        String pickId = pickOf(spec);
        return def == null || pickId.isEmpty() ? null : line(def.id(), pickId, subject.name());
    }

    /** The one spelling of the console line: an admin's teach of {@code pickId} on {@code trackId}. */
    @Nonnull
    static String line(@Nonnull String trackId, @Nonnull String pickId, @Nonnull String player) {
        return ZigTrackCommand.FAMILY + " " + ZigTrackCommand.PICK + " --player=" + player + " --track=" + trackId
                + " --pick=" + pickId;
    }
}
