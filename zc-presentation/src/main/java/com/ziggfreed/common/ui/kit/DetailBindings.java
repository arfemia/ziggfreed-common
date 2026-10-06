package com.ziggfreed.common.ui.kit;

import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.builder.EventData;

/**
 * The event data a page wants on a detail page's parts. A line's binding is made each paint (its line is appended in
 * the same update); the toggle's data is what a page hands {@link DetailPainter#bindActionsOnce}, since the toggle
 * is a live element bound once in build and never by a repaint.
 */
public interface DetailBindings {

    /** A selectable line's click; null leaves the line unselectable. Called only for a line with a select id. */
    @Nullable
    EventData line(DetailBlock b, DetailLine l);

    /** The header toggle's click. */
    @Nullable
    EventData toggle(DetailToggle t);
}
