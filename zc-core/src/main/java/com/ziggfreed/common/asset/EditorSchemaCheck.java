package com.ziggfreed.common.asset;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.config.Schema;

/**
 * Reads a schema set the way the in-game Asset Editor receives it and names every shape known to break
 * it. The editor is sent every asset type's schema at once (the engine's {@code SchemaGenerator}, each
 * schema encoded through {@code Schema.CODEC}, {@code other.json} and {@code NPCRole.json} left out), and
 * one schema it cannot read leaves every type unusable, not just the broken one.
 *
 * <p>The shapes it names, each found the hard way:
 * <ul>
 *   <li>an array that declares no {@code items}: the editor reads the item schema of every array
 *       without asking whether there is one;</li>
 *   <li>an {@code anyOf}, {@code oneOf} or {@code allOf} with no arms, or an {@code enum} with no
 *       values: a union or a dropdown with nothing to offer;</li>
 *   <li>a {@code $ref} that resolves nowhere in the set: a file the set does not hold, or a definition
 *       {@code common.json} does not hold. A ref into {@code other.json}, which the editor is never sent,
 *       is left alone: the engine's own NPC builder schemas point there and the editor opens with them.</li>
 * </ul>
 *
 * <p>Pure: it reads the schemas it is given and nothing else, so a unit test hands it a set built the
 * engine's way and a boot check hands it the engine's own.
 */
public final class EditorSchemaCheck {

    /** The shared definitions file every reference into a codec's own group points at. */
    public static final String COMMON = "common.json";

    /** The engine's second definitions file, which the Asset Editor drops before sending the set. */
    public static final String OTHER = "other.json";

    private EditorSchemaCheck() {
    }

    /** One shape the editor cannot read: the file, where in it (a JSON pointer), and what is wrong. */
    public record Problem(@Nonnull String file, @Nonnull String path, @Nonnull String what) {

        @Nonnull
        @Override
        public String toString() {
            return file + " at " + (path.isEmpty() ? "/" : path) + ": " + what;
        }
    }

