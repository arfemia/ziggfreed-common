package com.ziggfreed.common.ui.hud.bar;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.ui.hud.HudPosition;

/**
 * Where a panel hangs from, as a FILE states it: a corner preset and two pixel offsets from the
 * edges that preset pins. Every leaf is optional, so a file restates only what it wants different
 * from the layer below it: a placement says which corner, an owner nudges one offset, and
 * {@link #over} folds the two, leaf by leaf, onto whatever was already decided.
 *
 * <p>One group, shared by {@link HudBarPlacementAsset} (where a placement sits) and
 * {@link HudBarPanelAsset} (an inline restatement over the placement a panel names), so the two
 * files spell a position the same way and an author learns it once.
 */
public final class HudBarPosition {

    @Nullable protected String preset;
    @Nullable protected Integer offsetX;
    @Nullable protected Integer offsetY;

    public static final BuilderCodec<HudBarPosition> CODEC = BuilderCodec
            .builder(HudBarPosition.class, HudBarPosition::new)
            .appendInherited(new KeyedCodec<>("Preset", Codec.STRING, false),
                    (o, v) -> o.preset = v, o -> o.preset, (o, p) -> o.preset = p.preset)
            .metadata(EditorSchema.oneOf("TopLeft", "TopCenter", "TopRight", "CenterLeft", "Center",
                    "CenterRight", "BottomLeft", "BottomCenter", "BottomRight"))
            .documentation("Which corner or edge the panel hangs from. A Bottom preset pins the panel's "
                    + "bottom edge, so it grows upward as rows come up; a Right preset pins its right "
                    + "edge, so it grows leftward as columns open. Left out, the layer below decides.")
            .add()
            .appendInherited(new KeyedCodec<>("OffsetX", Codec.INTEGER, false),
                    (o, v) -> o.offsetX = v, o -> o.offsetX, (o, p) -> o.offsetX = p.offsetX)
            .documentation("Pixels in from the pinned left or right edge; for a centred preset, a nudge "
                    + "off centre. Left out, the layer below decides.")
            .add()
            .appendInherited(new KeyedCodec<>("OffsetY", Codec.INTEGER, false),
                    (o, v) -> o.offsetY = v, o -> o.offsetY, (o, p) -> o.offsetY = p.offsetY)
            .documentation("Pixels down from a Top preset, up from a Bottom one. Left out, the layer "
                    + "below decides.")
            .add()
            .build();

    public HudBarPosition() {
    }

    /** A position with these three leaves, for code assembling one; a null leaf stays unauthored. */
    public HudBarPosition(@Nullable String preset, @Nullable Integer offsetX, @Nullable Integer offsetY) {
        this.preset = preset;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
    }

    /** The authored corner preset, trimmed, or null. */
    @Nullable
    public String preset() {
        return preset == null || preset.isBlank() ? null : preset.trim();
    }

    /** The authored horizontal offset, or null. */
    @Nullable
    public Integer offsetX() {
        return offsetX;
    }

    /** The authored vertical offset, or null. */
    @Nullable
    public Integer offsetY() {
        return offsetY;
    }

    /** True when no leaf is authored, so folding this changes nothing. */
    public boolean isEmpty() {
        return preset() == null && offsetX == null && offsetY == null;
    }

    /**
     * These leaves folded over {@code under}: each authored leaf replaces {@code under}'s, an
     * unauthored one keeps it. A preset nothing recognises keeps the WHOLE of {@code under}, offsets
     * included, because offsets measured from one corner mean nothing against another.
     */
    @Nonnull
    public HudPosition over(@Nonnull HudPosition under) {
        int x = offsetX != null ? offsetX : under.getOffsetX();
        int y = offsetY != null ? offsetY : under.getOffsetY();
        String corner = preset();
        if (corner == null) {
            return new HudPosition(under.getAnchorEdge(), under.getHorizontalEdge(), x, y);
        }
        HudPosition parsed = HudPosition.parse(corner, x, y);
        return parsed != null ? parsed : under;
    }
}
