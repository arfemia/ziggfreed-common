package com.ziggfreed.common.objectives.trainer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackReading;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.asset.PrestigeBuild;
import com.ziggfreed.common.track.asset.PrestigeReset;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.pick.PickService;
import com.ziggfreed.common.track.pick.TrackPicks;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Tick;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.ui.toast.ToastKind;

/**
 * The library's own trainer services on a track's climb ({@link TrainerServices#registerBuiltIns}), each one entry
 * shown only where it can do something for this player on this track, each pressed twice ({@code ConfirmArm} through
 * {@link TrainerEntry#confirmed}) since none can be taken back from here, and each acting through the service that
 * owns it, which asks every rule again:
 *
 * <ul>
 *   <li>{@link #PRESTIGE}: the track's next prestige, once the player's climb has reached the level it needs
 *       ({@code TrackService.prestige}), refused in combat or mid-encounter as a respec is;</li>
 *   <li>{@link #RESPEND}: the picks a Remember prestige put aside, spent again in the order they were made as far as
 *       the points allow ({@code PickService.respendRemembered}, R598, R641), while any wait;</li>
 *   <li>{@link #STARTER}: what is left of the track's suggested build ({@code Starter}) spent in order
 *       ({@code PickService.spendInOrder}, R727), while the player has points to spend there and the build holds a
 *       pick they have not taken as often as it names it.</li>
 * </ul>
 *
 * <p>A mod replaces one by registering its own under the same id. What a press answers reads as the rest of the
 * page says it: a refusal through {@code TrackText.refusal}, the level a prestige needs through
 * {@code TrackText.levelNeeded}.
 */
final class ClimbServices {

    /** The prestige, as the library offers it. */
    static final TrainerServices.TrainerService PRESTIGE = ClimbServices::prestige;

    /** The re-spend of what a prestige put aside, as the library offers it. */
    static final TrainerServices.TrainerService RESPEND = ClimbServices::respend;

    /** The suggested build, as the library offers it. */
    static final TrainerServices.TrainerService STARTER = ClimbServices::starter;

    /** What a prestige pressed here says it came from, as a command says its own verb. */
    private static final String SOURCE = "trainer";

    private ClimbServices() {
    }

    // ==================== the prestige ====================

    /** The prestige of {@code track}, while the player's climb stands at the level the next one needs. */
    @Nonnull
    private static List<TrainerEntry> prestige(@Nonnull TrainerAt at, @Nonnull TrackDef track) {
        TrackAsset asset = track.asset();
        if (asset.isDerived() || !asset.prestiges()) {
            return List.of();
        }
        TrackReading reading = TrackService.read(TrackKey.player(at.playerId(), asset.getId()));
        if (reading == null) {
            return List.of();
        }
        int done = reading.prestige();
        if (done >= asset.prestigeRanks() || reading.level() < asset.prestigeLevel(done + 1)) {
            return List.of();
        }
        Message name = TrackText.name(asset);
        Picture picture = Picture.item(asset.icon());
        Message title = TrainerText.line("service.prestige.title", name);
        LedgerRow row = new LedgerRow("prestige/" + track.id(), title,
                TrainerText.line("service.prestige.meta", done + 1), picture, Tone.AVAILABLE,
                TrainerText.line("service.state.ready"), null, null, Mark.NONE, false);
        DetailAction action = new DetailAction(ActionSlot.PRIMARY, TrainerText.line("service.prestige.action"),
                ActionLook.DANGER, "prestige", null, true, null);
        DetailView page = new DetailView(picture, title, name, null, List.of(), null, null, null,
                TrainerText.line("service.prestige.lead", name),
                List.of(new DetailBlock("changes", TrainerText.line("service.prestige.block"), null,
                        prestigeLines(track, name))),
                List.of(action), null);
        String trackId = asset.getId();
        return List.of(TrainerEntry.confirmed(row, page, pressed -> startAgain(pressed, trackId),
                TrainerText.line("service.prestige.confirm")));
    }

