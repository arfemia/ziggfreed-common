package com.ziggfreed.common.icon;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The still the client renders of a creature, addressed by the role it is a picture of.
 *
 * <p>A live 3D preview is not something a server can drive, but every creature the game ships has a
 * pre-rendered portrait under one well-known path, and pointing an {@code AssetImage} at it is. This
 * is where that path is spelled, once, so a mob inspector and a quest step showing the same creature
 * cannot disagree about where its picture lives - and so a Hytale update that moves the folder is
 * one edit rather than a hunt.
 *
 * <p>The portraits are CLIENT assets, so a server cannot check that one exists before pointing at
 * it. That is what {@link #MISSING_TEXTURE} is for: a row asks for the portrait it wants and the
 * widget shows the fallback when there is nothing there, rather than the row collapsing or drawing
 * something misleading. A creature that never had one rendered - typically a FAMILY name like
 * {@code Trork}, whose members are each rendered but whose family name is not - is best given a
 * picture explicitly, by pointing at whichever member represents it.
 */
public final class Portraits {

    /** Where the client keeps its pre-rendered creature stills. */
    public static final String ROOT = "Icons/ModelsGenerated/";

    public static final String EXTENSION = ".png";

    /**
     * What a widget shows when the portrait it was pointed at is not there. Every row that can show
     * a portrait declares this as its {@code FallbackTexturePath}, so a miss looks the same
     * everywhere instead of looking like a different bug on each screen.
     */
    public static final String MISSING_TEXTURE = "UI/Custom/Pages/Memories/MissingIcon.png";

    private Portraits() {
    }

    /** The texture path of {@code roleId}'s portrait; null for a blank role. */
    @Nullable
    public static String pathFor(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return ROOT + roleId.trim() + EXTENSION;
    }

    /** {@code roleId}'s portrait as a picture a row can paint; null for a blank role. */
    @Nullable
    public static IconSpec forRole(@Nullable String roleId) {
        String path = pathFor(roleId);
        return path == null ? null : IconSpec.ofTexture(path);
    }
}
