package com.ziggfreed.common.loot.claim;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * The kind of payout a Claim is, read from where it was written: its {@link Claim#source}'s site, the part before
 * the first colon ({@code boss} in {@code boss:Zc_Encounter_Example}). A kind is the lang key of its one word
 * ("Boss Kill", "Dungeon Clear", "Bonus"), so two sites naming one key are one kind. A reward chest's card says where
 * its reward came from in that word, and alike rewards add up only within one kind.
 *
 * <p>One site reads two ways: what a reward chest holds of its own ({@code chest:<file>}) is a boss's reward when the
 * chest is filed under a boss fight's run (its {@link Claim#fight} a run id), and a bonus when a kill or a break stood
 * it under a key of its own ({@code chest:<uuid>}). {@link #kindOf(Claim)} is the one place that reads it.
 *
 * <p>Nothing saved moves: the kind is read from the source and the chest key every Claim has always carried, so a
 * Claim written before kinds existed reads its kind too. The sites the library writes are named here, once; a future
 * library site gets its word in this table. A consumer whose own code writes Claims names its site once at setup
 * ({@link #name}). A source whose site nobody named has no kind, and its card shows no word.
 *
 * <p>The site is structural (which code wrote the Claim), so the table is code; the words are content, in
 * {@code ziggfreedcommon.loot.lang}.
 */
public final class ClaimKinds {

    /** Where the library's words live, in {@code ziggfreedcommon.loot.lang}. */
    public static final String KEY_PREFIX = "ziggfreedcommon.loot.claim.kind.";

    /**
     * A boss fight's reward (zc-encounter's {@code EncounterClaims}, {@code boss:<script>}), and what its chest holds of
     * its own ({@link #BONUS}'s site filed under the fight's run).
     */
    public static final String BOSS = KEY_PREFIX + "boss";

    /** A reward granted outside any fight, a world event's (zc-encounter's {@code EncounterClaims}, {@code reward:<id>}). */
    public static final String EVENT = KEY_PREFIX + "event";

    /** A dungeon's clear (zc-instance's {@code DungeonRuns.SOURCE_PREFIX}, {@code dungeon:<id>}). */
    public static final String DUNGEON = KEY_PREFIX + "dungeon";

    /**
     * What a kill's or a break's reward chest holds, rolled from its file's {@code Contents} (zc-instance's
     * {@code SpoilsChests.KEY_PREFIX}, {@code chest:<file>}, filed under a {@code chest:<uuid>} key).
     */
    public static final String BONUS = KEY_PREFIX + "bonus";

    /** A quest step's item (zc-objectives' {@code QuestItemDrops}, {@code quest-drop:<quest>}). */
    public static final String QUEST = KEY_PREFIX + "quest";

    /** An admin's roll of a loot table ({@code /zigclaims give}, {@code admin:<table>}). */
    public static final String ADMIN = KEY_PREFIX + "admin";

    /** A reward chest's own site, which is also how the key of a chest no boss fight stood starts. */
    private static final String CHEST_SITE = "chest";

    /** The sites the library writes, lower-cased, each with its kind. A site's spelling is saved data: never change one. */
    private static final Map<String, String> LIBRARY = Map.of(
            "boss", BOSS,
            "reward", EVENT,
            "dungeon", DUNGEON,
            CHEST_SITE, BONUS,
            "quest-drop", QUEST,
            "admin", ADMIN);

    /** The sites consumers named, lower-cased. */
    private static final Map<String, String> NAMED = new ConcurrentHashMap<>();

    private ClaimKinds() {
    }

    /**
     * Name the kind of every Claim whose source starts {@code <site>:}: {@code wordKey}, the lang key of its word (a
     * consumer's own key, written as its lang file resolves it). Called once at setup by a consumer whose own code
     * writes Claims; naming a site again replaces its word. A site the library writes keeps the library's word, with
     * a warning.
     *
     * @return whether the site now reads as {@code wordKey}
     */
    public static boolean name(@Nonnull String site, @Nonnull String wordKey) {
        String key = siteKey(site);
        if (key.isEmpty() || wordKey.isBlank()) {
            return false;
        }
        if (LIBRARY.containsKey(key)) {
            SafeLog.warn("[claims] the Claim source site '" + key + "' is the library's own, so it keeps the word "
                    + LIBRARY.get(key));
            return false;
        }
        NAMED.put(key, wordKey.trim());
        return true;
    }

    /**
     * {@code claim}'s kind, the one reading every surface takes: its source's ({@link #kindOf(String)}), except that what
     * a reward chest holds of its own is a boss's reward ({@link #BOSS}) when the chest is filed under a boss fight's
     * run rather than under a key of its own; null when its site has no kind.
     */
    @Nullable
    public static String kindOf(@Nonnull Claim claim) {
        String fight = claim.fight();
        if (CHEST_SITE.equals(siteOf(claim.source())) && fight != null && !CHEST_SITE.equals(siteOf(fight))) {
            return BOSS;
        }
        return kindOf(claim.source());
    }

    /**
     * The kind of a Claim written with {@code source}: the lang key of its word, the library's for a site it writes,
     * else the one a consumer named; null for a site nobody named. A chest's own Claim reads as a bonus here; only
     * {@link #kindOf(Claim)}, which sees its chest key, reads one filed under a boss fight as the boss's.
     */
    @Nullable
    public static String kindOf(@Nullable String source) {
        if (source == null) {
            return null;
        }
        String key = siteOf(source);
        String library = LIBRARY.get(key);
        return library != null ? library : NAMED.get(key);
    }

    /** The site of a source or a chest key: the part before the first colon (all of it with none), lower-cased. */
    @Nonnull
    private static String siteOf(@Nonnull String source) {
        int colon = source.indexOf(':');
        return siteKey(colon < 0 ? source : source.substring(0, colon));
    }

    @Nonnull
    private static String siteKey(@Nonnull String site) {
        return site.trim().toLowerCase(Locale.ROOT);
    }
}
