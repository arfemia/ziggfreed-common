package com.ziggfreed.common.entity;

import java.time.Duration;
import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.common.util.TimeUtil;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.gameplay.CombatConfig;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import com.hypixel.hytale.server.core.entity.damage.DamageDataComponent;
import com.hypixel.hytale.server.core.modules.time.TimeResource;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Is an entity in combat, read the engine's own way: its {@code DamageDataComponent}'s last combat action (stamped
 * by the engine's {@code RecordLastCombat} on every hit dealt or taken) against its world's gameplay config
 * {@code CombatConfig.OutOfCombatDelay}, as the native {@code OutOfCombatCondition} reads it. World thread.
 */
public final class CombatState {

    private CombatState() {
    }

    /**
     * True while {@code ref} dealt or took a hit within its world's out-of-combat delay. False for an entity the
     * engine keeps no combat time for, or one it cannot read (never throws).
     */
    public static boolean inCombat(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return false;
        }
        try {
            DamageDataComponent data = accessor.getComponent(ref, DamageDataComponent.getComponentType());
            if (data == null) {
                return false;
            }
            Instant last = data.getLastCombatAction();
            Duration delay = delayIn(accessor.getExternalData().getWorld());
            Instant now = accessor.getResource(TimeResource.getResourceType()).getNow();
            return delay != null && last != null && now != null && !Instant.MIN.equals(last)
                    && TimeUtil.compareDifference(last, now, delay) < 0;
        } catch (Throwable t) {
            SafeLog.fine("[entity] could not read whether an entity is in combat: " + t.getMessage());
            return false;
        }
    }

    @Nullable
    private static Duration delayIn(@Nullable World world) {
        GameplayConfig config = world == null ? null : world.getGameplayConfig();
        CombatConfig combat = config == null ? null : config.getCombatConfig();
        return combat == null ? null : combat.getOutOfCombatDelay();
    }
}
