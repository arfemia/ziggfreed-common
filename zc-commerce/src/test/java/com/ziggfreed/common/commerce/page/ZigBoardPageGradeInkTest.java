package com.ziggfreed.common.commerce.page;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Both places the board page paints a contract's grade word wear the band's colour, and the detail panel
 * paints it both ways, since it is re-rendered in place. The page cannot be built in a unit JVM, so the
 * guard reads its source; {@link CommerceLabels#gradeInk} itself is pinned in {@code CommerceLabelsTest}.
 */
class ZigBoardPageGradeInkTest {

    private static final Path PAGE = Path.of("src", "main", "java", "com", "ziggfreed", "common", "commerce",
            "page", "ZigBoardPage.java");

    @Test
    void bothGradeWordsWearTheBandsColour() throws IOException {
        String source = Files.readString(PAGE, StandardCharsets.UTF_8);
        assertTrue(source.contains("cmd.set(sel + \" #RowBadge.Style.TextColor\", gradeInk(ref, board));"),
                "the row's grade word");
        assertTrue(source.contains("cmd.set(\"#DetailGrade.Style.TextColor\", gradeInk(ref, board));"),
                "and the detail panel's");
    }
}
