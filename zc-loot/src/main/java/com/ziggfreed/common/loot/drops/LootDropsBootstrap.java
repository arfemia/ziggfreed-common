package com.ziggfreed.common.loot.drops;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.asset.type.item.config.container.ItemDropContainer;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.ziggfreed.common.util.SafeLog;

/**
 * Our loot tables inside native drop lists: the {@code ZigLootable} container Type, into the engine's own drop
 * container codec before any drop list decodes, and the once-at-boot warning of what such a container cannot drop.
 * Called once from the wiring root's {@code setup()}.
 */
public final class LootDropsBootstrap {

    private LootDropsBootstrap() {
    }

    /** Install the Type and its boot warning. */
    public static void install(@Nonnull JavaPlugin plugin) {
        registerType(plugin);
        registerBootWarning(plugin);
    }

    /**
     * The Type, into {@code ItemDropContainer.CODEC} beside the engine's Multiple, Choice, Single, Droplist and Empty.
     * Not guarded: a failed registration throws here, at setup, rather than as an unknown Type inside an owner's drop
     * list later (R700).
     */
    private static void registerType(@Nonnull JavaPlugin plugin) {
        plugin.getCodecRegistry(ItemDropContainer.CODEC).register(ZigLootableDrop.TYPE, ZigLootableDrop.class,
                ZigLootableDrop.CODEC);
    }

    /** What a container cannot drop, warned once every store has loaded. */
    private static void registerBootWarning(@Nonnull JavaPlugin plugin) {
        try {
            plugin.getEventRegistry().register(BootEvent.class, event -> DropAudit.logAtBoot());
        } catch (Throwable t) {
            SafeLog.warn("[loot] drops: could not hang the drop-list audit on boot", t);
        }
    }
}
