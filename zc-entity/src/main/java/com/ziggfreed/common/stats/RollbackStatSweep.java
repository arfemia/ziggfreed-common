package com.ziggfreed.common.stats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The rollback sweep (CommonLib 2.5.1, R755): when a player is ready, every saved stat modifier keyed
 * {@code zigtrack:} comes off, on any stat the server loads. Only the CommonLib line after 2.5.x (Skills 2.0) writes
 * that prefix: its track grants ({@code zigtrack:<track>:<pick>}, a track's {@code zigtrack:<track>:points}) and its
 * runtime folds
 * ({@code zigtrack:@cond:<stat>}, {@code zigtrack:@counter:<id>}); this line, its engine and every consumer released
 * on it write none, so a server rolled back from that line never keeps its bonuses stacked on what a consumer here
 * re-applies under its own keys. Nothing here writes the prefix again, so a swept player stays clean: the sweep runs at
 * every ready (the stat map is saved with the player) and says one INFO line, with the count, only for a player it
 * actually swept. A stat type the server does not load keeps its saved values in the engine's unknown section, out
 * of reach and inert. The library registers it ({@code EntityBootstrap.installRollbackSweep}); a consumer adds none.
 */
public final class RollbackStatSweep {

    /** The key prefix swept: the track grants' owner on the later line, never written on this one. */
    static final String PREFIX = "zigtrack:";

    private RollbackStatSweep() {
    }

    /** {@code PlayerReadyEvent} (a login, a world entry): sweep the player on their world thread. */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            Ref<EntityStore> ref = event.getPlayerRef();
            World world = player == null ? null : player.getWorld();
            if (world == null || ref == null) {
                return;
            }
            world.execute(() -> sweepNow(ref));
        } catch (Throwable t) {
            SafeLog.warn("[stats] the rollback sweep could not be queued: " + t.getMessage());
        }
    }

    /** Sweep the player at {@code ref} now, saying so only when something came off. World thread. */
    private static void sweepNow(@Nonnull Ref<EntityStore> ref) {
        try {
            if (!ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            EntityStatMap stats = store.getComponent(ref, EntityStatsModule.get().getEntityStatMapComponentType());
            if (stats == null) {
                return;
            }
            int removed = sweep(stats);
            if (removed > 0) {
                PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
                SafeLog.info("[stats] rollback sweep: removed " + removed + " saved " + PREFIX
                        + " stat modifier(s) from " + who(player));
            }
        } catch (Throwable t) {
            SafeLog.warn("[stats] the rollback sweep failed for a player: " + t.getMessage());
        }
    }

    /** Take every {@code zigtrack:} modifier off every stat on {@code stats}; how many came off. World thread. */
    static int sweep(@Nonnull EntityStatMap stats) {
        int removed = 0;
        for (int i = 0; i < stats.size(); i++) {
            EntityStatValue value = stats.get(i);
            Map<String, Modifier> modifiers = value == null ? null : value.getModifiers();
            if (modifiers == null || modifiers.isEmpty()) {
                continue;
            }
            for (String key : sweptKeys(modifiers.keySet())) {
                if (stats.removeModifier(i, key) != null) {
                    removed++;
                }
            }
        }
        return removed;
    }

    /**
     * The keys of {@code keys} the sweep takes off, in a list of their own (pure): the engine's map is live, and a
     * removal writes it.
     */
    @Nonnull
    static List<String> sweptKeys(@Nonnull Collection<String> keys) {
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            if (swept(key)) {
                out.add(key);
            }
        }
        return out;
    }

    /** Does the sweep take {@code key} off: does it start with {@code zigtrack:}, exactly as the later line writes it? */
    static boolean swept(@Nullable String key) {
        return key != null && key.startsWith(PREFIX);
    }

    @Nonnull
    private static String who(@Nullable PlayerRef player) {
        return player == null ? "a player" : player.getUsername() + " (" + player.getUuid() + ")";
    }
}
