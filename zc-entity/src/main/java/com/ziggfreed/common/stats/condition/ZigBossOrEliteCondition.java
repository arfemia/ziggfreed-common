package com.ziggfreed.common.stats.condition;

import java.time.Instant;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * An entity condition: is the entity a boss or an elite ({@link BossRules#isBossOrElite}: a creature in the library's
 * {@value BossRules#FAMILY_GROUP} group or in a group tagged {@value BossRules#RANK_TAG})? Asked with {@code Inverse}
 * to keep one out whatever its rules say of a control (a talent's added target). Registered into the engine's entity
 * condition map as {@value #TYPE_NAME}.
 *
 * <pre>{@code
 * { "Id": "ZigBossOrElite", "Inverse": true }
 * }</pre>
 */
public final class ZigBossOrEliteCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigBossOrElite";

    private static final class Holder {
        static final BuilderCodec<ZigBossOrEliteCondition> CODEC = BuilderCodec.builder(
                        ZigBossOrEliteCondition.class, ZigBossOrEliteCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while the entity is a boss or an elite: a creature in the Zig_Boss_Or_Elite "
                        + "NPC group, or in a group whose Tags carry ZigRank Boss or Elite.")
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigBossOrEliteCondition> codec() {
        return Holder.CODEC;
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        return BossRules.isBossOrElite(componentAccessor, ref);
    }
}
