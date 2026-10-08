package com.ziggfreed.common.ui.kit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reads the kit's shipped documents the way its document tests need them: a document from the classpath with its
 * {@code //} comments stripped, a template's or an element's block, a block's own properties, a document's named
 * values, and a size written either as a number or as a {@code $ZK.@Name} token of {@code Common/ZigTokens.ui}.
 */
final class KitDocs {

    /** Every kit document under {@code Common/UI/Custom/}: the shared documents, then the appended templates. */
    static final List<String> DOCUMENTS = List.of("Common/ZigTokens.ui", "Common/ZigText.ui", "Common/ZigStyles.ui",
            "Common/ZigKit.ui", "Pages/ZigLedgerRow.ui", "Pages/ZigLedgerRowCompact.ui", "Pages/ZigLedgerRowTall.ui",
            "Pages/ZigLedgerSection.ui",
            "Pages/ZigShowMoreRow.ui", "Pages/ZigDetailBlock.ui", "Pages/ZigDetailLine.ui", "Pages/ZigCollectionTile.ui",
            "Pages/ZigKeepsakeTile.ui", "Pages/ZigStatTile.ui", "Pages/ZigSegment.ui", "Pages/ZigViewTab.ui",
            "Pages/ZigPill.ui", "Pages/ZigItemSlotTile.ui");

    /** The appended templates (a page appends each by path), each rooted at a Group named after its file. */
    static final List<String> APPENDED = DOCUMENTS.subList(4, DOCUMENTS.size());

    /** A named value, {@code @Name = value;}, on one line at a document's top level. */
    private static final Pattern NAMED = Pattern.compile("(?m)^@([A-Za-z][A-Za-z0-9]*)\\s*=\\s*([^;{]+);");

    private KitDocs() {
    }

    /** A shipped document under {@code Common/UI/Custom/}, exactly as shipped. */
    @Nonnull
    static String raw(@Nonnull String path) throws IOException {
        try (InputStream in = KitDocs.class.getResourceAsStream("/Common/UI/Custom/" + path)) {
            assertNotNull(in, "the classpath ships " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    /** A shipped document with every {@code //} comment removed. */
    @Nonnull
    static String document(@Nonnull String path) throws IOException {
        StringBuilder out = new StringBuilder();
        for (String line : raw(path).split("\n")) {
            int at = line.indexOf("//");
            out.append(at < 0 ? line : line.substring(0, at)).append('\n');
        }
        return out.toString();
    }

    /** The body of {@code @Name = Type { ... };}, braces included. */
    @Nonnull
    static String template(@Nonnull String ui, @Nonnull String name) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\s*=\\s*\\w+\\s*\\{").matcher(ui);
        assertTrue(m.find(), "the document defines the template " + name);
        return braces(ui, m.end() - 1);
    }

    /** The parenthesised body of {@code @Name = Type( ... );} or {@code @Name = ( ... );}. */
    @Nonnull
    static String style(@Nonnull String ui, @Nonnull String name) {
        Matcher m = Pattern.compile("(?m)^" + Pattern.quote(name) + "\\s*=\\s*\\w*\\s*\\(").matcher(ui);
        assertTrue(m.find(), "the document defines the style " + name);
        return parens(ui, m.end() - 1);
    }

    /** Does {@code ui} define {@code @Name} at all (a value, a style or a template)? */
    static boolean defines(@Nonnull String ui, @Nonnull String name) {
        return Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + "\\s*=").matcher(ui).find();
    }

    /**
     * The block an element {@code id} declares, braces included: {@code Group #Id {}}, {@code Button #Id {}} or a
     * template instance {@code $ZW.@ZigPicture #Id {}}.
     */
    @Nonnull
    static String block(@Nonnull String ui, @Nonnull String id) {
        Matcher m = declaration(ui, id);
        assertTrue(m.find(), "declares " + id);
        return braces(ui, m.end() - 1);
    }

    /** What {@code id} is declared as: {@code Group}, {@code Label}, {@code $ZW.@ZigPicture}, ... */
    @Nonnull
    static String type(@Nonnull String ui, @Nonnull String id) {
        Matcher m = declaration(ui, id);
        assertTrue(m.find(), "declares " + id);
        return m.group(1);
    }

    /** Is {@code id} declared anywhere in {@code ui}? */
    static boolean declares(@Nonnull String ui, @Nonnull String id) {
        return declaration(ui, id).find();
    }

    /** How many times {@code id} is declared in {@code ui}. */
    static int declarations(@Nonnull String ui, @Nonnull String id) {
        Matcher m = declaration(ui, id);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** A block's own properties and its instance overrides, without the blocks nested in it. */
    @Nonnull
    static String own(@Nonnull String block) {
        StringBuilder own = new StringBuilder();
        int depth = 0;
        for (char c : block.toCharArray()) {
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            } else if (depth == 1) {
                own.append(c);
            }
        }
        return own.toString();
    }

    /** One property of a block's own, {@code Name: value;} (the value up to its semicolon), or null. */
    @Nullable
    static String property(@Nonnull String block, @Nonnull String name) {
        Matcher m = Pattern.compile("(?<![@\\w])" + Pattern.quote(name) + "\\s*:\\s*([^;]+);").matcher(own(block));
        return m.find() ? m.group(1).trim() : null;
    }

    /** One instance override of a block's own, {@code @Name = value;}, or null. */
    @Nullable
    static String parameter(@Nonnull String block, @Nonnull String name) {
        Matcher m = Pattern.compile("@" + Pattern.quote(name) + "\\s*=\\s*([^;]+);").matcher(own(block));
        return m.find() ? m.group(1).trim() : null;
    }

    /** One leaf of an object value such as an {@code Anchor}, {@code (Width: 4, Right: 8)}, or null. */
    @Nullable
    static String leaf(@Nullable String object, @Nonnull String name) {
        if (object == null) {
            return null;
        }
        Matcher m = Pattern.compile("(?<![\\w@])" + Pattern.quote(name) + "\\s*:\\s*([^,)]+)").matcher(object);
        return m.find() ? m.group(1).trim() : null;
    }

    /** Every top-level {@code @Name = value;} of a document, the value as spelled (no templates, no styles). */
    @Nonnull
    static Map<String, String> namedValues(@Nonnull String ui) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = NAMED.matcher(ui);
        while (m.find()) {
            String value = m.group(2).trim();
            if (!value.contains("(")) {
                out.put(m.group(1), value);
            } else if (value.matches("#[0-9a-fA-F]{6}\\([0-9.]+\\)")) {
                out.put(m.group(1), value);
            }
        }
        return out;
    }

    /**
     * A size as a number: a literal, or a {@code $ZK.@Name} token read from {@code Common/ZigTokens.ui}; null when
     * neither.
     */
    @Nullable
    static Integer size(@Nullable String value) throws IOException {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.matches("-?\\d+")) {
            return Integer.parseInt(v);
        }
        Matcher token = Pattern.compile("\\$ZK\\.@([A-Za-z][A-Za-z0-9]*)").matcher(v);
        if (token.matches()) {
            String spelled = namedValues(document("Common/ZigTokens.ui")).get(token.group(1));
            return spelled != null && spelled.matches("\\d+") ? Integer.parseInt(spelled) : null;
        }
        return null;
    }

    @Nonnull
    private static Matcher declaration(@Nonnull String ui, @Nonnull String id) {
        return Pattern.compile("([A-Za-z]+|\\$[A-Za-z]+\\.@[A-Za-z][A-Za-z0-9]*|@[A-Za-z][A-Za-z0-9]*)\\s+"
                + Pattern.quote(id) + "\\s*\\{").matcher(ui);
    }

    @Nonnull
    private static String braces(@Nonnull String ui, int open) {
        return balanced(ui, open, '{', '}');
    }

    @Nonnull
    private static String parens(@Nonnull String ui, int open) {
        return balanced(ui, open, '(', ')');
    }

    @Nonnull
    private static String balanced(@Nonnull String ui, int open, char opener, char closer) {
        int depth = 0;
        for (int i = open; i < ui.length(); i++) {
            char c = ui.charAt(i);
            if (c == opener) {
                depth++;
            } else if (c == closer && --depth == 0) {
                return ui.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced " + opener + closer + " from " + open);
    }
}
