package com.ziggfreed.common.ui;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

import com.ziggfreed.common.i18n.PlainText;

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
 * <p><b>So the rule is: text the client can resolve whole is sent for the client to resolve, and
 * text it cannot is sent as plain characters.</b> A bare translation keeps its client-side
 * localization (the server never reads the viewer's locale); anything else - a raw literal a pack
 * author wrote, a concatenation, a translation carrying params or parts, a key nothing ships a value
 * for - is flattened to a {@link String} and lands in the sink the property actually is. Content
 * that is missing a translation then READS wrong on screen, which is a bug an author can see and
 * fix, instead of taking the server's players with it.
 *
 * <p><b>One caveat worth authoring around:</b> a {@code .Text} sink resolves a translation's key but
 * never substitutes its {@code {0},{1},...} params and drops its children, so a parameterized or
 * composite message sent whole would print its placeholders literally ("{0}/{1}"). This class
 * flattens such a message instead, which fills the params but reads in the server's default
 * language, not the viewer's. A {@code Label} takes {@code .TextSpans} (a plain {@code cmd.set}),
 * which takes the full message tree and keeps the viewer's locale, its number formats and plurals:
 * always the better sink when the element offers one. This class is for the sinks with no
 * {@code TextSpans}, {@code TextButton} chief among them. Known limit: {@link PlainText} fills only a
 * bare {@code {N}} slot, so a {@code {0, number}} or a plural on such a sink still prints as written;
 * a clickable line that needs one wants a {@code Button} with a child {@code Label}
 * ({@code ZigRichButton}).
 *
 * <p><b>Tooltips have both sinks.</b> Every element carries {@code TooltipText} (the same
 * {@code String} sink, placeholders and all) and {@code TooltipTextSpans} (a full message tree,
 * params filled and children kept, in the viewer's own locale; vanilla's Memories page hangs its
 * pluralized chest tooltips on it). On the client the two are one tooltip, the last write winning.
 * {@link #setTooltip} picks between them and turns a tooltip off the one way that shows no empty
 * box, so a tooltip is written only through it.
 */
public final class UiText {

    private UiText() {
        // static primitive
    }

    /**
     * Write {@code value} to a {@code .Text} property: a bare translation ({@link #isClientResolvable})
     * is sent as a {@link Message} so the client resolves it in its own locale; anything else, a
     * translation with params or children included, is {@link #flatten flattened} to plain characters
     * in the server's default language. A null value writes nothing, leaving whatever the markup
     * authored. A {@code Label} takes {@code .TextSpans} instead (see the class note).
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
     * Write {@code value} as {@code element}'s tooltip, in the viewer's own locale. A message with
     * params or children goes on {@code TooltipTextSpans}, which fills the params and keeps the
     * children; a bare translation or bare raw text goes on {@code TooltipText} exactly as
     * {@link #setText(UICommandBuilder, String, Message)} writes a {@code .Text}. A null value turns
     * off the tooltip an earlier paint left. Nothing here flattens a translation on the server.
     *
     * <p>What the client does with the two sinks (its {@code Element} setters, read off the installed
     * client): they are ONE tooltip. Either setter replaces whatever the other held, so the last write
     * wins and one write on one sink settles an element repainted in place (a detail page's toggle
     * and action buttons). Either setter also marks the element as having a tooltip whenever the value
     * is not null, and the hover box opens on that mark alone, so a {@code TooltipText} of {@code ""}
     * or an empty spans message still opens an EMPTY box. Only a JSON null on {@code TooltipText}
     * ({@code setNull}, the way vanilla's Memories page clears a {@code .Background}) leaves no box.
     * A null on {@code TooltipTextSpans} throws on the client and fails the set, so it is never sent.
     * A message with nothing to show (no key, no params, blank raw text, every child the same) is
     * treated as null, so it turns the tooltip off rather than opening an empty box.
     *
     * @param element the element's selector with no property (e.g. {@code "#Grid[0] #Slot"})
     */
    public static void setTooltip(@Nonnull UICommandBuilder cmd, @Nonnull String element,
            @Nullable Message value) {
        if (value == null || isBlank(value.getFormattedMessage())) {
            cmd.setNull(element + ".TooltipText");
            return;
        }
        if (hasParams(value) || hasChildren(value)) {
            cmd.set(element + ".TooltipTextSpans", value);
            return;
        }
        setText(cmd, element + ".TooltipText", value);
    }

    /**
     * Whether any part of {@code value} binds a param (a nested message or a scalar, the fields
     * {@link PlainText} substitutes), so a {@code String} sink would print its {@code {0}} slots
     * literally.
     */
    public static boolean hasParams(@Nullable Message value) {
        return value != null && hasParams(value.getFormattedMessage());
    }

    private static boolean hasParams(@Nullable FormattedMessage value) {
        if (value == null) {
            return false;
        }
        if ((value.messageParams != null && !value.messageParams.isEmpty())
                || (value.params != null && !value.params.isEmpty())) {
            return true;
        }
        if (value.children != null) {
            for (FormattedMessage child : value.children) {
                if (hasParams(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether {@code value} shows nothing: no key, no params, blank raw text and only blank children. */
    private static boolean isBlank(@Nullable FormattedMessage value) {
        if (value == null) {
            return true;
        }
        if ((value.messageId != null && !value.messageId.isEmpty())
                || (value.messageParams != null && !value.messageParams.isEmpty())
                || (value.params != null && !value.params.isEmpty())
                || (value.rawText != null && !value.rawText.isBlank())) {
            return false;
        }
        if (value.children != null) {
            for (FormattedMessage child : value.children) {
                if (!isBlank(child)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether {@code value} carries children, which a {@code String} sink drops. */
    private static boolean hasChildren(@Nonnull Message value) {
        FormattedMessage formatted = value.getFormattedMessage();
        return formatted != null && formatted.children != null && formatted.children.length > 0;
    }

    /**
     * Whether the client can resolve this message WHOLE on a {@code String} sink ({@code .Text}, a
     * dropdown entry's key): true only for a bare translation, a non-empty {@code MessageId} with no
     * params and no children. The client looks the key up in the viewer's locale but fills no
     * {@code {0}} slot and drops every child, so a translation carrying either is not resolvable
     * here and goes out {@link #flatten flattened}; so does raw text, which has no key to look up.
     */
    public static boolean isClientResolvable(@Nullable Message value) {
        if (value == null) {
            return false;
        }
        String messageId = value.getMessageId();
        return messageId != null && !messageId.isEmpty() && !hasParams(value) && !hasChildren(value);
    }

    /**
     * The plain characters behind a message, for a {@code String}-only sink or a server-side read
     * (an item slot's hover name, a dropdown entry, a search haystack, an A-Z sort key): raw text
     * as written, each child in order, and a translation part RESOLVED through the server's
     * default-language catalogue with its params substituted - see {@link PlainText}, the one
     * resolver behind this. A part whose id has no value contributes the id itself, so a missing
     * translation shows up on screen as the key that needs writing rather than as a blank line
     * nobody can trace.
     */
    @Nonnull
    public static String flatten(@Nullable Message value) {
        return PlainText.of(value);
    }
}