    /**
     * What a prestige of {@code track} changes: the best level kept; the tree's picks as its {@code Prestige.Build}
     * says when its {@code Prestige.Resets} resets them, else kept, and what a trainer taught kept beside them while
     * the Resets keeps it; the gain a prestiged climb earns on top.
     */
    @Nonnull
    private static List<DetailLine> prestigeLines(@Nonnull TrackDef track, @Nonnull Message name) {
        List<DetailLine> lines = new ArrayList<>();
        lines.add(DetailLine.of(Picture.NONE, TrainerText.line("service.prestige.line.best")));
        PrestigeBuild build = track.asset().prestigeResets(PrestigeReset.PICKS) ? track.prestigeBuild()
                : PrestigeBuild.KEEP;
        switch (build) {
            case REMEMBER -> lines.add(DetailLine.of(Picture.NONE, TrainerText.line("service.prestige.line.remember")));
            case CLEAR -> lines.add(DetailLine.of(Picture.NONE, TrainerText.line("service.prestige.line.clear")));
            default -> lines.add(DetailLine.of(Picture.NONE, TrainerText.line("service.prestige.line.keep")));
        }
        if (build != PrestigeBuild.KEEP && !track.asset().prestigeResets(PrestigeReset.TRAINER_PICKS)) {
            lines.add(DetailLine.of(Picture.NONE, TrainerText.line("service.prestige.line.taught")));
        }
        double percent = track.asset().prestigeGainPercent();
        if (percent > 0.0) {
            lines.add(DetailLine.of(Picture.NONE, TrainerText.line("service.prestige.line.gain", percent, name)));
        }
        return lines;
    }

    /**
     * Start the climb again, and say how it went: refused in combat or mid-encounter, as a player's own respec and a
     * build swap are (R895, {@code PickService.respecRefusal}, the one rule).
     */
    @Nonnull
    private static TrainerResult startAgain(@Nonnull TrainerAt at, @Nonnull String trackId) {
        PickService.Status refused = PickService.respecRefusal(at.playerId());
        if (refused != null) {
            return TrainerResult.toast(ToastKind.WARNING, TrackText.refusal(refused));
        }
        TrackKey key = TrackKey.player(at.playerId(), trackId);
        TrackService.Result result = TrackService.prestige(key, SOURCE);
        Message name = LearnOffers.trackName(trackId);
        return switch (result.status()) {
            case CHANGED -> TrainerResult.toast(ToastKind.SUCCESS, TrainerText.line("service.prestige.done", name,
                    result.change() == null ? 1 : result.change().after().prestige()));
            case NOT_READY -> TrainerResult.toast(ToastKind.WARNING, TrackText.levelNeeded(nextLevel(key), name));
            case NOT_ALLOWED -> TrainerResult.toast(ToastKind.WARNING,
                    TrainerText.line("service.prestige.refused.none", name));
            default -> TrainerResult.toast(ToastKind.WARNING, TrainerText.line("action.failed"));
        };
    }

    /** The level the next prestige of {@code key}'s climb needs, as it reads now. */
    private static int nextLevel(@Nonnull TrackKey key) {
        TrackReading reading = TrackService.read(key);
        return reading == null ? 1 : reading.def().asset().prestigeLevel(reading.prestige() + 1);
    }

    // ==================== what a prestige put aside ====================

    /** The picks a Remember prestige put aside on {@code track}, while any wait. */
    @Nonnull
    private static List<TrainerEntry> respend(@Nonnull TrainerAt at, @Nonnull TrackDef track) {
        List<String> remembered = PickService.rememberedTakes(at.playerId(), track.id());
        if (remembered.isEmpty()) {
            return List.of();
        }
        Message name = TrackText.name(track.asset());
        Picture picture = Picture.item(track.asset().icon());
        Message title = TrainerText.line("service.respend.title", name);
        LedgerRow row = new LedgerRow("respend/" + track.id(), title,
                TrainerText.line("service.respend.meta", remembered.size()), picture, Tone.AVAILABLE,
                TrainerText.line("service.state.ready"), null, null, Mark.NONE, false);
        DetailAction action = new DetailAction(ActionSlot.PRIMARY, TrainerText.line("service.respend.action"),
                ActionLook.NORMAL, "respend", null, true, null);
        DetailView page = new DetailView(picture, title, name, null, List.of(), null, null, null,
                TrainerText.line("service.respend.lead"),
                List.of(new DetailBlock("picks", TrainerText.line("service.respend.block"), null,
                        pickLines(remembered))),
                List.of(action), null);
        String trackId = track.id();
        return List.of(TrainerEntry.confirmed(row, page, pressed -> spendRemembered(pressed, trackId),
                TrainerText.line("service.respend.confirm")));
    }

