package com.ziggfreed.common.instance.dungeon.omen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.instance.dungeon.DungeonRuns;
import com.ziggfreed.common.util.SafeLog;

/**
 * A run's omens as the running server applies them ({@link DungeonOmenAsset}, drawn by {@link OmenDraw}):
 * <ul>
 *   <li>{@link #draw}: a tier's omens this week, the first {@code Omens.Count} of the dungeon's week's set, which the
 *       gate lists and {@code DungeonRuns} keeps on each run at its start; {@link #weekSet} is the week's set;</li>
 *   <li>{@link #holdOn}: on the copy's world thread, once a second while a member stands in the run, each omen's player
 *       effects renewed on every member there (a timed effect to its full length, a lasting one put back if it fell
 *       off); {@link #takeOff} takes them off a member who left, on the world they are in now;</li>
 *   <li>{@link #mobEffects} and {@link #putOn}: the effects {@link OmenMobs} puts on each creature that comes into
 *       being in a run's area;</li>
 *   <li>the factor {@value #FACTOR} ({@link #contribute}): with an omen's id as its {@code Param}, 1 when the run the
 *       subject stands in drew it, 0 when it did not, and nothing (a shut gate) for a subject in no run, so a room's
 *       {@code ZigRequires} opens a door or raises a wave only under that omen.</li>
 * </ul>
 * An effect id no loaded effect answers to is skipped, said once in the log; the audit reports it at boot.
 */
public final class RunOmens {

    /** Who the factor is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    /** The omen reading: Param an omen id, 1 when the subject's run drew it. */
    public static final String FACTOR = "ziggfreedcommon:dungeon_omen";

    private static final String LOG = "[dungeon] ";

    /** The glue between two omen names in a list: {@code {0}, {1}} in English. */
    private static final String LIST_KEY = "ziggfreedcommon.dungeon.omen.list";

    /** Effect ids already warned about. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private RunOmens() {
    }

    /** Claim {@value #FACTOR} process-wide. Once, at setup. */
    public static void contribute() {
        FactorContributions.register(FACTOR, OWNER, RunOmens::resolve);
    }

    // ==================== drawing ====================

    /**
     * The omens {@code dungeon}'s pool gives this week ({@link OmenDraw#weekSet}), by id as their files name them: its
     * {@code Omens.Pool} as loaded, else every loaded omen.
     */
    @Nonnull
    public static List<String> weekSet(@Nonnull DungeonAsset dungeon, long nowMs) {
        return OmenDraw.weekSet(pool(dungeon), dungeon.omensPerWeek(), OmenDraw.weekOf(nowMs));
    }

    /**
     * {@code dungeon}'s omens this week ({@link OmenDraw#forTier}): the first {@code Omens.Count} of the week's set at
     * {@code nowMs}, the same for every run of it that week and nesting under a higher tier's. A run's start keeps them,
     * and the gate lists them.
     */
    @Nonnull
    public static List<String> draw(@Nonnull DungeonAsset dungeon, long nowMs) {
        int count = dungeon.omenCount();
        return count <= 0 ? List.of() : OmenDraw.forTier(weekSet(dungeon, nowMs), count);
    }

    /** The loaded omens {@code dungeon}'s pool names, else every loaded omen, each by its file's own id. */
    @Nonnull
    static List<String> pool(@Nonnull DungeonAsset dungeon) {
        DungeonOmenConfig omens = DungeonOmenConfig.getInstance();
        List<String> out = new ArrayList<>();
        List<String> authored = dungeon.omenPool();
        if (authored.isEmpty()) {
            for (DungeonOmenAsset omen : omens.all().values()) {
                if (omen != null && omen.getId() != null) {
                    out.add(omen.getId());
                }
            }
            return out;
        }
        for (String id : authored) {
            DungeonOmenAsset omen = omens.resolve(id);
            if (omen != null && omen.getId() != null) {
                out.add(omen.getId());
            }
        }
        return out;
    }

    // ==================== words ====================

