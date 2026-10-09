package com.ziggfreed.common.objectives.book.achievement;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.achievement.asset.AchievementCategoryConfig;
import com.ziggfreed.common.calendar.CalendarRuntime;
import com.ziggfreed.common.calendar.asset.CalendarEventAsset;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.book.BookActions;
import com.ziggfreed.common.objectives.book.ObjectiveBookDeps;
import com.ziggfreed.common.objectives.book.ObjectiveBookDeps.MilestoneView;
import com.ziggfreed.common.objectives.book.SeenMarks;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.occurrence.Occurrence;
import com.ziggfreed.common.occurrence.OccurrenceSource;
import com.ziggfreed.common.progress.CategoryNames;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.asset.ContentListingAsset.ChainMembership;
import com.ziggfreed.common.progress.runtime.ProgressionIcons;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.UiText;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.CollectionTile;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.DetailBlock;
import com.ziggfreed.common.ui.kit.DetailLine;
import com.ziggfreed.common.ui.kit.DetailToggle;
import com.ziggfreed.common.ui.kit.DetailView;
import com.ziggfreed.common.ui.kit.KitText;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.Progress;
import com.ziggfreed.common.ui.kit.Tick;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.util.SafeLog;

/**
 * One player's achievements read into the kit's records: a list row, a compact row, the page, the Browse list and
 * the Overview. Pure: it reads the engine, the deps' guarded seams, the calendar it is handed and the category
 * files, and paints nothing, so every rule is pinned by a test with no page. One reader serves one answer to one
 * event (it holds the clock reading it was made with and remembers what it has read).
 *
 * <p>The rules it keeps from the book before it: what a player earned is listed whatever its circulation
 * ({@link AchievementShelves}); Pin is offered only where {@link AchievementPinOffer} says; feats count on their
 * own shelf, never in the earned number; every category and subcategory is named through {@code CategoryNames}.
 */
public final class AchievementReader {

    /** The Overview's Recently earned strip. */
    public static final int RECENT = 5;

    /** The Overview's Nearly there strip. */
    public static final int NEARLY = 5;

    /** How many siblings the page's More in block lists. */
    static final int MORE_IN = 4;

    /** The lang domain every key here is read under ({@code ziggfreedcommon.progression.*}). */
    private static final String PREFIX = "ziggfreedcommon.";
    private static final String DOMAIN = "progression.";

    /** The glue between the parts of a meta line ("Tier 2 of 3  -  4 / 20"); punctuation, never words. */
    private static final String META_GLUE = "  -  ";

    private static final long DAY_MS = 86_400_000L;

    private final AchievementEngine engine;
    private final Subject subject;
    private final ObjectiveBookDeps deps;
    @Nullable
    private final UUID viewer;
    private final OccurrenceSource calendar;
    private final SeenMarks seen;
    private final long nowMs;
    private final Function<String, AchievementCategoryAsset> categories;

    /** What this reader already read, so one answer never asks twice. */
    private List<String> pins;
    private final Map<String, String> flatNames = new HashMap<>();
    private final Map<String, List<Achievement>> ladders = new HashMap<>();

