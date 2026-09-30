package com.ziggfreed.common.stats.gearset;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The look effect ids the gear-set engine answers for on a player, saved WITH the player.
 *
 * <p>A set's look is an {@code Infinite} effect, and the engine's {@code EffectControllerComponent}
 * saves it with the player. The first recompute after login cannot know what it put on in an
 * earlier session, so it answers for every look any folded set names; a set whose file was deleted
 * while its wearer was offline names nothing any more, and without this record its look would stay
 * on that player for good. With it, the hydrate also answers for every id recorded here, and takes
 * off whichever of those no active tier wants ({@code GearSets.hydrateAnswersFor}).
 *
 * <p>ECS state, not player data: it travels with the entity beside the effect controller it
 * describes (a world change moves both in the same component holder, a save writes both), so it
 * needs no player-data database domain. The record is only ever REPLACED, in place, at each
 * recompute, with what the engine answers for ({@code GearSets.answeredAfter}): what it asked to be
 * on (none while the player is dead), plus every recorded look whose remove did not go through, so
 * a look is never dropped from the record before it has really come off, and each later recompute
 * and login tries again. A look whose effect asset no longer exists at all is dropped rather than
 * retried: the engine's effect controller skips an effect whose asset is gone as it loads the
 * player and never saves it again, so there is nothing left on the player to take off, and a remove
 * of an id nothing resolves could never succeed.
 *
 * <p>{@link #register} runs once at the library's setup, unconditionally and before any world
 * loads (a component type registered later cannot be read off entities saved carrying it), and
 * {@link #install} hangs the connect hook that attaches one to every player on their holder, before
 * the entity joins a store: the recompute runs inside system ticks, where adding a component would
 * be an archetype change, so it only ever reads and updates an attached record and skips a player
 * who has none. The flair component's shape ({@code ZigFlairComponent}).
 */
public final class GearSetLooksComponent implements Component<EntityStore> {

    /** The engine registry id; the persisted save key for this component. */
    public static final String REGISTRY_ID = "ZiggfreedCommon:GearSetLooks";

    /** The registered type, or null until {@link #register} runs (or when it failed). */
    @Nullable
    private static volatile ComponentType<EntityStore, GearSetLooksComponent> type;

    @Nonnull
    public static final BuilderCodec<GearSetLooksComponent> CODEC = BuilderCodec
            .builder(GearSetLooksComponent.class, GearSetLooksComponent::new)
            .append(new KeyedCodec<>("Effects", Codec.STRING_ARRAY),
                    (c, v) -> c.effects = copyOf(v == null ? List.of() : Arrays.asList(v)),
                    c -> c.effects.toArray(String[]::new)).add()
            .build();

    private Set<String> effects = new LinkedHashSet<>();

    public GearSetLooksComponent() {
    }

    /**
     * Register the component type. Called once at library setup, BEFORE any world loads. Never
     * throws: a failure logs and leaves the type unset, and the engine then records nothing.
     */
    @Nullable
    public static ComponentType<EntityStore, GearSetLooksComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            type = registry.registerComponent(GearSetLooksComponent.class, REGISTRY_ID, CODEC);
            return type;
        } catch (Throwable t) {
            SafeLog.warn("[gearset] could not register GearSetLooksComponent", t);
            return null;
        }
    }

    /** Hang the connect hook that attaches one of these to every player's holder. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().register(PlayerConnectEvent.class, GearSetLooksComponent::onPlayerConnect);
    }

    /** The registered type, or null when registration has not run or failed. */
    @Nullable
    public static ComponentType<EntityStore, GearSetLooksComponent> getComponentType() {
        return type;
    }

    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            ComponentType<EntityStore, GearSetLooksComponent> registered = type;
            if (registered == null) {
                return;
            }
            event.getHolder().ensureAndGetComponent(registered);
        } catch (Throwable t) {
            SafeLog.warn("[gearset] could not attach the gear-set look record", t);
        }
    }

    /** The recorded look effect ids, in the order they were asked for; never null. */
    @Nonnull
    public Set<String> effects() {
        return Collections.unmodifiableSet(effects);
    }

    /** Replace the record with {@code asked}, what the engine answers for after this recompute. */
    public void record(@Nonnull Collection<String> asked) {
        effects = copyOf(asked);
    }

    @Nonnull
    private static Set<String> copyOf(@Nonnull Collection<String> ids) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                out.add(id);
            }
        }
        return out;
    }

    @Override
    public GearSetLooksComponent clone() {
        GearSetLooksComponent c = new GearSetLooksComponent();
        c.effects = new LinkedHashSet<>(effects);
        return c;
    }
}
