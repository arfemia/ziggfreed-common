package com.ziggfreed.common.ui.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * Where one part paints: the page's command and event builders, the part's root selector (the template
 * appended for it, {@code #Sections[2]}), its index among the page's sections (what a button in it carries,
 * so a click is answered from the plan the build painted), and the player looking, when known.
 */
public record PartCanvas(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String root,
        int index, @Nullable PlayerRef viewer) {

    /** {@code ids} inside the part's template: {@code at("#PlateTitle")} is {@code #Sections[2] #PlateTitle}. */
    @Nonnull
    public String at(@Nonnull String ids) {
        return root + " " + ids;
    }
}
