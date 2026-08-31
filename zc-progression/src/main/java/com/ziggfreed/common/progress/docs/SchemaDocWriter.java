package com.ziggfreed.common.progress.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.WrappedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.builder.BuilderField;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.achievement.asset.AchievementCategoryAsset;
import com.ziggfreed.common.achievement.asset.AchievementMilestoneAsset;
import com.ziggfreed.common.codec.JsonTreeCodec;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.progress.asset.GeneratorAxisAsset;
import com.ziggfreed.common.progress.asset.ObjectiveLeafAsset;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.quest.asset.QuestGeneratorAsset;
import com.ziggfreed.common.quest.asset.QuestObjectiveAsset;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.time.DurationGroup;

/**
 * Walks every authorable progression type's declared static {@code CODEC} field via
 * {@link BuilderCodec#getEntries()} and renders a GitHub-Markdown schema reference, committed at
 * this module's root as {@code SCHEMA.md} and regenerated on demand with
 * {@code gradlew :zc-progression:generateSchemaDocs}.
 *
 * <p>ONE pure {@link #render()} function is shared by the Gradle task ({@link #main}) and by
 * {@code SchemaDocDriftTest}, so the committed file and the test's expectation can never diverge
 * from each other or from the codecs. The documentation strings are REAL: every leaf of every group
 * in this module's authoring surface carries a {@code .documentation("...")} call by design, so
 * {@link BuilderField#getDocumentation()} already returns prose written for an author.
 *
 * <p><b>What {@link #render()} emits</b>: one {@code ##} section per registered root type (a table
 * of contents up top, then each section's own {@code Key | Type | Default | Documentation} table),
 * plus one further subsection per PRIVATE nested field group - a group with no independent section
 * of its own, such as {@code QuestAsset.Flow}. A SHARED type reused inline (the {@code Text} group,
 * a {@code Requires} block, a reward entry) instead links straight to its own top-level section
 * rather than re-inlining the whole subtree, which is what keeps one declaration visibly one
 * declaration. Every section carries an explicit {@code <a id="...">} anchor so cross-references
 * resolve regardless of how GitHub slugs the visible heading text.
 *
 * <p>Internally {@link #renderModel()} walks the codecs into a pure {@code Map}/{@code List}/
 * {@code String} model first (also consumed by the drift test's structural check), classifying each
 * field's child {@link Codec}:
 * <ul>
 *   <li>a nested {@link BuilderCodec} group renders {@code type:"object"} plus {@code nestedType};
 *       a nested type that is itself a registered root type carries a {@code "ref"} pointer instead
 *       of an inlined copy, and a group cycling back to an ancestor renders a {@code cyclic} marker
 *       rather than recursing forever;</li>
 *   <li>an array/map/set field (detected through {@link WrappedCodec} plus the concrete codec
 *       class's simple name) renders {@code type:"array"|"map"|"set"} plus an {@code "of"}
 *       classification of the element or value codec - which is how {@code Objectives} reads as a
 *       map of objectives and {@code Criteria} as an array of them;</li>
 *   <li>a {@link JsonTreeCodec} leaf renders {@code type:"json"}: a subtree kept exactly as
 *       authored, with no schema at this level;</li>
 *   <li>an {@link EnumCodec} renders {@code type:"enum"} plus its {@code "values"};</li>
 *   <li>anything else is a primitive leaf, named from the codec class's own simple name with a
 *       trailing {@code Codec} stripped: {@code string} / {@code integer} / {@code long} /
 *       {@code double} / {@code boolean} / ...</li>
 * </ul>
 */
public final class SchemaDocWriter {

    /**
     * The authorable and shared-vocabulary types this reference documents, keyed by the name used
     * as both the section heading and the cross-reference target. Registration order IS the order
     * the document reads in: the authorable file types first, then the groups they are built out of.
     */
    private static final Map<String, BuilderCodec<?>> ROOT_CODECS = new LinkedHashMap<>();

