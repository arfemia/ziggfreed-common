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
 * <p><b>A ROLE is not a MODEL, and the portraits are keyed by model.</b> The two coincide across
 * most of the base game - a role called {@code Boar} wears a model called {@code Boar} - which is
 * exactly why the difference goes unnoticed until a mod names a character of its own: a guide whose
 * role is {@code Mmo_Hub_Temple} wears {@code Kweebec_Rootling}, and only the second has a picture.
 * So a registered {@link RoleArt} is asked first, and it answers what the role actually wears; the
 * id as written is the fallback, which is the right answer for a target that names a model outright.
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

    /**
     * What one ROLE actually wears, as the texture path of that model's own portrait, or null when
     * the question cannot be answered - no role registry, an unknown role, a role naming no model,
     * a model with no icon.
     *
     * <p>It is a seam because the NPC registry lives well above this class, and because it must
     * never throw: a row asks it while a page is being built, and a page that throws mid-build
     * leaves its player watching a screen that never arrives.
     */
    public interface RoleArt {

        /** The Common-rooted texture path {@code roleId} is pictured by, or null. */
        @Nullable
        String iconFor(@Nonnull String roleId);
    }

    /** Answers nothing until something is installed, which leaves the id-as-written fallback. */
    private static volatile RoleArt roleArt = roleId -> null;

    private Portraits() {
    }

    /**
     * Install who answers what a role wears. Called once, from the wiring root, by the module that
     * can read the NPC registry; last write wins, and installing nothing simply leaves every
     * portrait addressed by the id it was asked for.
     */
    public static void roleArt(@Nonnull RoleArt art) {
        roleArt = art;
    }

    /** The texture path of {@code roleId}'s portrait; null for a blank role. */
    @Nullable
    public static String pathFor(@Nullable String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        String id = roleId.trim();
        String worn = wornArt(id);
        return worn != null ? worn : ROOT + id + EXTENSION;
    }

    /** {@code roleId}'s portrait as a picture a row can paint; null for a blank role. */
    @Nullable
    public static IconSpec forRole(@Nullable String roleId) {
        String path = pathFor(roleId);
        return path == null ? null : IconSpec.ofTexture(path);
    }

    /** The installed answer for {@code roleId}, or null when there is none and when one throws. */
    @Nullable
    private static String wornArt(@Nonnull String roleId) {
        try {
            String icon = roleArt.iconFor(roleId);
            return icon == null || icon.isBlank() ? null : icon;
        } catch (Throwable t) {
            return null;
        }
    }
}
