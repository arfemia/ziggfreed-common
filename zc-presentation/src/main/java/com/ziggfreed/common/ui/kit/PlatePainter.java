package com.ziggfreed.common.ui.kit;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.Anchor;
import com.hypixel.hytale.server.core.ui.Value;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.ziggfreed.common.icon.Portraits;
import com.ziggfreed.common.ui.UiRetint;
import com.ziggfreed.common.ui.icon.IconRenderer;

/**
 * Paints a plate: a page's hero ({@code Common/ZigKit.ui}'s {@code @ZigHeroPlate}, {@value #HERO_WIDTH} x
 * {@value #HERO_HEIGHT}, whose child ids only this painter spells) and the layers any plate a page's part draws
 * shares with it (an inline banner's, whose ids its own template declares and its part names in {@link Ids}).
 * Each layer is set both ways, so nothing a template ships shows by accident: the art layer only for art (or a
 * composition's texture); the flat fill over the plate, the tinted sky, the glow and the pictures (items and NPC
 * portraits) for a composition. Nothing clips a child to a plate in the client, so every box a page hands here is
 * already on its plate ({@link Plate}); a glow is fitted onto it with {@link #fitGlow}.
 */
public final class PlatePainter {

    /** The hero plate's size. */
    public static final int HERO_WIDTH = 962;
    public static final int HERO_HEIGHT = 240;

    /**
     * The least width or height a glow keeps once it is fitted onto its plate (it is drawn as the largest box about
     * its centre that the plate holds); smaller, it hides.
     */
    public static final int GLOW_MIN_FITTED = 16;

    /**
     * One composed item picture, appended onto a plate's pictures host and placed by a whole Anchor: the kit's plain
     * picture slot, an {@code AssetImage #IcoTex} painted through {@code IconRenderer.applyPlainIcon} (no tooltip, no
     * rarity square), filling the box the Anchor gives it.
     */
    public static final String PICTURE = "Group { AssetImage #IcoTex { Anchor: (Full: 0); Visible: false; "
            + "FallbackTexturePath: \"UI/Custom/Common/Glyphs/Blank.png\"; } }";

    /**
     * One composed picture that is an NPC's portrait: the same plain slot as {@link #PICTURE}, falling back to the
     * shared missing-picture icon ({@link Portraits#MISSING_TEXTURE}) for a model no still was rendered for, as every
     * portrait widget does.
     */
    public static final String PORTRAIT = "Group { AssetImage #IcoTex { Anchor: (Full: 0); Visible: false; "
            + "FallbackTexturePath: \"" + Portraits.MISSING_TEXTURE + "\"; } }";

    /**
     * Where one plate's layers are: the plate itself (its flat fill), its art layer, its white sky, its glow, and the
     * host its pictures are appended into.
     */
    public record Ids(@Nonnull String plate, @Nonnull String art, @Nonnull String sky, @Nonnull String glow,
            @Nonnull String pictures) {

        /** The kit hero plate instanced as {@code hero} ({@code #Hero}). */
        @Nonnull
        public static Ids hero(@Nonnull String hero) {
            return new Ids(hero, hero + " #HeroArt", hero + " #HeroGradient", hero + " #HeroGlow",
                    hero + " #HeroItems");
        }
    }

    private PlatePainter() {
    }

    /**
     * The hero plate instanced as {@code hero}: its layers; the page's own picture when nothing else is set; then the
     * chip, the title, the dates, the line under them and the accent strip.
     */
    public static void paintHero(@Nonnull UICommandBuilder cmd, @Nonnull String hero, @Nonnull Plate.Hero plate) {
        paintLayers(cmd, Ids.hero(hero), plate);
        String pic = hero + " #HeroPic";
        String own = plate.pictureTexture();
        cmd.set(pic + ".Visible", own != null);
        if (own != null) {
            IconRenderer.applyPlainIcon(cmd, pic, null, own);
        }
        PillPainter.paint(cmd, hero + " #HeroChip", plate.chip());
        KitPaint.text(cmd, hero + " #HeroTitle", plate.title());
        KitPaint.optional(cmd, hero + " #HeroDates", plate.dates());
        KitPaint.optional(cmd, hero + " #HeroNext", plate.next());
        UiRetint.fill(cmd, hero + " #HeroAccent", plate.accentHex());
    }

