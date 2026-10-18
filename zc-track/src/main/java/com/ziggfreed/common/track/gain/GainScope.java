package com.ziggfreed.common.track.gain;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackKey;

/**
 * What a factor compiles against ({@link GainFactor#compile}): whose climb, on which track, and where they stand,
 * as they are when the plan is built. A plan is built again whenever its epoch moves ({@link GainPlans}), so a
 * factor reading anything here reads it fresh after a world change, a permission change, a track fold or its own
 * {@link GainPlans#invalidate}.
 *
 * @param key   whose climb, on which track
 * @param def   the track and its curve
 * @param ref   the player's live entity, or null for another kind of subject or a player not here
 * @param world the world the player stands in, or null when there is none
 */
public record GainScope(@Nonnull TrackKey key, @Nonnull TrackDef def, @Nullable Ref<EntityStore> ref,
                        @Nullable World world) {

    /** The player whose climb this is, or null for another kind of subject. */
    @Nullable
    public UUID playerId() {
        return key.playerId();
    }

    /** The track id, lower-cased. */
    @Nonnull
    public String trackId() {
        return def.id();
    }

    /** The track's category id as written, or null. */
    @Nullable
    public String categoryId() {
        return def.asset().category();
    }
}
