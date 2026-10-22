package com.ziggfreed.common.track.tree;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.commerce.fold.CommerceFold;
import com.ziggfreed.common.commerce.page.CommerceChips;
import com.ziggfreed.common.cost.Cost;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.track.PickState;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackReading;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackCategoryAsset;
import com.ziggfreed.common.track.asset.TrackCategoryConfig;
import com.ziggfreed.common.track.pick.PickService;
import com.ziggfreed.common.track.pick.PickService.Status;
import com.ziggfreed.common.track.pick.TrackPickAsset;
import com.ziggfreed.common.track.pick.TrackPicks;
import com.ziggfreed.common.track.pick.TrackTree;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.KitText;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.Progress;
import com.ziggfreed.common.ui.kit.Stat;
import com.ziggfreed.common.ui.kit.Tick;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * What a track's tree page shows, read once per build from {@link TrackTree} and {@link PickService}: the header (the
 * track, the player's level on it, what every point spent gives, the points spent and left), a tab per branch in the
 * track's {@code Branches} order (the picks in no branch first, under Basics) and a Builds tab when the track keeps
 * saved builds or a respec, the shown tab's list (a branch's rows by the points each asks spent there, a locked one
 * saying how many more; the saved builds and the respec), each row's reading page, and what each of its buttons does.
 * The page paints it and answers every press from it, never from a recomputed list, and every press acts through
 * {@link PickService}, which asks every rule again. Also the one place the page document's ids are spelled.
 */
final class TreePagePlan {

    static final String DOCUMENT = "Pages/ZigTrackTreePage.ui";
    static final String CLOSE = "#CloseButton";
    static final String PIC = "#TreePic";
    static final String NAME = "#TreeName";
    static final String LEVEL = "#TreeLevel";
    static final String EACH = "#TreeEach";
    static final String SPENT = "#PointsSpent";
    static final String LEFT = "#PointsLeft";
    static final String TABS = "#TreeTabs";
    static final String LIST = "#TreeList";
    static final String PAGE = "#Page";
    static final String PAGE_EMPTY = "#PageEmpty";

    /** The tab of the picks in no branch. */
    static final String TAB_ROOT = "root";

    /** The tab of the saved builds and the respec. */
    static final String TAB_BUILDS = "builds";

    /** Before a branch's id, in its tab's id. */
    static final String TAB_BRANCH = "branch/";

    /** Before a pick's id, in its row's id. */
    static final String ROW_NODE = "node/";

    /** Before a saved build's number, in its row's id. */
    static final String ROW_BUILD = "build/";

    /** The respec's row. */
    static final String ROW_RESPEC = "respec";

    private TreePagePlan() {
    }

    /** One tab: its id, its name and its picture. */
    record Tab(@Nonnull String id, @Nonnull Message label, @Nonnull Picture picture) {
    }

    /** What one button does when pressed (World thread): the toast that says how it went, or null for none. */
    @FunctionalInterface
    interface Press {

        @Nullable
        ToastSpec press();
    }

    /**
     * One row of the list: its id, its reading page, what each of its buttons does, and the words a button that
     * charges or replaces reads once armed (it acts on the second press).
     */
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

