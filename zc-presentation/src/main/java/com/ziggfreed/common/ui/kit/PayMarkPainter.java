package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import com.ziggfreed.common.ui.UiRetint;

/**
 * Paints a {@link PayMark} into the kit's {@code @ZigPayMark} ({@code Common/ZigKit.ui}): an inline instance (a tier
 * card's {@code #Mark}, {@link TierCardPainter}), or one appended on its own ({@code Pages/ZigPayMark.ui},
 * {@link #append}). The head shows with words or hides; the word takes its ink from how many diamonds are lit
 * ({@link #ink}); each of the four diamonds ({@code #Pip0} to {@code #Pip3}, the game's {@code Common/BulletIcon.png}
 * under a tint) is tinted lit or unlit by its {@code Background}'s colour leaf, which the template declares. Every part
 * is painted both ways, so a partial update repaints a live mark whole.
 */
public final class PayMarkPainter {

    /** One mark appended on its own. */
    public static final String TEMPLATE = "Pages/ZigPayMark.ui";

    /** The appended mark's width and height in its template. */
    public static final int WIDTH = 212;
    public static final int HEIGHT = 66;

    private PayMarkPainter() {
    }

    /**
     * Paint {@code data} into the mark at {@code mark} (an inline {@code @ZigPayMark} instance's selector, or an
     * appended one's {@code host[i] #PayMark}), in a build or a partial update.
     */
    public static void paint(@Nonnull UICommandBuilder cmd, @Nonnull String mark, @Nonnull PayMark data) {
        KitPaint.optional(cmd, mark + " #MarkHead", data.head());
        KitPaint.text(cmd, mark + " #MarkWord", data.word());
        cmd.set(mark + " #MarkWord.Style.TextColor", ink(data.lit()));
        for (int i = 0; i < PayMark.PIPS; i++) {
            UiRetint.retintColor(cmd, mark + " #Pip" + i, i < data.lit() ? ZigTokens.MARK_LIT : ZigTokens.MARK_UNLIT);
        }
    }

    /**
     * Append one mark to {@code host} and paint it. Its position is the number of templates this same builder appended
     * to {@code host} since it last cleared it, so a page clears the host (or builds it fresh) in the same update
     * before appending.
     *
     * @return the appended mark's selector
     */
    @Nonnull
    public static String append(@Nonnull UICommandBuilder cmd, @Nonnull String host, @Nonnull PayMark data) {
        int index = SegmentPainter.appendedSoFar(cmd, host);
        cmd.append(host, TEMPLATE);
        String mark = KitPaint.child(host, index) + " #PayMark";
        paint(cmd, mark, data);
        return mark;
    }

    /**
     * A mark's word ink by how many diamonds are lit: the lit gold for a full mark, the strong ink for half or more,
     * the body ink for a quarter, the dim ink for none (the word is 20, so the dim ink reads at the large-text floor).
     */
    @Nonnull
    static String ink(int lit) {
        if (lit >= PayMark.PIPS) {
            return ZigTokens.MARK_LIT;
        }
        if (lit >= 2) {
            return ZigTokens.INK_STRONG;
        }
        return lit == 1 ? ZigTokens.INK_BODY : ZigTokens.INK_DIM;
    }
}
