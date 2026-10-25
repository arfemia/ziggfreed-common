package com.ziggfreed.common.track.boost;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.buff.TimedBuff;
import com.ziggfreed.common.effect.buff.TimedBuffSource;
import com.ziggfreed.common.util.SafeLog;

/**
 * The timed boosts a player has, as tiles on the native buff bar ({@code TimedBuffs}, which applies each tile as a
 * native {@code EntityEffect} with the boost's real time left, so its ring counts down and a relog shows the true
 * time): each server boost and each of the player's own, one tile per start, keyed by the boost and when it began.
 * A boost's tile is its own {@code Effect} (whose Name says what it lifts and by how much), else a slot of its
 * scope's shared pool ({@code Zig_Buff_Personal}, {@code Zig_Buff_Server}). The starts {@link Boosts#running} lists
 * whose {@code Requires} the player meets now, so a tile only ever shows a boost that lifts; a standing boost keeps
 * no timer and shows none. A calendar change re-syncs the bar ({@link Boosts#onCalendarChange}), so a tile leaves
 * with the season or the calendar-read {@code Requires} its boost needs and comes back with it.
 */
final class BoostTiles implements TimedBuffSource {

    public static final BoostTiles INSTANCE = new BoostTiles();

    /** Prefixes every tile key, so a boost never meets another source's key. */
    static final String KEY_PREFIX = "ziggfreedcommon:boost:";

    /** The unknown effect ids already named in the log, so each is named once, not per refresh. */
    private static final Set<String> WARNED_EFFECTS = ConcurrentHashMap.newKeySet();

    private BoostTiles() {
    }

    @Nonnull
    @Override
    public Collection<TimedBuff> buffsOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null) {
            return List.of();
        }
        List<TimedBuff> tiles = new ArrayList<>();
        for (Boosts.Running running : Boosts.running(player.getUuid())) {
            if (!Boosts.passes(running.boost().liveRequires(), ref)) {
                // A Requires the player no longer meets: the boost lifts none of their gains, so it shows no tile.
                continue;
            }
            BoostRun run = running.run();
            tiles.add(new TimedBuff(KEY_PREFIX + running.scope().word() + ":" + run.boost() + ":" + run.startedAt(),
                    knownEffect(running.boost().effect()), running.scope().buffScope(), run.endsAt()));
        }
        return tiles;
    }

    /** {@code effect} when a loaded pack ships it, else null (the scope's shared tile). */
    @Nullable
    private static String knownEffect(@Nullable String effect) {
        if (effect == null) {
            return null;
        }
        if (EntityEffect.getAssetMap().getIndex(effect) != Integer.MIN_VALUE) {
            return effect;
        }
        if (WARNED_EFFECTS.add(effect.toLowerCase(Locale.ROOT))) {
            SafeLog.warn("[boost] a boost names the effect '" + effect + "', which no loaded pack ships; it shows on "
                    + "the shared boost tile instead");
        }
        return null;
    }
}
