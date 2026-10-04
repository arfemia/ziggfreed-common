package com.ziggfreed.common.objectives.bonus;

import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.util.SafeLog;

/**
 * Hangs the library's bonus rows on every produced break, hand harvest and kill. Called once from
 * the wiring root's {@code setup()}; registered at library-default rank under the library's own
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
}
