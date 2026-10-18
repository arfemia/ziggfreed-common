package com.ziggfreed.common.objectives.gate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.adventure.worldevents.condition.EventCondition;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * A world event stage condition over a {@code Requires} block: a fork of the stage advances only while the
 * world meets it, so an event can wait for a season, a feature or an installed mod.
 *
 * <pre>{@code
 * "Conditions": [ { "Type": "ZigRequires",
 *                   "Requires": { "Factors": [ { "Factor": "yourmod:feature",
 *                                                "Param": "Breach", "Min": 1 } ] } } ]
 * }</pre>
 *
 * <p>A stage belongs to a world, not to a player, so the block is asked about the world with nobody in it
 * ({@link RequiresCheck#worldQuery}): a server-wide reading answers, and any requirement on a player (a quest,
 * a level, a permission) never passes here. Asked once a second (its {@link #TYPE}), and held between asks.
 * It keeps no state of its own in a saved event: a reloaded stage asks again on its next tick.
 *
 * <p>Registered on the WorldEvents plugin at setup ({@link ZigRequiresBootstrap}), only when that plugin is
 * running. As every world event condition, it is two Types under one id: the stage's authored {@link Config},
 * and the live condition it creates.
 */
public final class ZigRequiresEventCondition implements EventCondition.Ticking {

    /** The authored {@code "Type"} name, the same word every {@code ZigRequires} uses. */
    public static final String TYPE_NAME = "ZigRequires";

    /** The id and how often the engine asks: once a second, the cadence of a stage waiting on its world. */
    public static final EventCondition.Type TYPE = new EventCondition.Type(TYPE_NAME, 1.0f);

    @Nonnull
    private Config config = Config.EMPTY;

    private boolean met;

    /** The live condition's codec, holding nothing to save. Lazy: only registration and a codec test build it. */
    private static final class Holder {
        static final BuilderCodec<ZigRequiresEventCondition> CODEC = BuilderCodec.builder(
                        ZigRequiresEventCondition.class, ZigRequiresEventCondition::new)
                .build();
    }

    /** The live condition's codec, built on first call. */
    @Nonnull
    public static BuilderCodec<ZigRequiresEventCondition> codec() {
        return Holder.CODEC;
    }

    /** The authored block this condition asks, null when none was written. */
    @Nullable
    public GateSpec getRequires() {
        return config.requires;
    }

    @Override
    public boolean test() {
        return met;
    }

    @Override
    public void reset() {
        met = false;
    }

    @Override
    public void tick(float dt, @Nonnull Store<EntityStore> store) {
        met = RequiresCheck.passes(RequiresCheck.worldQuery(store), config.requires);
    }

    /** The stage's authored half: the block, and the factory the engine makes each live condition with. */
    public static final class Config implements EventCondition.Config {

        static final Config EMPTY = new Config();

        @Nullable
        private GateSpec requires;

        /** Lazy holder: only registration and a codec test build it. */
        private static final class Holder {
            static final BuilderCodec<Config> CODEC = BuilderCodec.builder(Config.class, Config::new)
                    .documentation("A condition that holds while the world meets a Requires block (the shape quests "
                            + "use: Factors, Permission, Quests, Custom, AllOf, AnyOf, Not). It is asked about the "
                            + "world with no player in it, so only server-wide readings (a feature, a season, an "
                            + "installed mod) can meet it; a requirement on a player never does.")
                    .append(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                            (c, v) -> c.requires = v, c -> c.requires)
                    .documentation("What the world must meet for the stage to advance. An unauthored block asks for "
                            + "nothing, so it holds at once.")
                    .add()
                    .build();
        }

        /** The authored half's codec, built on first call. */
        @Nonnull
        public static BuilderCodec<Config> codec() {
            return Holder.CODEC;
        }

        /** The authored block, null when none was written. */
        @Nullable
        public GateSpec getRequires() {
            return requires;
        }

        @Nonnull
        @Override
        public EventCondition create() {
            ZigRequiresEventCondition condition = new ZigRequiresEventCondition();
            condition.config = this;
            return condition;
        }
    }
}
