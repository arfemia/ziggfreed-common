package com.ziggfreed.common.ui.kit;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * What {@link PlatePainter} reads from a page's plan to draw a plate: a page's hero ({@code @ZigHeroPlate}, 962 x
 * 240) or a plate a part of its body draws (an inline banner). A page keeps its own records and has them implement
 * these, so its plan reads as it always has and the painter needs no copy of it. Every box a page hands here is
 * already on its plate: nothing clips a child to a plate in the client, so a picture is clamped onto it when read
 * and a glow is fitted onto it ({@link PlatePainter#fitGlow}).
 */
public final class Plate {

    private Plate() {
    }

    /** A box on a plate, by its top-left corner. */
    public interface Box {

        int x();

        int y();

        int width();

        int height();

        /** Makes a page's own box from a box's four numbers ({@code HeroBox::new}). */
        @FunctionalInterface
        interface Maker<B> {
            @Nonnull
            B make(int x, int y, int width, int height);
        }
    }

    /** A soft light: its tint and its box, fitted onto the plate. */
    public interface Light {

        @Nonnull
        String colorHex();

        @Nonnull
        Box box();
    }

    /** One picture on a plate: its texture, its box, and whether it is an NPC's portrait (its own fallback). */
    public interface Picture {

        @Nonnull
        String iconPath();

        @Nonnull
        Box box();

        boolean portrait();
    }

    /**
     * A plate's layers, drawn bottom up: the texture its art layer draws (shipped art, or a composition's texture;
     * null hides the layer), the flat fill over the plate, the tint of its white sky (null hides it), the glow, and
     * the pictures in order.
     */
    public interface Layers {

        @Nullable
        String artTexture();

        @Nullable
        String fillHex();

        @Nullable
        String skyHex();

        @Nullable
        Light glow();

        @Nonnull
        List<? extends Picture> pictures();
    }

    /**
     * A page's hero: its layers, then the page's own picture when nothing else is drawn, the status chip, the
     * title, the dates line and the line under it ({@code next}, null hides it), and the accent strip's colour.
     */
    public interface Hero extends Layers {

        @Nullable
        String pictureTexture();

        @Nonnull
        Pill chip();

        @Nonnull
        Message title();

        @Nullable
        Message dates();

        @Nullable
        Message next();

        @Nonnull
        String accentHex();
    }
}
