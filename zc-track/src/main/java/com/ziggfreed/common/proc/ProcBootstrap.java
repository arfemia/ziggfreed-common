package com.ziggfreed.common.proc;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.damage.onhit.ZigCritEvent;
import com.ziggfreed.common.effect.landing.EffectLandings;
import com.ziggfreed.common.health.ZigHealGivenEvent;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.util.SafeLog;

/**
 * Registers the proc runtime at library setup, called once from the wiring root's {@code setup()}: the systems behind
 * the triggers the family had no moment for (a jump, a projectile's miss, the health a killing blow took), the holder
 * pass and the forgetting of an entity that left; the reaction
 * on the library's own moments (hit, kill, break, harvest) at library-default rank, on zc-core's heal given, on
 * zc-entity's critical hit and on zc-effects' one landing watch (the effect moments, {@link EffectWatch}); the
 * counters' reading; and a departing player's waits forgotten. The three stores (procs, counters, held effects) are registered with the other
 * framework stores. Registration only ({@code RootRegistrationOnlyTest}); a failed system registration throws here,
 * at setup.
 */
public final class ProcBootstrap {

    private ProcBootstrap() {
    }

    /** Everything the runtime registers, in order. */
    public static void install(@Nonnull JavaPlugin plugin) {
        plugin.getEntityStoreRegistry().registerSystem(new ProcSystems.Taken());
        plugin.getEntityStoreRegistry().registerSystem(new ProcSystems.Incoming());
        plugin.getEntityStoreRegistry().registerSystem(new ProcSystems.ChainStarts());
        plugin.getEntityStoreRegistry().registerSystem(new ProcSystems.Landing());
        plugin.getEntityStoreRegistry().registerSystem(new ProcSystems.Jump());
        plugin.getEntityStoreRegistry().registerSystem(new KillBlows.Stamp());
        plugin.getEntityStoreRegistry().registerSystem(new ProjectileMisses.Left());
        plugin.getEntityStoreRegistry().registerSystem(new ProjectileMisses.Landed());
        plugin.getEntityStoreRegistry().registerSystem(new ProjectileMisses.Spent());
        plugin.getEntityStoreRegistry().registerSystem(new ProjectileMisses.Dropped());
        plugin.getEntityStoreRegistry().registerSystem(new ProcSystems.HolderPass());
        plugin.getEntityStoreRegistry().registerSystem(new ProcSystems.Left());
        plugin.getEntityStoreRegistry().registerSystem(new ProcSystems.Died());
        plugin.getEventRegistry().register(PlayerDisconnectEvent.class, ProcCooldowns::onDisconnect);
        plugin.getEventRegistry().register(ZigHealGivenEvent.class, ProcMoments::onHealGiven);
        plugin.getEventRegistry().register(ZigCritEvent.class, ProcMoments::onCrit);
        EffectLandings.register(EffectWatch.WATCHER);
        try {
            ProcFactors.contribute();
            ProgressionRuntime.defaults(Procs.OWNER).momentListener(ProcMoments.INSTANCE);
        } catch (Throwable t) {
            SafeLog.warn("[proc] the procs could not be hung on the produced moments or claim their reading, so no "
                    + "Hit, Kill, BlockBreak or Harvest proc runs this boot", t);
        }
    }
}
