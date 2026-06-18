package com.ziggfreed.common.dialogue.i18n;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * A tiny inline-markup parser that turns an authored string with Hytale-flavored
 * tags into a {@link Message} TREE the client renders as rich text via a Label's
 * {@code TextSpans} property. Hytale has NO server-side inline-markup parser (the
 * engine only substitutes {@code {param}}); rich text is the {@code FormattedMessage}
 * tree (per-segment {@code color}/{@code bold}/{@code italic}, the same mechanism the
 * engine's own {@code PortalDeviceActivePage} uses). This bridges the two: authors
 * keep writing readable inline markup, and the page renders it natively.
 *
 * <p>Supported tags (nestable): {@code <color is="#rrggbb">..</color>},
 * {@code <b>..</b>} (bold), {@code <i>..</i>} (italic), {@code <u>..</u>} (no engine
 * setter, so the text is kept but unstyled). A literal {@code \n} becomes a newline.
 * Unknown / malformed tags are dropped gracefully (their text is kept). The
 * {@link Message} carries the parsed (single-locale) text, so this is for content a
 * consumer is fine baking into one language (a minigame's en-US dialogue); a
 * multi-locale consumer leaves the value plain and gets a client-resolved
 * translation instead (the page only takes this path when {@link #hasMarkup} is true).
 *
 * <p><b>UI only:</b> {@code TextSpans} is a Label property. Use it for custom-page
 * Labels (the dialogue node text), NOT for toasts / event-title banners (which render
 * plain) or {@code TextButton} labels (which have no {@code TextSpans}).
 */
public final class RichText {

    private RichText() {
    }

    /** True if {@code s} contains any supported markup tag (cheap pre-check). */
    public static boolean hasMarkup(@Nullable String s) {
        return s != null && (s.contains("<color") || s.contains("<b>") || s.contains("<i>")
                || s.contains("<u>") || s.contains("</"));
    }

    /** Parse {@code markup} into a {@link Message} tree of styled segments (never null). */
    @Nonnull
    public static Message parse(@Nonnull String markup) {
        List<Message> segments = new ArrayList<>();
        Deque<Style> stack = new ArrayDeque<>();
        StringBuilder run = new StringBuilder();
        int i = 0;
        int len = markup.length();
        while (i < len) {
            char c = markup.charAt(i);
            if (c == '<') {
                int end = markup.indexOf('>', i);
                if (end < 0) {
                    run.append(c); // a stray '<' with no closing '>'
                    i++;
                    continue;
                }
                flush(segments, run, stack);
                applyTag(markup.substring(i + 1, end).trim(), stack);
                i = end + 1;
            } else if (c == '\\' && i + 1 < len && markup.charAt(i + 1) == 'n') {
                run.append('\n'); // literal "\n" -> newline
                i += 2;
            } else {
                run.append(c);
                i++;
            }
        }
        flush(segments, run, stack);

        if (segments.isEmpty()) {
            return Message.raw("");
        }
        if (segments.size() == 1) {
            return segments.get(0);
        }
        return Message.join(segments.toArray(new Message[0]));
    }

    /** Push (open) or pop (close) the style stack for one tag. */
    private static void applyTag(@Nonnull String tag, @Nonnull Deque<Style> stack) {
        if (tag.isEmpty()) {
            return;
        }
        if (tag.charAt(0) == '/') {
            if (!stack.isEmpty()) {
                stack.pop();
            }
            return;
        }
        Style parent = stack.isEmpty() ? Style.NONE : stack.peek();
        String name = tagName(tag);
        switch (name) {
            case "b" -> stack.push(parent.withBold());
            case "i" -> stack.push(parent.withItalic());
            case "color" -> {
                String hex = extractColor(tag);
                stack.push(hex != null ? parent.withColor(hex) : parent);
            }
            // <u> (no Message underline setter) + any unknown tag: push the parent so the
            // matching close tag still pops cleanly, but add no style (text is kept).
            default -> stack.push(parent);
        }
    }

    /** Emit the accumulated text run as a styled segment, then reset the run. */
    private static void flush(@Nonnull List<Message> segments, @Nonnull StringBuilder run,
                              @Nonnull Deque<Style> stack) {
        if (run.length() == 0) {
            return;
        }
        Style s = stack.isEmpty() ? Style.NONE : stack.peek();
        Message seg = Message.raw(run.toString());
        if (s.color != null) {
            seg.color(s.color);
        }
        if (s.bold) {
            seg.bold(true);
        }
        if (s.italic) {
            seg.italic(true);
        }
        segments.add(seg);
        run.setLength(0);
    }

    @Nonnull
    private static String tagName(@Nonnull String tag) {
        int i = 0;
        while (i < tag.length() && !Character.isWhitespace(tag.charAt(i))) {
            i++;
        }
        return tag.substring(0, i).toLowerCase(Locale.ROOT);
    }

    /** Extract the hex from {@code color is="#rrggbb"} (double or single quotes), or null. */
    @Nullable
    private static String extractColor(@Nonnull String tag) {
        int eq = tag.indexOf('=');
        if (eq < 0) {
            return null;
        }
        int i = eq + 1;
        while (i < tag.length() && Character.isWhitespace(tag.charAt(i))) {
            i++;
        }
        if (i >= tag.length() || (tag.charAt(i) != '"' && tag.charAt(i) != '\'')) {
            return null;
        }
        char quote = tag.charAt(i);
        int close = tag.indexOf(quote, i + 1);
        if (close < 0) {
            return null;
        }
        String hex = tag.substring(i + 1, close).trim();
        return hex.isEmpty() ? null : hex;
    }

    /** The cumulative style at one nesting depth (each open tag inherits its parent's). */
    private record Style(@Nullable String color, boolean bold, boolean italic) {
        static final Style NONE = new Style(null, false, false);

        Style withColor(@Nonnull String c) {
            return new Style(c, bold, italic);
        }

        Style withBold() {
            return new Style(color, true, italic);
        }

        Style withItalic() {
            return new Style(color, bold, true);
        }
    }
}
