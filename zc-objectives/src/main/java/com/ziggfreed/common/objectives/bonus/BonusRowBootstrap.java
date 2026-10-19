package com.ziggfreed.common.objectives.bonus;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.util.SafeLog;

/**
 * Hangs the library's bonus rows on every produced break, hand harvest and kill, and the drop credit beside them.
 * Called once from the wiring root's {@code setup()}; registered at library-default rank under the library's own
 * owner name, so it stacks beside any consumer's listener and displaces nothing.
 */
public final class BonusRowBootstrap {

    private BonusRowBootstrap() {
    }

    public static void registerReactions() {
        try {
            ProgressionRuntime.defaults(ProgressionDefaults.OWNER).momentListener(BonusRowReactions.INSTANCE);
        } catch (Throwable t) {
            SafeLog.warn("[loot] the bonus rows could not be hung on the produced moments", t);
        }
    }

    /**
     * Who a native drop list rolls for ({@link DropCredit}): the listener on the produced breaks and kills, then the
     * system that forgets each tick's stamps, then the one that stamps the engine's death drops, in that order, so a
     * stamp is never left without its forgetting.
     */
    public static void registerDropCredit(@Nonnull JavaPlugin plugin) {
        try {
            ProgressionRuntime.defaults(ProgressionDefaults.OWNER).momentListener(DropCredit.INSTANCE);
            plugin.getEntityStoreRegistry().registerSystem(new DropCredit.Close());
            plugin.getEntityStoreRegistry().registerSystem(new DropCredit.Stamp());
        } catch (Throwable t) {
            SafeLog.warn("[loot] drops: the drop credit could not be hung, so a ZigLootable drop rolls for nobody", t);
        }
    }
}
