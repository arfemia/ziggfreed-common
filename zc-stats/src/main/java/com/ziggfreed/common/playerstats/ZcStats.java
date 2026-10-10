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

    private ZcStats() {
    }
}
