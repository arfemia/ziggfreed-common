package com.ziggfreed.common.track;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.track.curve.CurveTable;
import com.ziggfreed.common.track.pick.PickOptionAsset;
import com.ziggfreed.common.track.pick.PickOptionConfig;
import com.ziggfreed.common.track.pick.TrackPickAsset;
import com.ziggfreed.common.track.pick.TrackPickConfig;

/**
 * Every word the track module says: its lines are keys in {@code ziggfreedcommon.track.lang}, and a track or a
 * tier is called by its own authored key, else its id.
 */
public final class TrackText {

    /** The key family every line here resolves under. */
    public static final String PREFIX = "ziggfreedcommon.track.";

    private TrackText() {
    }

    /** One of this module's lines, its arguments bound as given. */
    @Nonnull
    public static Message line(@Nonnull String key, @Nonnull Object... args) {
        return Msg.key(PREFIX + key, args);
    }

    /** What {@code track} is called: its {@code Text.TitleKey}, else its id. */
    @Nonnull
    public static Message name(@Nonnull TrackAsset track) {
        String key = track.titleKey();
        return key != null ? Msg.key(key) : Msg.raw(track.getId());
    }

    /** What the track {@code trackId} is called, or null when no folded track has that id. */
    @Nullable
    public static Message nameOf(@Nullable String trackId) {
        if (!TrackAsset.usableId(trackId)) {
            return null;
        }
        TrackAsset track = TrackConfig.getInstance().resolve(trackId.trim());
        return track == null ? null : name(track);
    }

    /**
     * What the option or pick {@code id} is called: an option file's {@code Text.TitleKey}, else a pick file's, else
     * the id as written.
     */
    @Nonnull
    public static Message pickName(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return Msg.raw("");
        }
        String trimmed = id.trim();
        PickOptionAsset option = PickOptionConfig.getInstance().resolve(trimmed);
        String key = option == null ? null : option.titleKey();
        if (key == null) {
            TrackPickAsset pick = TrackPickConfig.getInstance().resolve(trimmed);
            key = pick == null ? null : pick.titleKey();
        }
        return key != null ? Msg.key(key) : Msg.raw(trimmed);
    }

    /** What {@code tier} is called: its {@code Name}, else its id. */
    @Nonnull
    public static Message tierName(@Nonnull CurveTable.Tier tier) {
        return tier.nameKey() != null ? Msg.key(tier.nameKey()) : Msg.raw(tier.id());
    }

    /**
     * A Claim title argument naming {@code track}: its title key, resolved on the reader's own client, else its id
     * as written ({@link Claim#keyArg}).
     */
    @Nonnull
    public static String claimNameArg(@Nonnull TrackAsset track) {
        String key = track.titleKey();
        return key != null ? Claim.keyArg(key) : track.getId();
    }
}
