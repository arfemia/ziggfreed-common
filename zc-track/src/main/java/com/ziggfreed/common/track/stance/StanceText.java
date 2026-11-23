package com.ziggfreed.common.track.stance;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.track.asset.SlotRow;

/**
 * Every word the stances say: keys in {@code ziggfreedcommon.stance.lang}, and a stance or a kind called by its own
 * authored key, else its id. Why a hold or a release was refused reads through {@code TrackText.refusal}, the one
 * refusal wording every page shares.
 */
public final class StanceText {

    /** The key family every line here resolves under. */
    public static final String PREFIX = "ziggfreedcommon.stance.";

    private StanceText() {
    }

    /** One of the stances' lines, its arguments bound as given. */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }

    /** What {@code stance} is called: its {@code Text.TitleKey}, else its id. */
    @Nonnull
    public static Message name(@Nonnull TrackStanceAsset stance) {
        String key = stance.titleKey();
        return key != null ? Msg.key(key) : Msg.raw(stance.getId());
    }

    /** What the stance {@code stanceId} is called, or null when no switched-on stance has that id. */
    @Nullable
    public static Message nameOf(@Nullable String stanceId) {
        TrackStanceAsset stance = TrackStanceConfig.getInstance().live(stanceId);
        return stance == null ? null : name(stance);
    }

    /** What the kind {@code kind} of {@code stance} is called: its {@code Text.TitleKey}, else its word as written. */
    @Nonnull
    public static Message kindName(@Nonnull TrackStanceAsset stance, @Nullable String kind) {
        TrackStanceAsset.Kind declared = stance.kind(kind);
        String key = declared == null ? null : declared.titleKey();
        return key != null ? Msg.key(key) : Msg.raw(kind == null ? "" : kind.trim());
    }

    /** What a slot is called: the slot line with its id as the stance writes it ({@code Slots[].Id}). */
    @Nonnull
    public static Message slotName(@Nonnull TrackStanceAsset stance, @Nonnull String slotKey) {
        for (SlotRow row : stance.slots()) {
            if (row.key().equals(slotKey) && row.id() != null) {
                return line("slot.name", row.id());
            }
        }
        return line("slot.name", slotKey);
    }
}
