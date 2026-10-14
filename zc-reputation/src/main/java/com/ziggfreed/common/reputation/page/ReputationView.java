package com.ziggfreed.common.reputation.page;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.progress.gate.GatedContent;
import com.ziggfreed.common.reputation.ReputationDef;
import com.ziggfreed.common.reputation.ReputationFactors;
import com.ziggfreed.common.reputation.ReputationLadder;
import com.ziggfreed.common.reputation.ReputationService;
import com.ziggfreed.common.reputation.ReputationText;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.EmptyState;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.Progress;
import com.ziggfreed.common.ui.kit.Tick;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.util.SafeLog;

/**
 * What the Reputation page shows, decided where a test can reach it: one row per met reputation (in the
 * service's page order) with its rank, its bar and what comes next; which row is read; and the reading page of
 * the one picked (its standing on a bar, its description whole, how to earn it and how much worn gear moves
 * each gain, every rank with what each opens, what it pays past the top and how much of that waits to be
 * collected, and the numbers behind the standing). The page paints this and decides nothing.
 */
public final class ReputationView {

    /** The list's one section. */
    static final String SECTION = "known";

    /** The detail's blocks, in the order they read. */
    static final String BLOCK_EARN = "earn";
    static final String BLOCK_RANKS = "ranks";
    static final String BLOCK_BEYOND = "beyond";
    static final String BLOCK_STANDING = "standing";

    /** The page's own id for the Collect press, on the action bar's primary button. */
    public static final String ACTION_COLLECT = "collect";

    /**
     * One met reputation: its standing, the rank above (null at the top) and the next Beyond reward (null
     * without one).
     */
    public record Row(@Nonnull ReputationService.Standing standing, @Nullable ReputationLadder.Rank next,
                      @Nullable Long nextReward) {

        @Nonnull
        public ReputationDef reputation() {
            return standing.reputation();
        }

        @Nonnull
        public String id() {
            return standing.reputation().id();
        }
    }

    /** One thing a rank of a reputation opens: the rank and the gated entry. */
    public record Unlock(@Nonnull ReputationLadder.Rank rank, @Nonnull GatedContent.Entry entry) {
    }

    private ReputationView() {
    }

    /**
     * One row per met reputation, each read on its own ladder ({@code ladderOf}: the shared ranks plus the
     * reputation's own above the top): the rank above it, or past its top rank, where the next Beyond
     * reward lands.
     */
    @Nonnull
    public static List<Row> rows(@Nonnull List<ReputationService.Standing> met,
            @Nonnull Function<ReputationDef, ReputationLadder> ladderOf) {
        List<Row> out = new ArrayList<>(met.size());
        for (ReputationService.Standing standing : met) {
            ReputationLadder ladder = ladderOf.apply(standing.reputation());
            ReputationLadder.Rank top = ladder.top();
            ReputationLadder.Rank next = ladder.next(standing.rank());
            int every = standing.reputation().beyondEvery();
            Long reward = next == null && top != null && every > 0
                    ? ReputationLadder.nextRewardAt(standing.earned(), top.min(), every) : null;
            out.add(new Row(standing, next, reward));
        }
        return List.copyOf(out);
    }

    /** The row named {@code selectedId} (any case), else the first; null when there are none. */
    @Nullable
    public static Row pick(@Nonnull List<Row> rows, @Nullable String selectedId) {
        if (rows.isEmpty()) {
            return null;
        }
        if (selectedId != null) {
            for (Row row : rows) {
                if (row.id().equalsIgnoreCase(selectedId.trim())) {
                    return row;
                }
            }
        }
        return rows.get(0);
    }

    // ==================== the list ====================

