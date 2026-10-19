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

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
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
import com.ziggfreed.common.quest.asset.QuestCategoryAsset;
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
 * <p><b>Another module's reference</b> is a {@link Document} of its own (a title, its lead paragraphs and
 * the types it documents, in order), rendered by the same walk: a module above this one (zc-track's
 * {@code SCHEMA.md}) builds one and writes it from its own Gradle task, so every reference reads alike.
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
     * The authorable and shared-vocabulary types this module's reference documents, keyed by the name used
     * as both the section heading and the cross-reference target. Registration order IS the order the
     * document reads in: the authorable file types first, then the groups they are built out of.
     */
    private static final Document PROGRESSION = new Document("Quest and Achievement Schema Reference",
            "Generated by `gradlew :zc-progression:generateSchemaDocs` from the live asset "
                    + "codecs (`SchemaDocWriter`, walking each type's static `CODEC` via "
                    + "`BuilderCodec#getEntries()`). Do not hand-edit; `SchemaDocDriftTest` fails "
                    + "the build if this file drifts from the codecs.\n\n"
                    + "One quest is one file under `Server/ZiggfreedCommon/Quests/`, one "
                    + "achievement one file under `Server/ZiggfreedCommon/Achievements/`, "
                    + "and in both the FILE NAME is the id. Subfolders are free to "
                    + "organise the tree and change nothing, except one whose name starts "
                    + "with `_`, which contributes its name to the id: "
                    + "`_Wilds/First_Camp.json` is `wilds_first_camp`, and marked folders "
                    + "stack. A file may carry `\"Parent\": \"<id>\"` to start from another "
                    + "one: every leaf below inherits on omission except `Abstract`, which "
                    + "never carries down, so a child retunes one number and keeps the "
                    + "rest. Any key beginning `$` is authoring metadata the codec ignores, "
                    + "so write a `$Comment` saying what the content does and what each "
                    + "number means in game.\n\n"
                    + "Every field is optional and defaults to `null` unless its Default column reads "
                    + "*(required)*. A reward's `Kind` names a registered reward kind, which is "
                    + "its own asset type (`Server/ZiggfreedCommon/RewardKinds/<Id>.json`) rather "
                    + "than a field documented here.\n\n")
            .register("QuestAsset", QuestAsset.CODEC)
            .register("QuestObjective", QuestObjectiveAsset.CODEC)
            .register("QuestCategoryAsset", QuestCategoryAsset.CODEC)
            .register("AchievementAsset", AchievementAsset.CODEC)
            .register("AchievementCategoryAsset", AchievementCategoryAsset.CODEC)
            .register("AchievementMilestoneAsset", AchievementMilestoneAsset.CODEC)
            .register("QuestGeneratorAsset", QuestGeneratorAsset.CODEC)
            .register("GeneratorAxis", GeneratorAxisAsset.CODEC)
            .register("ContentText", ContentTextAsset.CODEC)
            .register("ObjectiveLeaf", ObjectiveLeafAsset.CODEC)
            .register("RewardEntry", RewardEntryAsset.CODEC)
            .register("Requires", GateSpec.CODEC)
            .register("GateClause", GateClause.CODEC)
            .register("FactorCondition", FactorCondition.CODEC)
            .register("Duration", DurationGroup.CODEC);

    private SchemaDocWriter() {
    }

    /** The root type names in registration (= document) order. */
    public static List<String> rootTypeNames() {
        return PROGRESSION.rootTypeNames();
    }

    /**
     * Walk every registered root type's codec into a pure in-memory model
     * ({@code Map}/{@code List}/{@code String}/{@code Boolean} only - this module carries no JSON
     * runtime dependency of its own), consumed by both {@link #render()} and the drift test's
     * structural check.
     */
    public static Map<String, Object> renderModel() {
        return PROGRESSION.renderModel();
    }

    /** {@link #renderModel()} rendered as stable, deterministic GitHub Markdown (LF-terminated). */
    public static String render() {
        return PROGRESSION.render();
    }

    /** Render and write {@link #render()} to {@code outFile}, creating parent directories as needed. */
    public static Path writeTo(Path outFile) {
        return PROGRESSION.writeTo(outFile);
    }

    /**
     * Gradle entry point: {@code args[0]} is the output file path (this module's {@code SCHEMA.md}).
     */
    public static void main(String[] args) {
        PROGRESSION.writeFromTask(args, "SchemaDocWriter");
    }

    /**
     * One schema reference: its title, its lead paragraphs (Markdown, each ending in a blank line) and the
     * types it documents, in reading order. A type registered here is a root: a field reusing it links to
     * its section instead of inlining it.
     */
    public static final class Document {

        private final String title;
        private final String intro;
        private final Map<String, BuilderCodec<?>> rootCodecs = new LinkedHashMap<>();

        /** Reverse lookup from a nested field's {@link BuilderCodec#getInnerClass()} to its root doc name. */
        private final Map<Class<?>, String> rootTypeNames = new LinkedHashMap<>();

        /** A paragraph of this document's own under a root type's section, by doc name ({@link #note}). */
        private final Map<String, String> notes = new LinkedHashMap<>();

        /** Whether the Default column reads each field's effective default ({@link #showingDefaults}). */
        private boolean showDefaults;

        /**
         * @param title the document's {@code #} heading
         * @param intro the paragraphs under it, Markdown, each ending in a blank line
         */
        public Document(@Nonnull String title, @Nonnull String intro) {
            this.title = title;
            this.intro = intro;
        }

        /** Document {@code codec} as the root type {@code docName}, after every type registered before it. */
        @Nonnull
        public Document register(@Nonnull String docName, @Nonnull BuilderCodec<?> codec) {
            rootCodecs.put(docName, codec);
            rootTypeNames.put(codec.getInnerClass(), docName);
            return this;
        }

        /**
         * Render each field's effective default in the Default column: the scalar an unwritten field reads as,
         * taken from its type's own default instance (the value the engine's schema export, and so the Asset
         * Editor, offers), where the plain reference reads {@code null} for every field that is not required. A
         * field whose default is nothing, or is not a scalar, still reads {@code null}. Opt-in, so a reference
         * whose intro says every field defaults to {@code null} keeps saying it truthfully.
         */
        @Nonnull
        public Document showingDefaults() {
            this.showDefaults = true;
            return this;
        }

        /**
         * A paragraph of this document's own under {@code docName}'s section, after its codec's documentation:
         * what this reference's types make of a shared group (a leaf one of them does not read, say), which the
         * shared group's own documentation cannot know. Markdown, one paragraph.
         */
        @Nonnull
        public Document note(@Nonnull String docName, @Nonnull String paragraph) {
            notes.put(docName, paragraph);
            return this;
        }

        /** The root type names in registration (= document) order. */
        @Nonnull
        public List<String> rootTypeNames() {
            return new ArrayList<>(rootCodecs.keySet());
        }

        /** Every root type walked into the pure model {@link #render()} renders. */
        @Nonnull
        public Map<String, Object> renderModel() {
            Map<String, Object> types = new LinkedHashMap<>();
            for (Map.Entry<String, BuilderCodec<?>> entry : rootCodecs.entrySet()) {
                BuilderCodec<?> codec = entry.getValue();
                Map<String, Object> typeDoc = new LinkedHashMap<>();
                String doc = codec.getDocumentation();
                if (doc != null) {
                    typeDoc.put("documentation", doc);
                }
                String note = notes.get(entry.getKey());
                if (note != null) {
                    typeDoc.put("note", note);
                }
                typeDoc.put("fields", renderFields(codec, new ArrayDeque<>(), defaultsOf(codec)));
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
        @Nonnull
        public String render() {
            return renderMarkdown(title, intro, renderModel());
        }

        /** Render and write {@link #render()} to {@code outFile}, creating parent directories as needed. */
        @Nonnull
        public Path writeTo(@Nonnull Path outFile) {
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
         * A Gradle task's entry: {@code args[0]} is the output file, written and counted on stdout.
         *
         * @param writer the entry class named in the usage line
         */
        public void writeFromTask(@Nonnull String[] args, @Nonnull String writer) {
            if (args.length < 1 || args[0].isBlank()) {
                throw new IllegalArgumentException("Usage: " + writer + " <outFile> (e.g. SCHEMA.md)");
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

        /**
         * {@code codec}'s default instance encoded, which is where {@link #showingDefaults} reads each field's
         * default; null when this document shows none, or the type builds no default (an abstract base).
         */
        @Nullable
        @SuppressWarnings("unchecked")
        private BsonDocument defaultsOf(@Nonnull BuilderCodec<?> codec) {
            if (!showDefaults) {
                return null;
            }
            try {
                BuilderCodec<Object> concrete = (BuilderCodec<Object>) codec;
                Object instance = concrete.getDefaultValue();
                return instance == null ? null : concrete.encode(instance, new ExtraInfo());
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** The scalar {@code value} as the Default column prints it, or null for nothing or a non-scalar. */
        @Nullable
        private static String defaultText(@Nullable BsonValue value) {
            if (value == null || value.isNull()) {
                return null;
            }
            if (value.isBoolean()) {
                return Boolean.toString(value.asBoolean().getValue());
            }
            if (value.isInt32()) {
                return Integer.toString(value.asInt32().getValue());
            }
            if (value.isInt64()) {
                return Long.toString(value.asInt64().getValue());
            }
            if (value.isDouble()) {
                return Double.toString(value.asDouble().getValue());
            }
            return value.isString() ? value.asString().getValue() : null;
        }

        /**
         * One codec's fields, its parent's first; {@code defaults} is the encoded default instance of the
         * concrete type being documented (the parent's fields read their defaults off it too), or null.
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private List<Object> renderFields(BuilderCodec<?> codec, Deque<Class<?>> stack,
                @Nullable BsonDocument defaults) {
            List<Object> out = new ArrayList<>();

            // An asset codec inherits the engine's own base fields, and a builder chain that called
            // .inherits(parent) would otherwise silently drop the parent's leaves from getEntries().
            BuilderCodec<?> parent = codec.getParent();
            if (parent != null) {
                out.addAll(renderFields(parent, stack, defaults));
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
                String def = defaults == null ? null : defaultText(defaults.get(keyed.getKey()));
                if (def != null) {
                    fieldDoc.put("default", def);
                }
                fieldDoc.putAll(classify((Codec) keyed.getChildCodec(), stack));
                out.add(fieldDoc);
            }
            return out;
        }

        /** Classify one field's child codec into {@code {type, nestedType?, ref?, of?, values?, fields?, cyclic?}}. */
        private Map<String, Object> classify(Codec<?> codec, Deque<Class<?>> stack) {
            Map<String, Object> out = new LinkedHashMap<>();

            if (codec instanceof BuilderCodec<?> bc) {
                Class<?> inner = bc.getInnerClass();
                out.put("type", "object");
                out.put("nestedType", inner.getSimpleName());

                String rootName = rootTypeNames.get(inner);
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
                    out.put("fields", renderFields(bc, stack, defaultsOf(bc)));
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
     * @param fields that group's own field list (the shape {@link Document#renderFields} produces)
     * @param level the Markdown heading level ({@code 1}-{@code 6}) this subsection renders at
     */
    private record PendingSection(String anchor, String heading, List<Object> fields, int level) {
    }

    @SuppressWarnings("unchecked")
    private static String renderMarkdown(String title, String intro, Map<String, Object> model) {
        List<String> rootTypes = (List<String>) model.get("rootTypes");
        Map<String, Object> types = (Map<String, Object>) model.get("types");

        StringBuilder sb = new StringBuilder(65_536);
        sb.append("# ").append(title).append("\n\n");
        sb.append(intro);

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
        String note = (String) typeDoc.get("note");
        if (note != null && !note.isBlank()) {
            sb.append(note.strip()).append("\n\n");
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
            Object declared = field.get("default");
            String def = required ? "*(required)*" : "`" + (declared == null ? "null" : declared) + "`";
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