    /** A plate's layers, bottom up: its art, its flat fill, its sky, its glow and its pictures. */
    public static void paintLayers(@Nonnull UICommandBuilder cmd, @Nonnull Ids ids, @Nonnull Plate.Layers plate) {
        String art = plate.artTexture();
        cmd.set(ids.art() + ".Visible", art != null);
        if (art != null) {
            cmd.set(ids.art() + ".AssetPath", art);
        }
        if (plate.fillHex() != null) {
            UiRetint.fill(cmd, ids.plate(), plate.fillHex());
        }
        String sky = plate.skyHex();
        cmd.set(ids.sky() + ".Visible", sky != null);
        if (sky != null) {
            UiRetint.retintColor(cmd, ids.sky(), sky);
        }
        Plate.Light glow = plate.glow();
        cmd.set(ids.glow() + ".Visible", glow != null);
        if (glow != null) {
            cmd.setObject(ids.glow() + ".Anchor", anchor(glow.box()));
            UiRetint.retintColor(cmd, ids.glow(), glow.colorHex());
        }
        paintPictures(cmd, ids.pictures(), plate.pictures());
    }

    /**
     * A plate's pictures: each a plain slot appended onto {@code host} in order (an NPC's portrait the slot that
     * falls back to the missing-picture icon), placed by a whole Anchor and painted through
     * {@code IconRenderer.applyPlainIcon}.
     */
    public static void paintPictures(@Nonnull UICommandBuilder cmd, @Nonnull String host,
            @Nonnull List<? extends Plate.Picture> pictures) {
        for (int i = 0; i < pictures.size(); i++) {
            Plate.Picture picture = pictures.get(i);
            String slot = KitPaint.child(host, i);
            cmd.appendInline(host, picture.portrait() ? PORTRAIT : PICTURE);
            cmd.setObject(slot + ".Anchor", anchor(picture.box()));
            IconRenderer.applyPlainIcon(cmd, slot, null, picture.iconPath());
        }
    }

    /**
     * A glow authored as a square box ({@code x}, {@code y}, {@code size}, by its top-left corner) fitted onto a
     * {@code plateWidth} x {@code plateHeight} plate, since the client does not clip a child to its parent (a glow
     * hanging past the plate would spill over the page): the largest box about its authored centre that the plate
     * holds, keeping the authored size on each axis where it fits, so the round glow may stretch into an ellipse. A
     * glow already on the plate keeps its box; one whose centre is off the plate, or that fits narrower or shorter
     * than {@link #GLOW_MIN_FITTED}, is null (drawn as nothing). On the hero plate (514, -78, 400) becomes 400 x 236
     * at (514, 4).
     */
    @Nullable
    public static <B> B fitGlow(int x, int y, int size, int plateWidth, int plateHeight,
            @Nonnull Plate.Box.Maker<B> box) {
        double half = size / 2.0;
        double cx = x + half;
        double cy = y + half;
        double halfWidth = Math.min(half, Math.min(cx, plateWidth - cx));
        double halfHeight = Math.min(half, Math.min(cy, plateHeight - cy));
        int width = (int) Math.floor(halfWidth * 2.0);
        int height = (int) Math.floor(halfHeight * 2.0);
        if (width < GLOW_MIN_FITTED || height < GLOW_MIN_FITTED) {
            return null;
        }
        return box.make((int) Math.round(cx - halfWidth), (int) Math.round(cy - halfHeight), width, height);
    }

    /** A whole Anchor for a box on a plate: the only form a layout input changes in from Java. */
    @Nonnull
    public static Anchor anchor(@Nonnull Plate.Box box) {
        Anchor anchor = new Anchor();
        anchor.setLeft(Value.of(box.x()));
        anchor.setTop(Value.of(box.y()));
        anchor.setWidth(Value.of(box.width()));
        anchor.setHeight(Value.of(box.height()));
        return anchor;
    }
}
