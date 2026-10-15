package com.ziggfreed.common.instance.leaderboard.statboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.instance.leaderboard.EncounterBoards;
import com.ziggfreed.common.instance.leaderboard.EncounterLeaderboardListener;
import com.ziggfreed.common.instance.leaderboard.LeaderboardPageDeps;
import com.ziggfreed.common.instance.leaderboard.LeaderboardSplit;

/**
 * Records' Bosses tab: the {@value #SECTION} section, split ({@link StatSplit}) into its bosses. Its first choice,
 * All bosses, shows the section's plain stats (every player's boss totals); each group is a {@link RecordGroup} (a
 * boss names one through its binding row's {@code Leaderboard.Group}) and folds those totals over its bosses; a
 * picked boss shows the section's {@code Stat/*} stats over its own lines (wins, fastest, deathless, first win),
 * and a boss whose records split by party size has its sizes as variants (Solo, 2 players, ...), each folding that
 * size alone. Hard mode is its own binding row, so its own boss.
 *
 * <p>A boss is a binding row with a {@code Leaderboard.Bucket} that some player holds a line of in All Time: every
 * recorded fight key the row holds now ({@link EncounterBoards#holds}, any split, any run it was filed under) is one
 * of its lines, read through the one builder every split shares ({@link RecordedThings}). It is named by its row's
 * {@code NameKey} as a bare translation (the picker resolves only a key), and a boss naming no group, or a group no
 * file defines, shows under All bosses alone. Every viewer sees every boss.
 *
 * <p>Registered at setup ({@link #split}), before anything ranks; with no stat source the section shows no tab.
 */
public final class StatBoardBossFights {

    /** The section the Bosses tab is: its plain stats the totals, its {@code Stat/*} stats one boss's columns. */
    public static final String SECTION = "boss_fights";

    private StatBoardBossFights() {
    }

    /** The Bosses tab's split, registered once at setup for {@value #SECTION}; its columns are the section's. */
    @Nonnull
    public static StatSplit split() {
        Message all = StatBoardMessages.line("bosses.all");
        return new StatSplit(List.of(), all, all, () -> RecordedThings.groups(SECTION, new Bosses()),
                LeaderboardSplit.EVERYONE).withVariantAllLabel(StatBoardMessages.line("bosses.sizes.all"));
    }

    /**
     * The picture of the Bosses tab: the {@value #SECTION} section's {@code Icon}, else the stat board's own section
     * picture.
     */
    @Nonnull
    public static String picture(@Nonnull StatSectionConfig config) {
        String icon = config.iconOf(SECTION);
        return icon != null ? icon : StatBoardRecords.PICTURE;
    }

    /** The pick {@code row} is on the Bosses tab: its binding row's id. */
    @Nonnull
    public static String pickOf(@Nonnull EncounterBindingAsset row) {
        return row.getId();
    }

    /**
     * Records as it opens now, or null while the board is off or nothing shows.
     *
     * @deprecated Records no longer has a Boss Fights page of its own: the bosses are a section of the stat board.
     *     Open {@code StatBoardRecords.INSTANCE.deps()}, whose primary tab {@link #SECTION} is the Bosses tab, and
     *     pick a boss there by {@link #pickOf}; this answers the same deps.
     */
    @Deprecated(since = "2.4.0")
    @Nullable
    public static LeaderboardPageDeps deps() {
        LeaderboardPageDeps deps = StatBoardRecords.INSTANCE.deps();
        return deps != null && deps.split(SECTION) != null ? deps : null;
    }

    /**
     * {@link #SECTION} when {@code deps}' Bosses tab offers {@code row}'s boss, else null.
     *
     * @deprecated a boss is a pick on the Bosses tab, never a tab of its own: open {@link #SECTION} and ask its split
     *     ({@code deps.split(SECTION).choiceOf(pickOf(row))}) which choice offers the boss.
     */
    @Deprecated(since = "2.4.0")
    @Nullable
    public static String tabOf(@Nonnull LeaderboardPageDeps deps, @Nonnull EncounterBindingAsset row) {
        LeaderboardSplit split = deps.split(SECTION);
        return split != null && split.choiceOf(pickOf(row)) != null ? SECTION : null;
    }

    /** How the Bosses tab reads a recorded fight key: the binding rows read once, as Records opens. */
    private static final class Bosses implements RecordedThings.Reading {

        /** The rows that keep records, by id, the order a key two rows share is claimed in. */
        private final Map<String, EncounterBindingAsset> rows = new LinkedHashMap<>();

        Bosses() {
            List<EncounterBindingAsset> ordered = new ArrayList<>();
            for (EncounterBindingAsset row : EncounterBindingConfig.getInstance().all().values()) {
                EncounterBindingAsset.Leaderboard group = row.getLeaderboard();
                if (row.getId() != null && group != null && group.getBucket() != null) {
                    ordered.add(row);
                }
            }
            ordered.sort(Comparator.comparing((EncounterBindingAsset row) -> row.getId().toLowerCase(Locale.ROOT)));
            for (EncounterBindingAsset row : ordered) {
                rows.put(row.getId().toLowerCase(Locale.ROOT), row);
            }
        }

        /** The first row (by id) that holds {@code line}, with its party size when the row splits by it. */
        @Override
        @Nullable
        public RecordedThings.Place place(@Nonnull String line) {
            for (EncounterBindingAsset row : rows.values()) {
                EncounterBindingAsset.Leaderboard group = row.getLeaderboard();
                if (group != null && EncounterBoards.holds(group, line)) {
                    return new RecordedThings.Place(row.getId(), group.byPartySize() ? partyOf(line, group) : null);
                }
            }
            return null;
        }

        /** The row's {@code NameKey} as the client resolves it, a bare translation; its id when it names none. */
        @Override
        @Nonnull
        public Message name(@Nonnull String thing) {
            EncounterBindingAsset row = rows.get(thing.toLowerCase(Locale.ROOT));
            String key = row == null ? null : row.getNameKey();
            return key != null ? Message.translation(ContentKeys.resolved(key)) : Message.raw(thing);
        }

        @Override
        @Nullable
        public String group(@Nonnull String thing) {
            EncounterBindingAsset row = rows.get(thing.toLowerCase(Locale.ROOT));
            EncounterBindingAsset.Leaderboard group = row == null ? null : row.getLeaderboard();
            return group == null ? null : group.getGroup();
        }

        /** One player is Solo; more read "{n} players", the count a typed number. */
        @Override
        @Nonnull
        public Message variantName(@Nonnull String variant) {
            long size = sizeOf(variant);
            return size == 1L ? StatBoardMessages.line("bosses.size.solo")
                    : StatBoardMessages.line("bosses.size.players", size);
        }

        /** Party sizes smallest first. */
        @Override
        @Nonnull
        public Comparator<String> variantOrder() {
            return Comparator.comparingLong(StatBoardBossFights::sizeOf);
        }

        /** The party size {@code line} was fought by under {@code group}'s split, as its variant id; null for none. */
        @Nullable
        private static String partyOf(@Nonnull String line, @Nonnull EncounterBindingAsset.Leaderboard group) {
            List<EncounterBoards.Split> splits = EncounterBoards.splits(
                    List.of(EncounterLeaderboardListener.fightOf(line)), group);
            Integer size = splits.isEmpty() ? null : splits.get(0).partySize();
            return size == null ? null : Integer.toString(size);
        }
    }

    /** A party-size variant's size, or 0 for one that is no number. */
    private static long sizeOf(@Nonnull String variant) {
        try {
            return Long.parseLong(variant.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
