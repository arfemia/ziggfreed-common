package com.ziggfreed.common.objectives.book.achievement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.objectives.book.ObjectiveBookDeps;
import com.ziggfreed.common.objectives.book.ObjectiveBookDeps.MilestoneView;
import com.ziggfreed.common.progress.CategoryNames;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.CollectionTile;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.Pill;
import com.ziggfreed.common.ui.kit.Progress;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.ui.kit.ZigTokens;

/**
 * The Achievements Overview as data: the hero's numbers, the next milestone, the category tiles and the three
 * strips. Built by {@link AchievementReader#overview}; the tab paints it.
 */
public final class AchievementOverview {

    /** The Feats tile's id (no category id starts with {@code *}); a click opens Browse on the Feats status. */
    public static final String FEATS_TILE = "*feats";

    private AchievementOverview() {
    }

    /**
     * The whole overview.
     *
     * @param earned         earned achievements, feats out (the header's rule)
     * @param total          what counts toward the earned number: in circulation or earned, feats out
     * @param points         the subject's points
     * @param completion     earned of total for the hero's bar, null when nothing counts
     * @param rewardsWaiting earned achievements (feats too) with rewards still to collect
     * @param next           the next milestone, or null when the consumer ships no ladder (the card hides)
     * @param tiles          one per category with anything to browse, in taxonomy order, then the Feats tile
     *                       ({@link #FEATS_TILE}) when any feat is earned
     * @param pinned         the live pins, compact rows
     * @param recent         the most recently earned, newest first, compact rows whose value is the date
     * @param nearly         the closest to done, closest first, compact rows
     */
    public record Overview(long earned, long total, long points, @Nullable Progress completion, int rewardsWaiting,
            @Nullable MilestoneCard next, @Nonnull List<CollectionTile> tiles, @Nonnull List<LedgerRow> pinned,
            @Nonnull List<LedgerRow> recent, @Nonnull List<LedgerRow> nearly) {

        public Overview {
            tiles = tiles == null ? List.of() : List.copyOf(tiles);
            pinned = pinned == null ? List.of() : List.copyOf(pinned);
            recent = recent == null ? List.of() : List.copyOf(recent);
            nearly = nearly == null ? List.of() : List.copyOf(nearly);
        }
    }

    /**
     * The next-milestone card, from the consumer's ladder ({@code ObjectiveBookDeps.milestones}).
     *
     * @param threshold    the rung's threshold; with every rung collected, the top one
     * @param title        "1000 points: Seasoned"; with every rung collected, "All milestones collected"
     * @param rewards      the rung's rewards in one caption, or null for none (and once all are collected)
     * @param progress     the subject's points toward {@code threshold}
     * @param claimable    whether Collect would pay now (the tab binds it to {@code claim_milestone})
     * @param allCollected whether every rung is collected
     * @param moreAfter    how many rungs follow this one
     * @param after        "4 more after this", or null when none follow
     * @param thresholds   every rung's threshold, ascending (the milestone track's markers)
     */
    public record MilestoneCard(int threshold, @Nonnull Message title, @Nullable Message rewards,
            @Nonnull Progress progress, boolean claimable, boolean allCollected, int moreAfter,
            @Nullable Message after, @Nonnull List<Integer> thresholds) {

        public MilestoneCard {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(progress, "progress");
            thresholds = thresholds == null ? List.of() : List.copyOf(thresholds);
        }
    }

    /** The card for {@code ladder} (ascending by threshold) at {@code points}; null for no ladder. */
    @Nullable
    static MilestoneCard milestone(@Nonnull AchievementReader reader, @Nonnull List<MilestoneView> ladder,
            long points) {
        List<MilestoneView> rungs = new ArrayList<>();
        for (MilestoneView rung : ladder) {
            if (rung != null) {
                rungs.add(rung);
            }
        }
        if (rungs.isEmpty()) {
            return null;
        }
        rungs.sort(Comparator.comparingInt(MilestoneView::threshold));
        List<Integer> thresholds = new ArrayList<>(rungs.size());
        int next = -1;
        for (int i = 0; i < rungs.size(); i++) {
            thresholds.add(rungs.get(i).threshold());
            if (next < 0 && !rungs.get(i).claimed()) {
                next = i;
            }
        }
        if (next < 0) {
            int top = rungs.get(rungs.size() - 1).threshold();
            return new MilestoneCard(top, reader.text("book.achievements.milestone.all_collected"), null,
                    toward(points, top), false, true, 0, null, thresholds);
        }
        MilestoneView rung = rungs.get(next);
        int moreAfter = rungs.size() - next - 1;
        return new MilestoneCard(rung.threshold(),
                reader.text("book.achievements.milestone.title", rung.threshold(), rung.title()),
                rewardsLine(reader, rung), toward(points, rung.threshold()), rung.claimable(), false, moreAfter,
                moreAfter > 0 ? reader.text("book.achievements.milestone.more_after", moreAfter) : null,
                thresholds);
    }

