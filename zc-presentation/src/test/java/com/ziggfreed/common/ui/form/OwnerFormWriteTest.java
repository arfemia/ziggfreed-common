package com.ziggfreed.common.ui.form;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.owner.OwnerFileBindings;

/**
 * An owner form writes each setting in the number type its file's reader expects (R23's one guard on the writer): a
 * whole number as {@code 16}, never {@code 16.0}, which an integer codec refuses; a decimal as a decimal; a switch as
 * true or false; a list as a list. Only the settings changed are written, every other key of the file (a
 * {@code $Comment} included) stays, a value the field refuses writes nothing, and a reset takes the leaf out.
 */
class OwnerFormWriteTest {

    @Test
    void eachSettingIsWrittenInItsOwnNumberTypeAndNothingElseMoves(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{ \"$Comment\": \"keep me\", \"Default\": { \"Cap\": 100.0, \"Other\": \"kept\" } }",
                StandardCharsets.UTF_8);
        OwnerFileBinding binding = OwnerFileBinding.builder("test/settings.json", () -> file).build();
        OwnerFormField cap = new OwnerFormField(0, OwnerFormKind.NUMBER, "Default.Cap", 0.0, null, List.of());
        OwnerFormField personal = new OwnerFormField(1, OwnerFormKind.WHOLE, "Default.Limits.Personal", 0.0, 50.0,
                List.of());
        OwnerFormField highest = new OwnerFormField(2, OwnerFormKind.SWITCH, "Default.HighestOnly", null, null,
                List.of());
        OwnerFormField names = new OwnerFormField(3, OwnerFormKind.LIST, "Default.Names", null, null, List.of());
        List<OwnerFormField> fields = List.of(cap, personal, highest, names);

        Map<Integer, String> typed = new LinkedHashMap<>();
        typed.put(0, "2");
        typed.put(1, "16");
        typed.put(2, "on");
        typed.put(3, "a, b");
        OwnerFormWrite.Collected collected = OwnerFormWrite.collect(fields, typed);
        assertTrue(collected.ok(), "every value fits its field: " + collected.refusal());
        assertEquals(Integer.valueOf(16), collected.leaves().get("Default.Limits.Personal"));
        assertEquals(Double.valueOf(2.0), collected.leaves().get("Default.Cap"));
        assertTrue(OwnerFormWrite.write(binding, collected.leaves()));

        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.contains("\"Personal\": 16") && !written.contains("16.0"),
                "a whole number is written whole: " + written);
        assertTrue(written.contains("\"Cap\": 2.0"), "a decimal is written as a decimal: " + written);
        assertTrue(written.contains("\"HighestOnly\": true"), written);
        assertTrue(written.replaceAll("\\s+", "").contains("\"Names\":[\"a\",\"b\"]"), written);
        assertTrue(written.contains("\"$Comment\": \"keep me\"") && written.contains("\"Other\": \"kept\""),
                "every key the form does not write stays: " + written);

        Map<Integer, String> tooMany = new LinkedHashMap<>();
        tooMany.put(1, "60");
        OwnerFormWrite.Collected refused = OwnerFormWrite.collect(fields, tooMany);
        assertFalse(refused.ok());
        assertEquals(OwnerFormWrite.Problem.ABOVE, refused.refusal().problem());
        Map<Integer, String> notWhole = new LinkedHashMap<>();
        notWhole.put(1, "1.5");
        assertEquals(OwnerFormWrite.Problem.WHOLE, OwnerFormWrite.collect(fields, notWhole).refusal().problem());
        assertEquals(written, Files.readString(file, StandardCharsets.UTF_8), "a refused value writes nothing");

