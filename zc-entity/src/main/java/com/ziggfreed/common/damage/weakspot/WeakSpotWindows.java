package com.ziggfreed.common.damage.weakspot;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.map.IndexedLookupTableAssetMap;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Which effects open a weak-spot window, as a table by effect index: every effect whose own native {@code Tags}
 * carry {@value WeakSpots#TAG} (a {@code Parent}'s tags are its child's too), and every effect the settings list
 * under {@code Windows.Effects} (vanilla's, which no other file can tag). An effect tagged
 * {@value WeakSpots#OWNER_ONLY} opens the window for its owner alone.
 *
 * <p>The table is one immutable snapshot behind a volatile field, rebuilt on the first read after it is marked
 * (a settings fold, or an effect load or removal touching a tagged or listed effect), so the runtime effects a
 * grid writes during play cost no rebuild. A rebuild walks the effect map by index; one that throws logs once and
 * leaves an empty table until the next mark.
 */
final class WeakSpotWindows {

    /** No window. */
    static final byte NONE = 0;

    /** A window for every attacker. */
    static final byte ANYONE = 1;

    /** A window for the effect's owner alone. */
    static final byte OWNER = 2;

    /** The window bits ({@link #ANYONE} or {@link #OWNER}). */
    static final byte WINDOW_BITS = ANYONE | OWNER;

    /** Beside a window: it is spent by the hit it pays ({@value WeakSpots#ONCE}). */
    static final byte ONCE = 4;

    /** On the attacker: their next hit is a weak spot, spent by it ({@value WeakSpots#TAG_NEXT}). */
    static final byte NEXT = 8;

    private static final Object LOCK = new Object();

    private static final AtomicBoolean BUILD_FAILURE_LOGGED = new AtomicBoolean();

    /** The table, the ids it classified (lower-cased), for the load and removal checks, and whether any is NEXT. */
    private record Table(@Nonnull byte[] kinds, @Nonnull Set<String> ids, boolean anyNext) {
        static final Table EMPTY = new Table(new byte[0], Set.of(), false);
    }

    @Nonnull
    private static volatile Table table = Table.EMPTY;

    private static volatile boolean dirty = true;

    private WeakSpotWindows() {
    }

    static void markDirty() {
        dirty = true;
    }

    /** Marks the table when one of {@code loaded} carries the tag, is listed, or was classified before. */
    static void effectsLoaded(@Nonnull Map<String, EntityEffect> loaded) {
        if (dirty) {
            return;
        }
        try {
            Set<String> known = table.ids();
            Set<String> listed = WeakSpotSettingsConfig.getInstance().settings().windowEffects();
            for (Map.Entry<String, EntityEffect> entry : loaded.entrySet()) {
                String id = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
                if (known.contains(id) || listed.contains(id) || kindOf(entry.getValue(), Set.of(), id) != NONE) {
                    markDirty();
                    return;
                }
            }
        } catch (Throwable t) {
            markDirty();
        }
    }

    /** Marks the table when one of {@code removed} was classified. */
    static void effectsRemoved(@Nonnull Set<String> removed) {
        if (dirty) {
            return;
        }
        Set<String> known = table.ids();
        for (String id : removed) {
            if (id != null && known.contains(id.toLowerCase(Locale.ROOT))) {
                markDirty();
                return;
            }
        }
    }

    /**
     * Is a window open on {@code target} for {@code attacker}: does the target carry an effect that opens one for
     * everyone, or one that opens it for its owner and was applied by the attacker? Never throws.
     */
    static boolean openFor(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> target,
            @Nonnull Ref<EntityStore> attacker) {
        return windowOn(accessor, target, attacker) != NO_EFFECT;
    }

    /** No effect answers. */
    static final int NO_EFFECT = -1;

    /**
     * The effect index of a window open on {@code target} for {@code attacker}, one that stays open preferred over
     * one a paid hit spends ({@link #ONCE}), so a lasting window never spends a strike's own; {@link #NO_EFFECT} when
     * none is. Never throws.
     */
    static int windowOn(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> target,
            @Nonnull Ref<EntityStore> attacker) {
        EffectControllerComponent ctrl = accessor.getComponent(target, EffectControllerComponent.getComponentType());
        if (ctrl == null || ctrl.getActiveEffects().isEmpty()) {
            return NO_EFFECT;
        }
        byte[] kinds = current().kinds();
        if (kinds.length == 0) {
            return NO_EFFECT;
        }
        int spent = NO_EFFECT;
        for (ActiveEntityEffect active : ctrl.getActiveEffects().values()) {
            int index = active.getEntityEffectIndex();
            byte kind = index >= 0 && index < kinds.length ? kinds[index] : NONE;
            byte window = (byte) (kind & WINDOW_BITS);
            boolean open = window == ANYONE || (window == OWNER && attacker.equals(active.getOwner()));
            if (!open) {
                continue;
            }
            if ((kind & ONCE) == 0) {
                return index;
            }
            spent = index;
        }
        return spent;
    }

    /** Is the effect at {@code index} a window a paid hit spends ({@value WeakSpots#ONCE})? */
    static boolean spentByHit(int index) {
        byte[] kinds = current().kinds();
        return index >= 0 && index < kinds.length && (kinds[index] & ONCE) != 0;
    }

    /**
     * The effect index of an effect on {@code attacker} that makes their next hit a weak spot
     * ({@value WeakSpots#TAG_NEXT}), or {@link #NO_EFFECT}. One look at a flag first, so a server with no such effect
     * reads no attacker's effects. Never throws.
     */
    static int nextOn(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> attacker) {
        Table now = current();
        if (!now.anyNext()) {
            return NO_EFFECT;
        }
        try {
            EffectControllerComponent ctrl =
                    accessor.getComponent(attacker, EffectControllerComponent.getComponentType());
            if (ctrl == null || ctrl.getActiveEffects().isEmpty()) {
                return NO_EFFECT;
            }
            byte[] kinds = now.kinds();
            for (ActiveEntityEffect active : ctrl.getActiveEffects().values()) {
                int index = active.getEntityEffectIndex();
                if (index >= 0 && index < kinds.length && (kinds[index] & NEXT) != 0) {
                    return index;
                }
            }
        } catch (Throwable ignored) {
            // An attacker whose effects cannot be read wears none.
        }
        return NO_EFFECT;
    }

    /**
     * The window an effect opens: {@link #OWNER} when its tag names {@value WeakSpots#OWNER_ONLY}, {@link #ANYONE}
     * when it carries the tag otherwise or is listed, else {@link #NONE}; with {@link #ONCE} beside it when the tag
     * names {@value WeakSpots#ONCE}, and {@link #NEXT} when it carries {@value WeakSpots#TAG_NEXT}. Pure over the
     * effect's raw tags.
     */
    static byte kindOf(@Nullable Map<String, String[]> rawTags, boolean listed) {
        String[] values = rawTags == null ? null : tagValues(rawTags, WeakSpots.TAG);
        byte next = rawTags != null && tagValues(rawTags, WeakSpots.TAG_NEXT) != null ? NEXT : NONE;
        if (values != null) {
            byte window = ANYONE;
            byte once = NONE;
            for (String value : values) {
                if (value == null) {
                    continue;
                }
                if (value.trim().equalsIgnoreCase(WeakSpots.OWNER_ONLY)) {
                    window = OWNER;
                } else if (value.trim().equalsIgnoreCase(WeakSpots.ONCE)) {
                    once = ONCE;
                }
            }
            return (byte) (window | once | next);
        }
        return (byte) ((listed ? ANYONE : NONE) | next);
    }

    @Nullable
    private static String[] tagValues(@Nonnull Map<String, String[]> rawTags, @Nonnull String key) {
        for (Map.Entry<String, String[]> entry : rawTags.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue() == null ? new String[0] : entry.getValue();
            }
        }
        return null;
    }

    private static byte kindOf(@Nullable EntityEffect effect, @Nonnull Set<String> listed, @Nonnull String id) {
        if (effect == null) {
            return NONE;
        }
        AssetExtraInfo.Data data = EntityEffect.CODEC.getData(effect);
        return kindOf(data == null ? null : data.getRawTags(), listed.contains(id));
    }

    @Nonnull
    private static Table current() {
        if (dirty) {
            rebuild();
        }
        return table;
    }

    private static void rebuild() {
        synchronized (LOCK) {
            if (!dirty) {
                return;
            }
            // Cleared before the walk, so a load landing mid-walk marks it again and the next read rebuilds.
            dirty = false;
            try {
                table = build();
            } catch (Throwable t) {
                if (BUILD_FAILURE_LOGGED.compareAndSet(false, true)) {
                    SafeLog.warn("[weakspot] could not read which effects open a weak-spot window; none opens one"
                            + " until the next effect load", t);
                }
                table = Table.EMPTY;
            }
        }
    }

    @Nonnull
    private static Table build() {
        IndexedLookupTableAssetMap<String, EntityEffect> map = EntityEffect.getAssetMap();
        Set<String> listed = WeakSpotSettingsConfig.getInstance().settings().windowEffects();
        int end = map.getNextIndex();
        byte[] kinds = new byte[Math.max(0, end)];
        Set<String> ids = new HashSet<>();
        boolean anyNext = false;
        for (int index = 0; index < end; index++) {
            EntityEffect effect = map.getAsset(index);
            if (effect == null || effect.getId() == null) {
                continue;
            }
            String id = effect.getId().toLowerCase(Locale.ROOT);
            byte kind = kindOf(effect, listed, id);
            if (kind != NONE) {
                kinds[index] = kind;
                ids.add(id);
                anyNext |= (kind & NEXT) != 0;
            }
        }
        return new Table(kinds, Set.copyOf(ids), anyNext);
    }
}
