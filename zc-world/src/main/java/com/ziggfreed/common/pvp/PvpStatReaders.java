package com.ziggfreed.common.pvp;

import java.util.Map;
import java.util.function.ToIntFunction;

import javax.annotation.Nonnull;

import com.ziggfreed.common.counter.NativeReader;
import com.ziggfreed.common.counter.NativeReaders;

/**
 * The PvP record's numbers as the player stat store's native stats ({@code Server/ZiggfreedCommon/Stats/Pvp_*.json},
 * {@code Kind: Native}): read off {@link PvpRecord} when the store asks, never counted a second time, so the Records
 * board ranks them beside every other statistic. One reader per stat id, registered at setup.
 */
public final class PvpStatReaders {

    /** Who the readers are attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** The stat ids, each a file this module ships. */
    public static final String KILLS = "Pvp_Kills";
    public static final String ASSISTS = "Pvp_Assists";
    public static final String DEATHS = "Pvp_Deaths";
    public static final String BEST_STREAK = "Pvp_Best_Streak";
    public static final String BOUNTIES = "Pvp_Bounties";

    private PvpStatReaders() {
    }

    /** Register every reader. Once, from setup. */
    public static void register() {
        NativeReaders.register(OWNER, KILLS, reader(PvpRecord::kills));
        NativeReaders.register(OWNER, ASSISTS, reader(PvpRecord::assists));
        NativeReaders.register(OWNER, DEATHS, reader(PvpRecord::deaths));
        NativeReaders.register(OWNER, BEST_STREAK, reader(PvpRecord::bestStreak));
        NativeReaders.register(OWNER, BOUNTIES, reader(PvpRecord::bounties));
    }

    /** A reader of one of the record's numbers: its total, no lines; null for a player with no record. */
    @Nonnull
    private static NativeReader reader(@Nonnull ToIntFunction<PvpRecord> number) {
        return (store, ref) -> {
            PvpRecord record = PvpRecord.of(store, ref);
            return record == null ? null : new NativeReader.Reading((long) number.applyAsInt(record), Map.of());
        };
    }
}
