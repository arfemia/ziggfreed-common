package com.ziggfreed.common.ui;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;

import com.ziggfreed.common.i18n.Msg;

/**
 * The Java half of the shared search row, {@code Common/UI/Custom/Common/ZigSearchRow.ui}: one
 * text field, a Search button and a Clear button, instantiated in a page's {@code .ui} under an id
 * of the page's choosing ({@code $S.@ZigSearchRow #QSearch { ... }}). Every method here takes that
 * instance selector and addresses the row's parts as its descendants ({@code "#QSearch
 * #SearchField"}), which is what lets one page hold two rows.
 *
 * <p><b>The {@code @} on the key is the client's directive to resolve the value as an element
 * path.</b> A binding's event data is a map of key to string; when the key starts with {@code @},
 * the client reads the string as a path ({@code "#QSearch #SearchField.Value"}) and ships the
 * element's live value under that same {@code @}-prefixed key. Without it the string goes over
 * literally, so a page hears {@code "#QSearch #SearchField.Value"} as the text the player typed,
 * and puts it back into the field on the next repaint. Every first-party binding that pulls a
 * live value spells its key this way ({@code "@SearchQuery"}, {@code "@Filter"},
 * {@code "@RespawnPointName"}), and the receiving codec declares the SAME {@code @}-prefixed key.
 * {@link #carry} refuses a bare key so the mistake cannot be made through this seam.
 *
 * <p><b>No per-keystroke binding, ever.</b> A page that rebuilt on {@code ValueChanged} would
 * steal focus from the field on every character. The Search button submits, and the live text
 * rides along on the page's OTHER bindings ({@link #carry} on each of them, exactly as the
 * objective book does), so a click on a filter or a row never discards what was typed but not
 * yet searched. {@link #wire} does the whole row in one call: seeds the field, labels both
 * buttons from the library's own lang file, binds Search (with the live text carried) and Clear,
 * and shows Clear only while there is something to clear.
 */
public final class ZigSearchRow {

    /** The text field inside the row. */
    public static final String FIELD = "#SearchField";

    /** The Search button inside the row. */
    public static final String SEARCH_BUTTON = "#SearchBtn";

    /** The Clear button inside the row; hidden until the field holds something. */
    public static final String CLEAR_BUTTON = "#ClearBtn";

    /** This library's own lang namespace; the two button words live in {@code ziggfreedcommon.ui.lang}. */
    private static final String PREFIX = "ziggfreedcommon.";

    /** The Search button's word, under {@link #PREFIX}. */
    static final String SEARCH_LABEL = "ui.search.button";

    /** The Clear button's word, under {@link #PREFIX}. */
    static final String CLEAR_LABEL = "ui.search.clear";

    private ZigSearchRow() {
        // static seam
    }

    /**
     * The element path of the row's live field value, {@code "<row> #SearchField.Value"}: what a
     * binding names under its {@code @}-prefixed key, and what {@link #wire} seeds.
     */
    @Nonnull
    public static String valuePath(@Nonnull String rowSelector) {
        return rowSelector + " " + FIELD + ".Value";
    }

    /**
     * Carry the row's live text on a binding: {@code data} gains {@code key -> valuePath(row)}, so
     * the click ships whatever the field holds at that moment. Call it on every binding whose
     * handler rebuilds the page, or the rebuild paints the field with the last SEARCHED text and
     * loses what was typed since.
     *
     * @param key the event-data key, which MUST start with {@code @} (the client's resolve-as-path
     *            directive) and MUST be declared with that same {@code @} in the receiving codec
     * @throws IllegalArgumentException on a bare key, which would ship the path string literally
     */
    @Nonnull
    public static EventData carry(@Nonnull EventData data, @Nonnull String key,
            @Nonnull String rowSelector) {
        return data.append(SettingsUiUtil.directive(key), valuePath(rowSelector));
    }

    /**
     * Wire one row: seed the field with {@code current}, label both buttons, bind the Search click
     * to {@code onSearch} with the live text carried under {@code key}, and, when {@code onClear}
     * is given and {@code current} is not blank, show the Clear button and bind it to
     * {@code onClear}. Clear never carries the text: its whole point is to drop it.
     *
     * @param current the text searched so far, or null for none
     * @param key     the {@code @}-prefixed key the live text rides under (see {@link #carry})
     * @param onClear the Clear click's data, or null for a row that offers no Clear
     */
    public static void wire(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull String rowSelector, @Nullable String current, @Nonnull String key,
            @Nonnull EventData onSearch, @Nullable EventData onClear) {
        String text = current == null ? "" : current;
        cmd.set(valuePath(rowSelector), text);

        String searchButton = rowSelector + " " + SEARCH_BUTTON;
        ZigRichButton.text(cmd, searchButton, Msg.tr(PREFIX, SEARCH_LABEL));
        events.addEventBinding(CustomUIEventBindingType.Activating, searchButton,
                carry(onSearch, key, rowSelector), false);

        String clearButton = rowSelector + " " + CLEAR_BUTTON;
        boolean clearable = onClear != null && !text.isBlank();
        cmd.set(clearButton + ".Visible", clearable);
        if (clearable) {
            ZigRichButton.text(cmd, clearButton, Msg.tr(PREFIX, CLEAR_LABEL));
            events.addEventBinding(CustomUIEventBindingType.Activating, clearButton, onClear,
                    false);
        }
    }
}
