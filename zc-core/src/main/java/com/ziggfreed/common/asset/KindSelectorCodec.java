package com.ziggfreed.common.asset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.InheritCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.codec.validation.ValidatableCodec;
import com.ziggfreed.common.util.SafeLog;

/**
 * A row codec that decodes exactly as the object codec it wraps, and tells the in-game Asset Editor
 * that one of its text keys picks the row's form: a reward row's {@code Kind} picks which parameters
 * it shows, an objective's {@code Kind} picks which picker its {@code Target} opens.
 *
 * <p>The schema is the engine's own type selector, the shape every {@code Type}-keyed codec exports
 * ({@code ACodecMapCodec.toSchema}): an {@code anyOf} of one arm per value, each arm the wrapped
 * object's own schema with the key fixed to that value and any leaf the value changes replaced, the
 * arms filed in {@code common.json} like any object group, one more arm for any value the list does not
 * name (the object's own form, as the engine writes a selector's default arm), and a type field naming the
 * key and every listed value so the editor offers them as the type dropdown. The arms are asked of a live
 * vocabulary each time a schema is made (the editor makes its set when it first opens), so a kind a mod
 * registers at setup, or a kind file that folds before then, is offered.
 *
 * <p><b>Schema only.</b> Decode, encode, validation and {@code Parent} inheritance are the wrapped
 * codec's own, so a file reads exactly as it always did, a value no arm names included (a kind a mod
 * this server does not run registers, which the any-other arm shows). With no arm to offer (a unit test,
 * a schema doc), the schema is
 * the wrapped object's own, unchanged. An arm source that throws is reported once and offers nothing.
 *
 * <p>A schema doc that walks codecs reads a row through {@link #inner()}, as its own object.
 */
public final class KindSelectorCodec<T> implements Codec<T>, InheritCodec<T>, ValidatableCodec<T> {

    /** What one value changes in the wrapped object's schema, applied to a fresh copy of it. */
    @FunctionalInterface
    public interface Shape {
        void shape(@Nonnull ObjectSchema arm, @Nonnull SchemaContext context);
    }

    /**
     * One value the key may take: the value as authored, a sentence the editor shows for it, and what it
     * changes in the row's form (null for nothing beyond the key).
     */
    public record Arm(@Nonnull String value, @Nullable String documentation, @Nullable Shape shape) {
    }

    /**
     * The definition-name suffix of the any-other-value arm. A listed value's arm is named {@code @<value>} with
     * letters, digits and underscores alone, so this one can never be a value's.
     */
    private static final String OTHER_ARM = "@@other";

    private final BuilderCodec<T> inner;
    private final String key;
    private final Supplier<? extends List<Arm>> arms;
    private volatile boolean warned;

    /**
     * @param inner the row's own object codec, which decodes, encodes and inherits
     * @param key   the text key that picks the form ({@code Kind})
     * @param arms  the values on offer right now, asked each time a schema is made
     */
    public KindSelectorCodec(@Nonnull BuilderCodec<T> inner, @Nonnull String key,
            @Nonnull Supplier<? extends List<Arm>> arms) {
        this.inner = inner;
        this.key = key;
        this.arms = arms;
    }

    /** The row's own object codec. */
    @Nonnull
    public BuilderCodec<T> inner() {
        return inner;
    }

    // ==================== decode, encode, inherit: the wrapped codec's own ====================

    @Override
    public T decode(@Nonnull BsonValue bsonValue, ExtraInfo extraInfo) {
        return inner.decode(bsonValue, extraInfo);
    }

    @Nonnull
    @Override
    public BsonValue encode(@Nonnull T value, ExtraInfo extraInfo) {
        return inner.encode(value, extraInfo);
    }

