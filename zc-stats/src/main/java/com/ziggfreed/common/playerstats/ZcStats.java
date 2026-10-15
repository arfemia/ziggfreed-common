package com.ziggfreed.common.playerstats;

/**
 * The ids of the stats the library counts in code rather than off a {@code From} list, each shipped as
 * its own {@code Server/ZiggfreedCommon/Stats/<Id>.json}. The spellings are the ones every save already
 * holds, so a stat that moves here from a consumer keeps its tallies.
 */
public final class ZcStats {

    /** How many times the player has died. */
    public static final String DEATHS = "Deaths";

    /** Seconds spent in the world, committed in whole seconds. */
    public static final String PLAYTIME_SECONDS = "Playtime_Seconds";

    /** Boss fights won, one line per fight (its bucket string), a Sum. */
    public static final String ENCOUNTERS_WON = "Encounters_Won";

    /** A fight's fastest win in whole seconds, one line per fight, a Min. */
    public static final String ENCOUNTER_BEST_TIME = "Encounter_Best_Time";

    /** The best share of a fight's damage credit in whole percent (the top contributor reads 100), one line per fight, a Max. */
    public static final String ENCOUNTER_BEST_SHARE = "Encounter_Best_Share";

    /** Raw damage dealt to a fight's boss on its wins, one line per fight, a Sum. */
    public static final String ENCOUNTER_DAMAGE_DEALT = "Encounter_Damage_Dealt";

    /** Boss fights won without dying in the fight, one line per fight, a Sum. */
    public static final String ENCOUNTER_DEATHLESS_WINS = "Encounter_Deathless_Wins";

    /** Boss fights won with the player landing the boss's last hit, one line per fight, a Sum. */
    public static final String ENCOUNTER_KILLING_BLOWS = "Encounter_Killing_Blows";

    /**
     * When a fight was first won, in seconds since the epoch on the server's clock, one line per fight, a Min:
     * the total is the first win of any fight.
     */
    public static final String ENCOUNTER_FIRST_WIN = "Encounter_First_Win";

    /** Boss fights lost (a wipe), one line per fight, a Sum. */
    public static final String ENCOUNTER_WIPES = "Encounter_Wipes";

    /**
     * A dungeon run's fastest clear in whole seconds, one line per difficulty and dungeon
     * ({@code dungeon:<difficulty>@<dungeon id>}), a Min, timed off each won dungeon round.
     */
    public static final String DUNGEON_BEST_TIME = "Dungeon_Best_Time";

    private ZcStats() {
    }
}
