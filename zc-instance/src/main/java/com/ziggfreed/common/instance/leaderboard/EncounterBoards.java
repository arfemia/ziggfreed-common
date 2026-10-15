package com.ziggfreed.common.instance.leaderboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.run.EncounterLifecycle;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.dungeon.DungeonRuns;

/**
 * Boss fights as Records reads them: which binding row a recorded fight key belongs to ({@link #holds}, the Bosses
 * tab's question) and which split of it ({@link #splits}, its party size). A fight's records are keyed by the bucket
 * string {@link EncounterLeaderboardListener#bucketFor}
 * composes from its binding row ({@code Leaderboard.Bucket}, suffixed with the party size and the run's
 * difficulty when the row splits by them), the key the stat store's encounter stats file each line under;
 * the row says how its bucket is split, the recorded keys which of those splits hold records, and a
 * {@link LeaderboardLayout} whose id is the bucket may name the split labels.
 *
 * <p><b>The keys round-trip.</b> Every tab key is built by the same {@link EncounterLeaderboardListener#bucketFor},
 * so a tab reads exactly the lines a defeat writes. A key written under another split of the same bucket
 * (the row's knobs changed since) is left out, so a tab never shows a half-read key.
 *
 * <p><b>Tab labels.</b> An unsplit fight's tab is its name (the binding row's {@code NameKey}), a bare
 * translation on a {@code TextButton}. A split one joins its name and the split's recorded value (the party
 * size as a typed number, the difficulty's label as text, or a layout's name for either), so it is a
 * {@link LeaderboardBucketTab#withSpans} tab, painted unflattened on a rich button's {@code .TextSpans} and
 * read in each player's own language. A fight fought inside a dungeon area is filed under its run's source
 * ({@code <key>@dungeon:<id>}) and gets a tab of its own, naming the dungeon after the fight
 * ({@code DungeonRuns.fightIn}), a {@code withSpans} tab too.
 */
public final class EncounterBoards {

    /** One recorded split of a fight's bucket: its party size and its difficulty label, null where the row does not split. */
    public record Split(@Nullable Integer partySize, @Nullable String difficulty) {
    }

    private static final Comparator<Split> ORDER = Comparator
            .comparing(Split::partySize, Comparator.nullsFirst(Comparator.<Integer>naturalOrder()))
            .thenComparing(Split::difficulty, Comparator.nullsFirst(Comparator.<String>naturalOrder()));

    private EncounterBoards() {
    }

    /**
     * The splits {@code keys} hold records for under {@code group}'s bucket, party sizes ascending, then
     * labels A to Z. A key written under another split of the same bucket (the row's knobs changed since) is
     * left out, so a tab never shows a half-read key; so is another bucket's.
     */
    @Nonnull
    public static List<Split> splits(@Nonnull Collection<String> keys, @Nonnull EncounterBindingAsset.Leaderboard group) {
        String bucket = group.getBucket();
        if (bucket == null) {
            return List.of();
        }
        Set<Split> found = new TreeSet<>(ORDER);
        for (String key : keys) {
            Split split = parse(key, bucket.trim(), group.byPartySize(), group.byDifficulty());
            if (split != null) {
                found.add(split);
            }
        }
        return List.copyOf(found);
    }

