package com.ziggfreed.common.text;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * What a player READS about a piece of authored content, as localization keys the player's own
 * client resolves in the player's own language.
 *
 * <pre>{@code
 * "Text": { "TitleKey": "yourmod.thing.title", "FlavorKey": "yourmod.thing.flavor",
 *           "TextArgs": { "Flavor": [ "@amount" ] } }
 * }</pre>
 *
 * <p>The SAME group under every kind of content in this library, spelled the same way, so a surface
 * that can render one can render the next and a translator sees one convention rather than several.
 *
 * <p>{@code DisplayName} exists only as a fallback while a key is still being written: it reaches
 * every player in the one language it was typed in, so anything shipping to players carries the key
 * instead.
 *
 * <h2>Filling a key's numbered slots</h2>
 *
 * <p>A whole ladder of content usually wants ONE written line - "Mine {0} ore" - with each rung
 * supplying its own number. {@code TextArgs} is where those numbers come from, so the line is
 * translated once instead of once per rung and the number stays where it already lives, in the
 * content itself. Write {@code @amount} and the amount this content asks for fills that slot;
 * anything else is used exactly as typed.
 *
 * <h2>A paragraph per lifecycle state</h2>
 *
 * <p>{@code Lore} is the longer narrative some surfaces show under the title, one paragraph per
 * state the content is in for the player ({@code Incomplete}, {@code Active}, {@code Complete}).
 * It is on the {@code DisplayName} contract: a raw paragraph typed in one language, kept only as
 * the fallback while the localized key is being written. The convention key
 * {@code quest.<id>.md.<state>} wins whenever it ships, so author that key for anything shipped
 * and leave this group to a server owner's own quick edit. Today the quest fold carries it (the
 * shared NPC quest page reads it under the title of the quest it is showing); an achievement's
 * {@code Text} decodes the group too, since the group is shared, but no achievement surface reads
 * it yet.
 *
 * <p>Every leaf is {@code appendInherited}, so a file with a {@code Parent} can retitle without
 * losing the description it did not mention.
 */
public final class ContentTextAsset {

    /**
     * The one argument sentinel this library names, so every consumer spells it the same: the
     * amount the content asks for.
     *
     * <p>Its VALUE belongs to the consumer rather than to this library. A count is printed
     * differently in different places - grouped, abbreviated, beside a unit - and which of those a
     * player should see is a rendering decision nothing here can make.
     */
    public static final String ARG_AMOUNT = "@amount";

    @Nullable protected String titleKey;
    @Nullable protected String flavorKey;
    @Nullable protected String displayName;
    @Nullable protected TextArgs textArgs;
    @Nullable protected Lore lore;

    public static final BuilderCodec<ContentTextAsset> CODEC =
            BuilderCodec.builder(ContentTextAsset.class, ContentTextAsset::new)
                    .appendInherited(new KeyedCodec<>("TitleKey", Codec.STRING, false),
                            (o, v) -> o.titleKey = v, o -> o.titleKey, (o, p) -> o.titleKey = p.titleKey)
                    .documentation("Localization key for the name.").add()
                    .appendInherited(new KeyedCodec<>("FlavorKey", Codec.STRING, false),
                            (o, v) -> o.flavorKey = v, o -> o.flavorKey, (o, p) -> o.flavorKey = p.flavorKey)
                    .documentation("Localization key for the longer description.").add()
                    .appendInherited(new KeyedCodec<>("DisplayName", Codec.STRING, false),
                            (o, v) -> o.displayName = v, o -> o.displayName,
                            (o, p) -> o.displayName = p.displayName)
                    .documentation("A plain fallback name for content whose key is not written yet. It reaches "
                            + "every player in the one language it is typed in, so author TitleKey for anything "
                            + "you ship.").add()
                    .appendInherited(new KeyedCodec<>("TextArgs", TextArgs.CODEC, false),
                            (o, v) -> o.textArgs = v, o -> o.textArgs, (o, p) -> o.textArgs = p.textArgs)
                    .documentation("What fills the numbered slots of the keys above, so one written line can "
                            + "serve a whole ladder of content instead of one line per rung.").add()
                    .appendInherited(new KeyedCodec<>("Lore", Lore.CODEC, false),
                            (o, v) -> o.lore = v, o -> o.lore, (o, p) -> o.lore = p.lore)
                    .documentation("A plain paragraph per lifecycle state, shown under the title where a surface has "
                            + "room for one. Like DisplayName it reaches every player in the one language it is "
                            + "typed in, so ship the localized key instead: quest.<id>.md.incomplete, .active and "
                            + ".complete win over this group whenever they resolve.").add()
                    .build();

