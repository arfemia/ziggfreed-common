package com.ziggfreed.common.i18n;

import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.BoolParamValue;
import com.hypixel.hytale.protocol.DoubleParamValue;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.IntParamValue;
import com.hypixel.hytale.protocol.LongParamValue;
import com.hypixel.hytale.protocol.ParamValue;
import com.hypixel.hytale.protocol.StringParamValue;
import com.hypixel.hytale.server.core.Message;

/**
 * The plain characters behind a {@link Message}, for a sink that takes only a {@code String}.
 *
 * <p>Display text stays a client-resolved {@link Message} everywhere a sink can take one - that is
 * the localization model, and nothing here weakens it. But some engine sinks are {@code String}-only
 * (an item slot's hover name, a dropdown entry's label) and some server-side READS genuinely want
 * characters (a search haystack, an A-Z sort key), and a translation handed to those as its
 * registered id reads as the id: the player hovers an icon and sees {@code yourmod.thing.title},
 * a search for the title matches nothing, and A-Z sorts by key. So a translation part is resolved
 * HERE, against the same default-language catalogue the {@link ContentKeys} existence probes use
 * (the server never reads a viewer's locale; a one-string-for-everyone sink gets the probe
 * language's reading), with its {@code {0}}-style slots substituted - a nested message argument
 * recursively, a scalar as written.
 *
 * <p>A part whose id the catalogue does not carry still contributes the id itself, on purpose: a
 * missing translation shows up on screen as the key that needs writing, which somebody reading a
 * screenshot can trace to a file, where a blank cannot. A JVM with no catalogue at all (a unit
 * test, early boot) degrades every translation the same way and never throws.
 */
public final class PlainText {

    private PlainText() {
        // static primitive
    }

    /** The plain reading of {@code value}; null reads as the empty string. */
    @Nonnull
    public static String of(@Nullable Message value) {
        if (value == null) {
            return "";
        }
        return render(value.getFormattedMessage(), LangCatalog::value);
    }

    /**
     * {@link #of} over an explicit catalogue (key to authored value, null for absent) - the
     * decision core. Public so a caller with its own catalogue can drive substitution without an
     * engine: a unit test asserting a composed sentence, a tool rendering against a fixed
     * language.
     */
    @Nonnull
    public static String render(@Nullable FormattedMessage value,
            @Nonnull Function<String, String> catalogue) {
        StringBuilder out = new StringBuilder();
        append(value, catalogue, out);
        return out.toString();
    }

    private static void append(@Nullable FormattedMessage value,
            @Nonnull Function<String, String> catalogue, @Nonnull StringBuilder out) {
        if (value == null) {
            return;
        }
        String raw = value.rawText;
        if (raw != null && !raw.isEmpty()) {
            out.append(raw);
        } else if (raw == null) {
            String messageId = value.messageId;
            if (messageId != null && !messageId.isEmpty()) {
                String authored = catalogue.apply(messageId);
                out.append(authored != null
                        ? substitute(authored, value, catalogue) : messageId);
            }
        }
        FormattedMessage[] children = value.children;
        if (children == null) {
            return;
        }
        for (FormattedMessage child : children) {
            append(child, catalogue, out);
        }
    }

    /**
     * The authored value with each bound slot filled: a message param recursively rendered (which
     * is what makes a {@code Msg.cat} fold or a nested name read whole), a scalar as written. A
     * slot nothing bound stays literal, exactly as the client leaves it.
     */
    @Nonnull
    private static String substitute(@Nonnull String authored, @Nonnull FormattedMessage value,
            @Nonnull Function<String, String> catalogue) {
        String out = authored;
        Map<String, FormattedMessage> messageParams = value.messageParams;
        if (messageParams != null) {
            for (Map.Entry<String, FormattedMessage> entry : messageParams.entrySet()) {
                out = out.replace("{" + entry.getKey() + "}",
                        render(entry.getValue(), catalogue));
            }
        }
        Map<String, ParamValue> params = value.params;
        if (params != null) {
            for (Map.Entry<String, ParamValue> entry : params.entrySet()) {
                out = out.replace("{" + entry.getKey() + "}", scalarText(entry.getValue()));
            }
        }
        return out;
    }

    /** A scalar param's characters; an unknown kind contributes nothing rather than a toString. */
    @Nonnull
    private static String scalarText(@Nullable ParamValue value) {
        return switch (value) {
            case null -> "";
            case StringParamValue s -> s.value == null ? "" : s.value;
            case IntParamValue i -> Integer.toString(i.value);
            case LongParamValue l -> Long.toString(l.value);
            case DoubleParamValue d -> Double.toString(d.value);
            case BoolParamValue b -> Boolean.toString(b.value);
            default -> "";
        };
    }
}
