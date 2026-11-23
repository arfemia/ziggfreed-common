package com.ziggfreed.common.ui.hud.statbar;

import java.util.Locale;

import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.ui.hud.panel.HudSpotPosition;

/**
 * Where a stat bar sits: against another HUD ({@code Beside}, the key that HUD is registered under, with the
 * {@code Side} and {@code Gap} it keeps), following wherever that HUD's owner put it and hiding whenever it hides; or
 * standing on its own at a corner and offsets ({@code Position}, the shape every HUD spot writes) at its own
 * {@code Width}. {@code Beside} wins while that HUD is on the player's screen; {@code Position} is where the bar
 * stands when no such HUD is installed for them. Every leaf is nullable and inherited, so an owner restates one.
 */
public final class StatBarPlacement {

    /** The side of the other HUD a bar sits on when the file names none, and the word for it. */
    public static final String ABOVE = "Above";

    /** The other side. */
    public static final String BELOW = "Below";

    /** Pixels between a bar and the HUD it sits beside (and between two bars there) when the file names none. */
    public static final int DEFAULT_GAP = 4;

    /** A standing bar's width when the file names none. */
    public static final int DEFAULT_WIDTH = 240;

    @Nullable protected String beside;
    @Nullable protected String side;
    @Nullable protected Integer gap;
    @Nullable protected HudSpotPosition position;
    @Nullable protected Integer width;

    public static final BuilderCodec<StatBarPlacement> CODEC = BuilderCodec
            .builder(StatBarPlacement.class, StatBarPlacement::new)
            .appendInherited(new KeyedCodec<>("Beside", Codec.STRING, false),
                    (o, v) -> o.beside = v, o -> o.beside, (o, p) -> o.beside = p.beside)
            .documentation("The key of the HUD this bar sits against, as the mod that draws that HUD registers it "
                    + "(the key its own files or docs name). The bar takes that HUD's corner and offsets, so it "
                    + "follows wherever its owner puts it, lines up with its left edge and matches the width of what "
                    + "it shows, and hides whenever it hides. A HUD that sizes itself to its content has no height to "
                    + "sit against, so a bar beside one is not drawn. Left out, or naming a HUD the player does not "
                    + "have, the bar stands at Position.")
            .add()
            .appendInherited(new KeyedCodec<>("Side", Codec.STRING, false),
                    (o, v) -> o.side = v, o -> o.side, (o, p) -> o.side = p.side)
            .metadata(EditorSchema.oneOf(ABOVE, BELOW))
            .metadata(EditorSchema.defaultValue(ABOVE))
            .documentation("Which side of the Beside HUD the bar sits on. Above when unwritten. A HUD pinned to the "
                    + "top of the screen with no room above it gets the bar below instead.")
            .add()
            .appendInherited(new KeyedCodec<>("Gap", Codec.INTEGER, false),
                    (o, v) -> o.gap = v, o -> o.gap, (o, p) -> o.gap = p.gap)
            .metadata(EditorSchema.defaultValue((long) DEFAULT_GAP))
            .documentation("Pixels between the bar and the Beside HUD, and between two bars stacked on the same side "
                    + "of it. " + DEFAULT_GAP + " when unwritten.")
            .add()
            .appendInherited(new KeyedCodec<>("Position", HudSpotPosition.CODEC, false),
                    (o, v) -> o.position = v, o -> o.position, (o, p) -> o.position = p.position)
            .documentation("Where the bar stands on its own: a corner preset and pixel offsets, the shape every HUD "
                    + "spot writes. Used when Beside is left out or names a HUD the player does not have. Left out "
                    + "too, the bar is not drawn.")
            .add()
            .appendInherited(new KeyedCodec<>("Width", Codec.INTEGER, false),
                    (o, v) -> o.width = v, o -> o.width, (o, p) -> o.width = p.width)
            .metadata(EditorSchema.defaultValue((long) DEFAULT_WIDTH))
            .documentation("A standing bar's width in pixels (at least " + StatBarLayout.MIN_WIDTH + "). "
                    + DEFAULT_WIDTH + " when unwritten. A bar beside a HUD takes the width of what that HUD shows.")
            .add()
            .build();

    public StatBarPlacement() {
    }

    /** The HUD key the bar sits beside, trimmed, or null for a standing bar. */
    @Nullable
    public String beside() {
        return beside == null || beside.isBlank() ? null : beside.trim();
    }

    /** True when the bar sits below the Beside HUD; above when unwritten or any other word. */
    public boolean below() {
        return side != null && BELOW.toLowerCase(Locale.ROOT).equals(side.trim().toLowerCase(Locale.ROOT));
    }

    /** The gap in pixels, never below 0; {@value #DEFAULT_GAP} when unwritten. */
    public int gap() {
        return gap == null ? DEFAULT_GAP : Math.max(0, gap);
    }

    /** The standing position as written, or null. */
    @Nullable
    public HudSpotPosition position() {
        return position == null || position.isEmpty() ? null : position;
    }

    /** A standing bar's width, held to at least {@link StatBarLayout#MIN_WIDTH}; {@value #DEFAULT_WIDTH} unwritten. */
    public int width() {
        return width == null ? DEFAULT_WIDTH : Math.max(StatBarLayout.MIN_WIDTH, width);
    }
}
