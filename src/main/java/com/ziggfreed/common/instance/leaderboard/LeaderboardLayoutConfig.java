package com.ziggfreed.common.instance.leaderboard;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold authority for {@link LeaderboardLayout}s, keyed by
 * lowercase layout id - the cross-cutting layout twin of a consumer's gameplay preset config (a
 * layout id is usually the game-mode, e.g. {@code "chase"}, one layout PER board). It lets a pack
 * author the leaderboard tab axes + Stats columns in {@code Server/<Mod>/Leaderboard/*.json} instead
 * of a consumer baking them as Java literals at startup.
 *
 * <p>The fold mechanics (the three layers, lower-casing, idempotent re-import, resolve order) live
 * in the shared {@link AbstractKeyedAssetConfig} base; this singleton adds only the
 * {@link LeaderboardLayout} type binding and {@link #getInstance()}. A consumer reads a resolved
 * layout back through {@link #resolve} and builds its {@link LeaderboardPageDeps} from it.
 *
 * <p>The cross-cutting leaderboard-layout store is registered by ziggfreed-common itself at
 * {@code Server/ZiggfreedCommon/Leaderboard} (the orchestrator wires the registrar + the merge
 * listener); a consumer authors its layouts there and reads them back through this singleton.
 */
public final class LeaderboardLayoutConfig extends AbstractKeyedAssetConfig<LeaderboardLayout> {

    private static final LeaderboardLayoutConfig INSTANCE = new LeaderboardLayoutConfig();

    @Nonnull
    public static LeaderboardLayoutConfig getInstance() {
        return INSTANCE;
    }

    private LeaderboardLayoutConfig() {
    }
}
