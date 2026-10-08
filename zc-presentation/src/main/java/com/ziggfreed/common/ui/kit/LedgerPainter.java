package com.ziggfreed.common.ui.kit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.UiRetint;

/**
 * Paints a {@link LedgerModel} into a list: one {@code Pages/ZigLedgerSection.ui} per section (head with label,
 * count and chevron; rows in its {@code #Rows}), one row template per row up to the section's cap, then a
 * {@code Pages/ZigShowMoreRow.ui}. A row's look is its named styles ({@link ZigStyles}): the row and selected-row
 * button styles, the tone's state word, the selected title; its accent bar is the tone's fill. A closed section
 * appends its rows only when first opened ({@link #openSection}).
 *
 * <p><b>Selectors</b> (every appended template is a {@code Group} root named after its file, the 2.8 ids inside):
 * section {@code list[s] #Section}, head {@code list[s] #Head}, rows host {@code list[s] #Rows}, a row's button
 * {@code list[s] #Rows[r] #Select}, the show-more button {@code list[s] #Rows[n] #More}. A page never spells them;
 * {@link LedgerIndex} hands them back.
 *
 * <p><b>Open sections.</b> A section opens when the viewer opened it, and when the viewer said nothing about it and it
 * opens by default. {@code openSections} carries both answers: a section id opened, the id after {@link #CLOSED}
 * closed ({@link #isOpen}, {@link #withSection}), so one set round-trips through a page's event data.
 */
public final class LedgerPainter {

    /** The section template. */
    public static final String SECTION_TEMPLATE = "Pages/ZigLedgerSection.ui";

    /** The "Show N more" row. */
    public static final String SHOW_MORE_TEMPLATE = "Pages/ZigShowMoreRow.ui";

    /** The mark before a section id in {@code openSections} that the viewer closed. */
    public static final String CLOSED = "!";

    private LedgerPainter() {
    }

    /**
     * Clear {@code listSelector} and paint the whole model into it, binding every row, head and show-more row that
     * the bindings answer for. Call it in a full build, or in a partial update that repaints the whole list (every
     * element it binds is appended in that same update).
     *
     * @param openSections   the viewer's open and closed sections ({@link #withSection})
     * @param selectedRowId  the row painted selected, or null
     * @return where everything went, for {@link #select}, {@link #openSection} and {@link #closeSection}
     */
    @Nonnull
    public static LedgerIndex paint(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull String listSelector, @Nonnull LedgerModel model, @Nonnull Set<String> openSections,
            @Nullable String selectedRowId, @Nonnull LedgerBindings bindings, @Nonnull RowSize size,
            @Nullable PlayerRef viewer) {
        LedgerIndex index = new LedgerIndex(listSelector, size, viewer);
        index.select(selectedRowId);
        cmd.clear(listSelector);
        List<LedgerSection> sections = model.sections();
        for (int s = 0; s < sections.size(); s++) {
            LedgerSection section = sections.get(s);
            String root = KitPaint.child(listSelector, s);
            cmd.append(listSelector, SECTION_TEMPLATE);
            String head = root + " #Head";
            KitPaint.text(cmd, head + " #Label", section.label());
            KitPaint.text(cmd, head + " #Count", Msg.num(section.rows().size()));
            EventData toggle = bindings.section(section);
            bind(events, head, toggle);
            boolean open = isOpen(section, openSections);
            index.section(section.id(), s, open);
            chevron(cmd, head, open);
            // A head nothing answers shows no chevron, so it never looks as if it folds.
            cmd.set(head + " #Chevron.Visible", toggle != null);
            cmd.set(root + " #Rows.Visible", open);
            if (open) {
                appendRows(cmd, events, index, s, section, bindings);
            }
        }
        return index;
    }

    /**
     * Move the selection in a partial update: the old row back to its resting style, the new one steel blue with its
     * title in bright ink. Every copy of a row (a pinned row shows twice) moves together. A row that is not painted
     * (in a closed section, or gone) is skipped.
     */
    public static void select(@Nonnull UICommandBuilder cmd, @Nonnull LedgerIndex index, @Nullable String fromRowId,
            @Nonnull String toRowId, @Nullable PlayerRef viewer) {
        if (fromRowId != null && !fromRowId.equals(toRowId)) {
            LedgerRow from = index.rowData(fromRowId);
            for (String row : index.rowSelectors(fromRowId)) {
                rowState(cmd, row, from, false, index.size(), viewer);
            }
        }
        LedgerRow to = index.rowData(toRowId);
        for (String row : index.rowSelectors(toRowId)) {
            rowState(cmd, row, to, true, index.size(), viewer);
        }
        index.select(toRowId);
    }

