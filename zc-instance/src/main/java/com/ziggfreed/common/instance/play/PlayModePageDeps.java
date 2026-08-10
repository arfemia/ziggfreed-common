package com.ziggfreed.common.instance.play;

import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.instance.preset.QueueModeSet;
import com.ziggfreed.common.lobby.LobbyService;

/**
 * The immutable consumer-policy bundle a {@link PlayModePage} is built from. Built once
 * at the consumer's startup; the page stays mod-agnostic.
 *
 * @param lobby      the live-queue source (the page reads its roster + countdown via {@code currentQueueOf})
 * @param modes      resolves a preset id to its {@link QueueModeSet} (must be null-safe -> {@code QueueModeSet.defaults()})
 * @param presetName resolves a preset id to its display-name {@link Message} (for the difficulty line)
 * @param key        resolves a raw lang key to a {@link Message} (for an authored mode {@code LabelKey} override)
 * @param handler    the Public/Party/Solo launch policy
 * @param text       the locale-free chrome
 * @param claim      OPTIONAL pending-reward claim hook (a "Claim Rewards" button in the chooser); {@code null} = none
 */
public record PlayModePageDeps(@Nonnull LobbyService lobby,
                               @Nonnull Function<String, QueueModeSet> modes,
                               @Nonnull Function<String, Message> presetName,
                               @Nonnull Function<String, Message> key,
                               @Nonnull PlayModeHandler handler,
                               @Nonnull PlayScreenMessages text,
                               @Nullable PlayRewardClaim claim) {
}