        assertTrue(OwnerFormWrite.write(binding, OwnerFormWrite.reset(personal)));
        String reset = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(reset.contains("\"Personal\""), "a reset takes the owner's value out: " + reset);
        assertTrue(reset.contains("\"Cap\": 2.0"), "and leaves the rest: " + reset);
    }

    /**
     * A {@code Signed} setting takes a number of any sign and writes it as a decimal ({@code -0.9}, {@code 3.0}), held to
     * its bounds like any number; text that is no number is refused and writes nothing.
     */
    @Test
    void aSignedSettingIsWrittenAsADecimalOfAnySign(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("clamps.json");
        Files.writeString(file, "{ \"Default\": { \"Min\": 0.0 } }", StandardCharsets.UTF_8);
        OwnerFileBinding binding = OwnerFileBinding.builder("test/clamps.json", () -> file).build();
        OwnerFormField min = new OwnerFormField(0, OwnerFormKind.SIGNED, "Default.Min", -1.0, null, List.of());
        OwnerFormField offset = new OwnerFormField(1, OwnerFormKind.SIGNED, "Default.Offset", null, null, List.of());
        List<OwnerFormField> fields = List.of(min, offset);

        Map<Integer, String> typed = new LinkedHashMap<>();
        typed.put(0, " -0.9 ");
        typed.put(1, "3");
        OwnerFormWrite.Collected collected = OwnerFormWrite.collect(fields, typed);
        assertTrue(collected.ok(), "both fit: " + collected.refusal());
        assertEquals(Double.valueOf(-0.9), collected.leaves().get("Default.Min"));
        assertEquals(Double.valueOf(3.0), collected.leaves().get("Default.Offset"));
        assertTrue(OwnerFormWrite.write(binding, collected.leaves()));
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(written.contains("\"Min\": -0.9") && written.contains("\"Offset\": 3.0"),
                "a signed number is written as a decimal: " + written);

        Map<Integer, String> below = new LinkedHashMap<>();
        below.put(0, "-2");
        assertEquals(OwnerFormWrite.Problem.BELOW, OwnerFormWrite.collect(fields, below).refusal().problem());
        Map<Integer, String> word = new LinkedHashMap<>();
        word.put(1, "lots");
        assertEquals(OwnerFormWrite.Problem.SIGNED, OwnerFormWrite.collect(fields, word).refusal().problem());
        assertEquals(written, Files.readString(file, StandardCharsets.UTF_8), "a refused value writes nothing");
    }

    /**
     * A form writes the ONE reserved setting its file's binding declares ({@code $MenuTab}, R1186), at the file's root,
     * and nothing else of the file moves; a form setting naming any other {@code $} key is left off the plan, and the
     * binding itself refuses, writing nothing, every leaf naming a {@code $} key it did not declare, a write mixing one
     * in included.
     */
    @Test
    void aFormWritesTheOneReservedSettingItsBindingDeclaresAndNoOtherDollarKey(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("almanac.json");
        Files.writeString(file, "{ \"$Enabled\": true, \"$MenuTab\": { \"Show\": true },"
                + " \"Spring_Fair\": { \"Order\": 5 } }", StandardCharsets.UTF_8);
        OwnerFileBinding binding = OwnerFileBinding.builder("yourmod/almanac.json", () -> file).reserved("$MenuTab")
                .build();
        OwnerFileBindings.register(binding);
        try {
            String json = """
                    { "File": "yourmod/almanac.json", "Sections": [ { "Fields": [
                        { "Path": "$MenuTab.Show", "Kind": "Switch" },
                        { "Path": "$MenuTab.OnlyWhileLive", "Kind": "Switch" },
                        { "Path": "$Enabled", "Kind": "Switch" } ] } ] }
                    """;
            OwnerFormAsset form = OwnerFormAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json),
                    null, new AssetExtraInfo<>(new AssetExtraInfo.Data(OwnerFormAsset.class, "Your_Tab", null)));
            OwnerFormPlan plan = OwnerFormPlan.read(form);
            assertEquals(List.of("$MenuTab.Show", "$MenuTab.OnlyWhileLive"),
                    plan.fields().stream().map(OwnerFormField::writePath).toList(),
                    "the declared setting's paths, and no other $ key");
            assertEquals("on", plan.settings().get(0).shown(), "read where the file writes it");

            Map<Integer, String> typed = new LinkedHashMap<>();
            typed.put(0, "off");
            OwnerFormWrite.Collected collected = OwnerFormWrite.collect(plan.fields(), typed);
            assertTrue(collected.ok() && OwnerFormWrite.write(binding, collected.leaves()));
            JsonObject after = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            assertFalse(after.getAsJsonObject("$MenuTab").get("Show").getAsBoolean(), "the setting is written");
            assertTrue(after.get("$Enabled").getAsBoolean(), "the file's other $ key is left as written");
            assertEquals(5, after.getAsJsonObject("Spring_Fair").get("Order").getAsInt(), "and so is every entry");

            String written = Files.readString(file, StandardCharsets.UTF_8);
            assertFalse(binding.write(Map.of("$Enabled", false)), "the binding refuses an undeclared $ key");
            assertFalse(binding.write(Map.of("$MenuTabs.Show", false)), "and one that only starts like the declared one");
            assertFalse(binding.write(Map.of("Spring_Fair.$Comment", "x")), "and a $ key inside an entry");
            Map<String, Object> mixed = new LinkedHashMap<>();
            mixed.put("$MenuTab.OnlyWhileLive", true);
            mixed.put("$Enabled", false);
            assertFalse(binding.write(mixed), "a write mixing one in is refused whole");
            assertEquals(written, Files.readString(file, StandardCharsets.UTF_8), "a refused write writes nothing");
            assertFalse(OwnerFileBinding.builder("yourmod/other.json", () -> file).build().reservesPath("$MenuTab.Show"),
                    "a binding that declares no reserved setting reserves nothing");
        } finally {
            OwnerFileBindings.clearForTests();
        }
    }
}