    /**
     * Open a section in a partial update: its rows are appended and bound the first time (so a binding only ever
     * targets an element appended in the same update), then shown. A section the index does not hold is skipped.
     */
    public static void openSection(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull LedgerIndex index, @Nonnull LedgerSection section, @Nonnull LedgerBindings bindings) {
        int s = index.sectionIndex(section.id());
        if (s < 0) {
            return;
        }
        String root = index.sectionRoot(s);
        if (!index.rowsAppended(section.id())) {
            appendRows(cmd, events, index, s, section, bindings);
        }
        cmd.set(root + " #Rows.Visible", true);
        chevron(cmd, root + " #Head", true);
        index.setOpen(section.id(), true);
    }

    /** Close a section in a partial update: its rows hide (they stay appended for a later open). */
    public static void closeSection(@Nonnull UICommandBuilder cmd, @Nonnull LedgerIndex index,
            @Nonnull String sectionId) {
        int s = index.sectionIndex(sectionId);
        if (s < 0) {
            return;
        }
        String root = index.sectionRoot(s);
        cmd.set(root + " #Rows.Visible", false);
        chevron(cmd, root + " #Head", false);
        index.setOpen(sectionId, false);
    }

    /** Whether {@code section} shows its rows, given the viewer's answers ({@link #withSection}). */
    public static boolean isOpen(@Nonnull LedgerSection section, @Nonnull Set<String> openSections) {
        if (openSections.contains(section.id())) {
            return true;
        }
        if (openSections.contains(CLOSED + section.id())) {
            return false;
        }
        return section.openByDefault();
    }

    /** {@code openSections} with the viewer's answer for one section recorded (opened, or closed). */
    @Nonnull
    public static Set<String> withSection(@Nonnull Set<String> openSections, @Nonnull String sectionId,
            boolean open) {
        Set<String> next = new LinkedHashSet<>(openSections);
        next.remove(sectionId);
        next.remove(CLOSED + sectionId);
        next.add(open ? sectionId : CLOSED + sectionId);
        return next;
    }

    /** Paint one row's content into an appended row template's button. */
    static void paintRow(@Nonnull UICommandBuilder cmd, @Nonnull String row, @Nonnull LedgerRow data,
            boolean selected, @Nonnull RowSize size, @Nullable PlayerRef viewer) {
        KitPaint.text(cmd, row + " #Title", data.title());
        if (size.hasMeta()) {
            KitPaint.optional(cmd, row + " #Meta", data.meta());
            if (data.faint() && data.meta() != null) {
                if (size.metaWraps()) {
                    // The faint style is one line: a meta that wraps keeps its own style and takes the ink alone.
                    cmd.set(row + " #Meta.Style.TextColor", ZigTokens.INK_FAINT);
                } else {
                    ZigStyles.applyText(cmd, row + " #Meta.Style", ZigStyles.Text.FAINT);
                }
            }
        }
        KitPaint.picture(cmd, row + " #Pic", data.picture());

        String fill = data.tone().fillHex();
        if (fill != null) {
            UiRetint.fill(cmd, row + " #Accent", fill);
        }
        cmd.set(row + " #Accent.Visible", fill != null);

        KitPaint.optional(cmd, row + " #Value", data.value());
        KitPaint.optional(cmd, row + " #State", data.state());
        if (data.state() != null) {
            ZigStyles.apply(cmd, row + " #State.Style", data.tone().stateStyle(), viewer);
        }

        Progress progress = data.progress();
        cmd.set(row + " #BarTrack.Visible", progress != null);
        if (progress != null) {
            cmd.set(row + " #Bar.Value", progress.fraction());
        }

        Mark mark = data.mark();
        cmd.set(row + " #Mark.Visible", mark != Mark.NONE);
        if (mark != Mark.NONE) {
            cmd.set(row + " #Mark #Pinned.Visible", mark == Mark.PINNED);
            cmd.set(row + " #Mark #Tracked.Visible", mark == Mark.TRACKED);
        }
        if (selected) {
            // A fresh row carries its authored resting styles; only the selected one is swapped, last, so its
            // white words win over the tone's state style above.
            rowState(cmd, row, data, true, size, viewer);
        }
    }

