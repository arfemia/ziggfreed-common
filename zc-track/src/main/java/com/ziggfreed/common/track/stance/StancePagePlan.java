package com.ziggfreed.common.track.stance;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.commerce.page.CommerceChips;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.RefusalLines;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.asset.SlotRow;
import com.ziggfreed.common.track.pick.TrackPicks;
import com.ziggfreed.common.track.tree.TreeGrants;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.Stat;
import com.ziggfreed.common.ui.kit.Tick;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * What the stance page shows, read once per build from {@link Stances}: the header (the stance, a line on what it is
 * for, the slots open and the faces held), a tab per stance shown, the shown stance's list (its slots, then a section
 * per kind with the faces the player owns), each row's reading page, and what each of its buttons does. The page
 * paints it and answers every press from it; every press acts through {@link Stances}, which asks every rule again.
 * Also the one place the page document's ids are spelled.
 */
final class StancePagePlan {

    static final String DOCUMENT = "Pages/ZigTrackStancePage.ui";
    static final String CLOSE = "#CloseButton";
    static final String PIC = "#StancePic";
    static final String NAME = "#StanceName";
    static final String LEAD = "#StanceLead";
    static final String SLOTS = "#SlotsOpen";
    static final String HELD = "#FacesHeld";
    static final String TABS = "#StanceTabs";
    static final String LIST = "#StanceList";
    static final String PAGE = "#Page";
    static final String PAGE_EMPTY = "#PageEmpty";

    /** Before a slot's key, in its row's id. */
    static final String ROW_SLOT = "slot/";

    /** Before a face's pick id, in its row's id. */
    static final String ROW_FACE = "face/";

    private StancePagePlan() {
    }

    /** One tab: a stance's id, its name and its picture. */
    record Tab(@Nonnull String id, @Nonnull Message label, @Nonnull Picture picture) {
    }

    /** What one button does when pressed (World thread): the toast that says how it went. */
    @FunctionalInterface
    interface Press {

        @Nonnull
        ToastSpec press();
    }

    /** One row: its id, its reading page, what each button does, and the words a button that charges reads armed. */
    record Entry(@Nonnull String id, @Nonnull DetailView page, @Nonnull Map<ActionSlot, Press> presses,
                 @Nonnull Map<ActionSlot, Message> confirms) {

        Entry {
            presses = Map.copyOf(presses);
            confirms = Map.copyOf(confirms);
        }

        @Nullable
        Press press(@Nonnull ActionSlot slot) {
            return presses.get(slot);
        }

        @Nullable
        Message confirm(@Nonnull ActionSlot slot) {
            return confirms.get(slot);
        }
    }

    /** The header: the stance's picture, its name, its line, the slots open and the faces held. */
    record Header(@Nonnull Picture picture, @Nonnull Message name, @Nonnull Message lead, @Nonnull Stat slots,
                  @Nonnull Stat held) {
    }

    /** The whole page: the header, the tabs and the one shown, its list, its rows by id, and the row read. */
    record Plan(@Nonnull Header header, @Nonnull List<Tab> tabs, @Nonnull String tab, @Nonnull LedgerModel list,
                @Nonnull Map<String, Entry> entries, @Nullable Entry selected) {

        Plan {
            tabs = List.copyOf(tabs);
            entries = Map.copyOf(entries);
        }
    }

