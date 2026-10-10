package com.ziggfreed.common.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * R67 over the library's own effects: every effect the jar ships with a buff-bar icon names itself in
 * a key an en-US lang file carries exactly as the client looks it up ({@link EffectNameCheck}). It runs
 * at the root because the jar is assembled here: every module's resources form one scope, found by
 * listing the modules, so an effect a module adds later is covered with nothing to change. A gap in
 * another locale prints between lang waves and fails under {@code -PlangGate} (R123).
 */
class ShippedEffectNamesTest {

    @Test
    void everyShippedEffectWithAnIconNamesItselfInAKeyItsLangFilesCarry() throws IOException {
        List<Path> roots = resourceRoots();
        EffectNameCheck.Report report = EffectNameCheck.check(roots);

        System.out.println("[R67] " + report.summary());
        report.lines().forEach(System.out::println);
        assertTrue(report.englishKeys() > 0, "no en-US lang file under " + roots);
        assertFalse(report.failed(), () -> String.join("\n", report.lines()));
    }

    /** The root's resources, then each module's, in name order. */
    private static List<Path> resourceRoots() throws IOException {
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of("src", "main", "resources"));
        try (Stream<Path> children = Files.list(Path.of("."))) {
            children.filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().startsWith("zc-"))
                    .sorted()
                    .map(dir -> dir.resolve("src").resolve("main").resolve("resources"))
                    .filter(Files::isDirectory)
                    .forEach(roots::add);
        }
        return roots;
    }
}
