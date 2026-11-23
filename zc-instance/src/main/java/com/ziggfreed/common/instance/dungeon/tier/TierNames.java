package com.ziggfreed.common.instance.dungeon.tier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.instance.dungeon.DungeonConfig;
import com.ziggfreed.common.instance.dungeon.DungeonRefusals;
import com.ziggfreed.common.instance.dungeon.DungeonRuns;

/**
 * What players call a dungeon's tier: its file's {@code Tier.NameKey} line, the tier's {@code Rank} as its {@code {0}},
 * so one line on a tier names it and every tier that inherits it; a tier whose file names none reads as the library's
 * own word, Tier and its rank ({@value #DEFAULT_KEY}), so a server running the library alone, or a pack that names no
 * word, never shows another pack's. The one reading the gate's rows and every line naming a tier take
 * ({@code DungeonRefusals.tierName}), and the one a Claim's difficulty takes ({@link #ofDifficulty}). A list that names
 * every depth apart (Records' picker, a leaderboard line) reads {@link #displayName}, which names a daily as its
 * dungeon's Daily Run; a depth's card on its gate is titled by {@link #word}.
 *
 * <p>A Claim carries only the {@code Difficulty} label of what paid it (a tier's clear, a boss fight fought in its run,
 * or a fight's own label), so the label is named by the tier file whose {@code Difficulty} it is, as
 * {@link #ofDifficulty} says: a clear's own file, else only a tier that names its word. Disabled files answer too,
 * since a Claim outlives the switch. Pure reads of the folded files; never throws on a missing one.
 */
public final class TierNames {

    /** The library's own word for a tier whose file names none, {@code {0}} its rank: Tier 2. */
    static final String DEFAULT_KEY = "ziggfreedcommon.dungeon.tier.name";

    /** A daily's display name, {@code {0}} its dungeon's name: The Shifting Deep: Daily Run. */
    static final String DAILY_KEY = "ziggfreedcommon.dungeon.tier.daily_named";

    private TierNames() {
    }

    /**
     * A dungeon file's display name, the one a list naming every depth apart reads (Records' picker, a leaderboard
     * line, the gate's own lines): a daily ({@code Seed.Daily}) is its dungeon's name then Daily Run ("The Shifting
     * Deep: Daily Run"); a tier is its dungeon's name then what players call the tier ("The Shifting Deep: Depth 2",
     * {@link DungeonRefusals#tierName}); any other dungeon is its name alone. Never throws.
     */
    @Nonnull
    public static Message displayName(@Nonnull DungeonAsset dungeon) {
        if (dungeon.seedDaily()) {
            return Msg.key(DAILY_KEY, DungeonRefusals.dungeonName(dungeon));
        }
        return DungeonRefusals.tierName(dungeon);
    }

    /**
     * {@link #displayName(DungeonAsset)} of the dungeon file {@code dungeonId} names (any case), or null when it names
     * none or no loaded file defines it, so the caller keeps its own reading.
     */
    @Nullable
    public static Message displayName(@Nullable String dungeonId) {
        if (dungeonId == null || dungeonId.isBlank()) {
            return null;
        }
        DungeonAsset dungeon = DungeonConfig.getInstance().resolve(dungeonId.trim());
        return dungeon == null ? null : displayName(dungeon);
    }

    /**
     * What a depth's card on its gate is titled: a tier's own word ({@link #own}, "Depth 2"), else the library's, Tier
     * and its rank; a dungeon that is no tier, its name. A daily reads as the tier it is a daily of.
     */
    @Nonnull
    public static Message word(@Nonnull DungeonAsset dungeon) {
        if (!dungeon.isTier()) {
            return DungeonRefusals.dungeonName(dungeon);
        }
        Message own = own(dungeon);
        return own != null ? own : Msg.key(DEFAULT_KEY, (long) dungeon.tierRank());
    }

    /** What {@code dungeon}'s own file calls its tier, or null when it is no tier or names no word of its own. */
    @Nullable
    public static Message own(@Nonnull DungeonAsset dungeon) {
        String key = dungeon.isTier() ? dungeon.tierNameKey() : null;
        return key == null ? null : ContentKeys.tr(key, (long) dungeon.tierRank());
    }

    /**
     * What a Claim's {@code difficulty} label reads as, by the tier file whose {@code Difficulty} it is, or null when
     * no tier answers, and then the caller reads the label as it always has. {@code source} is the Claim's source.
     * <ul>
     *   <li>A dungeon clear's ({@code dungeon:<id>}) names its own file, which alone answers when it carries the label:
     *       as a tier, its own word ({@link #own}), else the library's, Tier and its rank ({@value #DEFAULT_KEY}),
     *       unless the label is itself a shipped lang key; as no tier, nothing.</li>
     *   <li>Any other Claim (a boss's, whose label may be its run's tier or the fight's own, such as a binding row's
     *       Hard) is named only by the one loaded tier file carrying that label that names a word of its own; two that
     *       read differently name nothing. A tier with no word never claims it, so a fight's own label is never read
     *       as some dungeon's tier.</li>
     * </ul>
     */
    @Nullable
    public static Message ofDifficulty(@Nullable String difficulty, @Nullable String source) {
        String label = difficulty == null ? "" : difficulty.trim();
        if (label.isEmpty()) {
            return null;
        }
        DungeonConfig config = DungeonConfig.getInstance();
        String named = dungeonOf(source);
        DungeonAsset own = named == null ? null : config.resolve(named);
        if (own != null && label.equalsIgnoreCase(own.difficulty())) {
            if (!own.isTier()) {
                return null;
            }
            Message word = own(own);
            if (word != null) {
                return word;
            }
            return ContentKeys.known(label) ? null : Msg.key(DEFAULT_KEY, (long) own.tierRank());
        }
        DungeonAsset found = null;
        for (DungeonAsset dungeon : config.all().values()) {
            if (dungeon == null || !names(dungeon) || !label.equalsIgnoreCase(dungeon.difficulty())) {
                continue;
            }
            if (found == null) {
                found = dungeon;
            } else if (!sameWord(found, dungeon)) {
                return null;
            }
        }
        return found == null ? null : own(found);
    }

    /** Does {@code dungeon} name its tier ({@code Tier.NameKey})? */
    private static boolean names(@Nonnull DungeonAsset dungeon) {
        return dungeon.isTier() && dungeon.tierNameKey() != null;
    }

    /** Do two naming tiers read the same: one key (any case) at one rank, as a tier and its daily do? */
    private static boolean sameWord(@Nonnull DungeonAsset a, @Nonnull DungeonAsset b) {
        String key = a.tierNameKey();
        return a.tierRank() == b.tierRank() && key != null && key.equalsIgnoreCase(b.tierNameKey());
    }

    /** The dungeon a Claim's source names ({@code dungeon:<id>}), or null for any other source. */
    @Nullable
    private static String dungeonOf(@Nullable String source) {
        if (source == null || !source.regionMatches(true, 0, DungeonRuns.SOURCE_PREFIX, 0,
                DungeonRuns.SOURCE_PREFIX.length())) {
            return null;
        }
        String id = source.substring(DungeonRuns.SOURCE_PREFIX.length()).trim();
        return id.isEmpty() ? null : id;
    }
}