    /**
     * The list: one section holding a row per met reputation, in page order, each coloured on its own ladder
     * ({@code ladderOf}, as {@link #rows} reads it), so a tier above the shared top reads as that reputation's top.
     */
    @Nonnull
    public static LedgerModel ledger(@Nonnull List<Row> rows,
            @Nonnull Function<ReputationDef, ReputationLadder> ladderOf) {
        List<LedgerRow> out = new ArrayList<>(rows.size());
        for (Row row : rows) {
            out.add(ledgerRow(row, ladderOf.apply(row.reputation())));
        }
        return LedgerModel.of(List.of(new LedgerSection(SECTION, ReputationText.line("page.section"), out, true)));
    }

    /** A row: the picture, the name, the rank in its standing's colour, the bar and what comes next. */
    @Nonnull
    static LedgerRow ledgerRow(@Nonnull Row row, @Nonnull ReputationLadder ladder) {
        ReputationDef def = row.reputation();
        Message rank = ReputationText.rankName(def, row.standing().rank());
        return new LedgerRow(row.id(), ReputationText.name(def), rowMeta(row), picture(def),
                tone(ladder, def, row.standing().rank()), rank, null, bar(row), Mark.NONE, false);
    }

    /** "415 to Regular", "1,200 to the next reward" or "Top rank reached". */
    @Nonnull
    public static Message rowMeta(@Nonnull Row row) {
        ReputationService.Standing standing = row.standing();
        ReputationDef def = standing.reputation();
        if (row.next() != null) {
            return ReputationText.line("row.next", row.next().min() - standing.effective(),
                    ReputationText.rankName(def, row.next()));
        }
        if (row.nextReward() != null) {
            return ReputationText.line("row.reward", row.nextReward() - standing.earned());
        }
        return ReputationText.line("row.top");
    }

    /**
     * The bar: the way through the rank held toward the next one; past the top, the way toward the next Beyond
     * reward; full where nothing is left to count toward.
     */
    @Nonnull
    public static Progress bar(@Nonnull Row row) {
        ReputationService.Standing standing = row.standing();
        ReputationLadder.Rank rank = standing.rank();
        if (row.next() != null && rank != null) {
            return new Progress(Math.max(0L, standing.effective() - rank.min()), (long) row.next().min() - rank.min());
        }
        int every = standing.reputation().beyondEvery();
        if (row.nextReward() != null && every > 0) {
            // Never under zero, should earned standing ever read below the top's floor.
            return new Progress(Math.max(0L, every - (row.nextReward() - standing.earned())), every);
        }
        return new Progress(1, 1);
    }

    /**
     * A rank's colour, the way a standing reads at a glance: below where the reputation starts reads as
     * danger, the starting rank amber, every rank above it green, and the top blue.
     */
    @Nonnull
    public static Tone tone(@Nonnull ReputationLadder ladder, @Nonnull ReputationDef def,
            @Nullable ReputationLadder.Rank rank) {
        int at = ladder.indexOf(rank);
        if (at < 0) {
            return Tone.NEUTRAL;
        }
        if (rank.equals(ladder.top())) {
            return Tone.ACTIVE;
        }
        int start = Math.max(0, ladder.indexOf(ladder.rankFor(def.initial())));
        return at < start ? Tone.DANGER : at == start ? Tone.AVAILABLE : Tone.DONE;
    }

    // ==================== the reading page ====================

    /**
     * The reading page of {@code row}: {@code ladder} is that reputation's own
     * ({@link ReputationService#ladderFor}), never the shared one, and {@code gated} is every gated entry this
     * server lists.
     */
    @Nonnull
    public static DetailView detail(@Nonnull Row row, @Nonnull ReputationLadder ladder,
            @Nonnull List<GatedContent.Entry> gated) {
        return detail(row, ladder, gated, 0L);
    }

    /**
     * The reading page of {@code row} for a player with {@code owed} of its Beyond payouts waiting: the Beyond
     * block says how many wait and the action bar carries the gold Collect button ({@link #ACTION_COLLECT}).
     * Nothing waiting, or no Beyond block, shows neither. The player's worn gain shows nowhere.
     */
    @Nonnull
    public static DetailView detail(@Nonnull Row row, @Nonnull ReputationLadder ladder,
            @Nonnull List<GatedContent.Entry> gated, long owed) {
        return detail(row, ladder, gated, owed, 0L);
    }