    /** Spend again what was put aside, and say how it went. */
    @Nonnull
    private static TrainerResult spendRemembered(@Nonnull TrainerAt at, @Nonnull String trackId) {
        PickService.Result result = PickService.respendRemembered(at.playerId(), trackId);
        Message name = LearnOffers.trackName(trackId);
        return switch (result.status()) {
            case DONE -> TrainerResult.toast(ToastKind.SUCCESS,
                    TrainerText.line("service.respend.done", result.count(), name));
            case NOTHING -> TrainerResult.toast(ToastKind.WARNING, TrainerText.line("service.respend.none", name));
            default -> TrainerResult.toast(ToastKind.WARNING, TrackText.refusal(result.status()));
        };
    }

    // ==================== the suggested build ====================

    /** What is left of {@code track}'s suggested build, while the player has points to spend there. */
    @Nonnull
    private static List<TrainerEntry> starter(@Nonnull TrainerAt at, @Nonnull TrackDef track) {
        List<String> left = starterLeft(at.playerId(), track.id());
        if (left.isEmpty() || PickService.waiting(at.playerId(), track.id()).pointsLeft() <= 0) {
            return List.of();
        }
        Message name = TrackText.name(track.asset());
        Picture picture = Picture.item(track.asset().icon());
        Message title = TrainerText.line("service.starter.title", name);
        LedgerRow row = new LedgerRow("starter/" + track.id(), title,
                TrainerText.line("service.starter.meta", left.size()), picture, Tone.AVAILABLE,
                TrainerText.line("service.state.ready"), null, null, Mark.NONE, false);
        DetailAction action = new DetailAction(ActionSlot.PRIMARY, TrainerText.line("service.starter.action"),
                ActionLook.NORMAL, "starter", null, true, null);
        DetailView page = new DetailView(picture, title, name, null, List.of(), null, null, null,
                TrainerText.line("service.starter.lead", name),
                List.of(new DetailBlock("picks", TrainerText.line("service.starter.block"), null, pickLines(left))),
                List.of(action), null);
        String trackId = track.id();
        return List.of(TrainerEntry.confirmed(row, page, pressed -> spendStarter(pressed, trackId),
                TrainerText.line("service.starter.confirm")));
    }

    /** Spend what is left of the suggested build as it reads now, and say how it went. */
    @Nonnull
    private static TrainerResult spendStarter(@Nonnull TrainerAt at, @Nonnull String trackId) {
        List<String> left = starterLeft(at.playerId(), trackId);
        Message name = LearnOffers.trackName(trackId);
        PickService.Result result = left.isEmpty() ? null : PickService.spendInOrder(at.playerId(), trackId, left);
        if (result == null || result.status() == PickService.Status.NOTHING) {
            return TrainerResult.toast(ToastKind.WARNING, TrainerText.line("service.starter.none", name));
        }
        return result.done()
                ? TrainerResult.toast(ToastKind.SUCCESS, TrainerText.line("service.starter.done", result.count(), name))
                : TrainerResult.toast(ToastKind.WARNING, TrackText.refusal(result.status()));
    }

    /**
     * The takes of {@code trackId}'s suggested build ({@code PickService.starter}) the player has not made yet, in
     * its order: each pick's first takes stand for the ones the player already holds, so a build half made is
     * finished from where it stands, never taken again on top. Empty for a track with no suggested build.
     */
    @Nonnull
    private static List<String> starterLeft(@Nonnull UUID player, @Nonnull String trackId) {
        List<String> starter = PickService.starter(trackId);
        if (starter.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> held = new HashMap<>();
        Map<String, Integer> named = new HashMap<>();
        List<String> left = new ArrayList<>();
        for (String pickId : starter) {
            int has = held.computeIfAbsent(pickId, id -> PickService.count(player, trackId, id));
            if (named.merge(pickId, 1, Integer::sum) > has) {
                left.add(pickId);
            }
        }
        return left;
    }

    // ==================== shared ====================

    /** The picks {@code takes} names, once each in their first order, a pick named more than once with its ranks. */
    @Nonnull
    private static List<DetailLine> pickLines(@Nonnull List<String> takes) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String pickId : takes) {
            counts.merge(pickId, 1, Integer::sum);
        }
        List<DetailLine> lines = new ArrayList<>(counts.size());
        for (Map.Entry<String, Integer> take : counts.entrySet()) {
            TrackPicks.PickDef def = TrackPicks.pick(take.getKey());
            Picture picture = Picture.item(def == null ? null : def.asset().icon());
            Message ranks = take.getValue() > 1 ? TrainerText.line("service.picks.ranks", take.getValue()) : null;
            lines.add(new DetailLine(picture, TrackText.pickName(take.getKey()), ranks, null, Tick.NONE, null,
                    false));
        }
        return lines;
    }
}
