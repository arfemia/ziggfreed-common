package com.ziggfreed.common.instance.dungeon.kit;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.builtin.triggervolumes.YawRotation;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.math.vector.Vector3dUtil;
import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.feedback.EventTitles;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.instance.dungeon.DungeonRuns;
import com.ziggfreed.common.instance.dungeon.RunCheckpoints;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.Facing;

/**
 * A party checkpoint for any dungeon run, generated or hand-built: the trigger-volume effect {@code ZigCheckpoint}.
 * The first time a member of a run steps in (or a signal reaches it while the run is going), the run's respawn moves
 * here and the checkpoint before it is retired ({@link RunCheckpoints}), every member gets a short heal over time
 * and sees the checkpoint banner. A checkpoint the run already took does nothing again, so walking back through an
 * earlier statue never moves the respawn back.
 *
 * <pre>{@code
 * "Effects": [ { "Type": "ZigCheckpoint", "Event": "ENTER",
 *                "RespawnOffset": { "X": 2, "Y": 0, "Z": 0 }, "RespawnYaw": 90 } ]
 * }</pre>
 *
 * <p>Its settings are flat leaves of the shapes the in-game trigger-volume inspector edits with real controls
 * ({@code RespawnOffset} a vector row, {@code RespawnYaw} a number, {@code HealEffect} an entity-effect picker, the
 * two banner keys text), each nullable, null meaning its declared default.
 *
 * <p>Only this run's members respawn here, and only while the run is going: another party in the same copy keeps
 * its own entry, and the world's spawn and every player's own respawn points are never touched (those are kept by
 * world name, and copy names repeat). It grants nothing.
 *
 * <p>Registered into the engine's trigger effect map at setup ({@code InstanceBootstrap.registerRoomKit}), the
 * library's manifest depending on the TriggerVolumes plugin that reads it.
 */
public final class ZigCheckpointEffect extends TriggerEffect {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigCheckpoint";

    /** The key naming the heal, which the inspector offers as an entity-effect picker. */
    public static final String HEAL_EFFECT_KEY = "HealEffect";

    /** The heal every member gets when {@code HealEffect} is unauthored, an effect the library ships. */
    public static final String DEFAULT_HEAL = "Zc_Checkpoint_Heal";

    /** The banner's headline when {@code BannerTitleKey} is unauthored. */
    public static final String DEFAULT_TITLE_KEY = "ziggfreedcommon.dungeon.checkpoint.title";

    /** The banner's second line when {@code BannerSubtitleKey} is unauthored. */
    public static final String DEFAULT_SUBTITLE_KEY = "ziggfreedcommon.dungeon.checkpoint.subtitle";

    private static final String LOG = "[dungeon] ";

    /** Heal effects a checkpoint named that no loaded file defines, already logged. */
    private static final Set<String> WARNED_HEALS = ConcurrentHashMap.newKeySet();

    @Nullable
    private Vector3d respawnOffset;
    @Nullable
    private Float respawnYaw;
    @Nullable
    private String healEffect;
    @Nullable
    private String bannerTitleKey;
    @Nullable
    private String bannerSubtitleKey;

    /** Lazy holder, as an interaction Type's: only registration builds the codec. */
    private static final class Holder {
        static final BuilderCodec<ZigCheckpointEffect> CODEC = BuilderCodec.builder(ZigCheckpointEffect.class,
                        ZigCheckpointEffect::new, TriggerEffect.BASE_CODEC)
                .documentation("A dungeon run's checkpoint: the first time a member steps in, the run's members "
                        + "respawn here from then on (the checkpoint before it retired), each gets a short heal and "
                        + "sees a banner. Only that run's members, and only while it lasts; another party in the same "
                        + "copy keeps its own. Outside a dungeon run it does nothing.")
                .append(new KeyedCodec<>("RespawnOffset", Vector3dUtil.CODEC, false),
                        (e, v) -> e.respawnOffset = v, e -> e.respawnOffset)
                .metadata(EditorSchema.defaultVector(0.0, 0.0, 0.0))
                .documentation("Where a member respawns, in blocks from the volume's Position. It turns with the "
                        + "room when the room is placed turned. Unauthored means 0 0 0, the Position itself, so set "
                        + "it beside the statue, on the floor.").add()
                .append(new KeyedCodec<>("RespawnYaw", Codec.FLOAT, false),
                        (e, v) -> e.respawnYaw = v, e -> e.respawnYaw)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("Which way a member faces when they respawn, in degrees: 0 faces north (toward "
                        + "negative Z), 90 west, 180 south, 270 east. It turns with the room too. Unauthored means 0.")
                .add()
                .append(new KeyedCodec<>(HEAL_EFFECT_KEY, Codec.STRING, false),
                        (e, v) -> e.healEffect = v, e -> e.healEffect)
                .metadata(EditorSchema.assetRef(EntityEffect.class))
                .metadata(EditorSchema.defaultValue(DEFAULT_HEAL))
                .documentation("The entity effect every member of the run standing in the world gets, at its own "
                        + "duration. Unauthored means Zc_Checkpoint_Heal, a heal over 8 seconds.").add()
                .append(new KeyedCodec<>("BannerTitleKey", Codec.STRING, false),
                        (e, v) -> e.bannerTitleKey = v, e -> e.bannerTitleKey)
                .metadata(EditorSchema.defaultValue(DEFAULT_TITLE_KEY))
                .documentation("The banner's headline, a lang key. Unauthored means the library's \"Checkpoint "
                        + "reached\".").add()
                .append(new KeyedCodec<>("BannerSubtitleKey", Codec.STRING, false),
                        (e, v) -> e.bannerSubtitleKey = v, e -> e.bannerSubtitleKey)
                .metadata(EditorSchema.defaultValue(DEFAULT_SUBTITLE_KEY))
                .documentation("The line under the headline, a lang key. Unauthored means the library's line saying "
                        + "the party heals and comes back here if it falls.").add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigCheckpointEffect> codec() {
        return Holder.CODEC;
    }

