package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import com.ziggfreed.common.ui.ZigRichButton;

/**
 * A strip of view tabs ({@code Pages/ZigViewTab.ui}): the views a page switches between, drawn as tabs with a picture
 * and an uppercase name so they never read as one of its filters (which stay {@link SegmentPainter}'s boxed
 * segments). The chosen tab swaps to {@code ZigViewTabOnStyle} and its name to {@code ZigViewTabLabelOnStyle} by
 * reference and shows its gold bar; a tab at rest keeps its authored look.
 */
public final class ViewTabPainter {

    /** One appended tab. */
    public static final String TEMPLATE = "Pages/ZigViewTab.ui";

    /** A tab's width in its template. */
    public static final int WIDTH = 186;

    /** A tab's height in its template, its gold bar included. */
    public static final int HEIGHT = 40;

    /** The chosen tab's gold bar, under its button. */
    public static final int BAR = 3;

    /** The template's right margin to the next tab ({@code $ZK.@ZigSpace1}). */
    public static final int GAP = 4;

    /** One tab and its gap: a strip of n tabs is n steps wide. */
    public static final int STEP = WIDTH + GAP;

    private ViewTabPainter() {
    }

    /**
     * Append one tab to {@code host}, paint it and bind it. The new tab's position is the number of templates this
     * same builder appended to {@code host} since it last cleared it, so a page clears the host (or builds it fresh)
     * in the same update before appending.
     *
     * @param picture the plain picture before the name ({@link Picture#NONE} leaves the slot empty)
     * @return the appended tab's button selector
     */
    @Nonnull
    public static String append(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String host,
            @Nonnull Message label, @Nonnull Picture picture, boolean on, @Nullable EventData binding) {
        int index = SegmentPainter.appendedSoFar(cmd, host);
        cmd.append(host, TEMPLATE);
        String root = KitPaint.child(host, index);
        String tab = root + " #Tab";
        ZigRichButton.text(cmd, tab, label);
        KitPaint.picture(cmd, tab + " #Pic", picture);
        if (on) {
            ZigStyles.apply(cmd, tab + ".Style", ZigStyles.Name.VIEW_TAB_ON, null);
            ZigStyles.apply(cmd, tab + " #Label.Style", ZigStyles.Name.VIEW_TAB_LABEL_ON, null);
            cmd.set(root + " #Bar.Visible", true);
        }
        if (binding != null) {
            events.addEventBinding(CustomUIEventBindingType.Activating, tab, binding);
        }
        return tab;
    }
}