    @Nullable
    @Override
    public T decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
        return inner.decodeJson(reader, extraInfo);
    }

    @Nullable
    @Override
    public T decodeAndInherit(BsonDocument document, T parent, ExtraInfo extraInfo) {
        return inner.decodeAndInherit(document, parent, extraInfo);
    }

    @Override
    public void decodeAndInherit(BsonDocument document, T t, T parent, ExtraInfo extraInfo) {
        inner.decodeAndInherit(document, t, parent, extraInfo);
    }

    @Nullable
    @Override
    public T decodeAndInheritJson(RawJsonReader reader, T parent, ExtraInfo extraInfo) throws IOException {
        return inner.decodeAndInheritJson(reader, parent, extraInfo);
    }

    @Override
    public void decodeAndInheritJson(RawJsonReader reader, T t, T parent, ExtraInfo extraInfo) throws IOException {
        inner.decodeAndInheritJson(reader, t, parent, extraInfo);
    }

    @Override
    public void validate(T t, ExtraInfo extraInfo) {
        inner.validate(t, extraInfo);
    }

    @Override
    public void validateDefaults(ExtraInfo extraInfo, Set<Codec<?>> tested) {
        inner.validateDefaults(extraInfo, tested);
    }

    // ==================== the schema: a type selector over the live values ====================

    @Nonnull
    @Override
    public Schema toSchema(@Nonnull SchemaContext context) {
        Schema base = context.refDefinition(inner);
        List<Arm> offered = offered();
        String baseRef = base.getRef();
        if (offered.isEmpty() || baseRef == null) {
            return base;
        }
        String baseName = baseRef.substring(baseRef.lastIndexOf('/') + 1);
        Map<String, Schema> definitions = context.getDefinitions();
        List<Schema> refs = new ArrayList<>(offered.size());
        String[] values = new String[offered.size()];
        for (int i = 0; i < offered.size(); i++) {
            Arm arm = offered.get(i);
            String name = baseName + "@" + safeName(arm.value());
            if (!definitions.containsKey(name)) {
                definitions.put(name, armSchema(arm, context));
            }
            refs.add(Schema.ref(EditorSchemaCheck.COMMON + "#/definitions/" + name));
            values[i] = arm.value();
        }
        // A row decodes a value no arm names too (a kind a mod this server does not run registers), so the union
        // advertises that arm as well: the wrapped object's own form, for any value but the listed ones.
        String otherName = baseName + OTHER_ARM;
        if (!definitions.containsKey(otherName)) {
            definitions.put(otherName, otherArmSchema(values, context));
        }
        refs.add(Schema.ref(EditorSchemaCheck.COMMON + "#/definitions/" + otherName));
        Schema selector = Schema.anyOf(refs.toArray(Schema[]::new));
        selector.getHytale().setMergesProperties(true);
        selector.setTitle("Type Selector");
        selector.setHytaleSchemaTypeField(new Schema.SchemaTypeField(key, null, values));
        return selector;
    }

    /** The wrapped object's schema with the key fixed to the arm's value and the arm's own changes made. */
    @Nonnull
    private ObjectSchema armSchema(@Nonnull Arm arm, @Nonnull SchemaContext context) {
        ObjectSchema schema = inner.toSchema(context);
        schema.setTitle(arm.value());
        if (arm.documentation() != null && !arm.documentation().isBlank()) {
            schema.setMarkdownDescription(arm.documentation());
        }
        StringSchema fixed = new StringSchema();
        fixed.setConst(arm.value());
        fixed.setMarkdownDescription("Picks this row's form: it must read \"" + arm.value()
                + "\" for the fields below. Choose another to change them.");
        // The key first, as the engine's own type selector writes it.
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put(key, fixed);
        Map<String, Schema> rest = new LinkedHashMap<>(schema.getProperties());
        rest.remove(key);
        properties.putAll(rest);
        schema.setProperties(properties);
        if (arm.shape() != null) {
            arm.shape().shape(schema, context);
        }
        return schema;
    }

    /**
     * The arm for any value the listed arms do not name: the wrapped object's own schema, its key any text but
     * the listed values, the way the engine writes the default arm of a type selector that has one
     * ({@code ACodecMapCodec.mutateChildSchema}).
     */
    @Nonnull
    private ObjectSchema otherArmSchema(@Nonnull String[] listed, @Nonnull SchemaContext context) {
        ObjectSchema schema = inner.toSchema(context);
        schema.setTitle("Any other " + key);
        StringSchema written = new StringSchema();
        written.setTypes(new String[] {"null", "string"});
        StringSchema others = new StringSchema();
        others.setEnum(listed.clone());
        Schema anyOther = new Schema();
        anyOther.setAllOf(written, Schema.not(others));
        anyOther.setMarkdownDescription("Any " + key + " not in the list, such as one a mod this server does not "
                + "run registers: the row keeps its fields as written.");
        Map<String, Schema> properties = new LinkedHashMap<>();
        properties.put(key, anyOther);
        Map<String, Schema> rest = new LinkedHashMap<>(schema.getProperties());
        rest.remove(key);
        properties.putAll(rest);
        schema.setProperties(properties);
        return schema;
    }

    /** The values on offer, one arm per value (the first of a repeated value kept), in value order. */
    @Nonnull
    private List<Arm> offered() {
        List<? extends Arm> asked;
        try {
            asked = arms.get();
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                SafeLog.warn("[editor] the " + key + " choices for " + inner.getInnerClass().getSimpleName()
                        + " could not be listed, so the Asset Editor shows the plain form", t);
            }
            return List.of();
        }
        if (asked == null || asked.isEmpty()) {
            return List.of();
        }
        Map<String, Arm> byValue = new LinkedHashMap<>();
        for (Arm arm : asked) {
            if (arm != null && !arm.value().isBlank()) {
                byValue.putIfAbsent(arm.value().trim().toLowerCase(Locale.ROOT), arm);
            }
        }
        List<Arm> out = new ArrayList<>(byValue.values());
        out.sort(Comparator.comparing(Arm::value, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** A value as a definition name: letters, digits and underscores, anything else an underscore. */
    @Nonnull
    static String safeName(@Nonnull String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        return out.toString();
    }
}
