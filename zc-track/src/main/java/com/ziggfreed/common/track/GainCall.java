package com.ziggfreed.common.track;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.track.asset.TrackAsset;

/**
 * One gain on its way through the {@link TrackGain} seam: whose climb, on which track, what was asked and by whom,
 * the record as it stands, and (only when asked for) the breakdown of what each factor did. One per
 * {@link TrackService#add}, on the subject's own thread, never kept.
 *
 * <p>The record is the one thing a factor may change: a factor spending something the record keeps (the rested
 * pool) writes the spent record back through {@link #record(TrackRecord)}, and the move lands on it.
 */
public final class GainCall {

    @Nonnull private final TrackKey key;
    @Nonnull private final TrackDef def;
    @Nonnull private final TrackRecord before;
    @Nonnull private TrackRecord record;
    @Nullable private final Ref<EntityStore> ref;
    private final double asked;
    @Nonnull private final String source;
    @Nullable private final List<GainBreakdown.Factor> explained;

    GainCall(@Nonnull TrackKey key, @Nonnull TrackDef def, @Nonnull TrackRecord before,
            @Nullable Ref<EntityStore> ref, double asked, @Nonnull String source, boolean explain) {
        this.key = key;
        this.def = def;
        this.before = before;
        this.record = before;
        this.ref = ref;
        this.asked = asked;
        this.source = source;
        this.explained = explain ? new ArrayList<>(6) : null;
    }

    /** A call built outside {@link TrackService} (a test, a preview), explaining when {@code explain}. */
    @Nonnull
    public static GainCall of(@Nonnull TrackKey key, @Nonnull TrackDef def, @Nonnull TrackRecord before,
            @Nullable Ref<EntityStore> ref, double asked, @Nonnull String source, boolean explain) {
        return new GainCall(key, def, before, ref, asked, source, explain);
    }

    /** Whose climb, on which track. */
    @Nonnull
    public TrackKey key() {
        return key;
    }

    /** The player whose climb this is, or null for another kind of subject. */
    @Nullable
    public UUID playerId() {
        return key.playerId();
    }

    /** The track and its curve. */
    @Nonnull
    public TrackDef def() {
        return def;
    }

    /** The track as folded. */
    @Nonnull
    public TrackAsset track() {
        return def.asset();
    }

    /** The record before this gain. */
    @Nonnull
    public TrackRecord before() {
        return before;
    }

    /** The record the move lands on: {@link #before()}, unless a factor spent something it keeps. */
    @Nonnull
    public TrackRecord record() {
        return record;
    }

    /** Write back the record a factor spent something from (the rested pool). */
    public void record(@Nonnull TrackRecord spent) {
        this.record = spent;
    }

    /** The player's live entity, or null for another kind of subject or a player not here. */
    @Nullable
    public Ref<EntityStore> ref() {
        return ref;
    }

    /** What was asked, before any factor. */
    public double asked() {
        return asked;
    }

    /** What asked: a reward's source id, {@code admin}, a track source's label. */
    @Nonnull
    public String source() {
        return source;
    }

    /** Is a breakdown wanted? Only then does a pipeline say what each factor did. */
    public boolean explaining() {
        return explained != null;
    }

    /** Say what the factor {@code id} did, as the multiplier it applied; nothing unless {@link #explaining()}. */
    public void explain(@Nonnull String id, double multiplier) {
        if (explained != null) {
            explained.add(new GainBreakdown.Factor(id, multiplier));
        }
    }

    /** What the factors said, in order; empty when nothing was explained. */
    @Nonnull
    List<GainBreakdown.Factor> explained() {
        return explained == null ? List.of() : List.copyOf(explained);
    }
}