    /**
     * The reading page of {@code row} for a player with {@code owed} of its Beyond payouts waiting (as the
     * four-argument reading) whose worn gear moves each gain here by {@code gainPercent} whole percent points
     * ({@link ReputationService#wornGain}): a gain that is not 0 adds one last line to How to earn
     * ({@link #gearLine}).
     */
    @Nonnull
    public static DetailView detail(@Nonnull Row row, @Nonnull ReputationLadder ladder,
            @Nonnull List<GatedContent.Entry> gated, long owed, long gainPercent) {
        ReputationDef def = row.reputation();
        ReputationService.Standing standing = row.standing();
        Tone tone = tone(ladder, def, standing.rank());
        Pill rank = Pill.of(ReputationText.line("detail.rank", ReputationText.rankName(def, standing.rank())), tone);
        List<DetailBlock> blocks = new ArrayList<>();
        List<DetailLine> earn = new ArrayList<>(earnLines(def));
        DetailLine gear = gearLine(gainPercent);
        if (gear != null) {
            earn.add(gear);
        }
        blocks.add(new DetailBlock(BLOCK_EARN, ReputationText.line("block.earn"), null, earn));
        List<DetailLine> ranks = rankLines(row, ladder, unlocks(def, ladder, gated));
        if (!ranks.isEmpty()) {
            blocks.add(new DetailBlock(BLOCK_RANKS, ReputationText.line("block.ranks"),
                    ReputationText.line("block.ranks.meta"), ranks));
        }
        DetailBlock beyond = beyondBlock(row, ladder, owed);
        if (beyond != null) {
            blocks.add(beyond);
        }
        blocks.add(new DetailBlock(BLOCK_STANDING, ReputationText.line("block.standing"), null,
                standingLines(standing)));
        List<DetailAction> actions = beyond != null && owed > 0 ? List.of(collectAction()) : List.of();
        return new DetailView(picture(def), ReputationText.name(def), null, null, List.of(rank), null, bar(row),
                progressLabel(row), ReputationText.description(def), blocks, actions, null);
    }

    /** The gold Collect button that pays the Beyond payouts waiting. */
    @Nonnull
    static DetailAction collectAction() {
        return new DetailAction(ActionSlot.PRIMARY, ReputationText.line("beyond.collect"), ActionLook.COLLECT,
                ACTION_COLLECT, null, true, null);
    }

    /** The words over the detail's bar: "585 / 1,000 toward Regular", toward the next reward, or the top. */
    @Nonnull
    static Message progressLabel(@Nonnull Row row) {
        Progress bar = bar(row);
        if (row.next() != null) {
            return ReputationText.line("detail.standing", bar.current(), bar.total(),
                    ReputationText.rankName(row.reputation(), row.next()));
        }
        if (row.nextReward() != null) {
            return ReputationText.line("detail.standing.reward", bar.current(), bar.total());
        }
        return ReputationText.line("detail.standing.top");
    }

    /**
     * How to earn it: the author's own lines; without any, what the library can see (kills that count), and
     * with nothing at all, the plain fact that helping them out raises it.
     */
    @Nonnull
    static List<DetailLine> earnLines(@Nonnull ReputationDef def) {
        List<DetailLine> out = new ArrayList<>();
        for (String key : def.earnKeys()) {
            out.add(DetailLine.of(Picture.NONE, ContentKeys.tr(key)));
        }
        if (!out.isEmpty()) {
            return out;
        }
        if (!def.kills().isEmpty()) {
            out.add(DetailLine.of(Picture.NONE, ReputationText.line("earn.kills")));
        }
        if (out.isEmpty()) {
            out.add(DetailLine.of(Picture.NONE, ReputationText.line("earn.default")));
        }
        return out;
    }

