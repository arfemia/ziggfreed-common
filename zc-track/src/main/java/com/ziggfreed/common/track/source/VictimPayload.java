package com.ziggfreed.common.track.source;

import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.runtime.MomentPayload;

/**
 * A moment's payload that names the entity it happened to (a kill's victim, a hit's target), so a track source can
 * ask about it without knowing the producer's own record: {@code SkipSpawnerMobs} reads whether a spawn marker or a
 * spawn beacon stood it up, and a moment that happened to another player pays only where the world's rules write
 * {@code Gain.PlayerHits} true ({@link #victimIsPlayer}). zc-objectives' kill and damage payloads implement it; a
 * fourth party's may too.
 */
public interface VictimPayload extends MomentPayload {

    /** The entity the moment happened to, or null when it names none. */
    @Nullable
    Ref<EntityStore> victimRef();

    /**
     * Is the entity the moment happened to a player? False when it names none, is gone, or cannot be read. World
     * thread, as every reaction runs.
     */
    default boolean victimIsPlayer() {
        Ref<EntityStore> victim = victimRef();
        if (victim == null || !victim.isValid()) {
            return false;
        }
        try {
            return victim.getStore().getComponent(victim, PlayerRef.getComponentType()) != null;
        } catch (Throwable t) {
            return false;
        }
    }
}
