package com.ziggfreed.common.instance.play;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;

import com.ziggfreed.common.instance.preset.QueueModeId;

/**
 * The locale-free chrome text for the {@link PlayModePage} (the {@code QueueMessages} /
 * {@code PartyScreenMessages} convention): the title, the chosen-difficulty line, the
 * three mode-card labels + sublabels, and the live-roster chrome the page shows once
 * queued. The consumer returns pre-built, client-resolved {@link Message}s from its own
 * {@code Lang}, so common stays locale-free.
 */
public interface PlayScreenMessages {

    @Nonnull Message title();

    /** "Difficulty: {presetName}" - {@code presetName} is a NESTED Message so it resolves per-locale. */
    @Nonnull Message difficulty(@Nonnull Message presetName);

    /** The default card label for a mode (an authored {@code LabelKey} overrides this per preset). */
    @Nonnull Message modeLabel(@Nonnull QueueModeId mode);

    /** The card sublabel / one-line description for a mode. */
    @Nonnull Message modeDesc(@Nonnull QueueModeId mode);

    // ----- live roster / ready state -----

    /** A "{size}/{max} players" label. */
    @Nonnull Message playerCount(int size, int maxSize);

    /** Status while gathering players. */
    @Nonnull Message statusWaiting();

    /** Status during the launch countdown ("Launching in {seconds}"). */
    @Nonnull Message statusCountdown(int seconds);

    /** Status at launch. */
    @Nonnull Message statusLaunching();

    /** A derived upper-bound wait estimate ("up to ~{seconds}s"). */
    @Nonnull Message waitEstimate(int seconds);

    @Nonnull Message leaveButton();

    /** The "Claim Rewards" button label (chooser state; only shown when the player has pending spoils). */
    @Nonnull Message claimButton();

    /** One-shot toast after claiming pending spoils from the chooser. */
    @Nonnull Message toastClaimed();

    /** Shown when the viewer is no longer in any queue (left, or already launched). */
    @Nonnull Message notInQueue();

    /** One-shot in-page toast when the screen first shows the live queue ("You're in - {size}/{max}"). */
    @Nonnull Message toastQueued(int size, int maxSize);
}