    /**
     * How much the player's worn gear moves each gain, as How to earn's last line: "raises by 10%" for a
     * positive {@code gainPercent}, "lowers by 10%" for a negative one (the number always its size); null at 0.
     */
    @Nullable
    static DetailLine gearLine(long gainPercent) {
        if (gainPercent == 0L) {
            return null;
        }
        String key = gainPercent > 0L ? "earn.gear.more" : "earn.gear.less";
        return DetailLine.of(Picture.NONE, ReputationText.line(key, Math.abs(gainPercent)));
    }

    /**
     * The ladder from where this reputation starts (or lower, where the player stands below it) to the top,
     * each rank with the standing it takes and a tick (passed, held, ahead), the held one in bright ink beside
     * the kit's current-step dot; under each rank, what it opens, marked once the player holds it.
     */
    @Nonnull
    static List<DetailLine> rankLines(@Nonnull Row row, @Nonnull ReputationLadder ladder,
            @Nonnull List<Unlock> unlocks) {
        ReputationDef def = row.reputation();
        List<ReputationLadder.Rank> ranks = ladder.ranks();
        int held = ladder.indexOf(row.standing().rank());
        if (ranks.isEmpty() || held < 0) {
            return List.of();
        }
        int start = Math.max(0, ladder.indexOf(ladder.rankFor(def.initial())));
        Map<ReputationLadder.Rank, List<Unlock>> byRank = new LinkedHashMap<>();
        for (Unlock unlock : unlocks) {
            byRank.computeIfAbsent(unlock.rank(), r -> new ArrayList<>()).add(unlock);
        }
        List<DetailLine> out = new ArrayList<>();
        for (int i = Math.min(start, held); i < ranks.size(); i++) {
            ReputationLadder.Rank rank = ranks.get(i);
            boolean current = i == held;
            Tick tick = i < held ? Tick.DONE : current ? Tick.CURRENT : Tick.AHEAD;
            out.add(new DetailLine(Picture.NONE, ReputationText.rankName(def, rank), Msg.num(rank.min()), null,
                    tick, null, current));
            for (Unlock unlock : byRank.getOrDefault(rank, List.of())) {
                out.add(unlockLine(unlock, i <= held));
            }
        }
        return out;
    }

    /**
     * One thing a rank opens: its picture (an item handed over keeps its own tooltip; a stand-in draws plain),
     * its name and where it is found.
     */
    @Nonnull
    static DetailLine unlockLine(@Nonnull Unlock unlock, boolean open) {
        GatedContent.Entry entry = unlock.entry();
        Message text = entry.place() == null ? entry.name()
                : ReputationText.line("unlock.at", entry.name(), entry.place());
        Picture picture = entry.iconItemId() == null ? Picture.NONE
                : entry.showsItem() ? Picture.tooltipItem(entry.iconItemId()) : Picture.item(entry.iconItemId());
        Pill tag = open ? Pill.of(ReputationText.line("unlock.open"), Tone.DONE) : null;
        return new DetailLine(picture, text, null, tag, Tick.NONE, null, false);
    }

    /**
     * What each gated entry asks of this reputation: an entry opened by holding one of its ranks (the rank
     * reading) or by a standing (filed under the rank that standing falls in), at its lowest such rank when it
     * names several. Entries that ask nothing of this reputation are left out.
     */
    @Nonnull
    public static List<Unlock> unlocks(@Nonnull ReputationDef def, @Nonnull ReputationLadder ladder,
            @Nonnull List<GatedContent.Entry> gated) {
        List<Unlock> out = new ArrayList<>();
        if (!ladder.usable()) {
            return out;
        }
        for (GatedContent.Entry entry : gated) {
            ReputationLadder.Rank lowest = null;
            for (FactorCondition factor : GatedContent.positiveFactors(entry.requires())) {
                ReputationLadder.Rank rank = rankAsked(def, ladder, factor);
                if (rank != null && (lowest == null || rank.min() < lowest.min())) {
                    lowest = rank;
                }
            }
            if (lowest != null) {
                out.add(new Unlock(lowest, entry));
            }
        }
        return out;
    }

