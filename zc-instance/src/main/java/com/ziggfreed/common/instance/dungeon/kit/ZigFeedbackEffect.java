package com.ziggfreed.common.instance.dungeon.kit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.spatial.SpatialResource;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.feedback.moment.FeedbackEngine;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * A trigger volume showing a FeedbackMoment: the effect {@code ZigFeedback}. It draws {@code Moment} for every player
 * within {@code Radius} blocks of where it fired (the entity that set the volume off, else the volume itself), so a
 * pillar a boss crashes into tells the whole fight round it; with {@code Radius} 0 it draws for the player who set it
 * off alone, and for nobody when a creature did.
 *
 * <pre>{@code
 * "Effects": [ { "Type": "ZigFeedback", "Event": "ENTER", "Moment": "My_Pack_Trap_Sprung", "Radius": 24 } ]
 * }</pre>
 *
 * <p>It works in any world on a server running the library: a dungeon, an arena, an open field. The moment carries
 * only {@code player}, the name of whoever it is drawn for. A moment no file answers costs nothing.
 *
 * <p>Registered into the engine's trigger effect map at setup ({@code InstanceBootstrap.registerRoomKit}).
 */
public final class ZigFeedbackEffect extends TriggerEffect {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigFeedback";

    /** The reach when none is written, in blocks. */
    public static final double DEFAULT_RADIUS = 32.0;

    /** Moments already logged as unanswered, by id. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    @Nullable
    private String moment;
    @Nullable
    private Double radius;

    /** Lazy holder, as an interaction Type's: only registration builds the codec. */
    private static final class Holder {
        static final BuilderCodec<ZigFeedbackEffect> CODEC = BuilderCodec.builder(ZigFeedbackEffect.class,
                        ZigFeedbackEffect::new, TriggerEffect.BASE_CODEC)
                .documentation("Shows a FeedbackMoment (a toast, a banner, a sound) to every player near where the "
                        + "volume fired, or to the player who set it off.")
                .append(new KeyedCodec<>("Moment", Codec.STRING, false), (e, v) -> e.moment = v, e -> e.moment)
                .documentation("The FeedbackMoment to show, by its file name under "
                        + "Server/ZiggfreedCommon/FeedbackMoments. Unauthored, nothing is shown.").add()
                .append(new KeyedCodec<>("Radius", Codec.DOUBLE, false), (e, v) -> e.radius = v, e -> e.radius)
                .metadata(EditorSchema.defaultValue(DEFAULT_RADIUS))
                .documentation("How far from where it fired a player is shown it, in blocks: from the entity that "
                        + "set the volume off, else from the volume. 0 shows it only to the player who set it off.")
                .add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigFeedbackEffect> codec() {
        return Holder.CODEC;
    }

    @Override
    public void execute(@Nonnull TriggerContext context) {
        try {
            String id = moment == null ? null : moment.trim();
            if (id == null || id.isEmpty()) {
                return;
            }
            if (!FeedbackEngine.answers(id)) {
                if (WARNED.add(id)) {
                    SafeLog.warn("[dungeon] a " + TYPE_NAME + " volume names the moment " + id
                            + ", which no FeedbackMoments file answers, so it shows nothing");
                }
                return;
            }
            Store<EntityStore> store = context.getStore();
            for (Ref<EntityStore> player : audience(context, store)) {
                PlayerRef playerRef = store.getComponent(player, PlayerRef.getComponentType());
                Subject subject = Subject.of(playerRef, store.getComponent(player, Player.getComponentType()));
                if (subject != null) {
                    FeedbackEngine.fire(id, subject.withFacets(playerRef), new HashMap<>());
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] a " + TYPE_NAME + " volume could not show its moment", t);
        }
    }

    /** The players the moment is for: within the reach of where it fired, or the one who set it off. */
    @Nonnull
    private List<Ref<EntityStore>> audience(@Nonnull TriggerContext context, @Nonnull Store<EntityStore> store) {
        List<Ref<EntityStore>> out = new ArrayList<>();
        double reach = radius == null ? DEFAULT_RADIUS : radius;
        if (!(reach > 0.0)) {
            Ref<EntityStore> who = context.getEntityRef();
            if (who != null && who.isValid() && store.getComponent(who, PlayerRef.getComponentType()) != null) {
                out.add(who);
            }
            return out;
        }
        SpatialResource<Ref<EntityStore>, EntityStore> players =
                store.getResource(EntityModule.get().getPlayerSpatialResourceType());
        if (players == null) {
            return out;
        }
        Vector3d at = context.getActorPosition();
        if (at == null) {
            at = context.getVolume().getPosition();
        }
        List<Ref<EntityStore>> found = new ArrayList<>();
        players.getSpatialStructure().collect(new Vector3d(at), reach, found);
        for (Ref<EntityStore> player : found) {
            if (player != null && player.isValid()) {
                out.add(player);
            }
        }
        return out;
    }
}
