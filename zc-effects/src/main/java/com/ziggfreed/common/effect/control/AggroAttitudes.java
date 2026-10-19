package com.ziggfreed.common.effect.control;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.blackboard.view.attitude.IAttitudeProvider;

/**
 * This library's attitude provider on each world's {@code AttitudeView}: a creature the last aggro sweep reached is
 * hostile toward a taunter and ignores a suppressed source; every other question gets no answer, so the game's own
 * providers decide it. Registered by {@link AggroAttitudeSystem} at {@link #PRIORITY}.
 */
final class AggroAttitudes {

    /**
     * Just ahead of the game's own override memory ({@link IAttitudeProvider#OVERRIDE_PRIORITY}), so a taunt or a
     * suppress outranks a creature's "you hit me" grudge for as long as it runs.
     */
    static final int PRIORITY = IAttitudeProvider.OVERRIDE_PRIORITY - 1;

    private AggroAttitudes() {
    }

    /** The {@link IAttitudeProvider} answer: null whenever no open window speaks for this pair. */
    @Nullable
    static Attitude attitude(@Nonnull Ref<EntityStore> creature, int roleIndex, @Nonnull Ref<EntityStore> target,
            @Nonnull ComponentAccessor<EntityStore> accessor) {
        AggroWindows windows = AggroWindows.of(accessor);
        if (windows == null || windows.muted || windows.isEmpty()) {
            return null;
        }
        AggroWindow window = windows.get(target);
        return window == null ? null : window.attitudeOf(creature);
    }
}
