package com.ziggfreed.common.dialogue.schema;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.InheritCodec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * One shared option group under a conversation's {@code Fragments}: the lines it contributes, where
 * it lands on its own account, and which other groups it folds in after its own lines.
 *
 * <p>Two authored forms read into the same thing. The array form is the group's lines and nothing
 * else, the way a footer has always been written; the object form adds two nullable leaves:
 *
 * <pre>{@code
 * "footer":   [ { "LabelKey": "...", "Close": true } ]
 * "pointers": { "On": { "Tags": ["Temple_Landing"] },
 *               "Options": [ { "LabelKey": "...", "Goto": "mastery_intro" } ],
 *               "Include": ["footer"] }
 * }</pre>
 *
 * <p>{@code On} says which screens get this group without any of them naming it (see
 * {@link NodeSelector}); a screen is given a group when the screen names it with
 * {@code IncludeOptions} OR the group names the screen, and one reaching a screen both ways is
 * spliced once, where the screen named it. {@code Include} names other groups whose lines follow
 * this group's own, in the order written, the same append-after rule a screen's
 * {@code IncludeOptions} follows. A group that includes itself, however many steps round, is a
 * validator error and is dropped at the point it re-enters rather than followed.
 *
 * <p>Under {@code Parent} the object form merges leaf by leaf, so a child restating only {@code On}
 * keeps the parent's {@code Options}; the array form replaces the whole group, as it always did.
 */
public final class DialogueFragmentGroup {

    @Nullable DialogueOption[] options;
    @Nullable NodeSelector on;
    @Nullable String[] include;

    public DialogueFragmentGroup() {
    }

    /** The array form as a value: a group of these lines that lands nowhere on its own. */
    @Nonnull
    public static DialogueFragmentGroup ofOptions(@Nullable DialogueOption[] options) {
        DialogueFragmentGroup group = new DialogueFragmentGroup();
        group.options = options;
        return group;
    }

    /** Java-side construction of the object form (tests, a consumer building a tree in code). */
    @Nonnull
    public static DialogueFragmentGroup of(@Nullable DialogueOption[] options, @Nullable NodeSelector on,
                                           @Nullable String[] include) {
        DialogueFragmentGroup group = ofOptions(options);
        group.on = on;
        group.include = include;
        return group;
    }

    /** The lines this group contributes itself, before anything it includes. Empty when it has none. */
    @Nonnull
    public List<DialogueOption> getOptions() {
        return options == null ? Collections.emptyList() : List.of(options);
    }

    /** Where this group lands on its own account, or null when only a screen naming it gets it. */
    @Nullable
    public NodeSelector getOn() {
        return on;
    }

    /** The other groups folded in after this group's own lines, in order. Empty when there are none. */
    @Nonnull
    public List<String> getInclude() {
        return include == null ? Collections.emptyList() : List.of(include);
    }

    /** True when this group was written as a bare array: lines only, nothing else to say. */
    boolean isOptionsOnly() {
        return on == null && include == null;
    }

    /**
     * The object form's codec, over the assembled option-row codec (which is why it is built per
     * {@link DialogueTypeTable} rather than as a constant here). Every leaf inherits on its own
     * under {@code Parent}.
     */
    @Nonnull
    static BuilderCodec<DialogueFragmentGroup> groupCodec(@Nonnull Codec<DialogueOption[]> optionsArray) {
        return BuilderCodec.builder(DialogueFragmentGroup.class, DialogueFragmentGroup::new)
                .appendInherited(new KeyedCodec<>("Options", optionsArray, false),
                        (g, v) -> g.options = v, g -> g.options,
                        (child, parent) -> child.options = parent.options)
                .documentation("The lines this group contributes, in the order they are shown, before "
                        + "anything it includes.").add()
                .appendInherited(new KeyedCodec<>("On", NodeSelector.CODEC, false),
                        (g, v) -> g.on = v, g -> g.on, (child, parent) -> child.on = parent.on)
                .documentation("Which screens get this group without naming it: Nodes by exact id, Tags "
                        + "for every screen carrying one, Exclude to take named screens back out. The "
                        + "lines land after the screen's own Options and before the groups its "
                        + "IncludeOptions names. Leave it out for a group only a screen pulls in.").add()
                .appendInherited(new KeyedCodec<>("Include", Codec.STRING_ARRAY, false),
                        (g, v) -> g.include = v, g -> g.include,
                        (child, parent) -> child.include = parent.include)
                .documentation("Other groups whose lines follow this group's own, in the order written. "
                        + "Write a shared tail once and include it from every group that ends with it. "
                        + "A group may not include itself, however many steps round.").add()
                .build();
    }

