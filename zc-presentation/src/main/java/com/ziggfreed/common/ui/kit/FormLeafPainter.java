package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import com.ziggfreed.common.ui.ZigRichButton;

/**
 * Paints the kit's owner leaf row ({@code Pages/ZigFormLeafRow.ui}, a {@link FormLeaf}): one setting of an owner file
 * with its control, its state ("set here" or the default), the value under the owner's word and a Reset. The row is
 * the settings form's look by name ({@code Common/ZigForm.ui}); the state word swaps the kit's state styles
 * ({@link ZigStyles}), so no colour is pushed. This painter alone spells the row's child ids.
 *
 * <p>{@link #append} builds a row and binds it once: the control's change carries the caller's event data plus the
 * live value under {@link #VALUE_KEY} (declare it, with its {@code @}, on the receiving codec), and the Reset carries
 * the caller's own. {@link #paint} repaints a built row in place (a partial update): the bindings stay as appended,
 * which is why the Reset is bound even while it is hidden.
 */
public final class FormLeafPainter {

    /** The row template, appended by path. */
    public static final String TEMPLATE = "Pages/ZigFormLeafRow.ui";

    /** The key the control's live value rides under; the {@code @} is the client's resolve-as-path directive. */
    public static final String VALUE_KEY = "@Value";

    private static final String TITLE = " #Title";
    private static final String HINT = " #Hint";
    private static final String STATE = " #State";
    private static final String FIELD = " #Field";
    private static final String DROPDOWN = " #Dropdown";
    private static final String DEFAULT = " #Default";
    private static final String RESET = " #Reset";

    private FormLeafPainter() {
    }

    /**
     * Append row {@code index} of {@code host} for {@code leaf}, paint it and bind it: the control's change sends
     * {@code change} plus {@link #VALUE_KEY}, the Reset sends {@code reset}.
     *
     * @return the row's selector, {@code host[index]}
     */
    @Nonnull
    public static String append(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, @Nonnull String host,
            int index, @Nonnull FormLeaf leaf, @Nonnull EventData change, @Nonnull EventData reset,
            @Nullable PlayerRef viewer) {
        cmd.append(host, TEMPLATE);
        String row = host + "[" + index + "]";
        cmd.set(row + TITLE + ".TextSpans", leaf.label());
        if (leaf.hint() != null) {
            cmd.set(row + HINT + ".TextSpans", leaf.hint());
            cmd.set(row + HINT + ".Visible", true);
        }
        boolean choice = leaf.control() == FormLeaf.Control.CHOICE;
        cmd.set(row + FIELD + ".Visible", !choice);
        cmd.set(row + DROPDOWN + ".Visible", choice);
        if (choice && !leaf.value().isEmpty()) {
            cmd.set(row + DROPDOWN + ".Entries", leaf.choices());
        }
        ZigRichButton.text(cmd, row + RESET, leaf.reset());
        String control = row + (choice ? DROPDOWN : FIELD);
        events.addEventBinding(CustomUIEventBindingType.ValueChanged, control,
                change.append(VALUE_KEY, control + ".Value"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, row + RESET, reset, false);
        paint(cmd, row, leaf, viewer);
        return row;
    }

    /**
     * Repaint the built row at {@code row} for {@code leaf}: the control's value, the state word and its style, the
     * value under the owner's word, and whether the Reset shows. No append and no binding, for a partial update.
     */
    public static void paint(@Nonnull UICommandBuilder cmd, @Nonnull String row, @Nonnull FormLeaf leaf,
            @Nullable PlayerRef viewer) {
        if (leaf.control() == FormLeaf.Control.CHOICE) {
            if (leaf.value().isEmpty()) {
                // No value to pick: the entries again, which leaves the dropdown as it was first drawn.
                cmd.set(row + DROPDOWN + ".Entries", leaf.choices());
            } else {
                cmd.set(row + DROPDOWN + ".Value", leaf.value());
            }
        } else {
            cmd.set(row + FIELD + ".Value", leaf.value());
        }
        cmd.set(row + STATE + ".TextSpans", leaf.state());
        ZigStyles.apply(cmd, row + STATE + ".Style",
                leaf.overridden() ? ZigStyles.Name.STATE_ACTIVE : ZigStyles.Name.STATE_NEUTRAL, viewer);
        cmd.set(row + DEFAULT + ".TextSpans", leaf.defaultLine());
        cmd.set(row + RESET + ".Visible", leaf.overridden());
    }
}
