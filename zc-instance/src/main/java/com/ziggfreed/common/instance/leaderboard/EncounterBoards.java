package com.ziggfreed.common.instance.leaderboard;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;

/**
 * Boss fights as Records reads them: which binding row a recorded fight key belongs to ({@link #holds}, the Bosses
 * tab's question) and which split of it ({@link #splits}, its party size). A fight's records are keyed by the bucket
 * string {@link EncounterLeaderboardListener#bucketFor}
 * composes from its binding row ({@code Leaderboard.Bucket}, suffixed with the party size and the run's
 * difficulty when the row splits by them), the key the stat store's encounter stats file each line under;
 * the row says how its bucket is split, the recorded keys which of those splits hold records, and a
 * {@link LeaderboardLayout} whose id is the bucket may name the split labels ({@link #layoutFor}).
 *
 * <p><b>The keys round-trip.</b> A key is read under the same split {@link EncounterLeaderboardListener#bucketFor}
 * writes, so the Bosses tab reads exactly the lines a defeat writes. A key written under another split of the same
 * bucket (the row's knobs changed since) is left out, so a pick never shows a half-read key. A fight fought inside a
 * dungeon area is filed under its run's source ({@code <key>@dungeon:<id>}), which {@link #holds} reads through.
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