    /** A rung's rewards as one caption ("250 XP, Boost token"), or null for none. */
    @Nullable
    private static Message rewardsLine(@Nonnull AchievementReader reader, @Nonnull MilestoneView rung) {
        List<RewardChip> chips = reader.chips(rung.rewards());
        if (chips.isEmpty()) {
            return null;
        }
        Message[] parts = new Message[chips.size() * 2 - 1];
        for (int i = 0; i < chips.size(); i++) {
            parts[i * 2] = chips.get(i).label();
            if (i > 0) {
                parts[i * 2 - 1] = Msg.raw(", ");
            }
        }
        return Msg.cat(parts);
    }

    @Nonnull
    private static Progress toward(long points, int threshold) {
        long total = Math.max(1, threshold);
        return new Progress(Math.min(Math.max(0, points), total), total);
    }

    /** The overview for {@code reader}'s subject. */
    @Nonnull
    static Overview build(@Nonnull AchievementReader reader, int recent, int nearly,
            @Nonnull List<MilestoneView> ladder) {
        AchievementEngine engine = reader.engine();
        Subject subject = reader.subject();
        long earned = 0;
        long total = 0;
        int waiting = 0;
        List<Achievement> stamped = new ArrayList<>();
        List<Achievement> candidates = new ArrayList<>();
        for (Achievement a : engine.achievements()) {
            boolean unlocked = reader.unlocked(a);
            if (AchievementShelves.countsAsEarned(a.featOfStrength(), unlocked)) {
                earned++;
            }
            if (AchievementShelves.countsInCategory(a.available(), a.featOfStrength(), unlocked)) {
                total++;
            }
            if (reader.waiting(a)) {
                waiting++;
            }
            if (engine.unlockedAt(subject, a.id()) > 0L) {
                // Earned is earned: a retired one, or a yearly copy whose year is over, stays in Recent.
                stamped.add(a);
            }
            if (nearlyCandidate(reader, a)) {
                candidates.add(a);
            }
        }

        stamped.sort(Comparator.comparingLong((Achievement a) -> engine.unlockedAt(subject, a.id())).reversed()
                .thenComparing(Achievement::id));
        List<LedgerRow> recentRows = new ArrayList<>();
        for (Achievement a : stamped.subList(0, Math.min(Math.max(0, recent), stamped.size()))) {
            recentRows.add(reader.datedRow(a));
        }

        candidates.sort(Comparator.comparingDouble((Achievement a) -> reader.aggregate(a).fraction()).reversed()
                .thenComparing(Achievement::id));
        List<LedgerRow> nearlyRows = new ArrayList<>();
        for (Achievement a : candidates.subList(0, Math.min(Math.max(0, nearly), candidates.size()))) {
            nearlyRows.add(reader.compactRow(a));
        }

        List<LedgerRow> pinnedRows = new ArrayList<>();
        for (String id : reader.pins()) {
            Achievement a = engine.achievement(id);
            if (a != null && reader.shelf(a) != AchievementShelves.Shelf.NONE) {
                pinnedRows.add(reader.compactRow(a));
            }
        }

        long points = engine.points(subject);
        return new Overview(earned, total, points, total > 0 ? new Progress(earned, total) : null, waiting,
                milestone(reader, ladder, points), tiles(reader), pinnedRows, recentRows, nearlyRows);
    }

