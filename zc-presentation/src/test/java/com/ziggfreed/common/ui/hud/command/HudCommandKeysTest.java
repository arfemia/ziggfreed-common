package com.ziggfreed.common.ui.hud.command;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.ui.hud.panel.HudPanelLayout;
import com.ziggfreed.common.ui.hud.panel.HudPanels;

/**
 * Every line the {@code /zighud} family sends a player, and every command and argument description it
 * hands the engine's help, names a key the en-US {@code ziggfreedcommon.hud.lang} carries. A key the file
 * lacks reaches the player as the raw key. No test can stand a command up (it needs the engine's command
 * system), so the sources are read: each {@code HudMessages} call's literal keys (a ternary's both arms
 * included), each {@code desc} by literal or by {@link HudCommandLine} constant, the refusals
 * {@link HudCommandLocks} hands back, and the shared target-player base's own refusals, which it sends
 * through the family's {@code HudMessages::refused}.
 */
class HudCommandKeysTest {

    private static final Path COMMANDS = Path.of("src", "main", "java", "com", "ziggfreed", "common", "ui", "hud",
            "command");
    private static final Path TARGET_BASE = Path.of("..", "zc-core", "src", "main", "java", "com", "ziggfreed",
            "common", "command", "AbstractTargetPlayerCommand.java");
    private static final Path ENGLISH = Path.of("src", "main", "resources", "Server", "Languages", "en-US",
            "ziggfreedcommon.hud.lang");

    /** A command line: {@code HudMessages.refused(ctx, <key expression>, ...)} and its three siblings. */
    private static final Pattern SAID = Pattern.compile(
            "HudMessages\\.(?:heading|detail|done|refused)\\(\\s*ctx\\s*,\\s*([^,)]+)");
    private static final Pattern LINE = Pattern.compile("HudMessages\\.(?:line|key)\\(\\s*([^,)]+)");
    private static final Pattern DESC = Pattern.compile("HudMessages\\.desc\\(([^)]*)\\)");
    private static final Pattern LITERAL = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern COMMAND_LINE_CONSTANT = Pattern.compile("HudCommandLine\\.([A-Z_]+)");
    private static final Pattern LOCK_KEY = Pattern.compile("\"([a-z_]+\\.[a-z_]+)\"");
    private static final Pattern BASE_REFUSAL = Pattern.compile("refusal\\.refuse\\(\\s*ctx\\s*,\\s*\"([^\"]+)\"");

    @Test
    void everyKeyTheZighudFamilySendsShipsInEnglish() throws Exception {
        Set<String> sent = sentKeys();
        for (String shape : List.of("hide.all", "show.all", "desc.open", "desc.arg.player", "place.locked",
                "player.offline")) {
            assertTrue(sent.contains(shape), "the scan reads every shape a key is sent in, " + shape + " included");
        }
        Set<String> english = englishKeys();
        List<String> missing = new ArrayList<>();
        for (String key : sent) {
            if (!english.contains(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "/zighud sends keys en-US " + ENGLISH.getFileName()
                + " does not carry, so the player reads the raw key: " + missing);
    }

    @Test
    void theHelpAndTheUnknownPanelAnswerNameEveryPanelAPlayerCarries() throws IOException {
        Map<String, String> english = englishValues();
        for (String key : List.of("desc.arg.panel", "panel.unknown")) {
            String value = english.get(key);
            for (HudPanelLayout layout : HudPanels.panels()) {
                assertTrue(value != null && value.contains(layout.panelId()),
                        key + " = '" + value + "' does not name " + layout.panelId()
                                + ", so a player is never told the id that panel answers to");
            }
        }
    }

    @Nonnull
    private static Set<String> sentKeys() throws Exception {
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.list(COMMANDS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.equals("HudMessages.java") || name.equals("HudCommandLine.java")) {
                    continue;
                }
                String source = read(file);
                literalsIn(SAID, source, keys, "");
                literalsIn(LINE, source, keys, "");
                Matcher desc = DESC.matcher(source);
                while (desc.find()) {
                    Matcher literal = LITERAL.matcher(desc.group(1));
                    while (literal.find()) {
                        keys.add("desc." + literal.group(1));
                    }
                    Matcher constant = COMMAND_LINE_CONSTANT.matcher(desc.group(1));
                    while (constant.find()) {
                        keys.add("desc." + commandLine(constant.group(1)));
                    }
                }
                if (name.equals("HudCommandLocks.java")) {
                    Matcher lock = LOCK_KEY.matcher(source);
                    while (lock.find()) {
                        keys.add(lock.group(1));
                    }
                }
            }
        }
        assertTrue(Files.isRegularFile(TARGET_BASE), "the shared target-player base is at " + TARGET_BASE);
        Matcher refusal = BASE_REFUSAL.matcher(read(TARGET_BASE));
        while (refusal.find()) {
            keys.add(refusal.group(1));
        }
        return keys;
    }

    private static void literalsIn(@Nonnull Pattern call, @Nonnull String source, @Nonnull Set<String> keys,
            @Nonnull String prefix) {
        Matcher m = call.matcher(source);
        while (m.find()) {
            Matcher literal = LITERAL.matcher(m.group(1));
            while (literal.find()) {
                keys.add(prefix + literal.group(1));
            }
        }
    }

    @Nonnull
    private static String commandLine(@Nonnull String constant) throws Exception {
        Field field = HudCommandLine.class.getField(constant);
        return (String) field.get(null);
    }

    @Nonnull
    private static String read(@Nonnull Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Nonnull
    private static Map<String, String> englishValues() throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String raw : Files.readAllLines(ENGLISH, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (!line.isEmpty() && !line.startsWith("#") && eq > 0) {
                values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return values;
    }

    @Nonnull
    private static Set<String> englishKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        for (String raw : Files.readAllLines(ENGLISH, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            int eq = line.indexOf('=');
            if (!line.isEmpty() && !line.startsWith("#") && eq > 0) {
                keys.add(line.substring(0, eq).trim());
            }
        }
        return keys;
    }
}