    /**
     * The codec a {@code Fragments} entry is read by: an array reads as a group of those lines and
     * nothing else, an object reads the group in full. Under {@code Parent} the object form merges
     * per leaf through the group codec and the array form replaces whole, which is what each form
     * says on its face: the object restates some leaves, the array restates the lines.
     */
    @Nonnull
    static Codec<DialogueFragmentGroup> valueCodec(@Nonnull Codec<DialogueOption[]> optionsArray) {
        return new ValueCodec(optionsArray, groupCodec(optionsArray));
    }

    /** The array-or-object union behind {@link #valueCodec}. */
    private static final class ValueCodec implements Codec<DialogueFragmentGroup>, InheritCodec<DialogueFragmentGroup> {

        private final Codec<DialogueOption[]> optionsArray;
        private final BuilderCodec<DialogueFragmentGroup> group;

        ValueCodec(@Nonnull Codec<DialogueOption[]> optionsArray,
                   @Nonnull BuilderCodec<DialogueFragmentGroup> group) {
            this.optionsArray = optionsArray;
            this.group = group;
        }

        @Override
        @Nullable
        public DialogueFragmentGroup decode(BsonValue value, ExtraInfo extraInfo) {
            if (value != null && value.isArray()) {
                return ofOptions(optionsArray.decode(value, extraInfo));
            }
            return group.decode(value, extraInfo);
        }

        @Nonnull
        @Override
        public BsonValue encode(DialogueFragmentGroup value, ExtraInfo extraInfo) {
            if (value.isOptionsOnly()) {
                return optionsArray.encode(value.options == null ? new DialogueOption[0] : value.options,
                        extraInfo);
            }
            return group.encode(value, extraInfo);
        }

        @Override
        @Nullable
        public DialogueFragmentGroup decodeJson(RawJsonReader reader, ExtraInfo extraInfo) throws IOException {
            if (reader.peek() == '[') {
                return ofOptions(optionsArray.decodeJson(reader, extraInfo));
            }
            return group.decodeJson(reader, extraInfo);
        }

        // A BSON document can only be the object form, so the merge question has one answer here;
        // the JSON forms peek first, because an array in a child replaces the parent's group whole.

        @Override
        @Nullable
        public DialogueFragmentGroup decodeAndInherit(BsonDocument document, DialogueFragmentGroup parent,
                                                      ExtraInfo extraInfo) {
            return group.decodeAndInherit(document, parent, extraInfo);
        }

        @Override
        public void decodeAndInherit(BsonDocument document, DialogueFragmentGroup t,
                                     DialogueFragmentGroup parent, ExtraInfo extraInfo) {
            group.decodeAndInherit(document, t, parent, extraInfo);
        }

        @Override
        @Nullable
        public DialogueFragmentGroup decodeAndInheritJson(RawJsonReader reader, DialogueFragmentGroup parent,
                                                          ExtraInfo extraInfo) throws IOException {
            if (reader.peek() == '[') {
                return ofOptions(optionsArray.decodeJson(reader, extraInfo));
            }
            return group.decodeAndInheritJson(reader, parent, extraInfo);
        }

        @Override
        public void decodeAndInheritJson(RawJsonReader reader, DialogueFragmentGroup t,
                                         DialogueFragmentGroup parent, ExtraInfo extraInfo) throws IOException {
            if (reader.peek() == '[') {
                t.options = optionsArray.decodeJson(reader, extraInfo);
                return;
            }
            group.decodeAndInheritJson(reader, t, parent, extraInfo);
        }

        @Nonnull
        @Override
        public Schema toSchema(@Nonnull SchemaContext context) {
            // Decode accepts the bare array form beside the object, and the in-game Asset Editor
            // fails a property pane over any authored value shape the exported schema omits, so
            // the schema declares both arms, each carrying its own type.
            Schema lines = optionsArray.toSchema(context);
            lines.setTitle("Lines only");
            return Schema.anyOf(lines, group.toSchema(context));
        }
    }
}
