package com.ziggfreed.common.settings;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.ui.hud.HudPosition;
import com.ziggfreed.common.ui.hud.panel.HudSpotPosition;

/**
 * The player-settings record's {@code QuestTracker} group: the {@link SurfaceRules} every surface has (whether it
 * shows for a player who has not chosen, and which of a player's choices are fixed), plus the two things the owner
 * says about the tracker for everyone: whether it is on at all ({@code Enabled}) and where it sits for a player who
 * has not picked a spot of their own ({@code Position}, the HUD spots' corner and offsets).
 *
 * <pre>{@code
 * // mods/ziggfreedcommon/player-settings.json
 * { "Default": { "QuestTracker": { "Enabled": true, "Position": { "Preset": "TopLeft", "OffsetX": 16 } } } }
 * }</pre>
 *
 * <p>Every leaf is optional and folds leaf by leaf over the layer below it. Unauthored, the tracker is on and sits
 * in the native objective panel's corner ({@code TopRight}, 24 in from the right, 120 down).
 */
public final class TrackerRules extends SurfaceRules {

    /** A tracker nothing was said about: on, in the native corner, shown, nothing fixed. */
    public static final TrackerRules NONE = new TrackerRules();

    @Nullable protected Boolean enabled;
    @Nullable protected HudSpotPosition position;

    public static final BuilderCodec<TrackerRules> CODEC = BuilderCodec
            .builder(TrackerRules.class, TrackerRules::new, SurfaceRules.CODEC)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (o, v) -> o.enabled = v, o -> o.enabled, (o, p) -> o.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether the quest tracker is on at all, for everyone. False takes it off every player's "
                    + "screen and its card off the Settings tab, whatever a player chose.")
            .add()
            .appendInherited(new KeyedCodec<>("Position", HudSpotPosition.CODEC, false),
                    (o, v) -> o.position = v, o -> o.position, (o, p) -> o.position = p.position)
            .documentation("Where the tracker sits for a player who has not picked a spot of their own: a corner "
                    + "preset and two offsets, as the HUD spots write them. Left out, the native objective panel's "
                    + "corner (TopRight, 24 in, 120 down).")
            .add()
            .build();

    public TrackerRules() {
    }

    /** Whether the owner has the tracker on for everyone; true unless the file says otherwise. */
    public boolean enabled() {
        return !Boolean.FALSE.equals(enabled);
    }

    /** The server's place for the tracker as written, or null when no layer wrote one. */
    @Nullable
    public HudSpotPosition position() {
        return position == null || position.isEmpty() ? null : position;
    }

    /** The server's place for the tracker folded over {@code fallback}: {@code fallback} itself when none is written. */
    @Nonnull
    public HudPosition positionOver(@Nonnull HudPosition fallback) {
        HudSpotPosition written = position();
        return written == null ? fallback : written.over(fallback);
    }
}