    /** Reverse lookup from a nested field's {@link BuilderCodec#getInnerClass()} to its root doc name, so a shared group links instead of being re-inlined. */
    private static final Map<Class<?>, String> ROOT_TYPE_NAMES = new LinkedHashMap<>();

    static {
        register("QuestAsset", QuestAsset.CODEC);
        register("QuestObjective", QuestObjectiveAsset.CODEC);
        register("AchievementAsset", AchievementAsset.CODEC);
        register("AchievementCategoryAsset", AchievementCategoryAsset.CODEC);
        register("AchievementMilestoneAsset", AchievementMilestoneAsset.CODEC);
        register("QuestGeneratorAsset", QuestGeneratorAsset.CODEC);
        register("GeneratorAxis", GeneratorAxisAsset.CODEC);
        register("ContentText", ContentTextAsset.CODEC);
        register("ObjectiveLeaf", ObjectiveLeafAsset.CODEC);
        register("RewardEntry", RewardEntryAsset.CODEC);
        register("Requires", GateSpec.CODEC);
        register("GateClause", GateClause.CODEC);
        register("FactorCondition", FactorCondition.CODEC);
        register("Duration", DurationGroup.CODEC);
    }

    private SchemaDocWriter() {
    }

    private static void register(String docName, BuilderCodec<?> codec) {
        ROOT_CODECS.put(docName, codec);
        ROOT_TYPE_NAMES.put(codec.getInnerClass(), docName);
    }

    /** The root type names in registration (= document) order. */
    public static List<String> rootTypeNames() {
        return new ArrayList<>(ROOT_CODECS.keySet());
    }

    /**
     * Walk every registered root type's codec into a pure in-memory model
     * ({@code Map}/{@code List}/{@code String}/{@code Boolean} only - this module carries no JSON
     * runtime dependency of its own), consumed by both {@link #render()} and the drift test's
     * structural check.
     */
    public static Map<String, Object> renderModel() {
        Map<String, Object> types = new LinkedHashMap<>();
        for (Map.Entry<String, BuilderCodec<?>> entry : ROOT_CODECS.entrySet()) {
            BuilderCodec<?> codec = entry.getValue();
            Map<String, Object> typeDoc = new LinkedHashMap<>();
            String doc = codec.getDocumentation();
            if (doc != null) {
                typeDoc.put("documentation", doc);
            }
            typeDoc.put("fields", renderFields(codec, new ArrayDeque<>()));
            types.put(entry.getKey(), typeDoc);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedFrom",
                "SchemaDocWriter (walks each authorable type's static CODEC via BuilderCodec#getEntries())");
        root.put("rootTypes", rootTypeNames());
        root.put("types", types);
        return root;
    }

