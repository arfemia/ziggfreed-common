package com.ziggfreed.common.validation;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * R67: every effect that shows a tile on the native buff bar names itself, in a key a shipped lang
 * file carries exactly as the client will look it up.
 *
 * <p>The client resolves an effect's {@code Name} exactly as written, while the server registers each
 * lang key under its file's prefix: {@code <subfolders, dotted>.<file stem>.<key>} (the engine's
 * {@code I18nModule.loadMessagesFrom} and {@code getPrefix}). So the {@code Name} has to be that full
 * key. A bare key (the file's own spelling, without its prefix) passes every catalogue probe that
 * accepts a key under any namespace, {@code ContentKeys.known} and {@code TextKeyAudit} among them,
 * and still shows nothing on hover; that is why this check reads the lang files itself and never
 * asks either of them.
 *
 * <p>Pure over files: one scope is a list of roots, each the folder that holds {@code Server/} (a mod's
 * {@code src/main/resources}, a content pack's folder, or a jar opened as a zip file system). Every
 * {@code Server/Entity/Effects/**}{@code /*.json} in the scope is read as a JSON object, by its keys and
 * never as text, so a {@code $Comment} that names a field counts for nothing. An effect's id is its
 * file name; an id two roots share is checked once, from the last root that holds it (pass the defaults
 * first and the packs after, so a pack's same-id restyle is the file checked). Lang files are
 * read from every root's {@code Server/Languages/<locale>/}, and a key counts in the scope whichever
 * root ships it. {@code Parent} is walked inside the scope only: a parent outside it (a vanilla effect,
 * another mod's) adds nothing, so a field the effect inherits from there reads as unauthored.
 *
 * <ul>
 *   <li>An effect whose effective {@code StatusEffectIcon} is set needs an effective {@code Name}
 *       ({@link #ICON_WITHOUT_NAME}) that an {@code en-US} lang file in the scope carries as written
 *       ({@link #NAME_NOT_SHIPPED}). Both are errors in every run.</li>
 *   <li>Every other locale a root that carries the key ships should carry it too
 *       ({@link #NAME_LOCALE_GAP}): a warning between lang waves, an error under the release gate
 *       (R123, the {@code langGate} system property, as the MMO's lang parity test reads it).</li>
 *   <li>A costume-shaped effect (a {@code ModelChange} and not a {@code Debuff}, the rule zc-effects'
 *       {@code CostumeRules} decides a costume by) needs an icon ({@link #COSTUME_WITHOUT_ICON}).</li>
 * </ul>
 *
 * <p>Findings are filed under the {@link #DOMAIN} domain with the effect's id as their source, in raw
 * English for whoever fixes the file.
 */
public final class EffectNameCheck {

    /** The domain every finding here is filed under. */
    public static final String DOMAIN = "effect";

    /** The default locale, the one every run fails a gap in. */
    public static final String DEFAULT_LOCALE = "en-US";

    /** The system property the release gate sets ({@code -PlangGate}, R123): locale gaps fail instead of printing. */
    public static final String LANG_GATE_PROPERTY = "langGate";

    /** An effect with an icon and no Name: its tile has nothing to show on hover. */
    public static final String ICON_WITHOUT_NAME = "ICON_WITHOUT_NAME";

    /** An effect's Name no en-US lang file in the scope carries as written. */
    public static final String NAME_NOT_SHIPPED = "NAME_NOT_SHIPPED";

    /** An effect's Name another shipped locale lacks. */
    public static final String NAME_LOCALE_GAP = "NAME_LOCALE_GAP";

    /** A costume-shaped effect with no icon. */
    public static final String COSTUME_WITHOUT_ICON = "COSTUME_WITHOUT_ICON";

    /** An effect whose Parent chain comes back to itself. */
    public static final String PARENT_CYCLE = "PARENT_CYCLE";

    /** A file or folder this check could not read, so what it holds went unchecked. */
    public static final String UNREADABLE = "UNREADABLE";

    private static final String JSON_EXTENSION = ".json";
    private static final String LANG_EXTENSION = ".lang";
    private static final String NAME = "Name";
    private static final String ICON = "StatusEffectIcon";
    private static final String MODEL_CHANGE = "ModelChange";
    private static final String DEBUFF = "Debuff";
    private static final String PARENT = "Parent";

    /** A UTF-8 byte order mark, spelled by code point so this file never carries one. */
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

    /**
     * What one run found: the findings in scan order, how many effects it read, how many of them show
     * an icon, and how many en-US keys the scope ships (zero means the roots hold no en-US lang file).
     */
    public record Report(@Nonnull List<Finding> findings, int effects, int withIcon, int englishKeys) {

        public Report {
            findings = List.copyOf(findings);
        }

        /** Does any finding fail the run? Errors do; a warning only prints. */
        public boolean failed() {
            return ValidationReport.hasErrors(findings);
        }

        /** One headline line, for a test's output above the detail. */
        @Nonnull
        public String summary() {
            return ValidationReport.summarize("Effect names (R67)", findings) + " over " + effects + " effects, "
                    + withIcon + " with an icon";
        }

        /** Every finding as one line. */
        @Nonnull
        public List<String> lines() {
            return ValidationReport.formatAll("Effect", findings);
        }
    }

    private EffectNameCheck() {
    }

    /**
     * Check one repo's resource root plus any extra roots (content packs), as one scope, with the
     * release gate read from the {@code langGate} system property.
     */
    @Nonnull
    public static Report check(@Nonnull Path root, @Nonnull Path... extraRoots) {
        List<Path> roots = new ArrayList<>();
        roots.add(root);
        roots.addAll(List.of(extraRoots));
        return check(roots);
    }

    /** Check {@code roots} as one scope, with the release gate read from the {@code langGate} system property. */
    @Nonnull
    public static Report check(@Nonnull Collection<Path> roots) {
        return check(roots, Boolean.getBoolean(LANG_GATE_PROPERTY));
    }

    /**
     * Check {@code roots} as one scope. A root with no {@code Server/} folder contributes nothing.
     * Under {@code langGate} a gap in a locale other than en-US is an error; otherwise a warning.
     */
    @Nonnull
    public static Report check(@Nonnull Collection<Path> roots, boolean langGate) {
        List<Finding> findings = new ArrayList<>();
        Langs langs = readLangs(roots, findings);
        Map<String, JsonObject> byId = new LinkedHashMap<>();
        for (Path root : roots) {
            readEffects(root, byId, findings);
        }

        int withIcon = 0;
        for (Map.Entry<String, JsonObject> effect : byId.entrySet()) {
            if (checkEffect(effect.getKey(), effect.getValue(), byId, langs, langGate, findings)) {
                withIcon++;
            }
        }
        Set<String> english = langs.keys.get(DEFAULT_LOCALE);
        return new Report(findings, byId.size(), withIcon, english == null ? 0 : english.size());
    }

    // ==================== one effect ====================

    /** Checks one effect; answers whether it shows an icon. */
    private static boolean checkEffect(@Nonnull String id, @Nonnull JsonObject self, @Nonnull Map<String, JsonObject> byId,
            @Nonnull Langs langs, boolean langGate, @Nonnull List<Finding> findings) {
        Chain chain = chain(id, self, byId);
        if (chain.cycleAt != null) {
            findings.add(Finding.error(DOMAIN, PARENT_CYCLE, "comes back to '" + chain.cycleAt
                    + "' through its Parent chain, so the server cannot resolve what it inherits", id));
        }

        Leaf icon = chain.string(ICON);
        Leaf model = chain.string(MODEL_CHANGE);
        if (icon == null) {
            if (model != null && !chain.debuff()) {
                findings.add(Finding.error(DOMAIN, COSTUME_WITHOUT_ICON, "changes the wearer's model"
                        + model.from(id) + " and is not a Debuff, so it is a costume, but it has no StatusEffectIcon,"
                        + " so the wearer sees no tile for what they wear; give it an icon and a Name", id));
            }
            return false;
        }

        Leaf name = chain.string(NAME);
        if (name == null) {
            findings.add(Finding.error(DOMAIN, ICON_WITHOUT_NAME, "has a StatusEffectIcon" + icon.from(id)
                    + " but no Name, so its buff bar tile has no name to show on hover; add a Name that is a full"
                    + " lang key (the lang file's name, then the key)", id));
            return true;
        }

        String key = name.value;
        if (!langs.has(DEFAULT_LOCALE, key)) {
            String full = langs.fullKeyFor(key);
            String fix = full != null
                    ? "; the en-US file carries it as '" + full + "', so write that"
                    : "; a Name is the lang file's name, then the key, so write '<file>.<key>' and add the key to an"
                            + " en-US file";
            findings.add(Finding.error(DOMAIN, NAME_NOT_SHIPPED, "has the Name '" + key + "'" + name.from(id)
                    + ", which no en-US lang file carries as written; the client looks a Name up exactly as written,"
                    + " so its tile cannot show its name" + fix, id));
            return true;
        }

        List<String> missing = langs.localesLacking(key);
        if (!missing.isEmpty()) {
            String message = "has the Name '" + key + "'" + name.from(id) + ", which the " + String.join(", ", missing)
                    + " lang files lack, so a player in that language reads it in English until a lang wave adds it";
            findings.add(langGate
                    ? Finding.error(DOMAIN, NAME_LOCALE_GAP, message, id)
                    : Finding.warning(DOMAIN, NAME_LOCALE_GAP, message, id));
        }
        return true;
    }

    // ==================== Parent ====================

    /** One authored value and the id of the effect that authored it. */
    private record Leaf(@Nonnull String value, @Nonnull String owner) {

        /** " (inherited from 'X')" when an ancestor authored it, else nothing. */
        @Nonnull
        String from(@Nonnull String id) {
            return owner.equals(id) ? "" : " (inherited from '" + owner + "')";
        }
    }

    /** An effect and the in-scope ancestors its Parent chain reaches, nearest first. */
    private record Chain(@Nonnull List<String> ids, @Nonnull List<JsonObject> layers, @Nullable String cycleAt) {

        /** The nearest non-blank string authored for {@code field}, as the codec's inheritance reads it. */
        @Nullable
        Leaf string(@Nonnull String field) {
            for (int i = 0; i < layers.size(); i++) {
                JsonElement element = layers.get(i).get(field);
                if (element == null || element.isJsonNull()) {
                    continue;
                }
                if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()
                        && !element.getAsString().isBlank()) {
                    return new Leaf(element.getAsString(), ids.get(i));
                }
                return null;
            }
            return null;
        }

        /** The nearest authored {@code Debuff}, false when none in the scope authors it. */
        boolean debuff() {
            for (JsonObject layer : layers) {
                JsonElement element = layer.get(DEBUFF);
                if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()) {
                    return element.getAsBoolean();
                }
            }
            return false;
        }
    }

    /** Walk {@code self}'s Parent chain inside the scope; a parent outside it ends the walk. */
    @Nonnull
    private static Chain chain(@Nonnull String id, @Nonnull JsonObject self, @Nonnull Map<String, JsonObject> byId) {
        List<String> ids = new ArrayList<>();
        List<JsonObject> layers = new ArrayList<>();
        ids.add(id);
        layers.add(self);
        Set<String> seen = new HashSet<>(ids);
        JsonObject at = self;
        while (true) {
            JsonElement parent = at.get(PARENT);
            if (parent == null || !parent.isJsonPrimitive() || !parent.getAsJsonPrimitive().isString()) {
                return new Chain(ids, layers, null);
            }
            String parentId = parent.getAsString();
            if (!seen.add(parentId)) {
                return new Chain(ids, layers, parentId);
            }
            JsonObject next = byId.get(parentId);
            if (next == null) {
                return new Chain(ids, layers, null);
            }
            ids.add(parentId);
            layers.add(next);
            at = next;
        }
    }

    // ==================== effects ====================

    private static void readEffects(@Nonnull Path root, @Nonnull Map<String, JsonObject> byId,
            @Nonnull List<Finding> findings) {
        Path dir = root.resolve("Server").resolve("Entity").resolve("Effects");
        for (Path file : files(dir, JSON_EXTENSION, findings)) {
            String name = file.getFileName().toString();
            String id = name.substring(0, name.length() - JSON_EXTENSION.length());
            JsonObject json = readJson(file, id, findings);
            if (json != null) {
                // A later root wins a shared id: roots run defaults first, then packs, so a pack's
                // same-id restyle (R63) is the file the server loads and the one checked.
                byId.put(id, json);
            }
        }
    }

    @Nullable
    private static JsonObject readJson(@Nonnull Path file, @Nonnull String id, @Nonnull List<Finding> findings) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK) {
                text = text.substring(1);
            }
            JsonElement element = JsonParser.parseString(text);
            if (element.isJsonObject()) {
                return element.getAsJsonObject();
            }
            findings.add(Finding.error(DOMAIN, UNREADABLE, file + " is not a JSON object, so its effect cannot be"
                    + " checked and the server cannot load it", id));
        } catch (IOException | RuntimeException e) {
            findings.add(Finding.error(DOMAIN, UNREADABLE, file + " could not be read as JSON (" + e.getMessage()
                    + "), so its effect cannot be checked and the server cannot load it", id));
        }
        return null;
    }

    // ==================== lang files ====================

    /** Every key the scope ships, per locale, and what a gap check needs of each root. */
    private static final class Langs {
        /** Locale to every full key any root ships in it. */
        final Map<String, Set<String>> keys = new HashMap<>();
        /** Per root, its locales, each with the full keys that root ships in it. */
        final List<Map<String, Set<String>>> perRoot = new ArrayList<>();
        /** Every en-US file prefix, sorted, for the full spelling of a bare key. */
        final Set<String> englishPrefixes = new TreeSet<>();

        boolean has(@Nonnull String locale, @Nonnull String key) {
            Set<String> set = keys.get(locale);
            return set != null && set.contains(key);
        }

        /** The full key an en-US file carries {@code bare} under, or null when none does. */
        @Nullable
        String fullKeyFor(@Nonnull String bare) {
            for (String prefix : englishPrefixes) {
                String full = prefix + '.' + bare;
                if (has(DEFAULT_LOCALE, full)) {
                    return full;
                }
            }
            return null;
        }

        /** Every other locale a root shipping {@code key} in en-US ships, that lacks it in the scope; sorted. */
        @Nonnull
        List<String> localesLacking(@Nonnull String key) {
            Set<String> lacking = new TreeSet<>();
            for (Map<String, Set<String>> root : perRoot) {
                Set<String> english = root.get(DEFAULT_LOCALE);
                if (english == null || !english.contains(key)) {
                    continue;
                }
                for (String locale : root.keySet()) {
                    if (!DEFAULT_LOCALE.equals(locale) && !has(locale, key)) {
                        lacking.add(locale);
                    }
                }
            }
            return List.copyOf(lacking);
        }
    }

    @Nonnull
    private static Langs readLangs(@Nonnull Collection<Path> roots, @Nonnull List<Finding> findings) {
        Langs langs = new Langs();
        for (Path root : roots) {
            Map<String, Set<String>> mine = new HashMap<>();
            Path languages = root.resolve("Server").resolve("Languages");
            for (Path localeDir : localeDirs(languages, findings)) {
                String locale = localeDir.getFileName().toString();
                for (Path file : files(localeDir, LANG_EXTENSION, findings)) {
                    String prefix = prefix(localeDir, file);
                    Set<String> fileKeys = readLang(file, findings);
                    if (fileKeys == null) {
                        continue;
                    }
                    Set<String> rootKeys = mine.computeIfAbsent(locale, k -> new HashSet<>());
                    Set<String> allKeys = langs.keys.computeIfAbsent(locale, k -> new HashSet<>());
                    for (String key : fileKeys) {
                        rootKeys.add(prefix + '.' + key);
                        allKeys.add(prefix + '.' + key);
                    }
                    if (DEFAULT_LOCALE.equals(locale)) {
                        langs.englishPrefixes.add(prefix);
                    }
                }
            }
            langs.perRoot.add(mine);
        }
        return langs;
    }

    /** The engine's prefix for {@code file}: its subfolders under the locale folder, dotted, then its stem. */
    @Nonnull
    private static String prefix(@Nonnull Path localeDir, @Nonnull Path file) {
        StringBuilder prefix = new StringBuilder();
        Path directory = file.getParent();
        if (directory != null && !localeDir.equals(directory)) {
            for (Path part : localeDir.relativize(directory)) {
                prefix.append(part).append('.');
            }
        }
        String name = file.getFileName().toString();
        return prefix.append(name, 0, name.length() - LANG_EXTENSION.length()).toString();
    }

    /** The keys one lang file registers, parsed as the engine's {@code LangFileParser} does; null when unreadable. */
    @Nullable
    private static Set<String> readLang(@Nonnull Path file, @Nonnull List<Finding> findings) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return langKeys(reader);
        } catch (IOException | RuntimeException e) {
            findings.add(Finding.error(DOMAIN, UNREADABLE, file + " could not be read (" + e.getMessage()
                    + "), so the server loads none of its keys", file.getFileName().toString()));
            return null;
        }
    }

    /**
     * The keys of one lang file, by the engine's rules: a trimmed blank or {@code #} line is skipped,
     * even inside a continuation; a key line needs a non-empty key and a non-empty value; a value
     * ending in a backslash continues on the next line, whatever that line holds.
     */
    @Nonnull
    private static Set<String> langKeys(@Nonnull BufferedReader reader) throws IOException {
        Set<String> keys = new HashSet<>();
        String current = null;
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                continue;
            }
            if (current == null) {
                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                if (key.isEmpty() || value.isEmpty()) {
                    continue;
                }
                if (value.charAt(value.length() - 1) == '\\') {
                    current = key;
                } else {
                    keys.add(key);
                }
            } else if (line.charAt(line.length() - 1) != '\\') {
                keys.add(current);
                current = null;
            }
        }
        if (current != null) {
            keys.add(current);
        }
        return keys;
    }

    // ==================== walking ====================

    /** The locale folders directly under {@code languages}, sorted; none when it is absent. */
    @Nonnull
    private static List<Path> localeDirs(@Nonnull Path languages, @Nonnull List<Finding> findings) {
        if (!Files.isDirectory(languages)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(languages)) {
            return children.filter(Files::isDirectory).sorted().toList();
        } catch (IOException | RuntimeException e) {
            findings.add(Finding.error(DOMAIN, UNREADABLE, languages + " could not be listed (" + e.getMessage()
                    + "), so none of its lang files were read", languages.toString()));
            return List.of();
        }
    }

    /** Every regular file under {@code dir} whose name ends in {@code extension}, sorted; none when it is absent. */
    @Nonnull
    private static List<Path> files(@Nonnull Path dir, @Nonnull String extension, @Nonnull List<Finding> findings) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(extension))
                    .sorted()
                    .toList();
        } catch (IOException | RuntimeException e) {
            findings.add(Finding.error(DOMAIN, UNREADABLE, dir + " could not be walked (" + e.getMessage()
                    + "), so nothing under it was checked", dir.toString()));
            return List.of();
        }
    }
}