    /**
     * Every problem in {@code schemas} (file name to schema, as {@code SchemaGenerator.generateAssetSchemas}
     * answers), each schema read as the editor receives it. A schema that cannot even be encoded is one
     * problem at its root.
     */
    @Nonnull
    public static List<Problem> check(@Nonnull Map<String, Schema> schemas) {
        Map<String, BsonDocument> encoded = new LinkedHashMap<>();
        List<Problem> problems = new ArrayList<>();
        for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
            try {
                BsonValue value = Schema.CODEC.encode(entry.getValue(), new ExtraInfo());
                encoded.put(entry.getKey(), value.asDocument());
            } catch (RuntimeException e) {
                problems.add(new Problem(entry.getKey(), "", "the schema cannot be encoded: " + e));
            }
        }
        problems.addAll(checkEncoded(encoded));
        return problems;
    }

    /**
     * Every asset type a picker in {@code schemas} names ({@code hytaleAssetRef}) that is no file of the set
     * and none of {@code alsoKnown} (a type the editor serves without a schema file of its own, such as
     * {@code NPCRole}), with the first file naming it. A picker over a type the editor does not know offers
     * nothing; read for a diagnostic, never counted as a shape that breaks the editor.
     */
    @Nonnull
    public static Map<String, String> unknownPickerTypes(@Nonnull Map<String, Schema> schemas,
            @Nonnull Set<String> alsoKnown) {
        Set<String> known = new HashSet<>(alsoKnown);
        for (String file : schemas.keySet()) {
            known.add(file.endsWith(".json") ? file.substring(0, file.length() - ".json".length()) : file);
        }
        Map<String, String> unknown = new LinkedHashMap<>();
        for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
            try {
                collectPickerTypes(Schema.CODEC.encode(entry.getValue(), new ExtraInfo()), entry.getKey(), known,
                        unknown);
            } catch (RuntimeException e) {
                // A schema that cannot be encoded is check()'s to report.
            }
        }
        return unknown;
    }

    private static void collectPickerTypes(@Nonnull BsonValue value, @Nonnull String file, @Nonnull Set<String> known,
            @Nonnull Map<String, String> unknown) {
        if (value.isDocument()) {
            BsonDocument document = value.asDocument();
            BsonValue ref = document.get("hytaleAssetRef");
            if (ref != null && ref.isString() && !known.contains(ref.asString().getValue())) {
                unknown.putIfAbsent(ref.asString().getValue(), file);
            }
            for (BsonValue child : document.values()) {
                collectPickerTypes(child, file, known, unknown);
            }
        } else if (value.isArray()) {
            for (BsonValue child : value.asArray()) {
                collectPickerTypes(child, file, known, unknown);
            }
        }
    }

    /** As {@link #check}, over schemas already encoded the way the editor is sent them. */
    @Nonnull
    public static List<Problem> checkEncoded(@Nonnull Map<String, BsonDocument> files) {
        Map<String, Set<String>> definitions = new LinkedHashMap<>();
        for (Map.Entry<String, BsonDocument> file : files.entrySet()) {
            BsonValue defs = file.getValue().get("definitions");
            definitions.put(file.getKey(), defs != null && defs.isDocument()
                    ? new HashSet<>(defs.asDocument().keySet()) : Set.of());
        }
        List<Problem> problems = new ArrayList<>();
        for (Map.Entry<String, BsonDocument> file : files.entrySet()) {
            walk(file.getKey(), "", file.getValue(), definitions, problems);
        }
        return problems;
    }

    private static void walk(@Nonnull String file, @Nonnull String path, @Nonnull BsonDocument schema,
            @Nonnull Map<String, Set<String>> definitions, @Nonnull List<Problem> problems) {
        if (isArray(schema) && !schema.containsKey("items")) {
            problems.add(new Problem(file, path, "an array declares no items"));
        }
        for (String union : new String[] {"anyOf", "oneOf", "allOf"}) {
            BsonValue arms = schema.get(union);
            if (arms != null && (!arms.isArray() || arms.asArray().isEmpty())) {
                problems.add(new Problem(file, path, union + " offers no arm"));
            }
        }
        BsonValue values = schema.get("enum");
        if (values != null && (!values.isArray() || values.asArray().isEmpty())) {
            problems.add(new Problem(file, path, "an enum offers no value"));
        }
        BsonValue ref = schema.get("$ref");
        if (ref != null) {
            String unresolved = ref.isString() ? unresolved(file, ref.asString().getValue(), definitions)
                    : "a $ref that is not a string";
            if (unresolved != null) {
                problems.add(new Problem(file, path, unresolved));
            }
        }
        for (Map.Entry<String, BsonValue> entry : schema.entrySet()) {
            descend(file, path + "/" + entry.getKey(), entry.getValue(), definitions, problems);
        }
    }

    /** Walk into every schema a keyword's value holds: one schema, a list of them, or a map of them. */
    private static void descend(@Nonnull String file, @Nonnull String path, @Nonnull BsonValue value,
            @Nonnull Map<String, Set<String>> definitions, @Nonnull List<Problem> problems) {
        String keyword = path.substring(path.lastIndexOf('/') + 1);
        switch (keyword) {
            case "items", "not", "if", "then", "else", "additionalProperties", "propertyNames" -> {
                if (value.isDocument()) {
                    walk(file, path, value.asDocument(), definitions, problems);
                } else if (value.isArray()) {
                    walkAll(file, path, value.asArray(), definitions, problems);
                }
            }
            case "anyOf", "oneOf", "allOf" -> {
                if (value.isArray()) {
                    walkAll(file, path, value.asArray(), definitions, problems);
                }
            }
            case "properties", "definitions" -> {
                if (value.isDocument()) {
                    for (Map.Entry<String, BsonValue> member : value.asDocument().entrySet()) {
                        if (member.getValue().isDocument()) {
                            walk(file, path + "/" + member.getKey(), member.getValue().asDocument(),
                                    definitions, problems);
                        }
                    }
                }
            }
            default -> {
                // Every other keyword holds data (a title, a default, the editor's own metadata), not a schema.
            }
        }
    }

    private static void walkAll(@Nonnull String file, @Nonnull String path, @Nonnull BsonArray schemas,
            @Nonnull Map<String, Set<String>> definitions, @Nonnull List<Problem> problems) {
        for (int i = 0; i < schemas.size(); i++) {
            BsonValue arm = schemas.get(i);
            if (arm.isDocument()) {
                walk(file, path + "/" + i, arm.asDocument(), definitions, problems);
            } else {
                problems.add(new Problem(file, path + "/" + i, "an arm that is not a schema"));
            }
        }
    }

    private static boolean isArray(@Nonnull BsonDocument schema) {
        BsonValue type = schema.get("type");
        if (type == null) {
            return false;
        }
        if (type.isString()) {
            return "array".equals(type.asString().getValue());
        }
        if (type.isArray()) {
            for (BsonValue t : type.asArray()) {
                if (t.isString() && "array".equals(t.asString().getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** What is wrong with {@code ref} read from {@code file}, or null when it resolves inside the set. */
    @Nullable
    private static String unresolved(@Nonnull String file, @Nonnull String ref,
            @Nonnull Map<String, Set<String>> definitions) {
        int hash = ref.indexOf('#');
        String target = hash < 0 ? ref : ref.substring(0, hash);
        String pointer = hash < 0 ? "" : ref.substring(hash + 1);
        String targetFile = target.isEmpty() ? file : target;
        if (OTHER.equals(targetFile)) {
            // The engine's own NPC builder schemas (EncounterManager.json) point into other.json, which the
            // editor drops, and it opens with them: such a ref is not a shape that breaks it.
            return null;
        }
        Set<String> defs = definitions.get(targetFile);
        if (defs == null) {
            return "a $ref to a file the set does not hold: " + ref;
        }
        if (pointer.isEmpty() || "/".equals(pointer)) {
            return null;
        }
        String prefix = "/definitions/";
        if (!pointer.startsWith(prefix)) {
            return null;
        }
        String name = pointer.substring(prefix.length());
        int slash = name.indexOf('/');
        if (slash >= 0) {
            name = name.substring(0, slash);
        }
        return defs.contains(name) ? null : "a $ref to a definition " + targetFile + " does not hold: " + ref;
    }
}