    /** The rank of {@code def} that {@code factor} asks a player to reach, or null when it asks none. */
    @Nullable
    static ReputationLadder.Rank rankAsked(@Nonnull ReputationDef def, @Nonnull ReputationLadder ladder,
            @Nonnull FactorCondition factor) {
        String id = factor.getFactor() == null ? "" : factor.getFactor().trim();
        String param = factor.getParam() == null ? "" : factor.getParam().trim();
        Double min = factor.getMin();
        if (min == null || min <= 0) {
            return null;
        }
        if (id.equalsIgnoreCase(ReputationFactors.RANK)) {
            int slash = param.lastIndexOf('/');
            if (slash <= 0 || !param.substring(0, slash).trim().equalsIgnoreCase(def.id())) {
                return null;
            }
            return ladder.byId(param.substring(slash + 1));
        }
        if (id.equalsIgnoreCase(ReputationFactors.STANDING) && param.equalsIgnoreCase(def.id())) {
            return ladder.rankFor((long) Math.ceil(min));
        }
        return null;
    }

    /**
     * Past the top: every so much earned pays out, what it pays, where the next one lands, and, with
     * {@code owed} of them waiting, how many wait to be collected.
     */
    @Nullable
    static DetailBlock beyondBlock(@Nonnull Row row, @Nonnull ReputationLadder ladder, long owed) {
        ReputationDef def = row.reputation();
        ReputationLadder.Rank top = ladder.top();
        if (top == null || !def.hasBeyond()) {
            return null;
        }
        List<DetailLine> lines = new ArrayList<>();
        // A reward nothing on this server can name is left out; the block still says how often it pays.
        for (RewardChip chip : chips(def)) {
            lines.add(DetailLine.reward(chip, null));
        }
        long next = row.nextReward() != null ? row.nextReward()
                : ReputationLadder.nextRewardAt(row.standing().earned(), top.min(), def.beyondEvery());
        lines.add(DetailLine.of(Picture.NONE, ReputationText.line("beyond.next", next)));
        if (owed > 0) {
            lines.add(DetailLine.of(Picture.NONE, ReputationText.line("beyond.waiting", owed)));
        }
        return new DetailBlock(BLOCK_BEYOND, ReputationText.line("block.beyond", ReputationText.rankName(def, top)),
                ReputationText.line("block.beyond.meta", def.beyondEvery()), lines);
    }

    /** The Beyond rewards as chips; a reading that fails costs the chips, never the page. */
    @Nonnull
    private static List<RewardChip> chips(@Nonnull ReputationDef def) {
        try {
            return RewardChips.chipsFor(def.beyondRewards(), null);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the page could not name " + def.id() + "'s Beyond rewards", t);
            return List.of();
        }
    }

    /** The numbers behind the standing: earned, and the most earning can reach. */
    @Nonnull
    static List<DetailLine> standingLines(@Nonnull ReputationService.Standing standing) {
        List<DetailLine> out = new ArrayList<>();
        out.add(count("standing.earned", Msg.num(standing.earned())));
        Integer cap = standing.reputation().cap();
        if (cap != null) {
            out.add(count("standing.cap", Msg.num(cap)));
        }
        return out;
    }

    @Nonnull
    private static DetailLine count(@Nonnull String key, @Nonnull Message count) {
        return new DetailLine(Picture.NONE, ReputationText.line(key), count, null, Tick.NONE, null, false);
    }

    /** What a player who has met nobody sees in place of the reading page. */
    @Nonnull
    public static EmptyState empty() {
        return new EmptyState(Picture.item(ReputationMenuTab.ICON_ITEM), ReputationText.line("page.empty.title"),
                ReputationText.line("page.empty"), null);
    }

    @Nonnull
    private static Picture picture(@Nonnull ReputationDef def) {
        return def.icon() == null ? Picture.NONE : Picture.item(def.icon());
    }
}
