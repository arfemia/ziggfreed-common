package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommandType;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import com.ziggfreed.common.ui.ZigRichButton;

/**
 * A segmented choice ({@code @ZigSegment}, {@code Pages/ZigSegment.ui}): its label on {@code #Label.TextSpans}, on and
 * off as the {@code ZigSegmentOnStyle} / {@code ZigSegmentStyle} swap, an optional check and dot.
 */
public final class SegmentPainter {

    /** One appended segment. */
    public static final String TEMPLATE = "Pages/ZigSegment.ui";

    private SegmentPainter() {
    }

    /**
     * Paint a segment already in the document (an inline {@code @ZigSegment}, or one appended earlier), in a build
     * or a partial update.
     *
     * @param selector the segment's button (an inline instance's id, or {@code host[i] #Seg})
     */
    public static void set(@Nonnull UICommandBuilder cmd, @Nonnull String selector, @Nonnull Message label,
            boolean on, @Nullable PlayerRef viewer) {
        ZigRichButton.text(cmd, selector, label);
        ZigStyles.apply(cmd, selector + ".Style", on ? ZigStyles.Name.SEGMENT_ON : ZigStyles.Name.SEGMENT, viewer);
    }

    /**
     * Append one segment to {@code host}, paint it and bind it. The new segment's position is the number of
     * templates this same builder appended to {@code host} since it last cleared it, so a page clears the host (or
     * builds it fresh) in the same update before appending.
     *
     * @return the appended segment's button selector
     */
    @Nonnull
    public static String append(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String host,
            @Nonnull Message label, boolean on, boolean check, boolean dot, @Nullable EventData binding) {
        int index = appendedSoFar(cmd, host);
        cmd.append(host, TEMPLATE);
        String seg = KitPaint.child(host, index) + " #Seg";
        ZigRichButton.text(cmd, seg, label);
        if (on) {
            ZigStyles.apply(cmd, seg + ".Style", ZigStyles.Name.SEGMENT_ON, null);
        }
        cmd.set(seg + " #Check.Visible", check);
        cmd.set(seg + " #Dot.Visible", dot);
        if (binding != null) {
            events.addEventBinding(CustomUIEventBindingType.Activating, seg, binding);
        }
        return seg;
    }

    /** How many templates this builder appended to {@code host} since it last cleared it. */
    static int appendedSoFar(@Nonnull UICommandBuilder cmd, @Nonnull String host) {
        int count = 0;
        for (CustomUICommand command : cmd.getCommands()) {
            if (!host.equals(command.selector)) {
                continue;
            }
            if (command.type == CustomUICommandType.Clear) {
                count = 0;
            } else if (command.type == CustomUICommandType.Append) {
                count++;
            }
        }
        return count;
    }
}