    @Override
    public void execute(@Nonnull TriggerContext context) {
        try {
            UUID player = RoomKitTrigger.playerOf(context);
            DungeonRuns.LiveRun run = RoomKitTrigger.runOf(context, player);
            if (run == null) {
                return;
            }
            String placement = RoomKitTrigger.placement(context, null);
            Vector3d at = new Vector3d(context.getVolume().getPosition()).add(offset());
            if (!RunCheckpoints.shared().take(run.area(), run.runId(), placement,
                    new Transform(at, Facing.rotation(yaw())))) {
                return;
            }
            int healed = greet(context.getStore(), run);
            SafeLog.info(LOG + "the run " + run.runId() + " in " + run.area().world() + " area " + run.area().area()
                    + " reached the checkpoint " + placement + ": its members respawn at " + Math.round(at.x) + " "
                    + Math.round(at.y) + " " + Math.round(at.z) + "; " + healed + " of " + run.members().size()
                    + " member(s) healed");
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a checkpoint could not be taken", t);
        }
    }

    /**
     * Show every online member of {@code run} the banner and heal each one standing alive in {@code store}. How many
     * were healed.
     */
    private int greet(@Nonnull Store<EntityStore> store, @Nonnull DungeonRuns.LiveRun run) {
        Universe universe = Universe.get();
        if (universe == null) {
            return 0;
        }
        EntityEffect effect = healEffect();
        Message title = ContentKeys.tr(orDefault(bannerTitleKey, DEFAULT_TITLE_KEY));
        Message subtitle = ContentKeys.tr(orDefault(bannerSubtitleKey, DEFAULT_SUBTITLE_KEY));
        int healed = 0;
        for (UUID member : run.members()) {
            PlayerRef playerRef = universe.getPlayer(member);
            if (playerRef == null) {
                continue;
            }
            EventTitles.show(playerRef, title, subtitle, EventTitleStyle.Major, null);
            if (effect != null && heal(store, playerRef.getReference(), effect)) {
                healed++;
            }
        }
        return healed;
    }

    /** Give {@code effect} to the member at {@code ref} when they stand alive in {@code store}. */
    private static boolean heal(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull EntityEffect effect) {
        if (ref == null || !ref.isValid() || ref.getStore() != store
                || store.getComponent(ref, DeathComponent.getComponentType()) != null) {
            return false;
        }
        EffectControllerComponent controller = store.getComponent(ref, EffectControllerComponent.getComponentType());
        return controller != null && controller.addEffect(ref, effect, store);
    }

    /** The heal effect, or null when no loaded file defines it (logged once per id). */
    @Nullable
    private EntityEffect healEffect() {
        String id = orDefault(healEffect, DEFAULT_HEAL);
        EntityEffect effect = EntityEffect.getAssetMap().getAsset(id);
        if (effect == null && WARNED_HEALS.add(id.toLowerCase(Locale.ROOT))) {
            SafeLog.warn(LOG + "a checkpoint heals with the effect '" + id + "', which no loaded file defines, so "
                    + "its checkpoints heal nobody");
        }
        return effect;
    }

    /** {@code value} trimmed, or {@code fallback} when it is unauthored or blank (an inspector box cleared). */
    @Nonnull
    private static String orDefault(@Nullable String value, @Nonnull String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Nonnull
    private Vector3d offset() {
        return respawnOffset == null ? new Vector3d() : new Vector3d(respawnOffset);
    }

    private float yaw() {
        return respawnYaw == null ? 0f : respawnYaw;
    }

    /** A room placed turned turns its checkpoint's respawn spot and facing with it, as the engine's own effects do. */
    @Override
    public void rotateInPlace(float yawRadians, @Nonnull Vector3d volumeOrigin) {
        if (Math.abs(yawRadians) < YawRotation.EPSILON) {
            return;
        }
        if (respawnOffset != null) {
            Vector3d turned = new Vector3d(respawnOffset);
            YawRotation.rotate(turned, yawRadians);
            respawnOffset = turned;
        }
        respawnYaw = YawRotation.addDegrees(yaw(), yawRadians);
    }
}