    private AchievementReader(@Nonnull AchievementEngine engine, @Nonnull Subject subject,
            @Nonnull ObjectiveBookDeps deps, @Nullable UUID viewer, @Nonnull OccurrenceSource calendar,
            @Nonnull SeenMarks seen, long nowMs, @Nonnull Function<String, AchievementCategoryAsset> categories) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.deps = deps == null ? ObjectiveBookDeps.DEFAULTS : deps;
        this.viewer = viewer;
        this.calendar = calendar == null ? OccurrenceSource.NONE : calendar;
        this.seen = seen == null ? SeenMarks.NONE : seen;
        this.nowMs = nowMs;
        this.categories = categories;
    }

    /**
     * A reader for {@code subject} on {@code engine}. {@code viewer} is who reads a server-first claim
     * ({@code ObjectiveBookDeps.firstClaims}); {@code calendar} answers "On now" and "Ends in" (pass
     * {@code Occurrences.source()}); {@code seen} answers a tile's "new" mark ({@code SeenMarks.NONE} until marks
     * are kept); {@code nowMs} is the clock reading every answer uses.
     */
    @Nonnull
    public static AchievementReader of(@Nonnull AchievementEngine engine, @Nonnull Subject subject,
            @Nonnull ObjectiveBookDeps deps, @Nullable UUID viewer, @Nonnull OccurrenceSource calendar,
            @Nonnull SeenMarks seen, long nowMs) {
        return of(engine, subject, deps, viewer, calendar, seen, nowMs,
                AchievementCategoryConfig.getInstance()::category);
    }

    /** {@link #of} reading category files from {@code categories} (a test's own). */
    @Nonnull
    static AchievementReader of(@Nonnull AchievementEngine engine, @Nonnull Subject subject,
            @Nonnull ObjectiveBookDeps deps, @Nullable UUID viewer, @Nonnull OccurrenceSource calendar,
            @Nonnull SeenMarks seen, long nowMs, @Nonnull Function<String, AchievementCategoryAsset> categories) {
        return new AchievementReader(engine, subject, deps, viewer, calendar, seen, nowMs, categories);
    }

    /** The achievement with this id, or null. */
    @Nullable
    public Achievement achievement(@Nullable String id) {
        return engine.achievement(id);
    }

    // ==================== rows ====================

    /**
     * A list row: tone accent and state word (In progress, Done, Collect, Feat), picture (its own, else its
     * category's), title, meta ("0 / 50", "2 / 3 steps", "Tier 3 of 5", "Earned on 2026-10-01", "Ends in 5
     * days"), points as the value, the pin mark, the bar while in progress; finished rows read faint.
     */
    @Nonnull
    public LedgerRow row(@Nonnull Achievement a) {
        return row(a, false, null);
    }

    /** {@link #row} for a compact strip: no meta line. */
    @Nonnull
    public LedgerRow compactRow(@Nonnull Achievement a) {
        return row(a, true, null);
    }

    /** A compact row whose value is the day it was earned (the Overview's Recently earned strip). */
    @Nonnull
    LedgerRow datedRow(@Nonnull Achievement a) {
        return row(a, true, earnedDate(a));
    }

    @Nonnull
    private LedgerRow row(@Nonnull Achievement a, boolean compact, @Nullable Message value) {
        boolean unlocked = unlocked(a);
        boolean waiting = waiting(a);
        boolean feat = a.featOfStrength();
        Tone tone;
        Message state;
        if (waiting) {
            tone = Tone.COLLECT;
            state = text("book.achievements.state.collect");
        } else if (unlocked) {
            tone = Tone.DONE;
            state = text(feat ? "book.achievements.state.feat" : "book.achievements.state.done");
        } else {
            tone = Tone.ACTIVE;
            state = text("book.achievements.state.progress");
        }
        Aggregate aggregate = aggregate(a);
        Message shown = value != null ? value : feat ? null : text("book.achievements.row.points", a.points());
        return new LedgerRow(a.id(), name(a), compact ? null : meta(a, aggregate), picture(a), tone, state, shown,
                unlocked ? null : aggregate.progress(), pinned(a) ? Mark.PINNED : Mark.NONE, unlocked && !waiting);
    }

    /** The row's meta: where it stands on its ladder, its count or the day it was earned, and when it ends. */
    @Nullable
    private Message meta(@Nonnull Achievement a, @Nonnull Aggregate aggregate) {
        List<Message> parts = new ArrayList<>(3);
        Message tier = tierLine(a);
        if (tier != null) {
            parts.add(tier);
        }
        if (unlocked(a)) {
            Message earned = earnedLine(a);
            if (earned != null) {
                parts.add(earned);
            }
        } else {
            parts.add(aggregate.words(this));
        }
        Message ends = endsIn(a);
        if (ends != null) {
            parts.add(ends);
        }
        return glue(parts);
    }

    // ==================== the page ====================

    /**
     * The page: header (picture, title, breadcrumb and points, the claimant line, Server first / Seasonal / Feat
     * since pills, the Pin toggle where {@code AchievementPinOffer} offers it), progress, description, then the
     * blocks Criteria, Needs, Part of, Ladder, Rewards, More in &lt;subcategory&gt; and the consumer's own; the
     * action bar's Collect when rewards wait; the hint ("Earned on ..." or what is left).
     */
    @Nonnull
    public DetailView page(@Nonnull Achievement a) {
        boolean unlocked = unlocked(a);
        boolean feat = a.featOfStrength();
        Aggregate aggregate = aggregate(a);

        List<Message> metaParts = new ArrayList<>(3);
        metaParts.add(breadcrumb(a));
        if (!feat) {
            metaParts.add(text("book.achievements.page.points", a.points()));
        }
        Message ends = endsIn(a);
        if (ends != null) {
            metaParts.add(ends);
        }

        ObjectiveBookDeps.FirstClaim claim = a.serverFirst() ? deps.claimOfGuarded(a.id(), viewer) : null;
        Message claimant = claim != null && !claim.self()
                ? text("book.achievements.page.claimant", claim.claimantName()) : null;

        List<Pill> badges = new ArrayList<>(3);
        if (a.serverFirst() && (claim == null || claim.self())) {
            badges.add(Pill.of(text("book.achievements.pill.server_first"), Tone.AVAILABLE));
        }
        if (a.occurrence() != null) {
            badges.add(Pill.of(text("book.achievements.pill.seasonal"), liveRun(a) != null ? Tone.LIVE : Tone.BLOCKED));
        }
        if (feat && a.legacySince() != null) {
            badges.add(Pill.of(text("book.achievements.pill.feat_since", a.legacySince()), Tone.DONE));
        }

        DetailToggle toggle = null;
        if (offersPin(a)) {
            boolean pinned = pinned(a);
            toggle = new DetailToggle(text(pinned ? "book.achievements.action.unpin" : "book.achievements.action.pin"),
                    pinned, BookActions.PIN, text("book.tooltip.pin"));
        }

        List<DetailBlock> blocks = new ArrayList<>();
        addBlock(blocks, criteriaBlock(a));
        addBlock(blocks, needsBlock(a));
        addBlock(blocks, partOfBlock(a));
        addBlock(blocks, ladderBlock(a));
        addBlock(blocks, rewardsBlock(a));
        addBlock(blocks, moreInBlock(a));
        blocks.addAll(deps.detailBlocksGuarded(a, subject));

        List<DetailAction> actions = waiting(a)
                ? List.of(new DetailAction(ActionSlot.PRIMARY, text("book.achievements.action.collect"),
                        ActionLook.COLLECT, BookActions.CLAIM, null, true, null))
                : List.of();

        Message hint;
        if (unlocked) {
            hint = earnedLine(a);
        } else {
            long left = aggregate.total() - aggregate.current();
            hint = left > 0 ? text("book.achievements.hint.to_go", left) : null;
        }

        return new DetailView(picture(a), name(a), glue(metaParts), claimant, badges, toggle,
                aggregate.progress(), aggregate.steps() ? aggregate.words(this) : null,
                ProgressionTexts.flavor(a.id()), blocks, actions, hint);
    }

    /** "Seasons > Hallow's Eve", or the category alone, every name through {@code CategoryNames}. */
    @Nonnull
    private Message breadcrumb(@Nonnull Achievement a) {
        String bucket = AchievementGrouping.bucketOf(a);
        Message category = categoryName(bucket);
        String sub = a.subcategory();
        if (bucket.isEmpty() || sub == null || sub.isBlank()) {
            return category;
        }
        return text("book.achievements.page.breadcrumb", category, subcategoryName(bucket, sub));
    }

    /** Criteria: only for an achievement with more than one criterion of its own. */
    @Nullable
    private DetailBlock criteriaBlock(@Nonnull Achievement a) {
        if (a.isMeta() || a.criteria().size() <= 1) {
            return null;
        }
        boolean unlocked = unlocked(a);
        List<DetailLine> lines = new ArrayList<>(a.criteria().size());
        for (int i = 0; i < a.criteria().size(); i++) {
            ObjectiveProgressState state = engine.progressOf(subject, a, i);
            long required = Math.max(1, state.required());
            long current = unlocked ? required : Math.min(required, state.current());
            Message line = ProgressionTexts.objective(a.id(), Integer.toString(i));
            lines.add(new DetailLine(objectivePicture(a, i),
                    line != null ? line : text("book.achievements.criterion.untitled"),
                    KitText.count(current, required), null, current >= required ? Tick.DONE : Tick.AHEAD, null, false));
        }
        return new DetailBlock("criteria", text("book.achievements.block.criteria"), null, lines);
    }

    /**
     * Needs: what a capstone stands on, each line opening a child. A capstone over seasons ({@link #overSeasons})
     * lists each group in its count once ({@link #groupedNeeds}); every other capstone lists the children the
     * player may see ({@link #listedChildren}), counted by the engine's tally when it is grouped.
     */
    @Nullable
    private DetailBlock needsBlock(@Nonnull Achievement a) {
        if (!a.isMeta()) {
            return null;
        }
        if (overSeasons(a)) {
            return groupedNeeds(a);
        }
        List<DetailLine> lines = new ArrayList<>();
        int met = 0;
        for (Achievement child : listedChildren(a)) {
            boolean earned = unlocked(child);
            if (earned) {
                met++;
            }
            lines.add(new DetailLine(picture(child), name(child), earned ? null : aggregate(child).count(),
                    null, earned ? Tick.DONE : Tick.AHEAD, child.id(), false));
        }
        if (lines.isEmpty()) {
            return null;
        }
        Message count = a.metaGroups().isEmpty() ? KitText.count(met, lines.size()) : groupCount(a);
        return new DetailBlock("needs", text("book.achievements.block.needs"), count, lines);
    }

    /**
     * Does {@code a} stand on seasons: is any of its groups keyed by a calendar event's season
     * ({@link Achievement.MetaGroup#seasonKey}) and held by that event's yearly copies (what an {@code AnyYear}
     * selector builds, and the group the engine counts by its event's switch)? A group of one pick, keyed by the
     * pick's own id, is no season, even when that id is an event's.
     */
    private boolean overSeasons(@Nonnull Achievement a) {
        for (Achievement.MetaGroup group : a.metaGroups()) {
            for (String id : group.children()) {
                Achievement child = engine.achievement(id);
                Achievement.Occurrence occurrence = child == null ? null : child.occurrence();
                if (occurrence != null
                        && Achievement.MetaGroup.seasonKey(occurrence.eventId()).equals(group.key())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The Needs of a capstone over seasons only, its lines matching its count: one line per group the engine
     * counts (a season switched off has none, as it has no place in the count; its keepsakes stay earned in the
     * book), showing the group's {@link #groupFace}, ticked when any of its children is earned, and counted by the
     * engine's tally.
     */
    @Nullable
    private DetailBlock groupedNeeds(@Nonnull Achievement a) {
        List<DetailLine> lines = new ArrayList<>();
        for (Achievement.MetaGroup group : a.metaGroups()) {
            if (!group.isCounted()) {
                continue;
            }
            Achievement face = groupFace(group);
            if (face == null) {
                continue;
            }
            boolean earned = anyEarned(group);
            lines.add(new DetailLine(picture(face), name(face), earned ? null : aggregate(face).count(),
                    null, earned ? Tick.DONE : Tick.AHEAD, face.id(), false));
        }
        if (lines.isEmpty()) {
            return null;
        }
        return new DetailBlock("needs", text("book.achievements.block.needs"), groupCount(a), lines);
    }

    /** A grouped capstone's count: groups earned of groups needed, the engine's own tally, full once earned. */
    @Nonnull
    private Message groupCount(@Nonnull Achievement a) {
        AchievementEngine.CriterionTally tally = engine.tally(subject, a);
        return KitText.count(unlocked(a) ? tally.total() : tally.completed(), tally.total());
    }

    /**
     * The child a group's Needs line shows: its newest the player may see
     * ({@link AchievementShelves#listsAsCapstoneChild}; newest by the year a copy was minted for, the first by id
     * among equals); else, for a season between runs with nothing of it earned, its newest yearly copy that is not
     * hidden and whose year has come ({@link #yearHasCome}), so the season keeps its line. Null when nothing may
     * show (hidden or retired children nobody earned, or the calendar cannot say the season's year).
     */
    @Nullable
    private Achievement groupFace(@Nonnull Achievement.MetaGroup group) {
        Achievement seen = null;
        Achievement coming = null;
        for (String id : group.children()) {
            Achievement child = engine.achievement(id);
            if (child == null) {
                continue;
            }
            if (AchievementShelves.listsAsCapstoneChild(child.available(), child.hidden(), unlocked(child))) {
                if (seen == null || yearOf(child) > yearOf(seen)) {
                    seen = child;
                }
            } else if (yearHasCome(child) && (coming == null || yearOf(child) > yearOf(coming))) {
                coming = child;
            }
        }
        return seen != null ? seen : coming;
    }

    /** Has any child of {@code group} been earned? Any year's copy earns its season. */
    private boolean anyEarned(@Nonnull Achievement.MetaGroup group) {
        for (String id : group.children()) {
            if (engine.status(subject, id).isUnlocked()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Is {@code a} a yearly copy, not hidden, whose year its event has reached ({@code OccurrenceSource.currentYear},
     * the year a copy is minted for; the next year's copy is minted ahead)? A calendar that cannot say (no year, or
     * a read that throws) reads no, since the newest copy may be next year's.
     */
    private boolean yearHasCome(@Nonnull Achievement a) {
        Achievement.Occurrence occurrence = a.occurrence();
        if (occurrence == null || a.hidden()) {
            return false;
        }
        Integer current = currentYear(occurrence.eventId());
        return current != null && occurrence.year() <= current;
    }

    /** The year a copy was minted for, 0 for an ordinary achievement. */
    private static int yearOf(@Nonnull Achievement a) {
        Achievement.Occurrence occurrence = a.occurrence();
        return occurrence == null ? 0 : occurrence.year();
    }

    /** Part of: the capstones this one feeds. */
    @Nullable
    private DetailBlock partOfBlock(@Nonnull Achievement a) {
        List<DetailLine> lines = new ArrayList<>();
        for (Achievement parent : sorted(engine.achievements())) {
            if (!parent.isMeta() || !names(parent, a.id())) {
                continue;
            }
            boolean earned = unlocked(parent);
            if (!AchievementShelves.listsAsCapstone(parent.available(), earned)) {
                continue;
            }
            lines.add(new DetailLine(picture(parent), name(parent), earned ? null : aggregate(parent).count(),
                    null, earned ? Tick.DONE : Tick.CURRENT, parent.id(), false));
        }
        return lines.isEmpty() ? null : new DetailBlock("part_of", text("book.achievements.block.part_of"), null, lines);
    }

    /** Ladder: every rung of its primary ladder, the one being read marked current. */
    @Nullable
    private DetailBlock ladderBlock(@Nonnull Achievement a) {
        List<Achievement> tiers = ladder(a);
        if (tiers.size() < 2) {
            return null;
        }
        List<DetailLine> lines = new ArrayList<>(tiers.size());
        int position = 0;
        for (int i = 0; i < tiers.size(); i++) {
            Achievement tier = tiers.get(i);
            boolean self = tier.id().equals(a.id());
            if (self) {
                position = i + 1;
            }
            Tick tick = unlocked(tier) ? Tick.DONE : self ? Tick.CURRENT : Tick.AHEAD;
            lines.add(new DetailLine(picture(tier), name(tier), text("book.achievements.tier_short", i + 1), null, tick,
                    self ? null : tier.id(), self));
        }
        return new DetailBlock("ladder", text("book.achievements.block.ladder"),
                position > 0 ? text("book.achievements.tier", position, tiers.size()) : null, lines);
    }

    /** Rewards: what it pays on earning and on collecting, each with its state. */
    @Nullable
    private DetailBlock rewardsBlock(@Nonnull Achievement a) {
        boolean unlocked = unlocked(a);
        boolean claimed = engine.status(subject, a.id()) == AchievementStatus.CLAIMED;
        List<DetailLine> lines = new ArrayList<>();
        for (RewardChip chip : chips(a.autoRewards())) {
            lines.add(rewardLine(chip, unlocked
                    ? Pill.of(text("book.achievements.reward.auto"), Tone.DONE)
                    : Pill.of(text("book.achievements.reward.locked"), Tone.BLOCKED)));
        }
        for (RewardChip chip : chips(a.claimRewards())) {
            Pill tag;
            if (claimed) {
                tag = Pill.of(text("book.achievements.reward.collected"), Tone.DONE);
            } else if (unlocked) {
                tag = Pill.of(text("book.achievements.reward.waiting"), Tone.COLLECT);
            } else {
                tag = Pill.of(text("book.achievements.reward.locked"), Tone.BLOCKED);
            }
            lines.add(rewardLine(chip, tag));
        }
        return lines.isEmpty() ? null : new DetailBlock("rewards", text("book.achievements.block.rewards"), null, lines);
    }

    @Nonnull
    private static DetailLine rewardLine(@Nonnull RewardChip chip, @Nonnull Pill tag) {
        Picture picture = chip.hasIcon() ? Picture.tooltipItem(chip.iconItemId()) : Picture.NONE;
        return new DetailLine(picture, chip.label(), null, tag, Tick.NONE, null, false);
    }

    /** More in: up to {@link #MORE_IN} listed siblings in its subcategory (its category when it has none). */
    @Nullable
    private DetailBlock moreInBlock(@Nonnull Achievement a) {
        String bucket = AchievementGrouping.bucketOf(a);
        String sub = blankToNull(a.subcategory());
        String chain = chainId(a);
        List<Achievement> siblings = new ArrayList<>();
        for (Achievement other : engine.achievements()) {
            if (other.id().equals(a.id()) || !bucket.equals(AchievementGrouping.bucketOf(other))
                    || !Objects.equals(sub, blankToNull(other.subcategory()))
                    || (chain != null && chain.equalsIgnoreCase(chainId(other)))
                    || shelf(other) != AchievementShelves.Shelf.BROWSE) {
                continue;
            }
            siblings.add(other);
        }
        siblings = collapse(siblings);
        if (siblings.isEmpty()) {
            return null;
        }
        siblings.sort(defaultOrder());
        List<DetailLine> lines = new ArrayList<>(MORE_IN);
        for (Achievement sibling : siblings.subList(0, Math.min(MORE_IN, siblings.size()))) {
            boolean earned = unlocked(sibling);
            lines.add(new DetailLine(picture(sibling), name(sibling), earned ? null : aggregate(sibling).count(), null,
                    earned ? Tick.DONE : Tick.NONE, sibling.id(), false));
        }
        Message group = sub == null || bucket.isEmpty() ? categoryName(bucket) : subcategoryName(bucket, sub);
        return new DetailBlock("more_in", text("book.achievements.block.more_in", group), null, lines);
    }

    private static void addBlock(@Nonnull List<DetailBlock> blocks, @Nullable DetailBlock block) {
        if (block != null) {
            blocks.add(block);
        }
    }

    // ==================== lists ====================

    /**
     * The Browse list for {@code filter}: Pinned (when any), one section per subcategory (a category chosen) or
     * per category (all), then Feats of Strength; ladders collapse to the climbing rung (pinned rungs stay); a
     * search bypasses the collapse and opens every section; each section caps at 40 before "Show N more". A
     * row id is the achievement's id and is unique across the list.
     */
    @Nonnull
    public LedgerModel browse(@Nonnull BrowseFilter filter) {
        return AchievementBrowse.build(this, filter == null ? BrowseFilter.NONE : filter);
    }

    /** {@link #overview(int, int, List)} with no milestone ladder (the card hides). */
    @Nonnull
    public AchievementOverview.Overview overview(int recent, int nearly) {
        return overview(recent, nearly, List.of());
    }

    /**
     * The Overview, its milestone card from {@code ladder} (the deps' {@code milestonesGuarded(store, ref,
     * subject)}, ascending by threshold; empty hides the card).
     */
    @Nonnull
    public AchievementOverview.Overview overview(int recent, int nearly, @Nonnull List<MilestoneView> ladder) {
        return AchievementOverview.build(this, recent, nearly, ladder == null ? List.of() : ladder);
    }

    /**
     * The category tiles alone (the Overview's {@code tiles}), for a category dropdown: one per category with
     * anything to browse, in taxonomy order, then the Feats tile ({@link AchievementOverview#FEATS_TILE}) when
     * any feat is earned. A tile's {@code progress} holds its earned and total.
     */
    @Nonnull
    public List<CollectionTile> tiles() {
        return AchievementOverview.tiles(this);
    }

    /** Whether the page offers Pin for {@code a} ({@code AchievementPinOffer}). */
    public boolean offersPin(@Nonnull Achievement a) {
        return AchievementPinOffer.offersPin(a.featOfStrength(), pinned(a), engine.pinnable(subject, a.id()));
    }

    /** Whether {@code a} is earned with rewards still to collect (the page's Collect). */
    public boolean waiting(@Nonnull Achievement a) {
        return engine.status(subject, a.id()) == AchievementStatus.UNLOCKED && a.requiresClaim();
    }

    // ==================== shared readings (package: the browse and overview rules read them) ====================

    @Nonnull
    AchievementEngine engine() {
        return engine;
    }

    @Nonnull
    Subject subject() {
        return subject;
    }

    @Nonnull
    ObjectiveBookDeps deps() {
        return deps;
    }

    @Nullable
    UUID viewer() {
        return viewer;
    }

    long nowMs() {
        return nowMs;
    }

    /** A key under the book's lang domain. */
    @Nonnull
    Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(PREFIX, DOMAIN + key, args);
    }

    boolean unlocked(@Nonnull Achievement a) {
        return engine.status(subject, a.id()).isUnlocked();
    }

    /** The subject's pins, oldest first. */
    @Nonnull
    List<String> pins() {
        if (pins == null) {
            pins = engine.pinned(subject);
        }
        return pins;
    }

    boolean pinned(@Nonnull Achievement a) {
        return pins().contains(a.id());
    }

    /** Where {@code a} is listed for this subject ({@link AchievementShelves}), asking sight last. */
    @Nonnull
    AchievementShelves.Shelf shelf(@Nonnull Achievement a) {
        return AchievementShelves.shelfOf(a.available(), a.featOfStrength(), unlocked(a),
                () -> engine.isVisible(subject, a));
    }

    @Nonnull
    Message name(@Nonnull Achievement a) {
        return ProgressionTexts.titleOrUntitled(a.id());
    }

    /** The name as plain text, for a sort or a search haystack (a String-only use). */
    @Nonnull
    String flatName(@Nonnull Achievement a) {
        return flatNames.computeIfAbsent(a.id(), id -> UiText.flatten(name(a)));
    }

    /** Its own picture, else the folded definition's, else its category's. */
    @Nonnull
    Picture picture(@Nonnull Achievement a) {
        String icon = a.icon();
        if (icon == null || icon.isBlank()) {
            icon = ProgressionDefaults.achievementIcon(a.id());
        }
        AchievementCategoryAsset asset = category(AchievementGrouping.bucketOf(a));
        Picture fallback = asset == null ? Picture.NONE : Picture.item(asset.getIcon());
        return Picture.item(icon).or(fallback);
    }

    @Nonnull
    private Picture objectivePicture(@Nonnull Achievement a, int index) {
        try {
            IconSpec icon = ProgressionIcons.forObjective(a.id(), a.criteria().get(index));
            return icon == null || icon.isEmpty() ? Picture.NONE : new Picture(icon.itemId(), icon.texturePath());
        } catch (Throwable t) {
            SafeLog.fine("[progression] a criterion picture could not be read for " + a.id(), t);
            return Picture.NONE;
        }
    }

    /** The category file for {@code bucket}, or null; a lookup that throws reads as none. */
    @Nullable
    AchievementCategoryAsset category(@Nonnull String bucket) {
        if (bucket.isEmpty()) {
            return null;
        }
        try {
            return categories.apply(bucket);
        } catch (Throwable t) {
            SafeLog.fine("[progression] the achievement category '" + bucket + "' could not be read", t);
            return null;
        }
    }

    /** Where {@code bucket} reads among the categories: its file's order, then after every described one. */
    int rank(@Nonnull String bucket) {
        AchievementCategoryAsset asset = category(bucket);
        return AchievementGrouping.rankOf(bucket, asset == null ? Integer.MAX_VALUE : asset.orderOrLast());
    }

    /** A category's name; content with no category reads as the page's own "Other". */
    @Nonnull
    Message categoryName(@Nonnull String bucket) {
        if (bucket.isEmpty()) {
            return text("book.achievements.category.uncategorised");
        }
        return CategoryNames.achievementCategory(bucket, category(bucket));
    }

    /** A subcategory's name; one filed under a calendar event falls back to that event's own name. */
    @Nonnull
    Message subcategoryName(@Nonnull String bucket, @Nonnull String sub) {
        AchievementCategoryAsset asset = category(bucket);
        String fallbackKey = asset != null && asset.isSubcategoryEvents() ? eventTitleKey(asset.eventFor(sub)) : null;
        return CategoryNames.achievementSubcategory(bucket, sub, asset, fallbackKey);
    }

    /** The calendar title key of {@code eventId}, or null when no event file names one. */
    @Nullable
    private static String eventTitleKey(@Nullable String eventId) {
        if (eventId == null) {
            return null;
        }
        try {
            CalendarEventAsset event = CalendarRuntime.service().event(eventId);
            CalendarEventAsset.Presentation presentation = event == null ? null : event.presentation();
            return presentation == null ? null : presentation.titleKey();
        } catch (Throwable t) {
            SafeLog.fine("[progression] the calendar title for '" + eventId + "' could not be read", t);
            return null;
        }
    }

    /** The year {@code eventId} is in now ({@code OccurrenceSource.currentYear}); null when unknown or unreadable. */
    @Nullable
    private Integer currentYear(@Nonnull String eventId) {
        try {
            return calendar.currentYear(eventId, nowMs);
        } catch (Throwable t) {
            SafeLog.fine("[progression] the calendar's year for '" + eventId + "' could not be read", t);
            return null;
        }
    }

    /** The run of {@code eventId} on now, or null; a calendar that throws reads as nothing on. */
    @Nullable
    Occurrence live(@Nullable String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return null;
        }
        try {
            return calendar.live(eventId, nowMs);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book's calendar read failed: " + t.getMessage());
            return null;
        }
    }

    /** The run a seasonal copy belongs to when it is on now, else null. */
    @Nullable
    private Occurrence liveRun(@Nonnull Achievement a) {
        Achievement.Occurrence occurrence = a.occurrence();
        if (occurrence == null) {
            return null;
        }
        Occurrence run = live(occurrence.eventId());
        return run != null && run.year() == occurrence.year() ? run : null;
    }

    /** "Ends in 5 days" while a seasonal copy's run is on and it is not a feat yet; else null. */
    @Nullable
    private Message endsIn(@Nonnull Achievement a) {
        if (a.featOfStrength()) {
            return null;
        }
        Occurrence run = liveRun(a);
        if (run == null) {
            return null;
        }
        long days = Math.max(1L, (run.endMs() - nowMs + DAY_MS - 1) / DAY_MS);
        return text("book.achievements.meta.ends_in", days);
    }

    /** When {@code subject} last opened {@code bucket}; a mark that throws reads as just now. */
    long seenAt(@Nonnull String bucket) {
        try {
            return seen.seenAt(subject, bucket);
        } catch (Throwable t) {
            SafeLog.fine("[progression] the seen mark for '" + bucket + "' could not be read", t);
            return Long.MAX_VALUE;
        }
    }

    /** "Earned on 2026-10-01", or null for an unlock with no stamp. */
    @Nullable
    private Message earnedLine(@Nonnull Achievement a) {
        Message date = earnedDate(a);
        return date == null ? null : text("book.achievements.hint.earned_on", date);
    }

    /** The day it was earned as an ISO date (locale-neutral data), or null with no stamp. */
    @Nullable
    private Message earnedDate(@Nonnull Achievement a) {
        long at = engine.unlockedAt(subject, a.id());
        if (at <= 0L) {
            return null;
        }
        return Msg.raw(LocalDate.ofInstant(Instant.ofEpochMilli(at), ZoneId.systemDefault()).toString());
    }

    /** "Tier 2 of 3" for a rung of a ladder of two or more; else null. */
    @Nullable
    private Message tierLine(@Nonnull Achievement a) {
        List<Achievement> tiers = ladder(a);
        if (tiers.size() < 2) {
            return null;
        }
        for (int i = 0; i < tiers.size(); i++) {
            if (tiers.get(i).id().equals(a.id())) {
                return text("book.achievements.tier", i + 1, tiers.size());
            }
        }
        return null;
    }

    /** Every rung of {@code a}'s primary ladder, lowest tier first; empty when it is a rung of none. */
    @Nonnull
    List<Achievement> ladder(@Nonnull Achievement a) {
        String chain = chainId(a);
        if (chain == null) {
            return List.of();
        }
        return ladders.computeIfAbsent(chain.toLowerCase(Locale.ROOT), key -> {
            List<Achievement> tiers = new ArrayList<>();
            for (Achievement other : engine.achievements()) {
                if (chain.equalsIgnoreCase(chainId(other))) {
                    tiers.add(other);
                }
            }
            tiers.sort(Comparator.comparingInt(AchievementReader::tierOf).thenComparing(Achievement::id));
            return List.copyOf(tiers);
        });
    }

    @Nullable
    static String chainId(@Nonnull Achievement a) {
        ChainMembership primary = a.primaryChain();
        return primary == null ? null : blankToNull(primary.getId());
    }

    static int tierOf(@Nonnull Achievement a) {
        ChainMembership primary = a.primaryChain();
        return primary == null ? 0 : primary.tierOrZero();
    }

    /**
     * Each ladder in {@code input} collapsed to the rung being climbed (the lowest not yet earned, or the top
     * once all are), pinned rungs kept; everything else passes. Order of first appearance is kept.
     */
    @Nonnull
    List<Achievement> collapse(@Nonnull List<Achievement> input) {
        Map<String, List<Achievement>> byChain = new HashMap<>();
        for (Achievement a : input) {
            String chain = chainId(a);
            if (chain != null) {
                byChain.computeIfAbsent(chain.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(a);
            }
        }
        Map<String, Achievement> climbing = new HashMap<>();
        for (Map.Entry<String, List<Achievement>> entry : byChain.entrySet()) {
            List<Achievement> tiers = new ArrayList<>(entry.getValue());
            tiers.sort(Comparator.comparingInt(AchievementReader::tierOf).thenComparing(Achievement::id));
            Achievement active = null;
            for (Achievement tier : tiers) {
                if (!unlocked(tier)) {
                    active = tier;
                    break;
                }
            }
            climbing.put(entry.getKey(), active != null ? active : tiers.get(tiers.size() - 1));
        }
        List<Achievement> out = new ArrayList<>(input.size());
        for (Achievement a : input) {
            String chain = chainId(a);
            if (chain == null || climbing.get(chain.toLowerCase(Locale.ROOT)) == a || pinned(a)) {
                out.add(a);
            }
        }
        return out;
    }

    /** In progress first, then the authored order, then the name, then the id. */
    @Nonnull
    Comparator<Achievement> defaultOrder() {
        return Comparator.comparingInt((Achievement a) -> unlocked(a) ? 1 : 0)
                .thenComparingInt(Achievement::sortOrder)
                .thenComparing(this::flatName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Achievement::id);
    }

    /** {@code input} by id: a stable reading of the catalogue, whatever order the engine holds it in. */
    @Nonnull
    private List<Achievement> sorted(@Nonnull Iterable<Achievement> input) {
        List<Achievement> out = new ArrayList<>();
        input.forEach(out::add);
        out.sort(Comparator.comparing(Achievement::id));
        return out;
    }

    /**
     * A capstone's children the player may see ({@link AchievementShelves#listsAsCapstoneChild}): the Needs lines
     * of every capstone not over seasons, and a plain capstone's count. A grouped capstone counts by
     * {@code AchievementEngine.tally} (groups, never the copies standing for them), and one over seasons lists
     * each counted group once instead ({@link #groupedNeeds}), since two years of one season are two children here.
     */
    @Nonnull
    List<Achievement> listedChildren(@Nonnull Achievement capstone) {
        List<Achievement> out = new ArrayList<>();
        for (String childId : capstone.metaChildren()) {
            Achievement child = engine.achievement(childId);
            if (child != null && AchievementShelves.listsAsCapstoneChild(child.available(), child.hidden(),
                    unlocked(child))) {
                out.add(child);
            }
        }
        return out;
    }

    private static boolean names(@Nonnull Achievement capstone, @Nonnull String childId) {
        for (String id : capstone.metaChildren()) {
            if (childId.equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    /** The reward chips for {@code rewards} through the consumer's reading; a reading that throws shows none. */
    @Nonnull
    List<RewardChip> chips(@Nonnull List<RewardSpec> rewards) {
        if (rewards.isEmpty()) {
            return List.of();
        }
        try {
            return RewardChips.chipsFor(rewards, deps.rewardChips());
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book's reward reading failed: " + t.getMessage());
            return List.of();
        }
    }

    /** One progress reading per achievement: the bar, the count, the sort, the hint. */
    @Nonnull
    Aggregate aggregate(@Nonnull Achievement a) {
        boolean unlocked = unlocked(a);
        if (a.isMeta()) {
            if (!a.metaGroups().isEmpty()) {
                // Groups, not copies: the engine's tally is what earns it, so it is what the row reads.
                AchievementEngine.CriterionTally tally = engine.tally(subject, a);
                long total = tally.total();
                return new Aggregate(unlocked ? total : Math.min(tally.completed(), total), total, true);
            }
            List<Achievement> children = listedChildren(a);
            long met = 0;
            for (Achievement child : children) {
                if (unlocked(child)) {
                    met++;
                }
            }
            long total = Math.max(1, children.size());
            return new Aggregate(unlocked ? total : Math.min(met, total), total, true);
        }
        if (a.criteria().size() <= 1) {
            ObjectiveProgressState state = engine.progressOf(subject, a, 0);
            long required = Math.max(1, state.required());
            return new Aggregate(unlocked ? required : Math.min(required, state.current()), required, false);
        }
        long total = a.criteria().size();
        long met = 0;
        for (int i = 0; i < a.criteria().size(); i++) {
            ObjectiveProgressState state = engine.progressOf(subject, a, i);
            if (unlocked || (state.required() > 0 && state.current() >= state.required())) {
                met++;
            }
        }
        return new Aggregate(met, total, true);
    }

    /** How far along an achievement is: a count, or steps (several criteria, or a capstone's children). */
    record Aggregate(long current, long total, boolean steps) {

        double fraction() {
            return total <= 0 ? 0d : Math.min(1d, (double) current / total);
        }

        @Nonnull
        Progress progress() {
            return new Progress(current, total);
        }

        /** "3 / 5". */
        @Nonnull
        Message count() {
            return KitText.count(current, total);
        }

        /** "3 / 5", or "2 / 3 steps". */
        @Nonnull
        Message words(@Nonnull AchievementReader reader) {
            return steps ? reader.text("book.achievements.meta.steps", current, total) : count();
        }
    }

    /** {@code parts} glued with {@link #META_GLUE}; null when there are none. */
    @Nullable
    private static Message glue(@Nonnull List<Message> parts) {
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() == 1) {
            return parts.get(0);
        }
        Message[] glued = new Message[parts.size() * 2 - 1];
        for (int i = 0; i < parts.size(); i++) {
            glued[i * 2] = parts.get(i);
            if (i > 0) {
                glued[i * 2 - 1] = Msg.raw(META_GLUE);
            }
        }
        return Msg.cat(glued);
    }

    @Nullable
    static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