    /**
     * The page for {@code player} over {@code stances} (switched on, in order), at {@code placement} (the trainer the
     * page was opened at, null for none), the stance {@code tab} shown when it is still one of them (else the first)
     * and the row {@code row} read when it still lists it (else its first). Null when there is no stance to show.
     */
    @Nullable
    static Plan of(@Nonnull UUID player, @Nonnull List<TrackStanceAsset> stances, @Nullable String placement,
            @Nullable String tab, @Nullable String row) {
        if (stances.isEmpty()) {
            return null;
        }
        List<Tab> tabs = new ArrayList<>(stances.size());
        TrackStanceAsset shown = stances.get(0);
        for (TrackStanceAsset stance : stances) {
            String id = TrackKey.normalize(stance.getId());
            tabs.add(new Tab(id, StanceText.name(stance), Picture.item(stance.icon())));
            if (id.equals(tab)) {
                shown = stance;
            }
        }
        Read read = new Read(player, shown, placement);
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<LedgerSection> sections = new ArrayList<>();
        read.slots(sections, entries);
        read.faces(sections, entries);
        LedgerModel list = LedgerModel.of(sections);
        Entry selected = entries.get(row);
        if (selected == null && list.firstSelectable() != null) {
            selected = entries.get(list.firstSelectable());
        }
        return new Plan(read.header(), tabs, TrackKey.normalize(shown.getId()), list, entries, selected);
    }

    /** One stance as {@code player} stands in it now, read once per build. */
    private static final class Read {

        @Nonnull private final UUID player;
        @Nonnull private final TrackStanceAsset stance;
        @Nonnull private final String stanceId;
        @Nullable private final String placement;
        @Nonnull private final List<StanceView.HeldFace> live;
        @Nonnull private final List<String> open;
        @Nonnull private final Predicate<String> owns;

        Read(@Nonnull UUID player, @Nonnull TrackStanceAsset stance, @Nullable String placement) {
            this.player = player;
            this.stance = stance;
            this.stanceId = TrackKey.normalize(stance.getId());
            this.placement = placement;
            this.live = Stances.view(player).faces(stanceId);
            this.open = Stances.openSlots(player, stanceId);
            this.owns = Stances.owner(player);
        }

        @Nonnull
        Header header() {
            String flavor = stance.flavorKey();
            Message lead = flavor != null ? Msg.key(flavor) : StanceText.line("page.lead");
            return new Header(Picture.item(stance.icon()), StanceText.name(stance), lead,
                    new Stat(Msg.num(open.size()), StanceText.line("header.slots"), Tone.NEUTRAL),
                    new Stat(Msg.num(live.size()), StanceText.line("header.held"),
                            live.isEmpty() ? Tone.NEUTRAL : Tone.ACTIVE));
        }

        /** The face a slot holds that counts, or null. */
        @Nullable
        private StanceView.HeldFace inSlot(@Nonnull String slotKey) {
            for (StanceView.HeldFace face : live) {
                if (face.slotId().equals(slotKey)) {
                    return face;
                }
            }
            return null;
        }

        /** The slot holding {@code faceId} that counts, or null. */
        @Nullable
        private String slotOf(@Nonnull String faceId) {
            for (StanceView.HeldFace face : live) {
                if (face.faceId().equals(faceId)) {
                    return face.slotId();
                }
            }
            return null;
        }

        // ==================== the slots ====================

        void slots(@Nonnull List<LedgerSection> sections, @Nonnull Map<String, Entry> entries) {
            List<LedgerRow> rows = new ArrayList<>();
            for (SlotRow slot : stance.slots()) {
                StanceView.HeldFace face = inSlot(slot.key());
                boolean isOpen = open.contains(slot.key());
                Message name = StanceText.slotName(stance, slot.key());
                Message meta = face != null ? TrackText.pickName(face.faceId())
                        : StanceText.line(isOpen ? "slot.meta.empty" : "slot.meta.closed");
                Tone tone = face != null ? Tone.ACTIVE : isOpen ? Tone.AVAILABLE : Tone.BLOCKED;
                Message state = StanceText.line(face != null ? "state.held" : isOpen ? "state.open" : "state.closed");
                Picture picture = face == null ? Picture.item(stance.icon()) : facePicture(face.faceId());
                String id = ROW_SLOT + slot.key();
                rows.add(new LedgerRow(id, name, meta, picture, tone, state, null, null, Mark.NONE, !isOpen));
                entries.put(id, slotEntry(id, slot, name, picture, face, isOpen, tone, state));
            }
            sections.add(new LedgerSection("slots", StanceText.line("section.slots"), rows, true));
        }

