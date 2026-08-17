package com.ziggfreed.common.objectives.store;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.hypixel.hytale.codec.ExtraInfo;

/**
 * The one-shot manual escape hatch that PRODUCED the golden progress blob at
 * {@link ZigProgressBlobFixture#RESOURCE_PATH}: it encodes
 * {@link ZigProgressBlobFixture#buildFixtureComponent()} through {@link ZigProgressComponent#CODEC}
 * and renders the document as JSON, which is exactly the shape a consumer's database backend stores
 * this component as (encode to a {@link BsonDocument}, {@code toJson()}, and the reverse on load).
 *
 * <p><b>It has already run, and it is not meant to run again.</b> The file it wrote is checked in
 * and is the fixed point {@link ZigProgressBlobCompatTest} measures every future codec change
 * against; re-running this would move the measuring stick rather than the thing being measured. The
 * only sanctioned reason to run it is capturing a NEW fixture beside this one.
 *
 * <p>Disabled by default: it runs only under {@code -DexportProgressFixture=true}, which
 * {@code gradle/zc-module.gradle} forwards from the {@code gradlew} command line into the test JVM.
 *
 * <pre>{@code
 *   .\gradlew :zc-objectives:test --tests ZigProgressBlobFixtureGenerator -DexportProgressFixture=true
 * }</pre>
 */
@EnabledIfSystemProperty(named = "exportProgressFixture", matches = "true")
public class ZigProgressBlobFixtureGenerator {

    @Test
    void generateFixture() throws IOException {
        ZigProgressComponent fixture = ZigProgressBlobFixture.buildFixtureComponent();
        BsonDocument document = ZigProgressComponent.CODEC.encode(fixture, ExtraInfo.THREAD_LOCAL.get());

        // Derived from the constant the compat test READS it back through, so a rename can never
        // leave the writer and the reader pointing at two different files.
        Path outFile = Path.of("src/test/resources" + ZigProgressBlobFixture.RESOURCE_PATH);
        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, document.toJson(), StandardCharsets.UTF_8);

        System.out.println("[ZigProgressBlobFixtureGenerator] Wrote " + outFile.toAbsolutePath());
    }
}