    /**
     * Worth chasing on Nearly there: in circulation, not a feat, not hidden, not earned, some progress made, and
     * not a server first someone else already won.
     */
    private static boolean nearlyCandidate(@Nonnull AchievementReader reader, @Nonnull Achievement a) {
        if (!a.available() || a.featOfStrength() || a.hidden() || reader.unlocked(a)) {
            return false;
        }
        if (a.serverFirst()) {
            ObjectiveBookDeps.FirstClaim claim = reader.deps().claimOfGuarded(a.id(), reader.viewer());
            if (claim != null && !claim.self()) {
                return false;
            }
        }
        return reader.aggregate(a).fraction() > 0d;
    }

    /**
     * One tile per category with anything to browse (feats out of the counts), in taxonomy order, then the
     * Feats tile when any feat is earned.
     */
    @Nonnull
    static List<CollectionTile> tiles(@Nonnull AchievementReader reader) {
        AchievementEngine engine = reader.engine();
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, List<Achievement>> members = new LinkedHashMap<>();
        long feats = 0;
        for (Achievement a : engine.achievements()) {
            boolean unlocked = reader.unlocked(a);
            if (unlocked && a.featOfStrength()) {
                feats++;
            }
            if (!AchievementShelves.countsInCategory(a.available(), a.featOfStrength(), unlocked)) {
                continue;
            }
            String bucket = AchievementGrouping.bucketOf(a);
            if (bucket.isEmpty()) {
                continue;
            }
            long[] pair = counts.computeIfAbsent(bucket, k -> new long[2]);
            pair[1]++;
            if (unlocked) {
                pair[0]++;
            }
            members.computeIfAbsent(bucket, k -> new ArrayList<>()).add(a);
        }
        List<String> buckets = new ArrayList<>(counts.keySet());
        buckets.sort(Comparator.comparingInt(reader::rank).thenComparing(b -> b));

        List<CollectionTile> tiles = new ArrayList<>(buckets.size() + 1);
        for (String bucket : buckets) {
            long[] pair = counts.get(bucket);
            AchievementCategoryAsset asset = reader.category(bucket);
            boolean onNow = onNow(reader, asset, members.get(bucket));
            tiles.add(new CollectionTile(bucket, reader.categoryName(bucket),
                    reader.text("book.achievements.tile.count", pair[0], pair[1]),
                    asset == null ? Picture.NONE : Picture.item(asset.getIcon()),
                    new Progress(pair[0], pair[1]), pair[1] > 0 && pair[0] >= pair[1],
                    onNow ? Pill.of(reader.text("book.achievements.tile.on_now"), Tone.LIVE) : null,
                    CategoryNames.accent(bucket, asset == null ? null : asset.getAccent()),
                    unseen(reader, bucket, members.get(bucket))));
        }
        if (feats > 0) {
            tiles.add(new CollectionTile(FEATS_TILE, reader.text("book.achievements.tile.feats"),
                    reader.text("book.achievements.tile.feats_count", feats), Picture.NONE, null, false, null,
                    ZigTokens.ACCENT, false));
        }
        return tiles;
    }

    /**
     * Is a category on now? Its own event ({@code Event}) runs, or, when its subcategories are events
     * ({@code SubcategoryEvents}), one of its listed subcategories' events runs.
     */
    private static boolean onNow(@Nonnull AchievementReader reader, @Nullable AchievementCategoryAsset asset,
            @Nullable List<Achievement> members) {
        if (asset == null) {
            return false;
        }
        if (reader.live(asset.getEvent()) != null) {
            return true;
        }
        if (!asset.isSubcategoryEvents() || members == null) {
            return false;
        }
        Set<String> asked = new HashSet<>();
        for (Achievement a : members) {
            String sub = AchievementReader.blankToNull(a.subcategory());
            if (sub != null && asked.add(sub) && reader.live(asset.eventFor(sub)) != null) {
                return true;
            }
        }
        return false;
    }

    /** Was anything in {@code bucket} earned since the subject last opened it ({@code SeenMarks})? */
    private static boolean unseen(@Nonnull AchievementReader reader, @Nonnull String bucket,
            @Nullable List<Achievement> members) {
        if (members == null) {
            return false;
        }
        long seenAt = reader.seenAt(bucket);
        if (seenAt == Long.MAX_VALUE) {
            return false;
        }
        for (Achievement a : members) {
            if (reader.engine().unlockedAt(reader.subject(), a.id()) > seenAt) {
                return true;
            }
        }
        return false;
    }
}