        @Nonnull
        private Entry slotEntry(@Nonnull String id, @Nonnull SlotRow slot, @Nonnull Message name,
                @Nonnull Picture picture, @Nullable StanceView.HeldFace face, boolean isOpen, @Nonnull Tone tone,
                @Nonnull Message state) {
            List<DetailBlock> blocks = new ArrayList<>();
            List<DetailLine> opens = new ArrayList<>();
            if (slot.pick() != null) {
                opens.add(ticked(StanceText.line("slot.needs.pick", TrackText.pickName(slot.pick())),
                        owns.test(slot.pick())));
            }
            if (slot.requires() != null) {
                for (Message line : RefusalLines.linesOf(GateEvaluator.asks(slot.requires()))) {
                    opens.add(ticked(line, isOpen));
                }
            }
            if (opens.isEmpty()) {
                opens.add(DetailLine.of(Picture.NONE, StanceText.line("slot.needs.none")));
            }
            blocks.add(new DetailBlock("opens", StanceText.line("block.opens"), null, opens));
            Message lead = face != null ? StanceText.line("slot.lead.held", TrackText.pickName(face.faceId()))
                    : StanceText.line(isOpen ? "slot.lead.empty" : "slot.lead.closed");
            List<DetailAction> actions = new ArrayList<>();
            Map<ActionSlot, Press> presses = new EnumMap<>(ActionSlot.class);
            Map<ActionSlot, Message> confirms = new EnumMap<>(ActionSlot.class);
            Message hint = null;
            if (face != null) {
                Stances.Status release = Stances.releaseStatus(player, stanceId, face.faceId(), placement);
                actions.add(releaseAction(release));
                String faceId = face.faceId();
                presses.put(ActionSlot.DANGER, () -> release(faceId));
                confirmRelease(confirms);
                hint = release == Stances.Status.DONE ? null : TrackText.refusal(release);
            }
            DetailView page = new DetailView(picture, name, StanceText.name(stance), null, List.of(Pill.of(state,
                    tone)), null, null, null, lead, blocks, actions, hint);
            return new Entry(id, page, presses, confirms);
        }

        // ==================== the faces ====================

        void faces(@Nonnull List<LedgerSection> sections, @Nonnull Map<String, Entry> entries) {
            Map<String, List<TrackPicks.PickDef>> byKind = new LinkedHashMap<>();
            for (String kind : stance.kinds().keySet()) {
                byKind.put(kind, new ArrayList<>());
            }
            List<TrackPicks.PickDef> other = new ArrayList<>();
            for (TrackPicks.PickDef pick : TrackPicks.all().values()) {
                if (!StanceRules.isFaceOf(pick, stanceId) || !owns.test(pick.id())) {
                    continue;
                }
                List<TrackPicks.PickDef> into = pick.asset().stanceKind() == null ? null
                        : byKind.get(pick.asset().stanceKind());
                (into == null ? other : into).add(pick);
            }
            byKind.forEach((kind, faces) -> {
                if (!faces.isEmpty()) {
                    sections.add(kindSection(kind, faces, entries));
                }
            });
            if (!other.isEmpty()) {
                sections.add(section("faces", StanceText.line("section.faces"), other, entries));
            }
        }

        @Nonnull
        private LedgerSection kindSection(@Nonnull String kind, @Nonnull List<TrackPicks.PickDef> faces,
                @Nonnull Map<String, Entry> entries) {
            TrackStanceAsset.Kind declared = stance.kind(kind);
            int held = 0;
            for (StanceView.HeldFace face : live) {
                if (kind.equals(face.kind())) {
                    held++;
                }
            }
            Message name = StanceText.kindName(stance, kind);
            Message label = declared == null || declared.max() == Integer.MAX_VALUE
                    ? StanceText.line("section.kind", name, held)
                    : StanceText.line("section.kind.capped", name, held, declared.max());
            return section("kind/" + kind, label, faces, entries);
        }

