package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

/**
 * Two icon-widget mistakes that fail in game only, caught at build time instead, over every
 * shipped {@code .ui} in the library.
 *
 * <p>A {@code .ui} is never compiled or validated, so a widget type the client does not have is
 * silently laid out as an empty element, and an {@code ItemGrid} with no {@code Style:} is laid out
 * at its anchor width with nothing drawn inside it. Both read as "the icon is missing" with the
 * Java looking right, which is how the first one shipped: the shared icon seam drove an
 * {@code ItemIcon} widget for months, a type that exists nowhere in the client's own UI corpus (the
 * token appears there only as an element id and as {@code ItemGridStyle} property names), while
 * every item picture painted through it was a blank gap.
 *
 * <p>So: no {@code ItemIcon} widget declaration anywhere, and every {@code ItemGrid} block carries
 * a {@code Style:} (the shared ladder in {@code ZigButtons.ui} is where one comes from). Detection
 * is line-based over the block's own lines, with {@code //} comments stripped first so a sentence
 * about the rule never satisfies or trips it.
 */
class UiIconWidgetHygieneTest {

    /** Every module's shipped UI tree plus the root's own, whichever exist. */
    private static final List<Path> RESOURCE_ROOTS = resourceRoots();

    private static final Pattern ITEM_ICON_DECL = Pattern.compile("^\\s*ItemIcon(\\s+#\\w+)?\\s*\\{");

    private static final Pattern ITEM_GRID_DECL = Pattern.compile("^\\s*ItemGrid(\\s+#\\w+)?\\s*\\{");

    private static final Pattern STYLE_PROPERTY = Pattern.compile("\\bStyle\\s*:");

    private record Violation(int lineNo, String what) {
    }

    @Test
    void noItemIconWidgetIsDeclared() throws IOException {
        List<String> problems = scanAll(UiIconWidgetHygieneTest::findItemIconDeclarations);
        assertTrue(problems.isEmpty(), () -> problems.size() + " ItemIcon declaration(s) - the "
                + "client has no such widget type; an item's picture is a one-slot ItemGrid whose "
                + ".Slots the icon seam fills (see ui/icon/IconRenderer):\n"
                + String.join("\n", problems));
    }

    @Test
    void everyItemGridCarriesAStyle() throws IOException {
        List<String> problems = scanAll(UiIconWidgetHygieneTest::findUnstyledItemGrids);
        assertTrue(problems.isEmpty(), () -> problems.size() + " ItemGrid block(s) with no "
                + "Style: - a grid with no Style lays out its width and draws nothing; take a rung "
                + "of the @ZigIconGrid ladder in Common/ZigButtons.ui:\n"
                + String.join("\n", problems));
    }

    // ==================== the scanner, on in-memory lines ====================

    @Test
    void scannerFlagsAnItemIconDeclarationWithAndWithoutAnId() {
        List<Violation> found = findItemIconDeclarations(List.of(
                "Group #Row {",
                "  ItemIcon #IcoItem { Visible: false; }",
                "  ItemIcon {",
                "  }",
                "  // ItemIcon #Commented { }",
                "  ItemGrid #ItemIcon { Style: $Z.@ZigIconGrid18; }",
                "}"));
        assertEquals(List.of(2, 3), found.stream().map(Violation::lineNo).toList(),
                "both declaration forms, never a comment, never an element merely named ItemIcon");
    }

    @Test
    void scannerFlagsAGridWhoseBlockNamesNoStyle() {
        List<Violation> found = findUnstyledItemGrids(List.of(
                "ItemGrid #Styled {",
                "  Anchor: (Width: 18, Height: 18);",
                "  Style: $Z.@ZigIconGrid18;",
                "}",
                "ItemGrid #OneLine { SlotsPerRow: 1; Style: $Z.@ZigIconGrid20; }",
                "ItemGrid #Bare {",
                "  Anchor: (Width: 24, Height: 24); // Style: mentioned in a comment only",
                "}",
                "ItemGrid { Anchor: (Width: 24, Height: 24); }"));
        assertEquals(List.of(6, 9), found.stream().map(Violation::lineNo).toList(),
                "a Style inside the block satisfies it, on any line; a comment never does");
    }

    // ==================== the walk ====================

    @Nonnull
    private static List<String> scanAll(@Nonnull Function<List<String>, List<Violation>> rule)
            throws IOException {
        assertTrue(!RESOURCE_ROOTS.isEmpty(), "no resource roots found to scan");
        List<String> problems = new ArrayList<>();
        for (Path root : RESOURCE_ROOTS) {
            try (Stream<Path> entries = Files.walk(root)) {
                for (Path p : entries.filter(f -> f.getFileName().toString().endsWith(".ui"))
                        .sorted().toList()) {
                    List<String> lines;
                    try {
                        lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        problems.add(p + ": unreadable - " + e.getMessage());
                        continue;
                    }
                    for (Violation v : rule.apply(lines)) {
                        problems.add(p + ":" + v.lineNo() + ": " + v.what());
                    }
                }
            }
        }
        return problems;
    }

    @Nonnull
    static List<Path> resourceRoots() {
        List<Path> roots = new ArrayList<>();
        Path own = Path.of("src", "main", "resources");
        if (Files.isDirectory(own)) {
            roots.add(own);
        }
        try (Stream<Path> modules = Files.list(Path.of("."))) {
            for (Path module : modules
                    .filter(m -> m.getFileName().toString().startsWith("zc-"))
                    .filter(Files::isDirectory).sorted().toList()) {
                Path nested = module.resolve("src").resolve("main").resolve("resources");
                if (Files.isDirectory(nested)) {
                    roots.add(nested);
                }
            }
        } catch (IOException ignored) {
            // No module roots listable: the assertion above reports the empty scan.
        }
        return roots;
    }

    // ==================== the two rules ====================

    @Nonnull
    static List<Violation> findItemIconDeclarations(@Nonnull List<String> lines) {
        List<Violation> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = stripComment(lines.get(i));
            if (ITEM_ICON_DECL.matcher(line).find()) {
                out.add(new Violation(i + 1, line.trim()));
            }
        }
        return out;
    }

    /**
     * Each {@code ItemGrid} block, from its opening brace to the brace that closes it, must name
     * {@code Style:} on one of its own lines (a nested block's line counts too; a grid has none).
     */
    @Nonnull
    static List<Violation> findUnstyledItemGrids(@Nonnull List<String> lines) {
        List<Violation> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String opening = stripComment(lines.get(i));
            if (!ITEM_GRID_DECL.matcher(opening).find()) {
                continue;
            }
            boolean styled = false;
            int depth = 0;
            for (int j = i; j < lines.size(); j++) {
                String line = stripComment(lines.get(j));
                if (STYLE_PROPERTY.matcher(line).find()) {
                    styled = true;
                }
                depth += count(line, '{') - count(line, '}');
                if (depth <= 0) {
                    break;
                }
            }
            if (!styled) {
                out.add(new Violation(i + 1, opening.trim()));
            }
        }
        return out;
    }

    @Nonnull
    private static String stripComment(@Nonnull String line) {
        int at = line.indexOf("//");
        return at < 0 ? line : line.substring(0, at);
    }

    private static int count(@Nonnull String line, char c) {
        int n = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }
}