    /** {@link #renderModel()} rendered as stable, deterministic GitHub Markdown (LF-terminated). */
    public static String render() {
        return renderMarkdown(renderModel());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Object> renderFields(BuilderCodec<?> codec, Deque<Class<?>> stack) {
        List<Object> out = new ArrayList<>();

        // An asset codec inherits the engine's own base fields, and a builder chain that called
        // .inherits(parent) would otherwise silently drop the parent's leaves from getEntries().
        BuilderCodec<?> parent = codec.getParent();
        if (parent != null) {
            out.addAll(renderFields(parent, stack));
        }

        Map<String, List<BuilderField>> entries = (Map) codec.getEntries();
        for (Map.Entry<String, List<BuilderField>> e : entries.entrySet()) {
            List<BuilderField> versions = e.getValue();
            if (versions.isEmpty()) {
                continue;
            }
            // getEntries() sorts each key's versions ascending by minVersion; the last is the
            // current one. No field in this module uses version ranges today.
            BuilderField field = versions.get(versions.size() - 1);
            KeyedCodec keyed = field.getCodec();

            Map<String, Object> fieldDoc = new LinkedHashMap<>();
            fieldDoc.put("key", keyed.getKey());
            fieldDoc.put("required", keyed.isRequired());
            String doc = field.getDocumentation();
            if (doc != null) {
                fieldDoc.put("documentation", doc);
            }
            fieldDoc.putAll(classify((Codec) keyed.getChildCodec(), stack));
            out.add(fieldDoc);
        }
        return out;
    }

    /** Classify one field's child codec into {@code {type, nestedType?, ref?, of?, values?, fields?, cyclic?}}. */
    private static Map<String, Object> classify(Codec<?> codec, Deque<Class<?>> stack) {
        Map<String, Object> out = new LinkedHashMap<>();

        if (codec instanceof BuilderCodec<?> bc) {
            Class<?> inner = bc.getInnerClass();
            out.put("type", "object");
            out.put("nestedType", inner.getSimpleName());

            String rootName = ROOT_TYPE_NAMES.get(inner);
            if (rootName != null) {
                // A shared group reused inline (the Text group, a Requires block, a reward entry):
                // link to its own section instead of duplicating the whole subtree, so one
                // declaration reads as one declaration.
                out.put("ref", rootName);
            } else if (stack.contains(inner)) {
                // A private group cycling back to an ancestor with no root-type boundary between.
                // Nothing here does that today, but a future authoring change fails soft with a
                // marker rather than a StackOverflowError.
                out.put("cyclic", true);
            } else {
                stack.push(inner);
                out.put("fields", renderFields(bc, stack));
                stack.pop();
            }
            return out;
        }

        if (codec instanceof JsonTreeCodec) {
            // A subtree kept exactly as authored: there is no schema at this level, which is the
            // whole point of the field, so naming its shape would be a lie.
            out.put("type", "json");
            return out;
        }

        if (codec instanceof WrappedCodec<?> wrapped) {
            String simple = codec.getClass().getSimpleName();
            String bucket = simple.contains("Array") ? "array"
                    : simple.contains("Map") ? "map"
                    : simple.contains("Set") ? "set"
                    : "wrapped";
            out.put("type", bucket);
            out.put("of", classify(wrapped.getChildCodec(), stack));
            return out;
        }

        if (codec instanceof EnumCodec<?> ec) {
            out.put("type", "enum");
            out.put("values", Arrays.asList(ec.getEnumKeys()));
            return out;
        }

        // A primitive leaf codec, or any other terminal Codec no field here currently uses.
        out.put("type", friendlyLeafName(codec.getClass()));
        return out;
    }

    private static String friendlyLeafName(Class<?> codecClass) {
        String simple = codecClass.getSimpleName();
        if (simple.endsWith("Codec")) {
            simple = simple.substring(0, simple.length() - "Codec".length());
        }
        if (simple.isEmpty()) {
            return codecClass.getName();
        }
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }

    /**
     * One pending private nested-group subsection discovered while rendering a table, queued so it
     * renders (breadth-first, in discovery order) after the table that referenced it.
     *
     * @param anchor the {@code <a id="...">} target, ASCII and hyphens only
     * @param heading the dotted path shown as the section heading text
     * @param fields that group's own field list (the shape {@link #renderFields} produces)
     * @param level the Markdown heading level ({@code 1}-{@code 6}) this subsection renders at
     */
    private record PendingSection(String anchor, String heading, List<Object> fields, int level) {
    }

    @SuppressWarnings("unchecked")
    private static String renderMarkdown(Map<String, Object> model) {
        List<String> rootTypes = (List<String>) model.get("rootTypes");
        Map<String, Object> types = (Map<String, Object>) model.get("types");

        StringBuilder sb = new StringBuilder(65_536);
        sb.append("# Quest and Achievement Schema Reference\n\n");
        sb.append("Generated by `gradlew :zc-progression:generateSchemaDocs` from the live asset ")
                .append("codecs (`SchemaDocWriter`, walking each type's static `CODEC` via ")
                .append("`BuilderCodec#getEntries()`). Do not hand-edit; `SchemaDocDriftTest` fails ")
                .append("the build if this file drifts from the codecs.\n\n");
        sb.append("One quest is one file under `Server/ZiggfreedCommon/Quests/`, one ")
                .append("achievement one file under `Server/ZiggfreedCommon/Achievements/`, ")
                .append("and in both the FILE NAME is the id. Subfolders are free to ")
                .append("organise the tree and change nothing, except one whose name starts ")
                .append("with `_`, which contributes its name to the id: ")
                .append("`_Wilds/First_Camp.json` is `wilds_first_camp`, and marked folders ")
                .append("stack. A file may carry `\"Parent\": \"<id>\"` to start from another ")
                .append("one: every leaf below inherits on omission except `Abstract`, which ")
                .append("never carries down, so a child retunes one number and keeps the ")
                .append("rest. Any key beginning `$` is authoring metadata the codec ignores, ")
                .append("so write a `$Comment` saying what the content does and what each ")
                .append("number means in game.\n\n");
        sb.append("Every field is optional and defaults to `null` unless its Default column reads ")
                .append("*(required)*. A reward's `Kind` names a registered reward kind, which is ")
                .append("its own asset type (`Server/ZiggfreedCommon/RewardKinds/<Id>.json`) rather ")
                .append("than a field documented here.\n\n");

        sb.append("## Types\n\n");
        for (String name : rootTypes) {
            sb.append("- [").append(name).append("](#type-").append(slug(name)).append(")\n");
        }
        sb.append('\n');

        for (String name : rootTypes) {
            renderType(sb, name, (Map<String, Object>) types.get(name));
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void renderType(StringBuilder sb, String rootName, Map<String, Object> typeDoc) {
        sb.append("<a id=\"type-").append(slug(rootName)).append("\"></a>\n");
        sb.append("## ").append(rootName).append("\n\n");

        String doc = (String) typeDoc.get("documentation");
        if (doc != null && !doc.isBlank()) {
            sb.append(doc.strip()).append("\n\n");
        }

        Deque<PendingSection> queue = new ArrayDeque<>();
        renderFieldsTable(sb, (List<Object>) typeDoc.get("fields"), slug(rootName), rootName, 3, queue);

        while (!queue.isEmpty()) {
            PendingSection section = queue.poll();
            sb.append("<a id=\"field-").append(section.anchor()).append("\"></a>\n");
            sb.append("#".repeat(Math.min(Math.max(section.level(), 1), 6)))
                    .append(' ').append(section.heading()).append("\n\n");
            renderFieldsTable(sb, section.fields(), section.anchor(), section.heading(), section.level() + 1, queue);
        }
    }

    @SuppressWarnings("unchecked")
    private static void renderFieldsTable(StringBuilder sb, List<Object> fields, String anchorPrefix,
            String humanPrefix, int level, Deque<PendingSection> queue) {
        if (fields.isEmpty()) {
            sb.append("_No fields._\n\n");
            return;
        }
        sb.append("| Key | Type | Default | Documentation |\n");
        sb.append("|---|---|---|---|\n");
        for (Object o : fields) {
            Map<String, Object> field = (Map<String, Object>) o;
            String key = (String) field.get("key");
            boolean required = Boolean.TRUE.equals(field.get("required"));
            String anchor = anchorPrefix + "-" + slug(key);
            String human = humanPrefix + "." + key;
            String typeLabel = describeType(field, anchor, human, level, queue);
            String def = required ? "*(required)*" : "`null`";
            Object docValue = field.get("documentation");
            String docCell = docValue != null ? escapeCell((String) docValue) : "";
            sb.append("| `").append(key).append("` | ").append(typeLabel).append(" | ").append(def)
                    .append(" | ").append(docCell).append(" |\n");
        }
        sb.append('\n');
    }

    /** Describes one {@code classify()}-shaped type map as a Markdown table-cell label. */
    @SuppressWarnings("unchecked")
    private static String describeType(Map<String, Object> typeInfo, String anchor, String human, int level,
            Deque<PendingSection> queue) {
        String type = (String) typeInfo.get("type");
        switch (type) {
            case "object" -> {
                String ref = (String) typeInfo.get("ref");
                String nestedType = (String) typeInfo.get("nestedType");
                if (ref != null) {
                    return "[" + ref + "](#type-" + slug(ref) + ")";
                }
                if (Boolean.TRUE.equals(typeInfo.get("cyclic"))) {
                    return "*(cyclic reference to " + nestedType + ")*";
                }
                queue.add(new PendingSection(anchor, human, (List<Object>) typeInfo.get("fields"), level));
                return "[" + nestedType + "](#field-" + anchor + ")";
            }
            case "array", "map", "set" -> {
                Map<String, Object> of = (Map<String, Object>) typeInfo.get("of");
                String childLabel = describeType(of, anchor + "-item", human + "[]", level, queue);
                String prefix = switch (type) {
                    case "array" -> "array of ";
                    case "map" -> "map of ";
                    default -> "set of ";
                };
                return prefix + childLabel;
            }
            case "enum" -> {
                List<String> values = (List<String>) typeInfo.get("values");
                StringBuilder joined = new StringBuilder("enum (");
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) {
                        joined.append(", ");
                    }
                    joined.append('`').append(values.get(i)).append('`');
                }
                return joined.append(')').toString();
            }
            default -> {
                return "`" + type + "`";
            }
        }
    }

    /** Lowercase ASCII slug for a Markdown `<a id>` anchor: letters and digits kept, everything else collapsed to `-`. */
    private static String slug(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            } else if (!out.isEmpty() && out.charAt(out.length() - 1) != '-') {
                out.append('-');
            }
        }
        while (!out.isEmpty() && out.charAt(out.length() - 1) == '-') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /** Makes a documentation string safe inside one GitHub Markdown table cell. */
    private static String escapeCell(String s) {
        return s.replace("|", "\\|").replace("\r\n", "<br>").replace("\n", "<br>");
    }

