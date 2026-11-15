package com.ziggfreed.common.objectives.trainer;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.commerce.page.CommerceChips;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.pick.PickService;
import com.ziggfreed.common.track.pick.PickService.Status;
import com.ziggfreed.common.track.pick.PickService.TrainerPick;
import com.ziggfreed.common.track.pick.TrackPickAsset;
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
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.Tick;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.ui.page.PageSection;
import com.ziggfreed.common.ui.toast.ToastKind;

/**
 * The Learn part: every pick bought outright whose {@code Where} names this trainer ({@code PickService.trainerPicks}),
 * each with the level it opens at, what it costs (its points, and its price in wallets or items,
 * {@code PickService.price}), and Learn, the level still needed, or Learned. Learn takes it through
 * {@code PickService.take} at this placement, which asks every rule again and charges the price; a Learn with a price
 * pays only on its second press ({@link TrainerEntry#confirmed}).
 */
final class LearnOffers {

    private LearnOffers() {
    }

    /** What this trainer teaches the player at {@code at}; null when it names no pick. */
    @Nullable
    static TrainerOffers offers(@Nonnull TrainerAt at, @Nullable PageSection entry) {
        List<TrainerPick> picks = PickService.trainerPicks(at.playerId(), at.placementId());
        if (picks.isEmpty()) {
            return null;
        }
        List<TrainerEntry> entries = new ArrayList<>(picks.size());
        for (TrainerPick pick : picks) {
            entries.add(entry(pick));
        }
        return new TrainerOffers(TrainerText.line("part.learn"), entries);
    }

    /** One pick's row and page. */
    @Nonnull
    private static TrainerEntry entry(@Nonnull TrainerPick pick) {
        TrackPicks.PickDef def = TrackPicks.pick(pick.pickId());
        TrackPickAsset asset = def == null ? null : def.asset();
        Message name = TrackText.pickName(pick.pickId());
        Message track = trackName(pick.trackId());
        Picture picture = asset == null ? Picture.NONE : Picture.item(asset.icon());
        Tone tone = tone(pick);
        Message word = word(pick);
        List<DetailLine> price = CommerceChips.priceLines(PickService.price(pick.pickId()));
        Message cost = pick.costPoints() > 0 ? TrainerText.line("learn.cost", pick.costPoints())
                : price.size() == 1 ? price.get(0).text() : null;
        LedgerRow row = new LedgerRow(pick.pickId(), name, TrainerText.line("learn.meta", track, pick.opensAt()),
                picture, tone, word, cost, null, Mark.NONE, pick.learned());

        List<DetailLine> needs = new ArrayList<>();
        needs.add(new DetailLine(Picture.NONE, TrainerText.line("learn.need.level", pick.opensAt(), track), null,
                null, pick.level() >= pick.opensAt() ? Tick.DONE : Tick.LOCKED, null, false));
        if (pick.costPoints() > 0) {
            needs.add(DetailLine.of(Picture.NONE,
                    TrainerText.line("learn.need.points", pick.costPoints(), costName(pick))));
        } else if (price.isEmpty()) {
            needs.add(DetailLine.of(Picture.NONE, TrainerText.line("learn.need.free")));
        }
        needs.addAll(price);
        if (pick.max() > 1 && pick.max() != Integer.MAX_VALUE) {
            needs.add(DetailLine.of(Picture.NONE, TrainerText.line("learn.taken", pick.taken(), pick.max())));
        }
        boolean learnable = pick.status() == Status.DONE;
        DetailAction learn = new DetailAction(ActionSlot.PRIMARY, TrainerText.line("learn.action"),
                ActionLook.NORMAL, "learn", null, learnable, null);
        DetailView page = new DetailView(picture, name, track, null, List.of(Pill.of(word, tone)), null, null, null,
                flavor(asset), List.of(new DetailBlock("needs", TrainerText.line("learn.block"), null, needs)),
                List.of(learn), learnable ? null : TrackText.refusal(pick.status(), pick.opensAt(), track));
        String trackId = pick.trackId();
        String pickId = pick.pickId();
        // A Learn that charges a price arms on its first press and pays on the second: a respec keeps what a trainer
        // taught, so only the undo window would give back a misclick. Decided from the price itself, never its
        // rendered lines, so a line that fails to render never lets a charge through on one press.
        return PickService.price(pickId).isFree() ? TrainerEntry.of(row, page, at -> learn(at, trackId, pickId))
                : TrainerEntry.confirmed(row, page, at -> learn(at, trackId, pickId),
                        TrainerText.line("learn.confirm"));
    }

    /** Take the pick at this trainer, and say what happened. */
    @Nonnull
    private static TrainerResult learn(@Nonnull TrainerAt at, @Nonnull String trackId, @Nonnull String pickId) {
        PickService.Result result = PickService.take(at.playerId(), trackId, pickId, at.placementId());
        if (result.done()) {
            return TrainerResult.toast(ToastKind.SUCCESS, TrainerText.line("learn.done", TrackText.pickName(pickId)));
        }
        TrackPicks.PickDef def = TrackPicks.pick(pickId);
        int opensAt = def == null ? 1 : def.asset().opensAt();
        return TrainerResult.toast(ToastKind.WARNING,
                TrackText.refusal(result.status(), opensAt, trackName(trackId)));
    }

    /** The row's state: learned, ready to learn, a level away, or held back by something else. */
    @Nonnull
    private static Tone tone(@Nonnull TrainerPick pick) {
        return switch (pick.status()) {
            case MAXED -> Tone.DONE;
            case DONE -> Tone.AVAILABLE;
            default -> Tone.BLOCKED;
        };
    }

    @Nonnull
    private static Message word(@Nonnull TrainerPick pick) {
        return switch (pick.status()) {
            case MAXED -> TrainerText.line("learn.state.learned");
            case DONE -> TrainerText.line("learn.state.learn");
            case NOT_OPEN -> TrainerText.line("learn.state.level", pick.opensAt());
            default -> TrainerText.line("learn.state.locked");
        };
    }

    /** What the track {@code trackId} is called, else its id. */
    @Nonnull
    static Message trackName(@Nonnull String trackId) {
        Message name = TrackText.nameOf(trackId);
        return name != null ? name : Msg.raw(trackId);
    }

    /** The track whose points the pick spends: its Cost's own, else its own track. */
    @Nonnull
    private static Message costName(@Nonnull TrainerPick pick) {
        return trackName(pick.costTrack() != null ? pick.costTrack() : pick.trackId());
    }

    /** The pick's description, when its file names one. */
    @Nullable
    private static Message flavor(@Nullable TrackPickAsset asset) {
        String key = asset == null || asset.getText() == null ? null : asset.getText().getFlavorKey();
        return key == null || key.isBlank() ? null : ContentKeys.tr(key.trim());
    }
}
