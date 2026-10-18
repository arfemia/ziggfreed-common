package com.ziggfreed.common.objectives.gate;

import java.time.Instant;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * An entity condition over a {@code Requires} block, for wherever the engine asks one of an entity: an
 * effect's {@code ApplyConditions} (the effect holds only while the entity meets it), a stat's regeneration
 * {@code Conditions}, a map-marker reveal's {@code RunWhile}.
 *
 * <pre>{@code
 * "ApplyConditions": [ { "Id": "ZigRequires",
 *                        "Requires": { "Quests": ["Yourpack_Intro"] } } ]
 * }</pre>
 *
 * <p>The key is {@code Id}, the entity condition map's own. The entity the engine asks about is the gate's
 * subject: a player as the runtime's own subject, any other entity through its own factor context, so a
 * block of factors answers for a mob and a {@code Quests} leaf refuses it ({@link RequiresCheck#query}). The
 * native {@code Inverse} flips the answer, never a refusal for want of a reading ({@link #eval}). The engine
 * asks every tick an effect is active, so the ask is the
 * gate's kept-verdict one ({@link RequiresCheck#passes(GateQuery, GateSpec)}). It speaks no line: an effect
 * is never pressed.
 *
 * <p>Registered into the engine's entity condition map at setup ({@link ZigRequiresBootstrap}).
 */
public final class ZigRequiresEntityCondition extends Condition {

    /** The authored {@code "Id"} name, the same word every {@code ZigRequires} uses. */
    public static final String TYPE_NAME = "ZigRequires";

    @Nullable
    private GateSpec requires;

    /** Lazy holder, the same shape as an interaction Type's: only registration and a codec test build it. */
    private static final class Holder {
        static final BuilderCodec<ZigRequiresEntityCondition> CODEC = BuilderCodec.builder(
                        ZigRequiresEntityCondition.class, ZigRequiresEntityCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while the entity meets a Requires block (the shape quests use: Factors, "
                        + "Permission, Quests, Custom, AllOf, AnyOf, Not). A player is read as themselves; any other "
                        + "entity is read through its own factors, so a Quests requirement never holds for it.")
                .append(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                        (c, v) -> c.requires = v, c -> c.requires)
                .documentation("What the entity must already have or have done for this condition to hold. An "
                        + "unauthored block asks for nothing, so every entity meets it.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigRequiresEntityCondition> codec() {
        return Holder.CODEC;
    }

    /** The authored block, null when none was written. */
    @Nullable
    public GateSpec getRequires() {
        return requires;
    }

    /**
     * The engine's own answer ({@code Inverse} flipping {@link #eval0}), except that nobody to ask never holds,
     * whatever {@code Inverse} says. The engine computes {@code inverse != eval0(...)}, so left to it a refusal
     * for want of a reading (off the world thread, a ref that is gone, a player the runtime cannot name yet)
     * would turn into a pass under {@code "Inverse": true}, and an effect gated on "has not finished the quest"
     * would hold for a player whose progress could not be read. A block that asks for nothing holds for anyone,
     * so {@code Inverse} flips it as it flips any native condition.
     */
    @Override
    public boolean eval(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        if (RequiresCheck.asksNothing(requires)) {
            return !inverse;
        }
        GateQuery query = RequiresCheck.query(ref.getStore(), ref);
        return query != null && inverse != RequiresCheck.passes(query, requires);
    }

    /** The answer before {@code Inverse}: does the entity meet the block? Nobody to ask refuses. */
    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        return RequiresCheck.passes(RequiresCheck.query(ref.getStore(), ref), requires);
    }
}