    public ContentTextAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static ContentTextAsset of(@Nullable String titleKey, @Nullable String flavorKey,
            @Nullable String displayName) {
        ContentTextAsset t = new ContentTextAsset();
        t.titleKey = titleKey;
        t.flavorKey = flavorKey;
        t.displayName = displayName;
        return t;
    }

    @Nullable
    public String getTitleKey() {
        return titleKey;
    }

    @Nullable
    public String getFlavorKey() {
        return flavorKey;
    }

    @Nullable
    public String getDisplayName() {
        return displayName;
    }

    @Nullable
    public TextArgs getTextArgs() {
        return textArgs;
    }

    /** The authored per-state paragraphs, or null when the file wrote none. */
    @Nullable
    public Lore getLore() {
        return lore;
    }

    /**
     * The authored paragraphs keyed by state ({@link Lore#STATE_INCOMPLETE} and its two siblings),
     * blanks dropped; empty when the file wrote none. The shape a fold hands its runtime text.
     */
    @Nonnull
    public Map<String, String> loreMap() {
        Lore authored = lore;
        return authored == null ? Map.of() : authored.toMap();
    }

    /** What fills the title key's numbered slots, in order; empty when none was authored. */
    @Nonnull
    public List<String> titleArgs() {
        TextArgs args = textArgs;
        return args == null ? List.of() : args.titleList();
    }

    /** What fills the flavor key's numbered slots, in order; empty when none was authored. */
    @Nonnull
    public List<String> flavorArgs() {
        TextArgs args = textArgs;
        return args == null ? List.of() : args.flavorList();
    }

    // ==================== TextArgs ====================

    /**
     * What fills the numbered slots of each key above: one list per text slot the group has, so an
     * author never has to work out which of several lists a given key reads.
     */
    public static final class TextArgs {

        @Nullable protected String[] title;
        @Nullable protected String[] flavor;

        public static final BuilderCodec<TextArgs> CODEC =
                BuilderCodec.builder(TextArgs.class, TextArgs::new)
                        .appendInherited(new KeyedCodec<>("Title", Codec.STRING_ARRAY, false),
                                (o, v) -> o.title = v, o -> o.title, (o, p) -> o.title = p.title)
                        .documentation("Fills {0}, {1}, ... of TitleKey, in order. Write @amount for the "
                                + "amount this content asks for, or any other text to use it exactly as "
                                + "typed.").add()
                        .appendInherited(new KeyedCodec<>("Flavor", Codec.STRING_ARRAY, false),
                                (o, v) -> o.flavor = v, o -> o.flavor, (o, p) -> o.flavor = p.flavor)
                        .documentation("The same, for FlavorKey. This is how one written line serves a whole "
                                + "ladder: the key reads 'Mine {0} ore' and each rung supplies its own "
                                + "number.").add()
                        .build();

        public TextArgs() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static TextArgs of(@Nullable String[] title, @Nullable String[] flavor) {
            TextArgs a = new TextArgs();
            a.title = title == null ? null : title.clone();
            a.flavor = flavor == null ? null : flavor.clone();
            return a;
        }

        @Nonnull
        public List<String> titleList() {
            return trimmed(title);
        }

        @Nonnull
        public List<String> flavorList() {
            return trimmed(flavor);
        }

