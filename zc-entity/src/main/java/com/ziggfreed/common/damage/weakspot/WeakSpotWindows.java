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

    private static final Object LOCK = new Object();

    private static final AtomicBoolean BUILD_FAILURE_LOGGED = new AtomicBoolean();

    /** The table and the ids it classified (lower-cased), for the load and removal checks. */
    private record Table(@Nonnull byte[] kinds, @Nonnull Set<String> ids) {
        static final Table EMPTY = new Table(new byte[0], Set.of());
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
        EffectControllerComponent ctrl = accessor.getComponent(target, EffectControllerComponent.getComponentType());
        if (ctrl == null || ctrl.getActiveEffects().isEmpty()) {
            return false;
        }
        byte[] kinds = current().kinds();
        if (kinds.length == 0) {
            return false;
        }
        for (ActiveEntityEffect active : ctrl.getActiveEffects().values()) {
            int index = active.getEntityEffectIndex();
            byte kind = index >= 0 && index < kinds.length ? kinds[index] : NONE;
            if (kind == ANYONE) {
                return true;
            }
            if (kind == OWNER && attacker.equals(active.getOwner())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The window an effect opens: {@link #OWNER} when its tag names {@value WeakSpots#OWNER_ONLY}, {@link #ANYONE}
     * when it carries the tag otherwise or is listed, else {@link #NONE}. Pure over the effect's raw tags.
     */
    static byte kindOf(@Nullable Map<String, String[]> rawTags, boolean listed) {
        String[] values = rawTags == null ? null : tagValues(rawTags);
        if (values != null) {
            for (String value : values) {
                if (value != null && value.trim().equalsIgnoreCase(WeakSpots.OWNER_ONLY)) {
                    return OWNER;
                }
            }
            return ANYONE;
        }
        return listed ? ANYONE : NONE;
    }

    @Nullable
    private static String[] tagValues(@Nonnull Map<String, String[]> rawTags) {
        for (Map.Entry<String, String[]> entry : rawTags.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(WeakSpots.TAG)) {
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
            }
        }
        return new Table(kinds, Set.copyOf(ids));
    }
}