    /**
     * One tab per fight key {@code recorded} holds, the binding rows by id and each row's splits in
     * {@link #splits} order, the fights in no run first and then each run's (a dungeon area's) by its source:
     * its key is the fight's bucket string, filed under its run when it has one
     * ({@link EncounterLeaderboardListener#fightInRun}), its label the fight's name, followed by the run's
     * dungeon when it has one. A key no row claims is left out, and a key two rows share shows once, under the
     * first.
     *
     * @deprecated Records no longer shows a tab per fight: its Bosses tab picks a boss, folding every key its row
     *     holds ({@link #holds}), with its party sizes as variants ({@link #splits}).
     */
    @Deprecated(since = "2.4.0")
    @Nonnull
    public static List<LeaderboardBucketTab> fightTabs(@Nonnull Collection<String> recorded,
            @Nonnull Collection<EncounterBindingAsset> rows) {
        // The recorded fight keys by the run they are filed under, "" (first) for a fight in none.
        Map<String, List<String>> byRun = new TreeMap<>();
        for (String key : recorded) {
            String run = EncounterLeaderboardListener.runOf(key);
            byRun.computeIfAbsent(run == null ? "" : run, k -> new ArrayList<>())
                    .add(EncounterLeaderboardListener.fightOf(key));
        }
        List<EncounterBindingAsset> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(
                (EncounterBindingAsset row) -> row.getId() == null ? "" : row.getId().toLowerCase(Locale.ROOT)));
        Set<String> seen = new HashSet<>();
        List<LeaderboardBucketTab> out = new ArrayList<>();
        for (EncounterBindingAsset row : ordered) {
            EncounterBindingAsset.Leaderboard group = row.getLeaderboard();
            if (group == null || group.getBucket() == null) {
                continue;
            }
            Message title = EncounterLifecycle.titleOf(row.encounterAsset(), row);
            boolean split = group.byPartySize() || group.byDifficulty();
            LeaderboardLayout layout = split ? layoutFor(group) : null;
            for (Map.Entry<String, List<String>> filed : byRun.entrySet()) {
                String run = filed.getKey().isEmpty() ? null : filed.getKey();
                for (Split s : splits(filed.getValue(), group)) {
                    String fight = keyOf(group, s.partySize() == null ? 0 : s.partySize(), s.difficulty());
                    String key = fight.isEmpty() ? "" : EncounterLeaderboardListener.fightInRun(fight, run);
                    if (key.isEmpty() || !seen.add(key)) {
                        continue;
                    }
                    // A split fight's name joins parts, and a fight in a run names its dungeon too, which only a
                    // rich tab's TextSpans paints unflattened.
                    Message name = split ? Msg.cat(title, Msg.raw(" "), splitLabel(s, layout)) : title;
                    if (run != null) {
                        out.add(LeaderboardBucketTab.withSpans(key, DungeonRuns.fightIn(name, run)));
                    } else {
                        out.add(split ? LeaderboardBucketTab.withSpans(key, name) : new LeaderboardBucketTab(key, title));
                    }
                }
            }
        }
        return out;
    }

    /**
     * Whether {@code key} is one of {@code group}'s fight keys, under the split the row asks for now, whether or not
     * it is filed under a run.
     */
    public static boolean holds(@Nonnull EncounterBindingAsset.Leaderboard group, @Nonnull String key) {
        String bucket = group.getBucket();
        return bucket != null && parse(EncounterLeaderboardListener.fightOf(key), bucket.trim(), group.byPartySize(),
                group.byDifficulty()) != null;
    }

    /** The layout named after {@code group}'s bucket, or null when no pack authored one. */
    @Nullable
    public static LeaderboardLayout layoutFor(@Nonnull EncounterBindingAsset.Leaderboard group) {
        String bucket = group.getBucket();
        return bucket == null ? null : LeaderboardLayoutConfig.getInstance().resolve(bucket.trim());
    }

    /** One key read under {@code bucket}'s split, or null when it is another bucket's or another split's. */
    @Nullable
    static Split parse(@Nonnull String key, @Nonnull String bucket, boolean byParty, boolean byDifficulty) {
        if (!byParty && !byDifficulty) {
            return key.equals(bucket) ? new Split(null, null) : null;
        }
        String head = bucket + EncounterLeaderboardListener.BUCKET_SEPARATOR;
        if (!key.startsWith(head) || key.length() == head.length()) {
            return null;
        }
        String rest = key.substring(head.length());
        int separator = rest.indexOf(EncounterLeaderboardListener.BUCKET_SEPARATOR);
        if (byParty && byDifficulty) {
            Integer party = separator > 0 ? partyOf(rest.substring(0, separator)) : null;
            String label = separator > 0 ? rest.substring(separator + 1) : "";
            return party == null || label.isEmpty() ? null : new Split(party, label);
        }
        if (separator >= 0) {
            return null;
        }
        if (byParty) {
            Integer party = partyOf(rest);
            return party == null ? null : new Split(party, null);
        }
        return new Split(null, rest);
    }

    /** A split's recorded value: the party size, the difficulty, or both, each named by the layout when it names it. */
    @Nonnull
    private static Message splitLabel(@Nonnull Split split, @Nullable LeaderboardLayout layout) {
        Message party = split.partySize() == null ? null : partyLabel(split.partySize(), layout);
        Message difficulty = split.difficulty() == null ? null : difficultyLabel(split.difficulty(), layout);
        if (party != null && difficulty != null) {
            return Msg.cat(party, Msg.raw(" "), difficulty);
        }
        return party != null ? party : Objects.requireNonNullElse(difficulty, Msg.raw(""));
    }

    @Nonnull
    private static Message difficultyLabel(@Nonnull String label, @Nullable LeaderboardLayout layout) {
        LeaderboardBucketTab named = layout == null ? null : named(layout.primaryTabs(), label);
        return named != null ? named.label() : Msg.raw(label);
    }

    @Nonnull
    private static Message partyLabel(int party, @Nullable LeaderboardLayout layout) {
        String id = Integer.toString(party);
        LeaderboardBucketTab named = layout == null ? null : named(layout.secondaryTabs(), id);
        // A count binds as a typed number, which the rich tab's TextSpans substitutes.
        return named != null ? named.label() : Msg.num(party);
    }

    @Nullable
    private static LeaderboardBucketTab named(@Nonnull List<LeaderboardBucketTab> tabs, @Nonnull String id) {
        for (LeaderboardBucketTab tab : tabs) {
            if (tab.bucketKey().trim().equalsIgnoreCase(id)) {
                return tab;
            }
        }
        return null;
    }

    @Nonnull
    private static String keyOf(@Nonnull EncounterBindingAsset.Leaderboard group, int party, @Nullable String difficulty) {
        return Objects.requireNonNullElse(EncounterLeaderboardListener.bucketFor(group, party, difficulty), "");
    }

    @Nullable
    private static Integer partyOf(@Nonnull String digits) {
        try {
            int party = Integer.parseInt(digits);
            return party >= 0 ? party : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
