package com.ziggfreed.common.ui.kit;

import java.util.List;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import com.ziggfreed.common.ui.ZigRichButton;
import com.ziggfreed.common.ui.icon.IconRenderer;

/**
 * Paints a {@link DetailView} into an {@code @ZigDetailPage} instance: the header ({@code #DPic}, {@code #DTitle},
 * {@code #DMeta}, {@code #DSubMeta}, the pills in {@code #DBadges}, the {@code #DToggle} button), the progress block
 * ({@code #DProgress}: {@code #Bar}, {@code #Count}, {@code #Percent}), the lead ({@code #DLead}), the blocks
 * ({@code Pages/ZigDetailBlock.ui} into {@code #DBlocks}: its {@code @ZigSectionHeader #Head} with
 * {@code #HeadLabel} and {@code #Meta}, each line a {@code Pages/ZigDetailLine.ui}), and the action
 * bar ({@code #DActions}: {@code #Hint}, {@code #Danger}, {@code #Secondary}, {@code #Primary}).
 *
 * <p>The page is live: a selection repaints it in a partial update, so every leaf is painted both ways (shown and
 * hidden) and the pills and blocks are cleared and appended again. The header toggle and the three action buttons
 * are live elements bound once in a page's build ({@link #bindActionsOnce}); {@link #paint} never binds them, since a
 * second binding on a live element fires twice. Only a selectable line, appended in the same update, is bound here.
 */
public final class DetailPainter {

    /** One block. */
    public static final String BLOCK_TEMPLATE = "Pages/ZigDetailBlock.ui";

    /** One line of a block. */
    public static final String LINE_TEMPLATE = "Pages/ZigDetailLine.ui";

    /** One pill in the header's {@code #DBadges}. */
    public static final String PILL_TEMPLATE = "Pages/ZigPill.ui";

    /** The header toggle's id inside {@code @ZigDetailPage}. */
    public static final String TOGGLE = "#DToggle";

    /**
     * Whether a disabled action is greyed out from Java ({@code .Disabled}, vanilla {@code BarterPage}); the spike's
     * fallback (SP15) hides it instead.
     */
    static final boolean DISABLE_IN_PLACE = true;

    private DetailPainter() {
    }

