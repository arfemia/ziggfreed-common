package com.ziggfreed.common.objectives.bonus;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.loot.trigger.BonusRowAsset;
import com.ziggfreed.common.loot.trigger.BonusRowConfig;
import com.ziggfreed.common.loot.trigger.BonusRowOwnerLayers;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.owner.OwnerFileBindings;
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
     * The rows' owner file as one an in-game editor may write ({@code BonusRowOwnerLayers.BINDING}): its Default read
     * off the rows under the owner's layer, its reload the rows' own owner read. The kill drops screen writes through it.
     */
    public static void registerOwnerFile() {
        try {
            OwnerFileBindings.register(OwnerFileBinding.keyedStore(BonusRowOwnerLayers.BINDING,
                    BonusRowOwnerLayers::file, BonusRowConfig.getInstance(), BonusRowAsset.CODEC,
                    BonusRowOwnerLayers::reload));
        } catch (Throwable t) {
            SafeLog.warn("[loot] the bonus rows' owner file could not be bound, so the kill drops screen writes nothing",
                    t);
        }
    }

    /** The lucky find note's bursts are forgotten when their player leaves ({@link BonusFindNotes#onDisconnect}). */
    public static void registerFindNotes(@Nonnull JavaPlugin plugin) {
        try {
            plugin.getEventRegistry().register(PlayerDisconnectEvent.class, BonusFindNotes::onDisconnect);
        } catch (Throwable t) {
            SafeLog.warn("[loot] the lucky find note could not hear players leave, so a burst outlives its player", t);
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
