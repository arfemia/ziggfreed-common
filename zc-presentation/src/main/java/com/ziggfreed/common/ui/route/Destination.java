package com.ziggfreed.common.ui.route;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonString;
import org.bson.BsonValue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.schema.config.StringSchema;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * WHAT a click, a press-F or a conversation line opens, authored once and understood everywhere.
 *
 * <p>A destination is a {@code Type}-discriminated object whose fields belong to that type, so the
 * file says what it opens in the type's own words instead of in a compound string somebody has to
 * parse:
 *
 * <pre>{@code
 * "Open": { "Type": "Dialogue", "Dialogue": "guide_intro" }
 * "Open": { "Type": "Quests" }
 * "Open": "Quests"                                  the same thing, for a type with no fields
 * "Open": { "Type": "Mmo_Skill_Tree", "Skill": "MINING" }
 * }</pre>
 *
 * <p>The vocabulary is OPEN: this library seeds the generic types and every mod registers its own
 * with {@link Destinations}, so a pack can address another mod's screens with no Java and this
 * schema never learns them.
 *
 * <p><b>An unknown {@code Type} FAILS THE READ, naming the file.</b> A destination nothing can open
 * would otherwise be a button that silently does nothing, found only by a player pressing it. A file
 * naming a mod's destination already declares that mod as a dependency, so a startup error is the
 * honest answer.
 *
 * <p><b>The bare-string form is the same value.</b> {@code "Quests"} normalizes to
 * {@code {"Type": "Quests"}} before anything else looks at it, so a type with no fields stays one
 * word to author and there is still one model underneath.
 *
 * <p><b>Inheritance is whole-leaf.</b> A {@code Parent} file that authors a destination REPLACES the
 * one it inherited rather than merging field by field: half of one type's fields under another
 * type's discriminator is not a destination anybody meant to write.
 */
public abstract class Destination {

    /** The discriminator key every authored destination object carries. */
    public static final String TYPE_KEY = "Type";

    /**
     * The one codec every site authoring a destination uses. It accepts both the object form and the
     * bare-string form, and dispatches through the process-wide vocabulary assembled by
     * {@link Destinations}, so a type registered during a mod's {@code setup()} is readable by the
     * time assets decode.
     */
    public static final Codec<Destination> CODEC = new Codec<>() {

        @Override
        @Nullable
        public Destination decode(BsonValue value, ExtraInfo extraInfo) {
            if (value != null && value.isString()) {
                return decodeBare(value.asString().getValue(), extraInfo);
            }
            return Destinations.unionForRead().decode(value, extraInfo);
        }

        @Nonnull
        @Override
        public BsonValue encode(Destination destination, ExtraInfo extraInfo) {
            return Destinations.union().encode(destination, extraInfo);
        }

        @Override
        @Nullable
        public Destination decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
            if (reader.peek() == '"') {
                return decodeBare(reader.readString(), extraInfo);
            }
            return Destinations.unionForRead().decodeJson(reader, extraInfo);
        }

        @Nonnull
        @Override
        public Schema toSchema(@Nonnull SchemaContext context) {
            // The bare-string form decodes too, and the in-game Asset Editor fails a property
            // pane over any authored value shape the exported schema omits, so the schema
            // declares it beside the union.
            return Schema.anyOf(new StringSchema(), Destinations.union().toSchema(context));
        }
    };

    /**
     * The bare-string form as the object it means. Routing it back through the union is what makes
     * {@code "Nonsense"} fail exactly as loudly as {@code {"Type": "Nonsense"}} does. The
     * discriminator is ignored on the way through, the same way the union ignores it when a file
     * writes it out, so the synthesized key is never reported as one the type does not know.
     */
    @Nullable
    private static Destination decodeBare(@Nullable String typeId, @Nonnull ExtraInfo extraInfo) {
        extraInfo.ignoreUnusedKey(TYPE_KEY);
        try {
            return Destinations.unionForRead().decode(
                    new BsonDocument(TYPE_KEY, new BsonString(typeId == null ? "" : typeId.trim())), extraInfo);
        } finally {
            extraInfo.popIgnoredUnusedKey();
        }
    }
}
