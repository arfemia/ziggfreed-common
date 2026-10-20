package com.ziggfreed.common.objectives.trainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.commerce.fold.CommerceEngines;
import com.ziggfreed.common.commerce.page.CommerceChips;
import com.ziggfreed.common.commerce.page.CurrencyText;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.cost.ItemCost;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.CurrencyEngine;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.pick.PickService;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.ui.page.PageSection;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.util.SafeLog;

/**
 * The Services part: for each track the trainer teaches, a respec priced in the track's own {@code Respec.Cost}
 * (else its category's, else free; any wallet or item, paid {@code Respec.PerLevel} times per level of the player's
 * best level, R651, R665: {@code PickService.respecPrice}), which arms on its first press and charges on the second
 * ({@code PickService.buyRespec}, refused in combat or mid-encounter, which keeps what a trainer taught and gives
 * the receipt back if the clearing cannot be kept), only within the trainer's {@code Reach} ({@link TrainerReach});
 * then whatever {@link TrainerServices} a mod registered for that track (the prestige ceremony,
 * re-choosing a milestone, the update-day suggested build), none of which shows until something answers for it.
 */
final class ServiceOffers {

    private ServiceOffers() {
    }

    /** The services for every track the trainer teaches; null when it teaches no usable track. */
    @Nullable
    static TrainerOffers offers(@Nonnull TrainerAt at, @Nullable PageSection entry) {
        List<TrainerEntry> entries = new ArrayList<>();
        for (TrackDef track : at.tracks()) {
            entries.add(respec(at, track));
            entries.addAll(TrainerServices.entries(at, track));
        }
        return entries.isEmpty() ? null : new TrainerOffers(TrainerText.line("part.services"), entries);
    }

    /** The respec of {@code track}: what it clears, what it costs, and Reset, which asks to be pressed twice. */
    @Nonnull
    private static TrainerEntry respec(@Nonnull TrainerAt at, @Nonnull TrackDef track) {
        Message name = TrackText.name(track.asset());
        int clears = PickService.respecClears(at.playerId(), track.id());
        boolean ready = clears > 0;
        List<DetailLine> price = priceLines(PickService.respecPrice(at.playerId(), track.id()));
        Message value = price.isEmpty() ? TrainerText.line("service.respec.free")
                : price.size() == 1 ? price.get(0).text() : null;
        Picture picture = Picture.item(track.asset().icon());
        Message title = TrainerText.line("service.respec.title", name);
        LedgerRow row = new LedgerRow("respec/" + track.id(), title,
                ready ? TrainerText.line("service.respec.meta", clears) : TrainerText.line("service.respec.meta.none"),
                picture, ready ? Tone.AVAILABLE : Tone.NEUTRAL,
                ready ? TrainerText.line("service.respec.state.ready") : null, value, null, Mark.NONE, !ready);
        DetailAction reset = new DetailAction(ActionSlot.PRIMARY, TrainerText.line("service.respec.action"),
                ActionLook.DANGER, "respec", null, ready, null);
        List<DetailLine> priceBlock = price.isEmpty()
                ? List.of(DetailLine.of(Picture.NONE, TrainerText.line("service.respec.free"))) : price;
        DetailView page = new DetailView(picture, title, name, null, List.of(), null, null, null,
                TrainerText.line("service.respec.lead", name),
                List.of(new DetailBlock("price", TrainerText.line("service.respec.price"), null, priceBlock)),
                List.of(reset), ready ? null : TrainerText.line("service.respec.refused.nothing", name));
        String trackId = track.id();
        return TrainerEntry.confirmed(row, page, pressed -> buy(pressed, trackId),
                TrainerText.line("service.respec.confirm"));
    }

    /** Charge the respec and clear the track's picks; say what happened. */
    @Nonnull
    private static TrainerResult buy(@Nonnull TrainerAt at, @Nonnull String trackId) {
        PickService.Result result = PickService.buyRespec(at.playerId(), trackId);
        Message name = LearnOffers.trackName(trackId);
        return switch (result.status()) {
            case DONE -> TrainerResult.toast(ToastKind.SUCCESS,
                    TrainerText.line("service.respec.done", name, result.count()));
            case NOTHING -> TrainerResult.toast(ToastKind.WARNING,
                    TrainerText.line("service.respec.refused.nothing", name));
            case CANNOT_PAY -> TrainerResult.toast(ToastKind.WARNING, TrainerText.line("service.respec.refused.pay"));
            case IN_COMBAT -> TrainerResult.toast(ToastKind.WARNING,
                    TrainerText.line("service.respec.refused.combat"));
            case IN_ENCOUNTER -> TrainerResult.toast(ToastKind.WARNING,
                    TrainerText.line("service.respec.refused.encounter"));
            default -> TrainerResult.toast(ToastKind.WARNING, TrainerText.line("service.respec.refused.other"));
        };
    }

    /**
     * A price as the lines its reading page lists, one per wallet or item, each in the shops' own wording
     * ({@code CommerceChips}); empty for a free price.
     */
    @Nonnull
    private static List<DetailLine> priceLines(@Nonnull Cost cost) {
        List<DetailLine> out = new ArrayList<>();
        if (cost.isFree()) {
            return out;
        }
        try {
            CurrencyEngine currencies = CommerceEngines.costs().currencies();
            for (Map.Entry<String, Long> component : cost.currencies().entrySet()) {
                long amount = component.getValue() == null ? 0L : component.getValue();
                CurrencyDef def = currencies.catalog().get(component.getKey());
                out.add(DetailLine.of(def == null ? Picture.NONE : Picture.item(CurrencyText.iconOf(def)),
                        CommerceChips.amountAndName(currencies, component.getKey(), amount, null)));
            }
        } catch (Throwable t) {
            SafeLog.fine("[trainer] a respec's wallets could not be read: " + t.getMessage());
        }
        for (ItemCost item : cost.items()) {
            if (item != null && !item.isBlank()) {
                out.add(DetailLine.of(Picture.tooltipItem(item.item()),
                        CommerceChips.priceAmount(item.count(), CommerceChips.itemName(item.item()))));
            }
        }
        return out;
    }
}
