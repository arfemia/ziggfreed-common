package com.ziggfreed.common.instance.leaderboard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.i18n.Msg;

/**
 * One boss fight's records, readable on any server: how the rows {@link EncounterLeaderboardListener}
 * writes onto its own board become a {@link LeaderboardPage}. The fight's binding row says how its
 * bucket is split (by party size, by difficulty, both, or neither); the board says which of those
 * splits hold rows; a {@link LeaderboardLayout} whose id is the bucket may name the tabs, order the
 * difficulties and replace the Stats columns.
 *
 * <p><b>The keys round-trip.</b> Every tab key, and every pair a two-axis selection composes through
 * the deps' {@link LeaderboardPageDeps.BucketKeys}, is built by the listener's own
 * {@link EncounterLeaderboardListener#bucketFor}, so the page reads exactly the buckets the listener
 * writes. A two-way split is difficulty (the primary axis) over party size (the secondary).
 *
 * <p><b>Tab labels.</b> A tab is a {@code TextButton}, whose text resolves a translation but never
 * substitutes a parameter, so an unnamed tab shows its recorded value as it stands: the party size's
 * digits, the difficulty's label. A layout names them.
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
     * The splits {@code bucketKeys} hold rows for under {@code group}'s bucket, party sizes ascending,
     * then labels A to Z. A key written under another split of the same bucket (the row's knobs changed
     * since) is left out, so a tab never shows a half-read key; so is another bucket's.
     */
    @Nonnull
    public static List<Split> splits(@Nonnull Collection<String> bucketKeys,
            @Nonnull EncounterBindingAsset.Leaderboard group) {
        String bucket = group.getBucket();
        if (bucket == null) {
            return List.of();
        }
        Set<Split> found = new TreeSet<>(ORDER);
        for (String key : bucketKeys) {
            Split split = parse(key, bucket.trim(), group.byPartySize(), group.byDifficulty());
            if (split != null) {
                found.add(split);
            }
        }
        return List.copyOf(found);
    }

    /**
     * The page for one fight's records on {@code board}.
     *
     * @param layout the layout whose id is the fight's bucket, or null: it names the tabs, orders the
     *               difficulties and may replace the Stats columns
     */
    @Nonnull
    public static LeaderboardPageDeps deps(@Nonnull Leaderboard board, @Nonnull EncounterBindingAsset.Leaderboard group,
            @Nullable LeaderboardLayout layout, @Nonnull EncounterLeaderboardMessages text) {
        List<Split> splits = splits(board.bucketKeys(), group);
        boolean byParty = group.byPartySize();
        boolean byDifficulty = group.byDifficulty();
        List<LeaderboardBucketTab> difficultyTabs = new ArrayList<>();
        for (String label : difficulties(splits, layout)) {
            String key = byParty ? label : keyOf(group, 0, label);
            difficultyTabs.add(new LeaderboardBucketTab(key, difficultyLabel(label, layout)));
        }
        List<LeaderboardBucketTab> partyTabs = new ArrayList<>();
        for (int party : parties(splits)) {
            String key = byDifficulty ? Integer.toString(party) : keyOf(group, party, null);
            partyTabs.add(new LeaderboardBucketTab(key, partyLabel(party, layout)));
        }
        if (!byParty && !byDifficulty && !splits.isEmpty()) {
            partyTabs.add(new LeaderboardBucketTab(keyOf(group, 0, null), text.filterAll()));
        }
        return new LeaderboardPageDeps(board, difficultyTabs, partyTabs, statColumns(layout, text), text,
                (difficulty, party) -> keyOf(group, partyOrZero(party), difficulty));
    }

    /** The layout named after {@code group}'s bucket, or null when no pack authored one. */
    @Nullable
    public static LeaderboardLayout layoutFor(@Nonnull EncounterBindingAsset.Leaderboard group) {
        String bucket = group.getBucket();
        return bucket == null ? null : LeaderboardLayoutConfig.getInstance().resolve(bucket.trim());
    }

    /** Every binding row whose bucket holds rows on {@code board}, by row id. */
    @Nonnull
    public static List<EncounterBindingAsset> ranked(@Nonnull Leaderboard board,
            @Nonnull Collection<EncounterBindingAsset> rows) {
        Set<String> keys = board.bucketKeys();
        List<EncounterBindingAsset> out = new ArrayList<>();
        for (EncounterBindingAsset row : rows) {
            EncounterBindingAsset.Leaderboard group = row.getLeaderboard();
            if (group != null && !splits(keys, group).isEmpty()) {
                out.add(row);
            }
        }
        out.sort(Comparator.comparing(
                (EncounterBindingAsset row) -> row.getId() == null ? "" : row.getId().toLowerCase(Locale.ROOT)));
        return List.copyOf(out);
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

    /** The recorded difficulty labels: the layout's order first, then the rest A to Z. */
    @Nonnull
    private static List<String> difficulties(@Nonnull List<Split> splits, @Nullable LeaderboardLayout layout) {
        Set<String> recorded = new TreeSet<>();
        for (Split split : splits) {
            if (split.difficulty() != null) {
                recorded.add(split.difficulty());
            }
        }
        List<String> out = new ArrayList<>();
        if (layout != null) {
            for (LeaderboardBucketTab tab : layout.primaryTabs()) {
                String id = tab.bucketKey().trim().toLowerCase(Locale.ROOT);
                if (recorded.remove(id)) {
                    out.add(id);
                }
            }
        }
        out.addAll(recorded);
        return out;
    }

    @Nonnull
    private static List<Integer> parties(@Nonnull List<Split> splits) {
        Set<Integer> out = new TreeSet<>();
        for (Split split : splits) {
            if (split.partySize() != null) {
                out.add(split.partySize());
            }
        }
        return List.copyOf(out);
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
        // A tab cannot substitute a parameter, so an unnamed party size shows its own digits.
        return named != null ? named.label() : Msg.raw(id);
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
    private static List<StatColumnDef> statColumns(@Nullable LeaderboardLayout layout,
            @Nonnull EncounterLeaderboardMessages text) {
        if (layout != null && !layout.statColumns().isEmpty()) {
            return layout.statColumns();
        }
        return List.of(StatColumnDef.grouped(EncounterLeaderboardListener.STAT_DAMAGE_DEALT, text.statDamageDealt()),
                StatColumnDef.grouped(EncounterLeaderboardListener.STAT_DAMAGE_TAKEN, text.statDamageTaken()));
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

    private static int partyOrZero(@Nonnull String digits) {
        Integer party = partyOf(digits);
        return party == null ? 0 : party;
    }
}