        @Nonnull
        private LedgerSection section(@Nonnull String id, @Nonnull Message label,
                @Nonnull List<TrackPicks.PickDef> faces, @Nonnull Map<String, Entry> entries) {
            List<LedgerRow> rows = new ArrayList<>(faces.size());
            for (TrackPicks.PickDef face : faces) {
                String slot = slotOf(face.id());
                Stances.Status hold = slot != null ? Stances.Status.ALREADY_HELD
                        : Stances.holdStatus(player, stanceId, face.id(), placement);
                Tone tone = slot != null ? Tone.ACTIVE : hold == Stances.Status.DONE ? Tone.AVAILABLE : Tone.NEUTRAL;
                Message state = StanceText.line(slot != null ? "state.held" : "state.not_held");
                Message meta = slot != null ? StanceText.line("face.meta.held", StanceText.slotName(stance, slot))
                        : StanceText.line("face.meta.not_held");
                String rowId = ROW_FACE + face.id();
                rows.add(new LedgerRow(rowId, TrackText.pickName(face.id()), meta, facePicture(face.id()), tone,
                        state, null, null, Mark.NONE, false));
                entries.put(rowId, faceEntry(rowId, face, slot, hold, tone, state));
            }
            return new LedgerSection(id, label, rows, true);
        }

        @Nonnull
        private Entry faceEntry(@Nonnull String id, @Nonnull TrackPicks.PickDef face, @Nullable String slot,
                @Nonnull Stances.Status hold, @Nonnull Tone tone, @Nonnull Message state) {
            String kind = face.asset().stanceKind();
            TrackStanceAsset.Kind declared = kind == null ? null : stance.kind(kind);
            List<DetailBlock> blocks = new ArrayList<>();
            List<DetailLine> gives = new ArrayList<>();
            for (TrackPicks.PickDef step : stepsOf(face.id())) {
                for (Message line : TreeGrants.lines(step.asset().grants())) {
                    gives.add(DetailLine.of(Picture.NONE, line));
                }
            }
            if (gives.isEmpty()) {
                gives.add(DetailLine.of(Picture.NONE, StanceText.line("face.gives.none")));
            }
            blocks.add(new DetailBlock("gives", StanceText.line("block.gives"), null, gives));
            List<DetailLine> costs = new ArrayList<>();
            for (Message line : TreeGrants.lines(declared == null ? List.of() : declared.whileHeld())) {
                costs.add(DetailLine.of(Picture.NONE, line));
            }
            if (!costs.isEmpty()) {
                blocks.add(new DetailBlock("costs", StanceText.line("block.costs"), null, costs));
            }
            blocks.add(new DetailBlock("hold", StanceText.line("block.hold.price"), null,
                    priceOrFree(Stances.holdPrice(stance))));
            blocks.add(new DetailBlock("release", StanceText.line("block.release.price"), null,
                    priceOrFree(Stances.breakPrice(stance))));
            String faceId = face.id();
            List<DetailAction> actions = new ArrayList<>();
            Map<ActionSlot, Press> presses = new EnumMap<>(ActionSlot.class);
            Map<ActionSlot, Message> confirms = new EnumMap<>(ActionSlot.class);
            Message hint;
            if (slot != null) {
                Stances.Status release = Stances.releaseStatus(player, stanceId, faceId, placement);
                actions.add(releaseAction(release));
                presses.put(ActionSlot.DANGER, () -> release(faceId));
                confirmRelease(confirms);
                hint = release == Stances.Status.DONE ? null : TrackText.refusal(release);
            } else {
                actions.add(new DetailAction(ActionSlot.PRIMARY, StanceText.line("action.hold"), ActionLook.NORMAL,
                        "hold", null, hold == Stances.Status.DONE, null));
                presses.put(ActionSlot.PRIMARY, () -> hold(faceId));
                if (!Stances.holdPrice(stance).isFree()) {
                    confirms.put(ActionSlot.PRIMARY, StanceText.line("hold.confirm"));
                }
                hint = hold == Stances.Status.DONE ? null : reason(hold, kind);
            }
            Message meta = kind == null ? StanceText.name(stance) : StanceText.kindName(stance, kind);
            DetailView page = new DetailView(facePicture(faceId), TrackText.pickName(faceId), meta, null,
                    List.of(Pill.of(state, tone)), null, null, null, TrackText.pickDescription(faceId), blocks,
                    actions, hint);
            return new Entry(id, page, presses, confirms);
        }