    /**
     * A row's selected or resting look: the button's style, and its words white on the steel blue or back in their
     * own inks (the title by its text style, the state word by its tone's style, the meta and value by colour, which
     * keeps the one-line shape the selected styles share with the resting ones). A label the row does not show is
     * left alone.
     *
     * <p>A row whose meta wraps to two lines ({@link RowSize#TALL}) turns its meta white by the colour leaf alone,
     * never by {@link ZigStyles.Name#ROW_META_ON_SELECTED}, a one-line style that would clip its second line. The
     * trade-off: a theme document cannot restyle a tall row's selected meta (no theme ships, and the white is that
     * style's own fallback leaf).
     */
    private static void rowState(@Nonnull UICommandBuilder cmd, @Nonnull String row, @Nullable LedgerRow data,
            boolean selected, @Nonnull RowSize size, @Nullable PlayerRef viewer) {
        ZigStyles.apply(cmd, row + ".Style", selected ? ZigStyles.Name.ROW_SELECTED : ZigStyles.Name.ROW, viewer);
        boolean meta = data != null && size.hasMeta() && data.meta() != null;
        boolean value = data != null && data.value() != null;
        boolean state = data != null && data.state() != null;
        if (selected) {
            ZigStyles.apply(cmd, row + " #Title.Style", ZigStyles.Name.ROW_TITLE_ON_SELECTED, viewer);
            if (meta && size.metaWraps()) {
                cmd.set(row + " #Meta.Style.TextColor", ZigTokens.INK_BRIGHT);
            } else if (meta) {
                ZigStyles.apply(cmd, row + " #Meta.Style", ZigStyles.Name.ROW_META_ON_SELECTED, viewer);
            }
            if (value) {
                ZigStyles.apply(cmd, row + " #Value.Style", ZigStyles.Name.ROW_VALUE_ON_SELECTED, viewer);
            }
            if (state) {
                ZigStyles.apply(cmd, row + " #State.Style", ZigStyles.Name.STATE_ON_SELECTED, viewer);
            }
            return;
        }
        ZigStyles.applyText(cmd, row + " #Title.Style", ZigStyles.Text.ROW_TITLE);
        if (meta) {
            cmd.set(row + " #Meta.Style.TextColor", data.faint() ? ZigTokens.INK_FAINT : ZigTokens.INK_MUTED);
        }
        if (value) {
            cmd.set(row + " #Value.Style.TextColor", ZigTokens.INK_BODY);
        }
        if (state) {
            ZigStyles.apply(cmd, row + " #State.Style", data.tone().stateStyle(), viewer);
        }
    }

    private static void appendRows(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull LedgerIndex index, int sectionIndex, @Nonnull LedgerSection section,
            @Nonnull LedgerBindings bindings) {
        String host = KitPaint.child(index.listSelector(), sectionIndex) + " #Rows";
        List<LedgerRow> rows = section.rows();
        int shown = Math.min(rows.size(), section.cap());
        String selected = index.selectedRowId();
        for (int r = 0; r < shown; r++) {
            LedgerRow data = rows.get(r);
            cmd.append(host, index.size().template());
            String row = KitPaint.child(host, r) + " #Select";
            paintRow(cmd, row, data, data.id().equals(selected), index.size(), index.viewer());
            bind(events, row, bindings.row(section, data));
            index.row(data, row);
        }
        if (rows.size() > shown) {
            cmd.append(host, SHOW_MORE_TEMPLATE);
            String more = KitPaint.child(host, shown) + " #More";
            KitPaint.text(cmd, more + " #Label", KitText.showMore(Math.min(section.cap(), rows.size() - shown)));
            bind(events, more, bindings.showMore(section));
        }
        index.markAppended(section.id());
    }

    private static void chevron(@Nonnull UICommandBuilder cmd, @Nonnull String head, boolean open) {
        cmd.set(head + " #Chevron #Open.Visible", open);
        cmd.set(head + " #Chevron #Closed.Visible", !open);
    }

    private static void bind(@Nonnull UIEventBuilder events, @Nonnull String selector, @Nullable EventData data) {
        if (data != null) {
            events.addEventBinding(CustomUIEventBindingType.Activating, selector, data);
        }
    }
}
