package com.ziggfreed.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The one record writer every store's file goes through: a torn file reads the copy written before it, and
 * that copy outlives the next write; a file from a newer build is refused whole, read as nothing and never
 * written over. Both tests read back what the engine's atomic writer wrote, which on Update 7 loads only
 * under the engine's log manager, so they are tagged {@code engine-items}.
 */
class RecordFileTest {

    @TempDir
    Path dir;

    /** A record of one value, the smallest body a store could have. */
    private static final class OneValue implements RecordFile.Body {

        @Nullable
        String value;

        @Override
        @Nonnull
        public JsonObject write() {
            JsonObject doc = new JsonObject();
            doc.addProperty("value", value);
            return doc;
        }

        @Override
        public void read(@Nonnull JsonObject doc, int version) {
            value = doc.get("value").getAsString();
        }

        @Override
        public void forget() {
            value = null;
        }
    }

    @Nonnull
    private static RecordFile kept(@Nonnull OneValue body) {
        return RecordFile.builder("test", "the value", body).build();
    }

    private static void save(@Nonnull RecordFile file, @Nonnull OneValue body, @Nonnull String value) {
        body.value = value;
        file.writeNow();
    }

    @Nonnull
    private static String read(@Nonnull Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Tag("engine-items")
    @Test
    void aTornFileReadsTheCopyBeforeItAndKeepsThatCopyThroughTheNextWrite() throws IOException {
        Path path = dir.resolve("record.json");
        OneValue body = new OneValue();
        RecordFile file = kept(body);
        file.persistTo(path, null);
        save(file, body, "first");
        save(file, body, "second");
        assertEquals(1, JsonParser.parseString(read(path)).getAsJsonObject().get("version").getAsInt(),
                "every write carries the version");
        Files.writeString(path, "{ \"version\": 1, \"value\": \"sec", StandardCharsets.UTF_8);

        OneValue after = new OneValue();
        RecordFile restarted = kept(after);
        restarted.persistTo(path, null);
        assertEquals("first", after.value, "a torn file reads the copy written before it, never nothing");

        save(restarted, after, "third");
        assertEquals("third", JsonParser.parseString(read(path)).getAsJsonObject().get("value").getAsString());
        assertEquals("first", JsonParser.parseString(read(dir.resolve("record.json.bak"))).getAsJsonObject()
                .get("value").getAsString(), "the good copy is kept, never replaced by the torn file");
    }

    @Tag("engine-items")
    @Test
    void aFileFromANewerBuildIsRefusedWholeAndNeverWrittenOver() throws IOException {
        Path control = Files.createDirectories(dir.resolve("control")).resolve("record.json");
        OneValue fresh = new OneValue();
        RecordFile writes = kept(fresh);
        writes.persistTo(control, null);
        save(writes, fresh, "kept");
        assertTrue(Files.isRegularFile(control), "the engine's writer runs in this JVM");

        Path path = dir.resolve("record.json");
        String newer = "{ \"version\": 2, \"value\": \"from a newer build\" }";
        Files.writeString(path, newer, StandardCharsets.UTF_8);
        OneValue body = new OneValue();
        RecordFile file = kept(body);
        file.persistTo(path, null);

        assertTrue(file.refused());
        assertNull(body.value, "a newer shape is read as nothing, never misread as today's");
        save(file, body, "this build's value");
        assertEquals("this build's value", body.value, "memory still answers");
        assertEquals(newer, read(path), "the newer file waits, byte for byte, for the build that reads it");
        assertFalse(Files.exists(dir.resolve("record.json.bak")));
    }
}