        /** The face and every step of it the player owns, the face first. */
        @Nonnull
        private List<TrackPicks.PickDef> stepsOf(@Nonnull String faceId) {
            List<TrackPicks.PickDef> out = new ArrayList<>();
            TrackPicks.PickDef own = TrackPicks.pick(faceId);
            if (own != null) {
                out.add(own);
            }
            for (TrackPicks.PickDef pick : TrackPicks.all().values()) {
                if (pick != own && faceId.equals(pick.asset().stanceFace()) && stanceId.equals(pick.asset().stanceId())
                        && owns.test(pick.id())) {
                    out.add(pick);
                }
            }
            return out;
        }

        /** Why a hold is refused: a kind full names the kind, every other answer the one refusal wording. */
        @Nonnull
        private Message reason(@Nonnull Stances.Status status, @Nullable String kind) {
            if (status == Stances.Status.KIND_FULL && kind != null) {
                return StanceText.line("refused.kind.named", StanceText.kindName(stance, kind));
            }
            return TrackText.refusal(status);
        }

        @Nonnull
        private DetailAction releaseAction(@Nonnull Stances.Status status) {
            return new DetailAction(ActionSlot.DANGER, StanceText.line("action.release"), ActionLook.DANGER,
                    "release", null, status == Stances.Status.DONE, null);
        }

        /** A release that charges arms on its first press and pays on the second. */
        private void confirmRelease(@Nonnull Map<ActionSlot, Message> confirms) {
            if (!Stances.breakPrice(stance).isFree()) {
                confirms.put(ActionSlot.DANGER, StanceText.line("release.confirm"));
            }
        }

        @Nonnull
        private ToastSpec hold(@Nonnull String faceId) {
            Stances.Result result = Stances.hold(player, stanceId, faceId, placement);
            TrackPicks.PickDef face = TrackPicks.pick(faceId);
            return result.done()
                    ? ToastSpec.of(ToastKind.SUCCESS, StanceText.line("toast.held", TrackText.pickName(faceId),
                            StanceText.slotName(stance, result.slotId())))
                    : ToastSpec.of(ToastKind.WARNING, reason(result.status(),
                            face == null ? null : face.asset().stanceKind()));
        }

        @Nonnull
        private ToastSpec release(@Nonnull String faceId) {
            Stances.Result result = Stances.release(player, stanceId, faceId, placement);
            return result.done()
                    ? ToastSpec.of(ToastKind.SUCCESS, StanceText.line("toast.released", TrackText.pickName(faceId)))
                    : ToastSpec.of(ToastKind.WARNING, TrackText.refusal(result.status()));
        }
    }

    /** The picture of the face {@code faceId}: its pick's icon. */
    @Nonnull
    private static Picture facePicture(@Nonnull String faceId) {
        TrackPicks.PickDef pick = TrackPicks.pick(faceId);
        return Picture.item(pick == null ? null : pick.asset().icon());
    }

    /** A price's lines, or the one line saying it is free. */
    @Nonnull
    private static List<DetailLine> priceOrFree(@Nonnull Cost price) {
        List<DetailLine> lines = CommerceChips.priceLines(price);
        return lines.isEmpty() ? List.of(DetailLine.of(Picture.NONE, StanceText.line("price.free"))) : lines;
    }

    /** A need ticked met or locked. */
    @Nonnull
    private static DetailLine ticked(@Nonnull Message text, boolean met) {
        return new DetailLine(Picture.NONE, text, null, null, met ? Tick.DONE : Tick.LOCKED, null, false);
    }
}
