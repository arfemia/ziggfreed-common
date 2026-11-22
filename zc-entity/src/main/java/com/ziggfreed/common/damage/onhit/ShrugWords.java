package com.ziggfreed.common.damage.onhit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.damage.weakspot.WeakSpotSettingsAsset;
import com.ziggfreed.common.damage.weakspot.WeakSpotSettingsConfig;
import com.ziggfreed.common.stats.condition.ControlKind;
import com.ziggfreed.common.util.SafeLog;

/**
 * The word a foe shows a player whose crowd control it shrugged (R1134): zc-entity's {@code BossRules} hands it every
 * refusal that names a caster, a boss's own rules shrugging a kind or a talent's control on a boss alike. It is the
 * engine's combat text over the foe, queued to that player's viewer alone ({@link HitWords}), on every shrug but at
 * most once every {@value #EVERY_SECONDS} seconds per player, foe and kind, while the owner's {@code ShrugText} is on
 * ({@code WeakSpotSettings}) and the player's own choice wants it: the same Settings switch as the critical hit's word
 * ({@link Crits#installWordRule}). A caster that is no player (a fight's own stun, a creature's taunt) is told nothing.
 * World thread; never throws.
 */
public final class ShrugWords {

    /** The least time between two words to one player over one foe for one kind of control. */
    public static final int EVERY_SECONDS = 4;

    /** The word's lang key, in this module's {@code ziggfreedcommon.control.lang}. */
    static final String TEXT_KEY = "ziggfreedcommon.control.immune";

    /** Where the word floats: straight over the foe, between the crit's and the weak spot's. */
    static final float TEXT_ANGLE = 0f;

    private static final long EVERY_NANOS = EVERY_SECONDS * 1_000_000_000L;

    /** How many throttle entries are kept before the stale ones are swept. */
    private static final int SWEEP_PAST = 256;

    /** When each player was last shown the word over each foe for each kind (the kind's ordinal, -1 for none). */
    private static final Map<Key, Long> SHOWN = new ConcurrentHashMap<>();

    private record Key(@Nonnull Ref<EntityStore> caster, @Nonnull Ref<EntityStore> foe, int kind) {
    }

    private ShrugWords() {
    }

    /**
     * Show {@code caster} the word over {@code foe}, which shrugged their control of {@code kind} (null for a control
     * of no kind), unless the throttle, the owner's switch or the player's own choice says not to.
     */
    public static void show(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> caster,
            @Nonnull Ref<EntityStore> foe, @Nullable ControlKind kind) {
        if (caster == null || !caster.isValid() || !foe.isValid() || caster == foe) {
            return;
        }
        try {
            WeakSpotSettingsAsset settings = WeakSpotSettingsConfig.getInstance().settings();
            if (!settings.shrugTextEnabled()) {
                return;
            }
            Store<EntityStore> store = storeOf(accessor);
            if (store == null || caster.getStore() != store
                    || store.getComponent(caster, EntityTrackerSystems.EntityViewer.getComponentType()) == null) {
                return;
            }
            if (!Crits.wordWanted(store, caster) || !due(new Key(caster, foe, kind == null ? -1 : kind.ordinal()))) {
                return;
            }
            HitWords.show(store, caster, foe, TEXT_KEY, settings.shrugTextColor(), TEXT_ANGLE);
        } catch (Throwable t) {
            SafeLog.fine("[control] the shrug word failed: " + t.getMessage());
        }
    }

    /** Is the word due for {@code key} now: never shown, or shown {@value #EVERY_SECONDS} seconds ago or more? */
    private static boolean due(@Nonnull Key key) {
        long now = System.nanoTime();
        boolean[] due = {false};
        SHOWN.compute(key, (k, last) -> {
            if (last == null || now - last >= EVERY_NANOS) {
                due[0] = true;
                return now;
            }
            return last;
        });
        if (due[0] && SHOWN.size() > SWEEP_PAST) {
            SHOWN.entrySet().removeIf(e -> now - e.getValue() >= EVERY_NANOS || !e.getKey().foe().isValid()
                    || !e.getKey().caster().isValid());
        }
        return due[0];
    }

    /** The store behind {@code accessor}: itself, or a command buffer's; null for any other kind. */
    @Nullable
    private static Store<EntityStore> storeOf(@Nonnull ComponentAccessor<EntityStore> accessor) {
        if (accessor instanceof Store<EntityStore> store) {
            return store;
        }
        if (accessor instanceof CommandBuffer<EntityStore> buffer) {
            return buffer.getStore();
        }
        return null;
    }
}