    /**
     * {@code omens} by name as one line, each after the last behind the {@code omen.list} glue (a comma in English), so
     * it may be nested as a param; null for none.
     */
    @Nullable
    public static Message names(@Nonnull List<String> omens) {
        Message out = null;
        for (String omen : omens) {
            Message name = name(omen);
            out = out == null ? name : Msg.key(LIST_KEY, out, name);
        }
        return out;
    }

    /** An omen's name as players read it: its {@code Text.NameKey}, else its id. */
    @Nonnull
    public static Message name(@Nonnull String omenId) {
        DungeonOmenAsset omen = DungeonOmenConfig.getInstance().resolve(omenId);
        String key = omen == null ? null : omen.nameKey();
        return key == null ? Msg.raw(omenId) : ContentKeys.tr(key);
    }

    /** An omen's line saying what it does ({@code Text.DescriptionKey}), or null when it names none. */
    @Nullable
    public static Message description(@Nonnull String omenId) {
        DungeonOmenAsset omen = DungeonOmenConfig.getInstance().resolve(omenId);
        String key = omen == null ? null : omen.descriptionKey();
        return key == null ? null : ContentKeys.tr(key);
    }

    // ==================== players ====================

    /** Whether any of {@code omens} holds an effect on a run's players. */
    public static boolean holdsPlayers(@Nonnull List<String> omens) {
        for (String id : omens) {
            DungeonOmenAsset omen = DungeonOmenConfig.getInstance().resolve(id);
            if (omen != null && !omen.playerEffects().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * On {@code world}'s thread: renew {@code omens}' player effects on each of {@code members} standing there alive, a
     * timed effect to its full length, a lasting one put back if it is gone. Nothing off the world thread (a copy that
     * stopped taking tasks); never throws.
     */
    public static void holdOn(@Nonnull World world, @Nonnull List<UUID> members, @Nonnull List<String> omens) {
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            if (!store.isInThread()) {
                return;
            }
            List<EntityEffect> effects = effects(omens, DungeonOmenAsset::playerEffects);
            Universe universe = Universe.get();
            if (effects.isEmpty() || universe == null) {
                return;
            }
            for (UUID member : members) {
                PlayerRef player = universe.getPlayer(member);
                Ref<EntityStore> ref = player == null ? null : player.getReference();
                if (ref == null || !ref.isValid() || ref.getStore() != store
                        || store.getComponent(ref, DeathComponent.getComponentType()) != null) {
                    continue;
                }
                EffectControllerComponent controller = store.getComponent(ref,
                        EffectControllerComponent.getComponentType());
                if (controller == null) {
                    continue;
                }
                for (EntityEffect effect : effects) {
                    if (effect.isInfinite()) {
                        if (!controller.hasEffect(effect)) {
                            controller.addEffect(ref, effect, store);
                        }
                    } else {
                        controller.addEffect(ref, effect, effect.getDuration(), OverlapBehavior.OVERWRITE, store);
                    }
                }
            }
        } catch (Throwable t) {
            SafeLog.fine(LOG + "a run's omens could not be held on its players: " + t.getMessage());
        }
    }

    /**
     * {@code player} left a run that drew {@code omens}: its player effects come off, on the thread of the world they
     * stand in now. Nothing for a player offline or between worlds (a timed effect wears off on its own); never throws.
     */
    public static void takeOff(@Nonnull UUID player, @Nonnull List<String> omens) {
        try {
            Universe universe = Universe.get();
            PlayerRef playerRef = universe == null ? null : universe.getPlayer(player);
            Ref<EntityStore> ref = playerRef == null ? null : playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                return;
            }
            List<EntityEffect> effects = effects(omens, DungeonOmenAsset::playerEffects);
            if (effects.isEmpty()) {
                return;
            }
            World world = ref.getStore().getExternalData().getWorld();
            world.execute(() -> remove(ref, effects));
        } catch (Throwable t) {
            SafeLog.fine(LOG + "a run's omens could not be taken off a player who left: " + t.getMessage());
        }
    }

    /** On the player's world thread: each of {@code effects} they still carry comes off. */
    private static void remove(@Nonnull Ref<EntityStore> ref, @Nonnull List<EntityEffect> effects) {
        try {
            if (!ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            EffectControllerComponent controller = store.getComponent(ref,
                    EffectControllerComponent.getComponentType());
            if (controller == null) {
                return;
            }
            for (EntityEffect effect : effects) {
                int index = EntityEffect.getAssetMap().getIndex(effect.getId());
                if (index != AssetMapWithIndexes.NOT_FOUND && controller.hasEffect(effect)) {
                    controller.removeEffect(ref, index, store);
                }
            }
        } catch (Throwable t) {
            SafeLog.fine(LOG + "a run's omens could not come off a player: " + t.getMessage());
        }
    }

    // ==================== creatures ====================

    /** The effects {@code omens} put on a run's creatures, loaded, in the order written. */
    @Nonnull
    public static List<EntityEffect> mobEffects(@Nonnull List<String> omens) {
        return effects(omens, DungeonOmenAsset::mobEffects);
    }

    /** On the creature's world thread: put {@code effects} on it, each by its own length and overlap. Never throws. */
    public static void putOn(@Nonnull Ref<EntityStore> ref, @Nonnull List<EntityEffect> effects) {
        try {
            if (!ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            EffectControllerComponent controller = store.getComponent(ref,
                    EffectControllerComponent.getComponentType());
            if (controller == null) {
                return;
            }
            for (EntityEffect effect : effects) {
                controller.addEffect(ref, effect, store);
            }
        } catch (Throwable t) {
            SafeLog.fine(LOG + "a run's omens could not be put on a creature: " + t.getMessage());
        }
    }

    // ==================== the factor ====================

    /**
     * {@value #FACTOR}: 1 when the run the subject stands in drew the omen {@code Param} names (matched without regard to
     * case), 0 when it did not; null for a blank {@code Param}, no live subject, or a subject in no run. A player is
     * placed by the area they stand in, anything else by where it stands. On the subject's world thread.
     */
    @Nullable
    static Double resolve(@Nonnull FactorContext ctx) {
        String omen = ctx.param();
        if (omen == null || omen.isBlank() || !ctx.hasLiveSubject()) {
            return null;
        }
        Store<EntityStore> store = ctx.store();
        Ref<EntityStore> subject = ctx.subject();
        if (store == null || subject == null) {
            return null;
        }
        UUID worldUuid = store.getExternalData().getWorld().getWorldConfig().getUuid();
        PlayerRef player = store.getComponent(subject, PlayerRef.getComponentType());
        List<String> omens;
        if (player != null) {
            omens = DungeonRuns.shared().omensAt(player.getUuid(), worldUuid, null);
        } else {
            TransformComponent at = store.getComponent(subject, TransformComponent.getComponentType());
            omens = at == null ? null : DungeonRuns.shared().omensAt(null, worldUuid, new Vector3d(at.getPosition()));
        }
        if (omens == null) {
            return null;
        }
        String wanted = omen.trim();
        for (String drawn : omens) {
            if (drawn.equalsIgnoreCase(wanted)) {
                return 1.0;
            }
        }
        return 0.0;
    }

    // ==================== internals ====================

    /** The loaded effects {@code which} names across {@code omens}, each once, in the order written. */
    @Nonnull
    private static List<EntityEffect> effects(@Nonnull List<String> omens,
            @Nonnull Function<DungeonOmenAsset, List<String>> which) {
        List<EntityEffect> out = new ArrayList<>();
        for (String id : omens) {
            DungeonOmenAsset omen = DungeonOmenConfig.getInstance().resolve(id);
            if (omen == null) {
                continue;
            }
            for (String effectId : which.apply(omen)) {
                EntityEffect effect = EntityEffect.getAssetMap().getAsset(effectId);
                if (effect == null) {
                    if (WARNED.add(effectId.toLowerCase(Locale.ROOT))) {
                        SafeLog.warn(LOG + "the omen " + id + " names the effect " + effectId
                                + ", which no loaded file defines, so it is skipped");
                    }
                } else if (!out.contains(effect)) {
                    out.add(effect);
                }
            }
        }
        return out;
    }
}
