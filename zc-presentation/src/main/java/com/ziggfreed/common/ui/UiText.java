package com.ziggfreed.common.ui;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * The ONE way a page writes a {@code .Text} property, because that property is not the forgiving
 * sink it looks like: on the client it is a CLR {@code System.String}, and the value a
 * {@code cmd.set} sends is deserialized straight into it.
 *
 * <p><b>What that costs when it goes wrong.</b> A {@link Message} is encoded as a structured
 * document ({@code {"MessageId": ...}}, {@code {"RawText": ...}}, {@code {"Children": [...]}}), and
 * only the {@code MessageId} form survives the trip: the client recognizes a translation and
 * resolves it in the viewer's own locale. Any other document has nothing to resolve, so the client
 * tries to build a {@code System.String} out of an object, fails with
 * {@code "No parameterless constructor defined for type 'System.String'"}, and DISCONNECTS - on a
 * singleplayer world that ends the session. A blank label would be a cosmetic bug; this is the
 * whole world going down because one line of authored content had no translation behind it.
 *
 * <p><b>So the rule is: text that can be resolved is sent for the client to resolve, and text that
 * cannot is sent as plain characters.</b> A translation keeps its client-side localization (the
 * server never reads the viewer's locale); anything else - a raw literal a pack author wrote, a
 * concatenation, a key nothing ships a value for - is flattened to a {@link String} and lands in
 * the sink the property actually is. Content that is missing a translation then READS wrong on
 * screen, which is a bug an author can see and fix, instead of taking the server's players with it.
 *
 * <p><b>One caveat worth authoring around:</b> a {@code .Text} sink resolves a translation but does
 * NOT substitute its {@code {0},{1},...} params - a parameterized message prints its placeholders
 * literally. That is a rendering limit of the property, not something this class can paper over: a
 * clickable, parameterized line wants a {@code Button} element with a child {@code Label}, whose
 * {@code TextSpans} takes a full message tree. {@code TextSpans} has none of these limits and is
 * always the better sink when the element offers one; this class is for the elements that do not,
 * {@code TextButton} chief among them.
 */
public final class UiText {

    private UiText() {
        // static primitive
    }

    /**
     * Write {@code value} to a {@code .Text} property: a translation is sent as a {@link Message} so
     * the client resolves it in its own locale, anything else is flattened to plain characters. A
     * null value writes nothing, leaving whatever the markup authored.
     *
     * @param selector the FULL property selector, ending in {@code .Text}
     *                 (e.g. {@code "#OptionsList[0] #OptionBtn.Text"})
     */
    public static void setText(@Nonnull UICommandBuilder cmd, @Nonnull String selector,
            @Nullable Message value) {
        if (value == null) {
            return;
        }
        if (isClientResolvable(value)) {
            cmd.set(selector, value);
            return;
        }
        cmd.set(selector, flatten(value));
    }

    /**
     * Write already-plain text to a {@code .Text} property. A pass-through to {@code cmd.set}, here
     * so that EVERY {@code .Text} write in the library reads the same and the guard test can hold a
     * single rule ("write text through {@link UiText}") rather than trying to tell a {@link Message}
     * from a {@link String} by looking at the call site.
     */
    public static void setText(@Nonnull UICommandBuilder cmd, @Nonnull String selector,
            @Nullable String value) {
        cmd.set(selector, value == null ? "" : value);
    }

    /**
     * Whether the client can resolve this message on a {@code .Text} sink: true only for a
     * translation, which is the one form that carries a {@code MessageId} for the client to look up.
     */
    public static boolean isClientResolvable(@Nullable Message value) {
        if (value == null) {
            return false;
        }
        String messageId = value.getMessageId();
        return messageId != null && !messageId.isEmpty();
    }

    /**
     * The plain characters behind a message that a {@code .Text} sink cannot resolve: its raw text,
     * then each child's, in order. A part that carries only an unresolvable {@code MessageId}
     * contributes the id itself, so a missing translation shows up on screen as the key that needs
     * writing rather than as a blank line nobody can trace.
     */
    @Nonnull
    public static String flatten(@Nullable Message value) {
        StringBuilder out = new StringBuilder();
        append(value, out);
        return out.toString();
    }

    private static void append(@Nullable Message value, @Nonnull StringBuilder out) {
        if (value == null) {
            return;
        }
        String raw = value.getRawText();
        if (raw != null && !raw.isEmpty()) {
            out.append(raw);
        } else if (raw == null) {
            String messageId = value.getMessageId();
            if (messageId != null && !messageId.isEmpty()) {
                out.append(messageId);
            }
        }
        List<Message> children = value.getChildren();
        if (children == null) {
            return;
        }
        for (Message child : children) {
            append(child, out);
        }
    }
}
