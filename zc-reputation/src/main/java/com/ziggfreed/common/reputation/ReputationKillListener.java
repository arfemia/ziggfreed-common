package com.ziggfreed.common.reputation;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentListener;

/**
 * Standing per kill: hangs on the shared moment stream, answers only the kill moment, and looks the dead
 * NPC's role (the moment's Target) up in every switched-on reputation's kill table. The kill moment fires
 * inside the engine's death system, so the change itself runs on a LATER world task ({@link #WORLD_THREAD}):
 * a Beyond payout or a ground drop must never run while the store is processing.
 */
public final class ReputationKillListener implements MomentListener {

    /** When the change runs. */
    @FunctionalInterface
    public interface Later {

        void run(@Nonnull Moment moment, @Nonnull Runnable task);
    }

    /** Production: the kill's own world, after the death system that fired the moment. */
    public static final Later WORLD_THREAD =
            (moment, task) -> moment.store().getExternalData().getWorld().execute(task);

    private static final String SOURCE_PREFIX = "kill:";

    private final ReputationService service;
    private final Later later;

    public ReputationKillListener(@Nonnull ReputationService service, @Nonnull Later later) {
        this.service = service;
        this.later = later;
    }

    @Override
    public void react(@Nonnull Moment moment) {
        if (!ReputationKills.KILL_KIND.equalsIgnoreCase(moment.kindId())) {
            return;
        }
        Map<String, Integer> amounts = ReputationKills.amounts(service.all(), moment.target(),
                service.engine()::roleInGroup);
        if (amounts.isEmpty()) {
            return;
        }
        Ref<EntityStore> ref = moment.ref();
        String source = SOURCE_PREFIX + moment.target();
        later.run(moment, () -> {
            if (ref.isValid()) {
                apply(ref.getStore(), ref, amounts, source);
            }
        });
    }

    /** Move each reputation once for this kill. World thread, outside any system's dispatch. */
    void apply(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull Map<String, Integer> amounts, @Nonnull String source) {
        for (Map.Entry<String, Integer> entry : amounts.entrySet()) {
            service.change(store, ref, entry.getKey(), entry.getValue(), source);
        }
    }
}
