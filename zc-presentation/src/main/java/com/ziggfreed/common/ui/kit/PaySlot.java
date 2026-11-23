package com.ziggfreed.common.ui.kit;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One thing a tier pays, as its card draws it ({@code Pages/ZigItemSlotTile.ui} as a pay slot,
 * {@link TilePainter#paySlots}): one picture on its quality's slot art, or, for a drop list that hands over one of a
 * few, up to {@link #MAX_CHOICES} {@code choices} two by two. {@code quality} is the game's item quality id the slot
 * shows ({@code Rare}); null reads the picture's item's own quality, and a slot with neither (a picture that is no
 * item, a slot of choices) sits on the Memories tile. A reader builds these (zc-instance's tier pays read a clear
 * table's rolls); the kit only draws them, and names no item itself.
 */
public record PaySlot(@Nonnull Picture picture, @Nonnull List<Picture> choices, @Nullable String quality) {

    /** The most pictures a slot of choices shows. */
    public static final int MAX_CHOICES = 4;

    public PaySlot {
        picture = picture == null ? Picture.NONE : picture;
        List<Picture> kept = new ArrayList<>();
        if (choices != null) {
            for (Picture choice : choices) {
                if (choice != null && !choice.isEmpty() && kept.size() < MAX_CHOICES) {
                    kept.add(choice);
                }
            }
        }
        choices = List.copyOf(kept);
        quality = quality == null || quality.isBlank() ? null : quality.trim();
    }

    /** One picture, on its item's own quality. */
    @Nonnull
    public static PaySlot of(@Nonnull Picture picture) {
        return new PaySlot(picture, List.of(), null);
    }

    /** One of a few (a drop list): up to {@link #MAX_CHOICES} pictures, two by two. */
    @Nonnull
    public static PaySlot oneOf(@Nonnull List<Picture> choices) {
        return new PaySlot(Picture.NONE, choices, null);
    }

    /** This slot shown as the item quality {@code quality} names (null or blank for the picture's own). */
    @Nonnull
    public PaySlot withQuality(@Nullable String quality) {
        return new PaySlot(picture, choices, quality);
    }

    /** True for a slot of choices. */
    public boolean isChoice() {
        return !choices.isEmpty();
    }
}
