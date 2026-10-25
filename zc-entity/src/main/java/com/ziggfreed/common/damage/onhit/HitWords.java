package com.ziggfreed.common.damage.onhit;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.CombatTextUpdate;
import com.hypixel.hytale.server.core.asset.util.ColorParseUtil;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.i18n.PlainText;
import com.ziggfreed.common.util.SafeLog;

/**
 * The floating word a hit step shows its attacker over the target (a weak spot's, a crit's): the engine's own combat
 * text, queued to the attacker's viewer alone, only when the attacker is a player who can see the target. The packet
 * takes plain text, so the word is read from its lang key in the server's language. Never throws.
 */
public final class HitWords {

    private HitWords() {
    }

    /** Show the word {@code key} names, in {@code color} ({@code #RRGGBB}), at {@code angleDeg} over the target. */
    public static void show(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> attacker,
            @Nonnull Ref<EntityStore> target, @Nonnull String key, @Nonnull String color, float angleDeg) {
        try {
            EntityTrackerSystems.EntityViewer viewer =
                    store.getComponent(attacker, EntityTrackerSystems.EntityViewer.getComponentType());
            if (viewer == null || !viewer.visible.contains(target)) {
                return;
            }
            String text = PlainText.of(Msg.key(key));
            if (text.isEmpty() || text.equals(key)) {
                return;
            }
            CombatTextUpdate update = new CombatTextUpdate();
            update.hitAngleDeg = angleDeg;
            update.text = text;
            update.color = ColorParseUtil.parseColor(color);
            viewer.queueUpdate(target, update);
        } catch (Throwable t) {
            SafeLog.fine("[onhit] the combat text failed: " + t.getMessage());
        }
    }
}
