package com.ziggfreed.common.ui.page;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.exception.CodecException;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * One {@code Sections} entry, read against the page's registered parts at decode time ({@link PageParts}):
 * each group named for a part is decoded by that part's codec, under its own key so a failure names where it
 * is; a group no part is named for is skipped and kept by name on the section for the page's audit, never
 * handed to the engine's unused-key log, so it is reported once; a {@code $} key and a {@code null} group are
 * skipped. A group that will not read fails the entry, as a malformed field fails any object.
 *
 * <p>The schema is built the way the engine builds an object's: a builder codec of {@code type} with one
 * field per part registered at export, each carrying its part's codec and editor sentence.
 */
final class PageSectionCodec<S extends PageSection> implements Codec<S> {

    private final PageParts parts;
    private final Class<S> type;
    private final Supplier<S> factory;

    PageSectionCodec(@Nonnull PageParts parts, @Nonnull Class<S> type, @Nonnull Supplier<S> factory) {
        this.parts = parts;
        this.type = type;
        this.factory = factory;
    }

    @Override
    @Nonnull
    public S decodeJson(@Nonnull RawJsonReader reader, @Nonnull ExtraInfo extraInfo) throws IOException {
        Map<String, Object> groups = new HashMap<>();
        List<String> unknown = new ArrayList<>();
        reader.expect('{');
        reader.consumeWhiteSpace();
        if (!reader.tryConsume('}')) {
            while (true) {
                String key = reader.readString();
                reader.consumeWhiteSpace();
                reader.expect(':');
                reader.consumeWhiteSpace();
                PagePart<?, ?> part = metadata(key) ? null : parts.part(key);
                if (part == null) {
                    if (!metadata(key)) {
                        unknown.add(key);
                    }
                    reader.skipValue();
                } else if (!reader.tryConsume("null")) {
                    extraInfo.pushKey(key, reader);
                    try {
                        Object group = part.codec().decodeJson(reader, extraInfo);
                        if (group != null) {
                            groups.put(part.name(), group);
                        }
                    } catch (Exception e) {
                        throw new CodecException("Failed to decode", reader, extraInfo, e);
                    } finally {
                        extraInfo.popKey();
                    }
                }
                reader.consumeWhiteSpace();
                if (reader.tryConsumeOrExpect('}', ',')) {
                    break;
                }
                reader.consumeWhiteSpace();
            }
        }
        return section(groups, unknown);
    }

    @Override
    @Nonnull
    public S decode(@Nonnull BsonValue value, @Nonnull ExtraInfo extraInfo) {
        Map<String, Object> groups = new HashMap<>();
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, BsonValue> entry : value.asDocument().entrySet()) {
            String key = entry.getKey();
            if (metadata(key)) {
                continue;
            }
            PagePart<?, ?> part = parts.part(key);
            if (part == null) {
                unknown.add(key);
                continue;
            }
            if (entry.getValue() == null || entry.getValue().isNull()) {
                continue;
            }
            extraInfo.pushKey(key);
            try {
                Object group = part.codec().decode(entry.getValue(), extraInfo);
                if (group != null) {
                    groups.put(part.name(), group);
                }
            } catch (Exception e) {
                throw new CodecException("Failed to decode", entry.getValue(), extraInfo, e);
            } finally {
                extraInfo.popKey();
            }
        }
        return section(groups, unknown);
    }

    @Override
    @Nonnull
    public BsonValue encode(@Nonnull S section, @Nonnull ExtraInfo extraInfo) {
        BsonDocument document = new BsonDocument();
        for (String name : section.partsWritten()) {
            PagePart<?, ?> part = parts.part(name);
            Object group = section.group(name);
            if (part != null && group != null) {
                document.put(name, encodeGroup(part, group, extraInfo));
            }
        }
        return document;
    }

    @Override
    @Nonnull
    public Schema toSchema(@Nonnull SchemaContext context) {
        BuilderCodec.Builder<S> builder = BuilderCodec.builder(type, factory);
        for (PagePart<?, ?> part : parts.parts()) {
            field(builder, part);
        }
        return builder.build().toSchema(context);
    }

    /** The section the decode found: the parts written, put in registration order. */
    @Nonnull
    private S section(@Nonnull Map<String, Object> groups, @Nonnull List<String> unknown) {
        List<String> written = new ArrayList<>();
        for (String name : parts.names()) {
            if (groups.containsKey(name)) {
                written.add(name);
            }
        }
        S section = factory.get();
        section.fill(written, groups, unknown);
        return section;
    }

    /** The engine's own authoring keys ({@code $Comment}, {@code $TODO}, ...), never a part. */
    private static boolean metadata(@Nonnull String key) {
        return !key.isEmpty() && key.charAt(0) == '$';
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static <A> BsonValue encodeGroup(@Nonnull PagePart<A, ?> part, @Nonnull Object group,
            @Nonnull ExtraInfo extraInfo) {
        return part.codec().encode((A) group, extraInfo);
    }

    private static <T, A> void field(@Nonnull BuilderCodec.Builder<T> builder, @Nonnull PagePart<A, ?> part) {
        builder.append(part.key(), (section, group) -> { }, section -> null)
                .documentation(part.documentation()).add();
    }
}
