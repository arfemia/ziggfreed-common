package com.ziggfreed.common.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bug R67 exists for, which bit 41 effects at once: a Name written as the lang file's own key
 * ({@code effect.bare_aura}) where the client needs the key the server registers, the file's name
 * first ({@code fixture.effect.bare_aura}). A catalogue probe that accepts a key under any namespace
 * passes the bare spelling; this check must fail it, name the full key, and pass the full spelling.
 */
class EffectNameCheckTest {

    @TempDir
    Path root;

    @Test
    void aBareNameFailsNamingItsFullKeyAndTheFullKeyPasses() throws IOException {
        write("Server/Entity/Effects/Fixture/Fixture_Bare_Aura.json",
                "{ \"Name\": \"effect.bare_aura\", \"StatusEffectIcon\": \"Icons/ItemsGenerated/Fixture.png\" }");
        write("Server/Entity/Effects/Fixture/Fixture_Named_Aura.json",
                "{ \"Name\": \"fixture.effect.named_aura\", \"StatusEffectIcon\": \"Icons/ItemsGenerated/Fixture.png\" }");
        write("Server/Languages/en-US/fixture.lang", "effect.bare_aura = Bare Aura\neffect.named_aura = Named Aura\n");

        EffectNameCheck.Report report = EffectNameCheck.check(List.of(root), false);

        assertEquals(2, report.effects());
        assertEquals(2, report.withIcon());
        assertEquals(1, report.findings().size(), () -> String.join("\n", report.lines()));
        Finding finding = report.findings().get(0);
        assertEquals(Severity.ERROR, finding.severity());
        assertEquals(EffectNameCheck.NAME_NOT_SHIPPED, finding.code());
        assertEquals("Fixture_Bare_Aura", finding.sourceId());
        assertTrue(finding.message().contains("'fixture.effect.bare_aura'"), finding.message());
        assertTrue(report.failed());
    }

    private void write(String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
