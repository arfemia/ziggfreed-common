package com.ziggfreed.common.track.lamp;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.command.ZigTrackCommand;
import com.ziggfreed.common.track.price.TrackPriceAsset;
import com.ziggfreed.common.track.price.TrackPricing;

/**
 * The reward kind that hands a lamp (R618's lamp: one-off content's XP the player points at a track of their choice):
 * {@code {"Kind": "Track_Lamp", "Params": {"Price": "Quest", "Level": 30, "Picks": 2, "Category": "Gathering"}}}.
 * {@code Price}, {@code Minutes}, {@code Level} and {@code MaxLevel} price each pick as a Track reward does, but when
 * the lamp is used, at the level of the track it goes to; {@code Picks} different tracks each paid in full;
 * {@code Tracks} (ids split by commas) or {@code Category} the tracks it may go to (every track without either);
 * {@code MinLevel} the level a track must stand at to be picked; {@code Count} how many lamps. UNPREFIXED because the
 * library owns the lamp behind it, and registered once into the shared vocabulary, so every site that pays a reward
 * pays it, each a Claim (M428): the lamp is handed over when the player collects it.
 *
 * <p>It hands the lamp the way any pickup lands ({@link TrackLamps#give}). With no live player it fails, and
 * {@link #retryCommand} answers the {@code /zigtrack lamp} line a Claim keeps beside the reward.
 */
public final class TrackLampRewardKind implements RewardHandler {

    /** The kind id content writes. */
    public static final String KIND = "Track_Lamp";

    /** Who this registration is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** What a hand-over's overflow says it came from. */
    static final String SOURCE = "track-lamp";

    static final String PARAM_PICKS = "picks";
    static final String PARAM_TRACKS = "tracks";
    static final String PARAM_CATEGORY = "category";
    static final String PARAM_MIN_LEVEL = "minlevel";
    static final String PARAM_COUNT = "count";

    private TrackLampRewardKind() {
    }

    /** Register the kind into {@code kinds}, with the parameters the Asset Editor shows for it. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND, OWNER, new TrackLampRewardKind());
        kinds.describe(KIND, RewardParams.of("Hands the player a lamp: minutes of play they put into tracks of "
                        + "their choice when they use it, each priced at their level on the track they pick.",
                RewardParam.text("Price", "A row of the XP price table, by id: how many minutes each pick pays.")
                        .withPick(EditorPick.asset(TrackPriceAsset.class)),
                RewardParam.number("Minutes", "Minutes of play each pick pays, over the price row's."),
                RewardParam.integer("Level", "Price each pick at this level whatever the track's, over the row's."),
                RewardParam.integer("MaxLevel", "Price each pick at the player's level, never above this one."),
                RewardParam.integer("Picks", "How many different tracks the player picks, each paid in full. 1 "
                        + "when left out."),
                RewardParam.text("Tracks", "The tracks it may go to, by id, split by commas. Leave it out for "
                        + "every track of the Category.").withPick(EditorPick.list(TrackBootstrap.TRACKS_DATA_SET)),
                RewardParam.text("Category", "With no Tracks, every track of this category. Leave it out for "
                        + "every track."),
                RewardParam.integer("MinLevel", "The level a track must stand at to be picked."),
                RewardParam.integer("Count", "How many lamps. 1 when left out.")));
    }

    /** How a lamp reads in a reward list, for {@code RewardChips.contribute}. */
    @Nonnull
    public static RewardChips.Source chips() {
        return TrackLampRewardKind::chipFor;
    }

    /**
     * "A lamp: 15 minutes of play", beside the lamp item's own icon, the minutes a typed number; null for another kind
     * or a lamp paying nothing.
     */
    @Nullable
    public static RewardChip chipFor(@Nonnull RewardSpec spec) {
        if (!KIND.equalsIgnoreCase(spec.kind())) {
            return null;
        }
        TrackLamp lamp = lampOf(spec);
        double minutes = lamp.priced().minutes();
        return lamp.pays() && minutes > 0.0
                ? RewardChip.of(TrackLamp.ITEM_ID, TrackText.line("lamp.reward", minutes, lamp.picks())) : null;
    }

    /** The lamp {@code spec} hands. */
    @Nonnull
    public static TrackLamp lampOf(@Nonnull RewardSpec spec) {
        List<String> tracks = new ArrayList<>();
        String written = spec.param(PARAM_TRACKS);
        if (written != null) {
            for (String id : written.split(",")) {
                tracks.add(id);
            }
        }
        TrackPricing.Priced own = TrackPricing.of(spec);
        Double minutes = written(spec, TrackPricing.PARAM_MINUTES) ? spec.doubleParam(TrackPricing.PARAM_MINUTES, 0.0)
                : null;
        Integer level = written(spec, TrackPricing.PARAM_LEVEL) && own != null ? own.level() : null;
        Integer maxLevel = written(spec, TrackPricing.PARAM_MAX_LEVEL) && own != null ? own.maxLevel() : null;
        return new TrackLamp(spec.param(TrackPricing.PARAM_PRICE), minutes, level, maxLevel,
                (int) spec.longParam(PARAM_PICKS, 1L), tracks, spec.param(PARAM_CATEGORY),
                (int) spec.longParam(PARAM_MIN_LEVEL, 1L));
    }

    private static boolean written(@Nonnull RewardSpec spec, @Nonnull String param) {
        String value = spec.param(param);
        return value != null && !value.isBlank();
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
        TrackLamp lamp = lampOf(spec);
        if (!lamp.pays()) {
            throw new IllegalStateException("a reward of kind '" + KIND + "' needs a 'Price' or 'Minutes'");
        }
        int count = (int) Math.max(1L, spec.longParam(PARAM_COUNT, 1L));
        if (TrackLamps.give(subject, lamp, count, SOURCE) <= 0) {
            throw new IllegalStateException("no lamp could be handed to " + subject.name());
        }
        receipt.accept(spec);
    }

    @Override
    @Nullable
    public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId) {
        TrackLamp lamp = lampOf(spec);
        if (!lamp.pays()) {
            return null;
        }
        StringBuilder line = new StringBuilder(ZigTrackCommand.FAMILY + " " + ZigTrackCommand.LAMP + " --player="
                + subject.name());
        append(line, "price", lamp.price());
        append(line, "minutes", lamp.minutes() == null ? null : Double.toString(lamp.minutes()));
        append(line, "level", lamp.level() == null ? null : Integer.toString(lamp.level()));
        append(line, "maxlevel", lamp.maxLevel() == null ? null : Integer.toString(lamp.maxLevel()));
        append(line, "picks", lamp.picks() > 1 ? Integer.toString(lamp.picks()) : null);
        append(line, "tracks", lamp.tracks().isEmpty() ? null : String.join(",", lamp.tracks()));
        append(line, "category", lamp.category());
        append(line, "minlevel", lamp.minLevel() > 1 ? Integer.toString(lamp.minLevel()) : null);
        long count = spec.longParam(PARAM_COUNT, 1L);
        append(line, "quantity", count > 1 ? Long.toString(count) : null);
        return line.toString();
    }

    private static void append(@Nonnull StringBuilder line, @Nonnull String arg, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            line.append(" --").append(arg).append('=').append(value.trim());
        }
    }
}
