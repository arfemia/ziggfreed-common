package com.ziggfreed.common.instance.play;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The consumer policy a {@link PlayModePage} invokes when a player picks a queue mode
 * card. Each method maps a {@link com.ziggfreed.common.instance.preset.QueueModeId} to a
 * distinct lobby path; the page itself stays mod-agnostic and imports no lobby wiring.
 * All three run on the world thread (the page's event handler), so a hop to {@code
 * world.execute} is the consumer's call only if it needs one.
 *
 * <p>{@code presetId} is the difficulty the player chose upstream (never null at a card
 * click; the page only shows cards once a preset is in hand). The consumer resolves a
 * blank/unknown id to its own default.
 */
public interface PlayModeHandler {

    /**
     * Queue the player into the SHARED public queue for {@code presetId} (backfills with
     * strangers), then leave the player on the {@link PlayModePage} so it morphs to the
     * live roster - the page reopens itself, so the handler need not open a page.
     */
    void queuePublic(@Nonnull PlayerRef player, @Nonnull Ref<EntityStore> ref,
                     @Nonnull Store<EntityStore> store, @Nullable String presetId);

    /**
     * Launch the player SOLO and immediately (no fill window, no strangers) into
     * {@code presetId}. The round starts at once; the page is closed by the handler or by
     * the round teleport.
     */
    void launchSolo(@Nonnull PlayerRef player, @Nonnull Ref<EntityStore> ref,
                    @Nonnull Store<EntityStore> store, @Nullable String presetId);

    /**
     * Open the party manager for {@code presetId}: the player forms / manages a group and
     * the owner's Queue button carries this difficulty. The handler owns the next page.
     */
    void openParty(@Nonnull PlayerRef player, @Nonnull Ref<EntityStore> ref,
                   @Nonnull Store<EntityStore> store, @Nullable String presetId);
}