    /**
     * Paint {@code view} into the detail page at {@code host} (the {@code @ZigDetailPage} instance's selector).
     */
    public static void paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String host,
            @Nonnull DetailView view, @Nonnull DetailBindings bindings, @Nullable PlayerRef viewer) {
        paint(cmd, events, host, view, bindings, viewer, DISABLE_IN_PLACE);
    }

    /** {@link #paint} with a disabled action greyed in place, or hidden (the spike's fallback form). */
    static void paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String host,
            @Nonnull DetailView view, @Nonnull DetailBindings bindings, @Nullable PlayerRef viewer,
            boolean disableInPlace) {
        header(cmd, host, view, viewer);
        progress(cmd, host, view);
        KitPaint.optional(cmd, host + " #DLead", view.lead());
        blocks(cmd, events, host, view.blocks(), bindings);
        actions(cmd, host, view, viewer, disableInPlace);
    }

    /**
     * Bind the header toggle and the three action buttons, once, in the page's full build; the page dispatches a
     * press on the live state of what is shown. A null binding leaves that button unbound.
     */
    public static void bindActionsOnce(@Nonnull UIEventBuilder events, @Nonnull String host,
            @Nonnull Function<ActionSlot, EventData> binding, @Nullable EventData toggle) {
        for (ActionSlot slot : ActionSlot.values()) {
            EventData data = binding.apply(slot);
            if (data != null) {
                events.addEventBinding(CustomUIEventBindingType.Activating, host + " " + slot.id(), data);
            }
        }
        if (toggle != null) {
            events.addEventBinding(CustomUIEventBindingType.Activating, host + " " + TOGGLE, toggle);
        }
    }

    private static void header(@Nonnull UICommandBuilder cmd, @Nonnull String host, @Nonnull DetailView view,
            @Nullable PlayerRef viewer) {
        KitPaint.picture(cmd, host + " #DPic", view.picture());
        KitPaint.text(cmd, host + " #DTitle", view.title());
        KitPaint.optional(cmd, host + " #DMeta", view.meta());
        KitPaint.optional(cmd, host + " #DSubMeta", view.subMeta());

        String badges = host + " #DBadges";
        cmd.clear(badges);
        List<Pill> pills = view.badges();
        for (int i = 0; i < pills.size(); i++) {
            cmd.append(badges, PILL_TEMPLATE);
            PillPainter.paint(cmd, KitPaint.child(badges, i) + " #Pill", pills.get(i));
        }
        cmd.set(badges + ".Visible", !pills.isEmpty());

        DetailToggle toggle = view.toggle();
        String button = host + " " + TOGGLE;
        cmd.set(button + ".Visible", toggle != null);
        if (toggle != null) {
            ZigRichButton.text(cmd, button, toggle.label());
            ZigStyles.apply(cmd, button + ".Style",
                    toggle.on() ? ZigStyles.Name.BUTTON_PRIMARY : ZigStyles.Name.BUTTON_SECONDARY, viewer);
            KitPaint.tooltip(cmd, button, toggle.tooltip());
        }
    }

    private static void progress(@Nonnull UICommandBuilder cmd, @Nonnull String host, @Nonnull DetailView view) {
        String block = host + " #DProgress";
        Progress progress = view.progress();
        cmd.set(block + ".Visible", progress != null);
        if (progress == null) {
            return;
        }
        float fraction = progress.fraction();
        cmd.set(block + " #Bar.Value", fraction);
        Message label = view.progressLabel();
        KitPaint.text(cmd, block + " #Count",
                label != null ? label : KitText.count(progress.current(), progress.total()));
        KitPaint.text(cmd, block + " #Percent", KitText.percent(Math.round(fraction * 100f)));
    }

    private static void blocks(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String host,
            @Nonnull List<DetailBlock> blocks, @Nonnull DetailBindings bindings) {
        String list = host + " #DBlocks";
        cmd.clear(list);
        for (int b = 0; b < blocks.size(); b++) {
            DetailBlock block = blocks.get(b);
            cmd.append(list, BLOCK_TEMPLATE);
            String root = KitPaint.child(list, b) + " #Block";
            KitPaint.text(cmd, root + " #Head #HeadLabel", block.label());
            KitPaint.optional(cmd, root + " #Head #Meta", block.meta());
            appendLines(cmd, events, root + " #Lines", block.lines(), line -> bindings.line(block, line));
        }
    }

    /**
     * Paint {@code lines} into a plain list the page owns (a {@code Group} holding nothing else), each a
     * {@code Pages/ZigDetailLine.ui}: the list is cleared first, so a repaint never doubles it, and a line whose
     * {@code selectId} is set and whose binding answers shows and binds its {@code #LineSelect}. For lines outside a
     * detail page, such as the title picker's titles still to earn.
     */
    public static void lines(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String list,
            @Nonnull List<DetailLine> lines, @Nonnull Function<DetailLine, EventData> binding) {
        cmd.clear(list);
        appendLines(cmd, events, list, lines, binding);
    }

    private static void appendLines(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull String host, @Nonnull List<DetailLine> lines, @Nonnull Function<DetailLine, EventData> binding) {
        for (int l = 0; l < lines.size(); l++) {
            DetailLine line = lines.get(l);
            cmd.append(host, LINE_TEMPLATE);
            String sel = KitPaint.child(host, l);
            line(cmd, sel, line);
            EventData data = line.selectId() == null ? null : binding.apply(line);
            cmd.set(sel + " #LineSelect.Visible", data != null);
            if (data != null) {
                events.addEventBinding(CustomUIEventBindingType.Activating, sel + " #LineSelect", data);
            }
        }
    }

    /** One appended {@code ZigDetailLine.ui} (its root at {@code line}). */
    static void line(@Nonnull UICommandBuilder cmd, @Nonnull String line, @Nonnull DetailLine data) {
        Picture picture = data.picture();
        String slot = line + " #LineIconSlot";
        boolean drawn = picture.tooltip()
                ? IconRenderer.applyIcon(cmd, slot, picture.itemId(), picture.texturePath())
                : KitPaint.picture(cmd, slot, picture);
        cmd.set(slot + ".Visible", drawn);

        KitPaint.text(cmd, line + " #LineText", data.text());
        if (data.current()) {
            cmd.set(line + " #LineText.Style.TextColor", ZigTokens.INK_BRIGHT);
        }
        KitPaint.optional(cmd, line + " #Count", data.count());

        Pill tag = data.tag();
        cmd.set(line + " #Tag.Visible", tag != null);
        if (tag != null) {
            PillPainter.paint(cmd, line + " #Tag", tag);
        }

        Tick tick = data.tick();
        cmd.set(line + " #Tick.Visible", tick != Tick.NONE);
        if (tick != Tick.NONE) {
            // Each tick is its own authored glyph, tinted in the template; the line shows the one it is.
            cmd.set(line + " #Tick " + tickGlyph(tick) + ".Visible", true);
        }
    }

    private static void actions(@Nonnull UICommandBuilder cmd, @Nonnull String host, @Nonnull DetailView view,
            @Nullable PlayerRef viewer, boolean disableInPlace) {
        boolean any = false;
        for (ActionSlot slot : ActionSlot.values()) {
            DetailAction action = view.action(slot);
            String button = host + " " + slot.id();
            boolean shown = action != null && (action.enabled() || disableInPlace);
            cmd.set(button + ".Visible", shown);
            if (!shown) {
                continue;
            }
            any = true;
            ZigRichButton.text(cmd, button, action.label());
            ZigStyles.apply(cmd, button + ".Style", style(slot, action.look()), viewer);
            if (disableInPlace) {
                cmd.set(button + ".Disabled", !action.enabled());
            }
            KitPaint.tooltip(cmd, button, action.tooltip());
        }
        KitPaint.optional(cmd, host + " #DActions #Hint", view.hint());
        cmd.set(host + " #DActions.Visible", any || view.hint() != null);
    }

    /** The button style for an action: its look wins, else its slot's own. */
    @Nonnull
    static ZigStyles.Name style(@Nonnull ActionSlot slot, @Nonnull ActionLook look) {
        return switch (look) {
            case COLLECT -> ZigStyles.Name.BUTTON_COLLECT;
            case DANGER -> ZigStyles.Name.BUTTON_DANGER;
            case NORMAL -> switch (slot) {
                case PRIMARY -> ZigStyles.Name.BUTTON_PRIMARY;
                case SECONDARY -> ZigStyles.Name.BUTTON_SECONDARY;
                case DANGER -> ZigStyles.Name.BUTTON_DANGER;
            };
        };
    }

    @Nonnull
    private static String tickGlyph(@Nonnull Tick tick) {
        return switch (tick) {
            case DONE -> "#Done";
            case CURRENT -> "#Current";
            case AHEAD -> "#Ahead";
            case LOCKED, NONE -> "#Locked";
        };
    }

}