        @Nonnull
        private static List<String> trimmed(@Nullable String[] values) {
            if (values == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>(values.length);
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    out.add(value.trim());
                }
            }
            return out;
        }
    }

    // ==================== Lore ====================

    /**
     * One plain paragraph per lifecycle state, on the {@code DisplayName} contract: a fallback typed
     * in one language, outranked by the {@code quest.<id>.md.<state>} convention key whenever that
     * key ships. The three state words are the ones every surface asks the runtime text by.
     */
    public static final class Lore {

        /** The state of content the player has not taken or earned yet. */
        public static final String STATE_INCOMPLETE = "incomplete";

        /** The state of content the player is in the middle of. */
        public static final String STATE_ACTIVE = "active";

        /** The state of content the player has finished. */
        public static final String STATE_COMPLETE = "complete";

        @Nullable protected String incomplete;
        @Nullable protected String active;
        @Nullable protected String complete;

        public static final BuilderCodec<Lore> CODEC = BuilderCodec.builder(Lore.class, Lore::new)
                .appendInherited(new KeyedCodec<>("Incomplete", Codec.STRING, false),
                        (o, v) -> o.incomplete = v, o -> o.incomplete, (o, p) -> o.incomplete = p.incomplete)
                .documentation("The paragraph shown before the player has taken or earned it. A fallback for "
                        + "the quest.<id>.md.incomplete key.").add()
                .appendInherited(new KeyedCodec<>("Active", Codec.STRING, false),
                        (o, v) -> o.active = v, o -> o.active, (o, p) -> o.active = p.active)
                .documentation("The paragraph shown while the player is in the middle of it. A fallback for "
                        + "the quest.<id>.md.active key.").add()
                .appendInherited(new KeyedCodec<>("Complete", Codec.STRING, false),
                        (o, v) -> o.complete = v, o -> o.complete, (o, p) -> o.complete = p.complete)
                .documentation("The paragraph shown once the player has finished it. A fallback for the "
                        + "quest.<id>.md.complete key.").add()
                .build();

        public Lore() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Lore of(@Nullable String incomplete, @Nullable String active,
                @Nullable String complete) {
            Lore l = new Lore();
            l.incomplete = incomplete;
            l.active = active;
            l.complete = complete;
            return l;
        }

        @Nullable
        public String getIncomplete() {
            return incomplete;
        }

        @Nullable
        public String getActive() {
            return active;
        }

        @Nullable
        public String getComplete() {
            return complete;
        }

        /** The three paragraphs keyed by state word, in state order, blanks dropped. */
        @Nonnull
        public Map<String, String> toMap() {
            Map<String, String> out = new LinkedHashMap<>();
            put(out, STATE_INCOMPLETE, incomplete);
            put(out, STATE_ACTIVE, active);
            put(out, STATE_COMPLETE, complete);
            return out;
        }

        private static void put(@Nonnull Map<String, String> into, @Nonnull String state,
                @Nullable String text) {
            if (text != null && !text.isBlank()) {
                into.put(state, text);
            }
        }
    }

    // ==================== expansion ====================

    /**
     * Where a sentinel's value comes from.
     *
     * <p>This is the extension point, and it is deliberately a function rather than a registry: a
     * consumer answers the sentinels it knows and returns null for the rest, so the vocabulary grows
     * without this library having to enumerate it. {@link #ARG_AMOUNT} is the one every consumer
     * should answer; a consumer with more to offer (the name of whatever the content is about, a
     * place, a rank) simply answers those too, and content authored for it reads the same way.
     */
    @FunctionalInterface
    public interface ArgLookup {

        /** The value for {@code sentinel}, or null to leave the authored text as a literal. */
        @Nullable
        Object resolve(@Nonnull String sentinel);
    }

    /**
     * Expand authored args into the values a localization template binds to {@code {0}/{1}/...}.
     *
     * <p>An entry beginning {@code @} is a sentinel and is asked of {@code lookup}; everything else,
     * and any sentinel the lookup does not answer, is passed through exactly as authored. That last
     * part is deliberate: an unanswered sentinel showing up in the line is how an author finds out
     * they wrote one nothing provides, where a blank would read as a bug in the translation.
     */
    @Nonnull
    public static Object[] expand(@Nullable List<String> authored, @Nullable ArgLookup lookup) {
        if (authored == null || authored.isEmpty()) {
            return new Object[0];
        }
        Object[] out = new Object[authored.size()];
        for (int i = 0; i < authored.size(); i++) {
            String token = authored.get(i) == null ? "" : authored.get(i);
            Object value = lookup != null && token.startsWith("@") ? lookup.resolve(token) : null;
            out[i] = value != null ? value : token;
        }
        return out;
    }
}