    /** The header: the track's picture, its name, the player's level, what every point gives, the two figures. */
    record Header(@Nonnull Picture picture, @Nonnull Message name, @Nonnull Message level, @Nullable Message each,
                  @Nonnull Stat spent, @Nonnull Stat left) {
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
     * The page for {@code player} on {@code view}, the tab {@code tab} shown when the track still has it (else the
     * first), and the row {@code row} read when that tab still lists it (else its first row).
     */
    @Nonnull
    static Plan of(@Nonnull UUID player, @Nonnull TrackTree.View view, @Nullable String tab, @Nullable String row) {
        TrackDef def = view.def();
        TrackAsset track = def.asset();
        List<Tab> tabs = tabs(view);
        String shown = tabs.isEmpty() ? TAB_ROOT : tabs.get(0).id();
        for (Tab each : tabs) {
            if (each.id().equals(tab)) {
                shown = each.id();
            }
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        List<LedgerSection> sections = new ArrayList<>();
        if (TAB_BUILDS.equals(shown)) {
            builds(player, def, sections, entries);
        } else {
            TrackTree.Branch branch = view.branch(shown.startsWith(TAB_BRANCH) ? shown.substring(TAB_BRANCH.length())
                    : null);
            if (branch != null) {
                rows(player, view, branch, sections, entries);
            }
        }
        LedgerModel list = LedgerModel.of(sections);
        Entry selected = entries.get(row);
        if (selected == null && list.firstSelectable() != null) {
            selected = entries.get(list.firstSelectable());
        }
        return new Plan(header(view, track), tabs, shown, list, entries, selected);
    }

    /** The tabs: the root (when any pick sits in no branch), each branch in order, then Builds when there is one. */
    @Nonnull
    private static List<Tab> tabs(@Nonnull TrackTree.View view) {
        TrackAsset track = view.def().asset();
        List<Tab> tabs = new ArrayList<>();
        for (TrackTree.Branch branch : view.branches()) {
            if (branch.isRoot()) {
                tabs.add(new Tab(TAB_ROOT, TreeText.line("tab.root"), Picture.item(track.icon())));
            } else {
                TrackAsset.Branch declared = track.branch(branch.id());
                tabs.add(new Tab(TAB_BRANCH + branch.id(), TrackText.branchName(track, branch.id()),
                        Picture.item(declared == null ? null : declared.icon())));
            }
        }
        if (view.def().loadoutsMax() > 0 || offersRespec(view.def())) {
            tabs.add(new Tab(TAB_BUILDS, TreeText.line("tab.builds"), Picture.NONE));
        }
        return tabs;
    }

    /** Does the track (or its category) write a {@code Respec} group, so a player buys one here? */
    static boolean offersRespec(@Nonnull TrackDef def) {
        if (def.asset().respec() != null) {
            return true;
        }
        TrackCategoryAsset category = TrackCategoryConfig.getInstance().category(def.asset().category());
        return category != null && category.respec() != null;
    }

    @Nonnull
    private static Header header(@Nonnull TrackTree.View view, @Nonnull TrackAsset track) {
        Message each = TreeGrants.joined(TreeGrants.lines(track.pointsGrants()));
        return new Header(Picture.item(track.icon()), TrackText.name(track),
                TreeText.line("header.level", view.level()),
                each == null ? null : TreeText.line("header.each", each),
                new Stat(Msg.num(view.spent()), TreeText.line("header.spent"), Tone.NEUTRAL),
                new Stat(Msg.num(view.left()), TreeText.line("header.left"), view.left() > 0 ? Tone.COLLECT
                        : Tone.NEUTRAL));
    }

    // ==================== a branch's rows ====================

    /** One section per row of {@code branch}, a locked one reading how many more points it needs there. */
    private static void rows(@Nonnull UUID player, @Nonnull TrackTree.View view, @Nonnull TrackTree.Branch branch,
            @Nonnull List<LedgerSection> sections, @Nonnull Map<String, Entry> entries) {
        TrackAsset track = view.def().asset();
        Message branchName = branch.isRoot() ? null : TrackText.branchName(track, branch.id());
        boolean canUndo = PickService.canUndo(player);
        for (TrackTree.Row row : branch.rows()) {
            List<LedgerRow> lines = new ArrayList<>(row.nodes().size());
            for (TrackTree.Node node : row.nodes()) {
                NodeText text = new NodeText(view, branch, branchName, row, node);
                lines.add(text.ledgerRow());
                entries.put(text.id(), text.entry(player, canUndo));
            }
            Message label = row.open() || branchName == null ? TreeText.line("row.open", row.index())
                    : TreeText.line("row.locked", row.missing(), branchName);
            sections.add(new LedgerSection("row/" + row.index(), label, lines, true));
        }
    }

    /** One pick's row and reading page, from what the tree read of it. */
    private record NodeText(@Nonnull TrackTree.View view, @Nonnull TrackTree.Branch branch,
                            @Nullable Message branchName, @Nonnull TrackTree.Row row, @Nonnull TrackTree.Node node) {

        @Nonnull
        String id() {
            return ROW_NODE + node.pickId();
        }

        @Nullable
        TrackPickAsset asset() {
            TrackPicks.PickDef def = TrackPicks.pick(node.pickId());
            return def == null ? null : def.asset();
        }

        @Nonnull
        Message name() {
            return TrackText.pickName(node.pickId());
        }

        @Nonnull
        Picture picture() {
            TrackPickAsset asset = asset();
            return Picture.item(asset == null ? null : asset.icon());
        }

        /** Does it take ranks, a known number of them? */
        boolean ranked() {
            return node.ranks() > 1 && node.ranks() != Integer.MAX_VALUE;
        }

        @Nonnull
        LedgerRow ledgerRow() {
            List<DetailLine> price = node.cost() <= 0 ? CommerceChips.priceLines(PickService.price(node.pickId()))
                    : List.of();
            Message cost = node.cost() > 0 ? TreeText.line(ranked() ? "node.meta.rank" : "node.meta.once", node.cost())
                    : price.isEmpty() ? TreeText.line("node.meta.free") : price.get(0).text();
            Message meta = node.isFace() ? TreeText.line("node.meta.face", cost) : cost;
            Message value = ranked() ? KitText.count(node.taken(), node.ranks())
                    : node.ranks() == Integer.MAX_VALUE && node.taken() > 0 ? Msg.num(node.taken()) : null;
            return new LedgerRow(id(), name(), meta, picture(), tone(), state(), value, null, Mark.NONE,
                    node.maxed());
        }

        @Nonnull
        Tone tone() {
            return switch (node.status()) {
                case MAXED -> Tone.DONE;
                case DONE -> Tone.AVAILABLE;
                case NO_POINTS, SPEND_CAP -> Tone.WAITING;
                default -> Tone.BLOCKED;
            };
        }

        @Nonnull
        Message state() {
            return switch (node.status()) {
                case MAXED -> TreeText.line("state.taken");
                case DONE -> TreeText.line("state.ready");
                case NOT_OPEN -> TreeText.line("state.level", node.opensAt());
                case NODE_TAKEN -> TreeText.line("state.closed");
                case NO_POINTS, SPEND_CAP -> TreeText.line("state.points");
                default -> TreeText.line("state.locked");
            };
        }

        @Nonnull
        Entry entry(@Nonnull UUID player, boolean canUndo) {
            List<DetailBlock> blocks = new ArrayList<>();
            TrackPickAsset asset = asset();
            List<DetailLine> gives = new ArrayList<>();
            for (Message line : TreeGrants.lines(asset == null ? List.of() : asset.grants())) {
                gives.add(DetailLine.of(Picture.NONE, line));
            }
            if (!gives.isEmpty()) {
                blocks.add(new DetailBlock("gives", TreeText.line(ranked() ? "block.gives.rank" : "block.gives"),
                        null, gives));
            }
            blocks.add(new DetailBlock("needs", TreeText.line("block.needs"), null, needs()));
            if (node.isFace()) {
                blocks.add(new DetailBlock("choice", TreeText.line("block.choice"), null, faces()));
            }
            Message meta = branchName == null ? TreeText.line("detail.meta.root")
                    : TreeText.line("detail.meta", branchName, row.index());
            DetailAction take = new DetailAction(ActionSlot.PRIMARY, TreeText.line("action.take"), ActionLook.NORMAL,
                    "take", null, node.takeable(), null);
            DetailAction undo = new DetailAction(ActionSlot.SECONDARY, TreeText.line("action.undo"),
                    ActionLook.NORMAL, "undo", null, canUndo, null);
            DetailView page = new DetailView(picture(), name(), meta, null, List.of(Pill.of(state(), tone())), null,
                    ranked() ? new Progress(node.taken(), node.ranks()) : null,
                    ranked() ? TreeText.line("detail.ranks", node.taken(), node.ranks()) : null,
                    TrackText.pickDescription(node.pickId()),
                    blocks, List.of(take, undo), hint(canUndo));
            String trackId = view.def().id();
            String pickId = node.pickId();
            Map<ActionSlot, Press> presses = new EnumMap<>(ActionSlot.class);
            presses.put(ActionSlot.PRIMARY, () -> take(player, trackId, pickId));
            presses.put(ActionSlot.SECONDARY, () -> undo(player));
            // A take that charges a price arms on its first press and pays on the second: a respec keeps what it
            // bought, so only the undo window would give back a misclick.
            // Decided from the price itself, never its rendered lines, so a line that fails to render never lets a
            // charge through on one press.
            Map<ActionSlot, Message> confirms = PickService.price(pickId).isFree()
                    ? Map.of() : Map.of(ActionSlot.PRIMARY, TreeText.line("take.confirm"));
            return new Entry(id(), page, presses, confirms);
        }

        /** What the pick needs: its row's points, its level, what comes first, its points; each ticked when met. */
        @Nonnull
        private List<DetailLine> needs() {
            List<DetailLine> lines = new ArrayList<>();
            if (branchName != null && node.gate() > 0) {
                lines.add(ticked(TreeText.line("need.branch", node.gate(), branchName), row.open()));
            }
            if (node.opensAt() > 1) {
                lines.add(ticked(TreeText.line("need.level", node.opensAt()), view.level() >= node.opensAt()));
            }
            for (String needed : node.prerequisites()) {
                lines.add(ticked(TreeText.line("need.before", TrackText.pickName(needed)),
                        !node.missing().contains(needed)));
            }
            if (node.status() == Status.LOCKED && row.open()) {
                lines.add(ticked(TreeText.line("need.other"), false));
            }
            List<DetailLine> price = CommerceChips.priceLines(PickService.price(node.pickId()));
            if (node.cost() <= 0) {
                if (price.isEmpty()) {
                    lines.add(DetailLine.of(Picture.NONE, TreeText.line("need.free")));
                }
            } else if (node.costTrack() == null || node.costTrack().equals(view.def().id())) {
                lines.add(ticked(TreeText.line("need.points", node.cost()), view.left() >= node.cost()));
            } else {
                lines.add(ticked(TreeText.line("need.points.from", node.cost(), trackName(node.costTrack())),
                        node.status() != Status.NO_POINTS && node.status() != Status.SPEND_CAP));
            }
            // Its price in wallets or items besides its points, each line in the price's own colours.
            lines.addAll(price);
            return lines;
        }

        /** The choice this pick is one face of: this one first, then each other face, the one taken tagged. */
        @Nonnull
        private List<DetailLine> faces() {
            List<DetailLine> lines = new ArrayList<>();
            lines.add(new DetailLine(picture(), name(), null,
                    node.taken() > 0 ? Pill.of(TreeText.line("tag.taken"), Tone.DONE)
                            : Pill.of(TreeText.line("tag.this"), Tone.NEUTRAL), Tick.NONE, null, true));
            for (String face : node.faces()) {
                TrackTree.Node other = view.node(face);
                TrackPicks.PickDef def = TrackPicks.pick(face);
                lines.add(new DetailLine(Picture.item(def == null ? null : def.asset().icon()),
                        TrackText.pickName(face), null,
                        other != null && other.taken() > 0 ? Pill.of(TreeText.line("tag.taken"), Tone.DONE) : null,
                        Tick.NONE, null, false));
            }
            return lines;
        }

        /** What the action bar says: why the pick cannot be taken, else that picks can be undone. */
        @Nullable
        private Message hint(boolean canUndo) {
            if (node.maxed()) {
                return TreeText.line("hint.maxed");
            }
            if (node.takeable()) {
                return canUndo ? TreeText.line("hint.undo") : null;
            }
            return reason(node.status());
        }

        /** Why a take was or would be refused, in the player's words. */
        @Nonnull
        Message reason(@Nonnull Status status) {
            return switch (status) {
                case NOT_OPEN -> TreeText.line("refused.level", node.opensAt());
                case LOCKED -> !row.open() && branchName != null
                        ? TreeText.line("refused.row", branchName, row.missing()) : TreeText.line("refused.locked");
                case NEEDS_PREREQUISITE -> TreeText.line("refused.before");
                case NODE_TAKEN -> TreeText.line("refused.node", takenFace());
                case NO_POINTS -> TreeText.line("refused.points");
                case SPEND_CAP -> TreeText.line("refused.cap");
                case CANNOT_PAY -> TreeText.line("toast.pay");
                case MAXED -> TreeText.line("hint.maxed");
                default -> TreeText.line("refused.other");
            };
        }

        /** The other face of this pick's node that is taken, by name. */
        @Nonnull
        private Message takenFace() {
            for (String face : node.faces()) {
                TrackTree.Node other = view.node(face);
                if (other != null && other.taken() > 0) {
                    return TrackText.pickName(face);
                }
            }
            return TrackText.pickName(node.faces().isEmpty() ? node.pickId() : node.faces().get(0));
        }

        /** Take the pick once more on the track's own page, and say how it went. */
        @Nonnull
        private ToastSpec take(@Nonnull UUID player, @Nonnull String trackId, @Nonnull String pickId) {
            PickService.Result result = PickService.take(player, trackId, pickId, null);
            return result.done() ? ToastSpec.of(ToastKind.SUCCESS, TreeText.line("toast.taken", name()))
                    : ToastSpec.of(ToastKind.WARNING, reason(result.status()));
        }
    }

    /** A need ticked done or locked. */
    @Nonnull
    private static DetailLine ticked(@Nonnull Message text, boolean met) {
        return new DetailLine(Picture.NONE, text, null, null, met ? Tick.DONE : Tick.LOCKED, null, false);
    }

    /**
     * Undo the player's latest pick (the server's own undo, newest first), and say how it went: a batch made in one go
     * (a starter build, remembered takes re-spent) by how many picks came back and on which track, as
     * {@code /zigtrack undo} says it.
     */
    @Nonnull
    private static ToastSpec undo(@Nonnull UUID player) {
        PickService.Result result = PickService.undo(player);
        return switch (result.status()) {
            case DONE -> ToastSpec.of(ToastKind.SUCCESS, result.isBatch()
                    ? TreeText.line("toast.undone.batch", result.count(), trackName(result.trackId()))
                    : TreeText.line("toast.undone", undone(result)));
            case NOTHING -> ToastSpec.of(ToastKind.WARNING, TreeText.line("toast.undo.none"));
            case NEEDED -> ToastSpec.of(ToastKind.WARNING, result.isBatch()
                    ? TreeText.line("toast.undo.needed.batch", result.count(), trackName(result.trackId()))
                    : TreeText.line("toast.undo.needed", undone(result)));
            default -> ToastSpec.of(ToastKind.WARNING, TreeText.line("action.failed"));
        };
    }

    /** What an undo was about: a choice's option, else the pick. */
    @Nonnull
    private static Message undone(@Nonnull PickService.Result result) {
        String id = result.slot() != null && !result.options().isEmpty() ? result.options().get(0) : result.pickId();
        return TrackText.pickName(id);
    }

    /** What the track {@code trackId} is called, else its id as written. */
    @Nonnull
    private static Message trackName(@Nullable String trackId) {
        Message name = TrackText.nameOf(trackId);
        return name != null ? name : Msg.raw(trackId);
    }

    // ==================== the builds tab ====================

    /** The saved builds (when the track keeps any) and the respec (when it offers one), each its own section. */
    private static void builds(@Nonnull UUID player, @Nonnull TrackDef def, @Nonnull List<LedgerSection> sections,
            @Nonnull Map<String, Entry> entries) {
        Picture picture = Picture.item(def.asset().icon());
        PickService.Loadouts loadouts = PickService.loadouts(player, def.id());
        if (!loadouts.slots().isEmpty()) {
            Cost swap = CommerceFold.cost(def.swapCost(), "a build swap on " + def.id());
            List<DetailLine> price = priceOrFree(CommerceChips.priceLines(swap), "build.price.free");
            boolean changed = changedSinceSaved(player, def, loadouts.active());
            List<LedgerRow> rows = new ArrayList<>();
            for (PickService.LoadoutView slot : loadouts.slots()) {
                Entry entry = build(player, def, slot, swap, price, picture, slot.active() && changed);
                rows.add(buildRow(entry.id(), slot, picture));
                entries.put(entry.id(), entry);
            }
            sections.add(new LedgerSection("builds", TreeText.line("section.builds"), rows, true));
        }
        if (offersRespec(def)) {
            int clears = PickService.respecClears(player, def.id());
            Entry entry = respec(player, def, picture, clears);
            LedgerRow row = new LedgerRow(ROW_RESPEC, TreeText.line("respec.title"), respecMeta(clears), picture,
                    clears > 0 ? Tone.AVAILABLE : Tone.NEUTRAL, null, null, null, Mark.NONE, clears == 0);
            entries.put(entry.id(), entry);
            sections.add(new LedgerSection("respec", TreeText.line("section.respec"), List.of(row), true));
        }
    }

    @Nonnull
    private static LedgerRow buildRow(@Nonnull String id, @Nonnull PickService.LoadoutView slot,
            @Nonnull Picture picture) {
        return new LedgerRow(id, TreeText.line("build.title", slot.slot()), buildMeta(slot), picture,
                slot.active() ? Tone.ACTIVE : Tone.NEUTRAL,
                slot.active() ? TreeText.line("build.state.active") : null, null, null, Mark.NONE, false);
    }

    @Nonnull
    private static Message buildMeta(@Nonnull PickService.LoadoutView slot) {
        return slot.saved() ? TreeText.line("build.meta", slot.picks()) : TreeText.line("build.meta.empty");
    }

    /**
     * Have the picks in use moved since the build in use ({@code active}, 0 for none) was saved? What a swap to that
     * same build compares ({@code PickService.swapLoadout}): the picks a prestige would not keep, against the build as
     * saved. False with no build in use or nobody to read.
     */
    private static boolean changedSinceSaved(@Nonnull UUID player, @Nonnull TrackDef def, int active) {
        if (active <= 0) {
            return false;
        }
        TrackReading reading = TrackService.read(TrackKey.player(player, def.asset().getId()));
        if (reading == null) {
            return false;
        }
        PickState state = reading.record().picks();
        return !state.snapshot(PickService.keptThroughPrestige(def)).equals(state.loadout(active));
    }

    /**
     * One saved build's page. Another build: its swap price, Use this build (armed when it charges) and Save here
     * (armed over a saved build). The build in use: no price (a swap back to it is free), and Use this build armed
     * when picks made since it was saved would be cleared by putting it back as saved ({@code changed}).
     */
    @Nonnull
    private static Entry build(@Nonnull UUID player, @Nonnull TrackDef def, @Nonnull PickService.LoadoutView slot,
            @Nonnull Cost swap, @Nonnull List<DetailLine> price, @Nonnull Picture picture, boolean changed) {
        Message title = TreeText.line("build.title", slot.slot());
        DetailAction use = new DetailAction(ActionSlot.PRIMARY, TreeText.line("build.action.swap"),
                ActionLook.NORMAL, "swap", null, true, null);
        DetailAction save = new DetailAction(ActionSlot.SECONDARY, TreeText.line("build.action.save"),
                ActionLook.NORMAL, "save", null, true, null);
        List<DetailBlock> blocks = slot.active() ? List.of()
                : List.of(new DetailBlock("price", TreeText.line("build.block.price"), null, price));
        Message hint = !slot.active() ? null
                : TreeText.line(changed ? "build.hint.changed" : "build.hint.as_saved");
        DetailView page = new DetailView(picture, title, buildMeta(slot), null,
                slot.active() ? List.of(Pill.of(TreeText.line("build.state.active"), Tone.ACTIVE)) : List.of(),
                null, null, null, TreeText.line("build.lead"), blocks, List.of(use, save), hint);
        String trackId = def.id();
        int number = slot.slot();
        Map<ActionSlot, Press> presses = new EnumMap<>(ActionSlot.class);
        presses.put(ActionSlot.PRIMARY, () -> swap(player, trackId, number));
        presses.put(ActionSlot.SECONDARY, () -> save(player, trackId, number));
        Map<ActionSlot, Message> confirms = new EnumMap<>(ActionSlot.class);
        if (slot.active() ? changed : !swap.isFree()) {
            confirms.put(ActionSlot.PRIMARY, TreeText.line(slot.active() ? "build.confirm.reset" : "build.confirm.swap"));
        }
        if (slot.saved() && !slot.active()) {
            confirms.put(ActionSlot.SECONDARY, TreeText.line("build.confirm.save"));
        }
        return new Entry(ROW_BUILD + number, page, presses, confirms);
    }

    @Nonnull
    private static ToastSpec swap(@Nonnull UUID player, @Nonnull String trackId, int slot) {
        PickService.Result result = PickService.swapLoadout(player, trackId, slot, false);
        return switch (result.status()) {
            case DONE -> ToastSpec.of(ToastKind.SUCCESS, TreeText.line("build.toast.swapped", slot, result.count()));
            case NOTHING -> ToastSpec.of(ToastKind.INFO, TreeText.line("build.toast.in_use"));
            case UNSAVED -> ToastSpec.of(ToastKind.WARNING, TreeText.line("build.toast.unsaved"));
            default -> refusedCharge(result.status());
        };
    }

    @Nonnull
    private static ToastSpec save(@Nonnull UUID player, @Nonnull String trackId, int slot) {
        PickService.Result result = PickService.saveLoadout(player, trackId, slot);
        return result.done() ? ToastSpec.of(ToastKind.SUCCESS, TreeText.line("build.toast.saved", slot,
                result.count())) : ToastSpec.of(ToastKind.WARNING, TreeText.line("action.failed"));
    }

    // ==================== the respec ====================

    @Nonnull
    private static Message respecMeta(int clears) {
        return clears > 0 ? TreeText.line("respec.meta", clears) : TreeText.line("respec.meta.none");
    }

    /** The respec's page: what it clears, its price at the player's best level, and Respec, pressed twice. */
    @Nonnull
    private static Entry respec(@Nonnull UUID player, @Nonnull TrackDef def, @Nonnull Picture picture, int clears) {
        List<DetailLine> price = priceOrFree(CommerceChips.priceLines(PickService.respecPrice(player, def.id())),
                "build.price.free");
        Message title = TreeText.line("respec.title");
        DetailAction respec = new DetailAction(ActionSlot.DANGER, TreeText.line("respec.action"), ActionLook.DANGER,
                "respec", null, clears > 0, null);
        DetailView page = new DetailView(picture, title, respecMeta(clears), null, List.of(), null, null, null,
                TreeText.line("respec.lead"),
                List.of(new DetailBlock("price", TreeText.line("respec.block.price"), null, price)),
                List.of(respec), clears > 0 ? null : TreeText.line("respec.toast.nothing"));
        String trackId = def.id();
        Map<ActionSlot, Press> presses = new EnumMap<>(ActionSlot.class);
        presses.put(ActionSlot.DANGER, () -> buyRespec(player, trackId));
        return new Entry(ROW_RESPEC, page, presses, Map.of(ActionSlot.DANGER, TreeText.line("respec.confirm")));
    }

    @Nonnull
    private static ToastSpec buyRespec(@Nonnull UUID player, @Nonnull String trackId) {
        PickService.Result result = PickService.buyRespec(player, trackId);
        return switch (result.status()) {
            case DONE -> ToastSpec.of(ToastKind.SUCCESS, TreeText.line("respec.toast.done", result.count()));
            case NOTHING -> ToastSpec.of(ToastKind.WARNING, TreeText.line("respec.toast.nothing"));
            default -> refusedCharge(result.status());
        };
    }

    /** Why a swap or a respec was refused: a fight, a boss fight, the price, or something else. */
    @Nonnull
    private static ToastSpec refusedCharge(@Nonnull Status status) {
        return ToastSpec.of(ToastKind.WARNING, switch (status) {
            case IN_COMBAT -> TreeText.line("toast.combat");
            case IN_ENCOUNTER -> TreeText.line("toast.encounter");
            case CANNOT_PAY -> TreeText.line("toast.pay");
            default -> TreeText.line("action.failed");
        });
    }

    /** {@code price}, or one line saying it is free. */
    @Nonnull
    private static List<DetailLine> priceOrFree(@Nonnull List<DetailLine> price, @Nonnull String freeKey) {
        return price.isEmpty() ? List.of(DetailLine.of(Picture.NONE, TreeText.line(freeKey))) : price;
    }
}
