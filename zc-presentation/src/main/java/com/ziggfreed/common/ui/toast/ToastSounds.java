package com.ziggfreed.common.ui.toast;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.sound.Sound3D;

/**
 * The sounds the library's own payout toasts carry, and the one helper that plays a toast's sound wherever the
 * toast is shown: drawn into an open page ({@link ToastablePage#showToast}) or sent to the corner feed
 * ({@link ToastDelivery}). Every id here is a base-game {@code SoundEvent}.
 *
 * <p><b>Who owns the sound of a payout.</b> Where the engine announces a feedback moment for the press, the
 * moment's own {@code Sound} plays it, on every server, whatever happens to the toast: a quest's or an
 * achievement's Collect ({@code Quest_Claimed} and {@code Achievement_Claimed}, collected), a hand-in that pays
 * at once ({@code Quest_Completed}) and one that parks ({@code Quest_Parked}). The page's own toast for the
 * same press stays silent ({@link ToastKind#REWARD} has no sound, and a parked line is built
 * {@link ToastSpec#silent()}), so one press is one sound. Where no moment fires, the toast carries the sound
 * itself through {@link ToastSpec#withSound}: a receipt ({@link #RECEIPT}: a conversation's Grant, a used item's
 * payout) and a purchase ({@link #PURCHASE}).
 */
public final class ToastSounds {

    /**
     * Something handed over with no quest behind it: a conversation's Grant, a used item's payout. The cue
     * players already know for an item landing in the bag.
     */
    public static final String RECEIPT = "SFX_Player_Pickup_Item";

    /** A purchase at a shop: coins changing hands. */
    public static final String PURCHASE = "SFX_Coins_Land";

    private ToastSounds() {
    }

    /**
     * {@code spec} with {@code soundId} as its sound when it has none of its own: a toast a caller silenced on
     * purpose, or one that already names a sound (its own or its kind's), is returned unchanged.
     */
    @Nonnull
    public static ToastSpec orDefault(@Nonnull ToastSpec spec, @Nonnull String soundId) {
        if (spec.isSilent() || spec.effectiveSoundId() != null) {
            return spec;
        }
        return spec.withSound(soundId);
    }

    /**
     * Play {@code spec}'s sound to the player at {@code playerRef}, once. World thread. Cosmetic and guarded: a
     * silent toast, a toast with no sound, a missing asset or a player who has gone is silence, never a throw.
     */
    public static void play(@Nonnull PlayerRef playerRef, @Nonnull ToastSpec spec) {
        String soundId = spec.effectiveSoundId();
        if (soundId == null || soundId.isEmpty()) {
            return;
        }
        play(playerRef, soundId);
    }

    /** {@link #play(PlayerRef, ToastSpec)} for a sound id already resolved. */
    static void play(@Nonnull PlayerRef playerRef, @Nullable String soundId) {
        if (soundId == null || soundId.isEmpty()) {
            return;
        }
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null) {
                return;
            }
            Sound3D.playAt(soundId, Sound3D.DEFAULT_CATEGORY, ref, ref.getStore(), "TOAST", false);
        } catch (Throwable ignored) {
            // Cosmetic only: an audio failure never breaks the toast or what raised it.
        }
    }
}