    /** Render and write {@link #render()} to {@code outFile}, creating parent directories as needed. */
    public static Path writeTo(Path outFile) {
        try {
            Path parent = outFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outFile, render(), StandardCharsets.UTF_8);
            return outFile;
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to write " + outFile, ex);
        }
    }

    /**
     * Gradle entry point: {@code args[0]} is the output file path (this module's {@code SCHEMA.md}).
     */
    public static void main(String[] args) {
        if (args.length < 1 || args[0].isBlank()) {
            throw new IllegalArgumentException("Usage: SchemaDocWriter <outFile> (e.g. SCHEMA.md)");
        }
        Path outFile = writeTo(Path.of(args[0]));

        int fieldCount = 0;
        Map<String, Object> model = renderModel();
        @SuppressWarnings("unchecked")
        Map<String, Object> types = (Map<String, Object>) model.get("types");
        for (Object typeDoc : types.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> t = (Map<String, Object>) typeDoc;
            @SuppressWarnings("unchecked")
            List<Object> fields = (List<Object>) t.get("fields");
            fieldCount += countFieldsDeep(fields);
        }
        System.out.println("[generateSchemaDocs] Wrote " + types.size() + " types (" + fieldCount
                + " fields, including nested groups) to " + outFile.toAbsolutePath());
    }

    @SuppressWarnings("unchecked")
    private static int countFieldsDeep(List<Object> fields) {
        int count = 0;
        for (Object f : fields) {
            Map<String, Object> fieldDoc = (Map<String, Object>) f;
            count++;
            List<Object> nested = (List<Object>) fieldDoc.get("fields");
            if (nested != null) {
                count += countFieldsDeep(nested);
            }
        }
        return count;
    }
}
