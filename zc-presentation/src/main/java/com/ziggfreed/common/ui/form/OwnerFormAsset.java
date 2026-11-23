package com.ziggfreed.common.ui.form;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;

/**
 * An owner form: an in-game editor for one owner file, made from data, at
 * {@code Server/ZiggfreedCommon/OwnerForms/<Id>.json}. It names the file ({@code File}, the id the mod that reads the
 * file registered, {@code OwnerFileBindings}), the entry its settings sit under in an id-keyed file ({@code Entry}),
 * and its settings in headed sections, each a dotted {@code Path} with a {@code Kind} (a closed list,
 * {@link OwnerFormKind}), its words (lang keys in your own lang file), its bounds and its choices. The owner menu
 * ({@code /zigadmin}) shows it as a tile under its {@code Section}; the one owner form page draws it, shows each
 * setting's value in force and the value under the owner's word, and writes a change into the file through the
 * mod's own writer, which reads the file again.
 *
 * <pre>{@code
 * { "Title": "yourmod.form.title", "Icon": "Furniture_Signpost", "Section": "yourmod",
 *   "File": "yourmod/settings.json", "Entry": "Default",
 *   "Sections": [ { "Heading": "yourmod.form.heading",
 *     "Fields": [ { "Path": "Limits.Max", "Kind": "Whole", "Label": "yourmod.form.max", "Min": 0, "Max": 50 },
 *                 { "Path": "Mode", "Kind": "Choice", "Label": "yourmod.form.mode",
 *                   "Choices": [ { "Value": "Fast", "Label": "yourmod.form.mode.fast" } ] } ] } ] }
 * }</pre>
 *
 * <p><b>Two shapes, told apart by the fields written.</b> A form of settings edits one file's leaves (under
 * {@code Entry} in an id-keyed file). A form with an {@code Entries} group edits an id-keyed file one entry at a time:
 * a row per entry it lists (every entry, or those holding the {@code Having} path, less any its {@code Gate} hides),
 * each opening the form's settings for that one entry, the value under the owner's word read from the layer under the
 * owner, a Reset taking the owner's leaf out, and, when the group allows, an owner adding an entry by a new id or
 * removing one only their own file holds. Its writes go through the store's own entry writer.
 *
 * <pre>{@code
 * { "Title": "yourmod.form.boosts", "File": "yourmod/boosts.json",
 *   "Entries": { "Having": "Reusable", "Gate": "Reusable.Requires", "Name": "Text.TitleKey" },
 *   "Sections": [ { "Fields": [ { "Path": "Percent", "Kind": "Number", "Label": "yourmod.form.percent" } ] } ] }
 * }</pre>
 *
 * <p>A form writes only the file its binding names, and only the paths it declares, under its one entry or the
 * entries it lists; a path is dotted letters and digits, so no form reaches a reserved {@code $} key, and an id an
 * owner types is held to the store's id pattern before anything is written. A top-level {@code Requires} follows the
 * mod gate (a plain mod-presence check keeps the form out where that mod is missing) and asks the rest of the owner
 * each time the menu lists it. Every leaf is nullable and inherited through {@code Parent}; the server owner can retune
 * a form in {@code mods/ziggfreedcommon/owner-forms.json}.
 */
public final class OwnerFormAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, OwnerFormAsset>> {

    /** Where the forms are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/OwnerForms";

    /** The menu section a form writing none shows under: the library's. */
    public static final String DEFAULT_SECTION = "ziggfreedcommon";

    /** A form's place in its section when it writes none. */
    public static final int DEFAULT_ORDER = 100;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected String title;
    @Nullable protected String description;
    @Nullable protected String icon;
    @Nullable protected String section;
    @Nullable protected Integer order;
    @Nullable protected String file;
    @Nullable protected String entry;
    @Nullable protected Entries entries;
    @Nullable protected GateSpec requires;
    @Nullable protected Section[] sections;

    /** The live part of the {@code Requires} block, lifted once per instance ({@link StoreGates#live}). */
    @Nullable private volatile GateSpec[] live;

    public static final AssetBuilderCodec<String, OwnerFormAsset> CODEC = AssetBuilderCodec.builder(
                    OwnerFormAsset.class,
                    OwnerFormAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Title", Codec.STRING, false),
                    (a, v) -> a.title = v, a -> a.title, (a, p) -> a.title = p.title)
            .documentation("What the form is called, on its tile in the owner menu and over the form itself: a "
                    + "localization key in your own lang file. Without one the tile reads the form's id.").add()
            .appendInherited(new KeyedCodec<>("Description", Codec.STRING, false),
                    (a, v) -> a.description = v, a -> a.description, (a, p) -> a.description = p.description)
            .documentation("One line under the title on the tile and over the form, saying what it sets: a "
                    + "localization key. Leave it out for none.").add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .metadata(EditorSchema.assetRef(Item.class))
            .documentation("The item drawn on the form's tile. Leave it out for an empty slot.").add()
            .appendInherited(new KeyedCodec<>("Section", Codec.STRING, false),
                    (a, v) -> a.section = v, a -> a.section, (a, p) -> a.section = p.section)
            .metadata(EditorSchema.defaultValue(DEFAULT_SECTION))
            .documentation("Which mod's part of the owner menu the tile sits in, by that mod's id (ziggfreedcommon "
                    + "for CommonLib's, unless written). A section no installed mod opened gathers under More.").add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .metadata(EditorSchema.defaultValue((long) DEFAULT_ORDER))
            .documentation("Where the tile sits in its section, lowest first; 100 when unwritten. A tie goes by "
                    + "id.").add()
            .appendInherited(new KeyedCodec<>("File", Codec.STRING, false),
                    (a, v) -> a.file = v, a -> a.file, (a, p) -> a.file = p.file)
            .documentation("The owner file the form edits, by the id the mod that reads it registered: by "
                    + "convention its path under mods/, such as ziggfreedcommon/boost-settings.json. A form naming a "
                    + "file no installed mod registered opens with nothing it can change.").add()
            .appendInherited(new KeyedCodec<>("Entry", Codec.STRING, false),
                    (a, v) -> a.entry = v, a -> a.entry, (a, p) -> a.entry = p.entry)
            .documentation("For a file keyed by id, the entry every setting sits under (Default); leave it out for "
                    + "a file of plain settings, or for a form with Entries. Letters, digits and underscores.").add()
            .appendInherited(new KeyedCodec<>("Entries", Entries.CODEC, false),
                    (a, v) -> a.entries = v, a -> a.entries, (a, p) -> a.entries = p.entries)
            .documentation("Makes this form edit a file keyed by id one entry at a time: a row for each entry it "
                    + "lists, each opening the settings below for that one entry, where each shows the value the "
                    + "server would use without the owner's word and a Reset that takes the owner's value out. Leave "
                    + "it out for a form of settings.").add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation(StoreGates.DOCUMENTATION + " Asked of the owner each time the menu lists the form and "
                    + "at every change made on it.").add()
            .appendInherited(new KeyedCodec<>("Sections", new ArrayCodec<>(Section.CODEC, Section[]::new), false),
                    (a, v) -> a.sections = v, a -> a.sections, (a, p) -> a.sections = p.sections)
            .documentation("The form's settings, in headed groups, in order. A child's list replaces its "
                    + "parent's whole.").add()
            .build();

    public OwnerFormAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The title's localization key, or null. */
    @Nullable
    public String title() {
        return blankToNull(title);
    }

    /** The description's localization key, or null. */
    @Nullable
    public String description() {
        return blankToNull(description);
    }

    /** The tile's item, or null. */
    @Nullable
    public String icon() {
        return blankToNull(icon);
    }

    /** The menu section's owner id, {@link #DEFAULT_SECTION} when unwritten. */
    @Nonnull
    public String section() {
        String written = blankToNull(section);
        return written == null ? DEFAULT_SECTION : written;
    }

    /** The tile's place in its section. */
    public int order() {
        return order == null ? DEFAULT_ORDER : order;
    }

    /** The owner file's binding id, trimmed, or null. */
    @Nullable
    public String file() {
        return blankToNull(file);
    }

    /** The entry the settings sit under, trimmed, or null for a file of plain settings. */
    @Nullable
    public String entry() {
        return blankToNull(entry);
    }

    /** The {@code Entries} group, or null for a form of settings. */
    @Nullable
    public Entries entries() {
        return entries;
    }

    /** The top-level {@code Requires}, or null. */
    @Nullable
    public GateSpec requires() {
        return requires;
    }

    /** The part of {@link #requires()} asked of the owner, lifted once; null when nothing is. */
    @Nullable
    public GateSpec liveRequires() {
        GateSpec[] held = live;
        if (held == null) {
            held = new GateSpec[] {StoreGates.live(requires)};
            live = held;
        }
        return held[0];
    }

    /** The sections in order, without nulls. */
    @Nonnull
    public List<Section> sections() {
        List<Section> out = new ArrayList<>();
        if (sections != null) {
            for (Section s : sections) {
                if (s != null) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    /**
     * Is {@code path} a path a form may write: dotted segments of letters and digits, each starting with a letter (the
     * keyed stores' one rule, {@link AbstractKeyedAssetConfig#isOwnerLeafPath})?
     */
    public static boolean isPath(@Nullable String path) {
        return AbstractKeyedAssetConfig.isOwnerLeafPath(path);
    }

    /**
     * Is {@code entry} an entry id a form may write under: letters, digits and underscores (the keyed stores' one
     * rule, {@link AbstractKeyedAssetConfig#isOwnerEntryId})?
     */
    public static boolean isEntry(@Nullable String entry) {
        return AbstractKeyedAssetConfig.isOwnerEntryId(entry);
    }

    @Nullable
    private static String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * The Entries group: the form edits an id-keyed file one entry at a time. Which entries it lists ({@code Having},
     * {@code Gate}), what names each row ({@code Name}), and whether an owner may add or remove one.
     */
    public static final class Entries {

        @Nullable protected String having;
        @Nullable protected String gate;
        @Nullable protected String name;
        @Nullable protected Boolean add;
        @Nullable protected Boolean remove;

        public static final BuilderCodec<Entries> CODEC = BuilderCodec.builder(Entries.class, Entries::new)
                .append(new KeyedCodec<>("Having", Codec.STRING, false),
                        (o, v) -> o.having = v, o -> o.having)
                .documentation("List only the entries that hold this dotted path as they read now (Reusable lists the "
                        + "boosts players start themselves). Leave it out to list every entry.").add()
                .append(new KeyedCodec<>("Gate", Codec.STRING, false),
                        (o, v) -> o.gate = v, o -> o.gate)
                .documentation("A Requires block inside each entry, by its dotted path (Reusable.Requires). An entry "
                        + "whose block asks for a feature switched off on this server, or a mod that is not installed, "
                        + "is left off the form, as the game hides that content from players. The rest of the block (a "
                        + "permission, a level) is about whoever uses the entry and is not asked here.").add()
                .append(new KeyedCodec<>("Name", Codec.STRING, false),
                        (o, v) -> o.name = v, o -> o.name)
                .documentation("A dotted path inside each entry to the localization key that names it (Text.TitleKey). "
                        + "Without one, or for an entry that writes none, the row reads the entry's id.").add()
                .append(new KeyedCodec<>("Add", Codec.BOOLEAN, false),
                        (o, v) -> o.add = v, o -> o.add)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True lets an owner add an entry by typing a new id (letters, digits and underscores, "
                        + "one no entry has yet): it is written into the owner file, holding the Having path as an "
                        + "empty group, and opened to set. Unwritten means false.").add()
                .append(new KeyedCodec<>("Remove", Codec.BOOLEAN, false),
                        (o, v) -> o.remove = v, o -> o.remove)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True lets an owner remove an entry only their own file holds (one no pack ships). An "
                        + "entry a pack ships is never removed, only reset. Unwritten means false.").add()
                .build();

        public Entries() {
        }

        /** The path an entry must hold to be listed, trimmed, or null to list every entry. */
        @Nullable
        public String having() {
            return blankToNull(having);
        }

        /** The path of the Requires block that may hide an entry, trimmed, or null for none. */
        @Nullable
        public String gate() {
            return blankToNull(gate);
        }

        /** The path of the key naming an entry, trimmed, or null to name each by its id. */
        @Nullable
        public String name() {
            return blankToNull(name);
        }

        /** May an owner add an entry? */
        public boolean add() {
            return Boolean.TRUE.equals(add);
        }

        /** May an owner remove an entry only their own file holds? */
        public boolean remove() {
            return Boolean.TRUE.equals(remove);
        }
    }

    /** One headed group of settings. */
    public static final class Section {

        @Nullable protected String heading;
        @Nullable protected String note;
        @Nullable protected Field[] fields;

        public static final BuilderCodec<Section> CODEC = BuilderCodec.builder(Section.class, Section::new)
                .append(new KeyedCodec<>("Heading", Codec.STRING, false),
                        (o, v) -> o.heading = v, o -> o.heading)
                .documentation("The group's heading: a localization key. Leave it out for none.").add()
                .append(new KeyedCodec<>("Note", Codec.STRING, false),
                        (o, v) -> o.note = v, o -> o.note)
                .documentation("A line under the heading, for what the whole group does: a localization key.").add()
                .append(new KeyedCodec<>("Fields", new ArrayCodec<>(Field.CODEC, Field[]::new), false),
                        (o, v) -> o.fields = v, o -> o.fields)
                .documentation("The group's settings, in order.").add()
                .build();

        public Section() {
        }

        @Nullable
        public String heading() {
            return blankToNull(heading);
        }

        @Nullable
        public String note() {
            return blankToNull(note);
        }

        /** The fields in order, without nulls. */
        @Nonnull
        public List<Field> fields() {
            List<Field> out = new ArrayList<>();
            if (fields != null) {
                for (Field f : fields) {
                    if (f != null) {
                        out.add(f);
                    }
                }
            }
            return out;
        }
    }

    /** One setting: where it sits in the file, what kind it is, its words, its bounds and its choices. */
    public static final class Field {

        @Nullable protected String path;
        @Nullable protected String kind;
        @Nullable protected String label;
        @Nullable protected String hint;
        @Nullable protected Double min;
        @Nullable protected Double max;
        @Nullable protected Choice[] choices;

        public static final BuilderCodec<Field> CODEC = BuilderCodec.builder(Field.class, Field::new)
                .append(new KeyedCodec<>("Path", Codec.STRING, false),
                        (o, v) -> o.path = v, o -> o.path)
                .documentation("Where the setting sits inside the entry (or the file), as dotted keys exactly as the "
                        + "file spells them: Cap, Limits.Personal. Letters and digits only.").add()
                .append(new KeyedCodec<>("Kind", Codec.STRING, false),
                        (o, v) -> o.kind = v, o -> o.kind)
                .metadata(EditorSchema.oneOfDocumented(
                        "Text", "A word or a line, written as text.",
                        "Number", "A number of 0 or more, written as a decimal.",
                        "Whole", "A whole number, written without a decimal point.",
                        "Chance", "A number from 0 to 1, written as a decimal.",
                        "List", "Words split by commas, written as a list.",
                        "Switch", "On or off, written as true or false.",
                        "Choice", "One of the field's Choices, written as its Value."))
                .documentation("What the setting is, which decides its control and how it is written: match the "
                        + "type the file's reader expects (Whole for a whole number, Number for a decimal).").add()
                .append(new KeyedCodec<>("Label", Codec.STRING, false),
                        (o, v) -> o.label = v, o -> o.label)
                .documentation("The setting's name: a localization key. Without one the row reads its Path.").add()
                .append(new KeyedCodec<>("Hint", Codec.STRING, false),
                        (o, v) -> o.hint = v, o -> o.hint)
                .documentation("A line under the setting saying what it does: a localization key.").add()
                .append(new KeyedCodec<>("Min", Codec.DOUBLE, false),
                        (o, v) -> o.min = v, o -> o.min)
                .documentation("The least a number may be; a lower one is refused when saved.").add()
                .append(new KeyedCodec<>("Max", Codec.DOUBLE, false),
                        (o, v) -> o.max = v, o -> o.max)
                .documentation("The most a number may be; a higher one is refused when saved.").add()
                .append(new KeyedCodec<>("Choices", new ArrayCodec<>(Choice.CODEC, Choice[]::new), false),
                        (o, v) -> o.choices = v, o -> o.choices)
                .documentation("For a Choice, the values it may take, in order, each with its words.").add()
                .build();

        public Field() {
        }

        /** The path, trimmed, or null. */
        @Nullable
        public String path() {
            return blankToNull(path);
        }

        /** The kind, or null when unwritten or not one of the list. */
        @Nullable
        public OwnerFormKind kind() {
            return OwnerFormKind.of(kind);
        }

        /** The kind as written, or null. */
        @Nullable
        public String kindWord() {
            return blankToNull(kind);
        }

        @Nullable
        public String label() {
            return blankToNull(label);
        }

        @Nullable
        public String hint() {
            return blankToNull(hint);
        }

        @Nullable
        public Double min() {
            return min == null || !Double.isFinite(min) ? null : min;
        }

        @Nullable
        public Double max() {
            return max == null || !Double.isFinite(max) ? null : max;
        }

        /** The choices in order, without nulls or blank values. */
        @Nonnull
        public List<Choice> choices() {
            List<Choice> out = new ArrayList<>();
            if (choices != null) {
                for (Choice c : choices) {
                    if (c != null && c.value() != null) {
                        out.add(c);
                    }
                }
            }
            return out;
        }
    }

    /** One value a Choice may take, with its words. */
    public static final class Choice {

        @Nullable protected String value;
        @Nullable protected String label;

        public static final BuilderCodec<Choice> CODEC = BuilderCodec.builder(Choice.class, Choice::new)
                .append(new KeyedCodec<>("Value", Codec.STRING, false),
                        (o, v) -> o.value = v, o -> o.value)
                .documentation("The value written into the file, exactly as its reader expects it.").add()
                .append(new KeyedCodec<>("Label", Codec.STRING, false),
                        (o, v) -> o.label = v, o -> o.label)
                .documentation("What the dropdown shows for it: a localization key. Without one it shows the "
                        + "Value.").add()
                .build();

        public Choice() {
        }

        @Nullable
        public String value() {
            return blankToNull(value);
        }

        @Nullable
        public String label() {
            return blankToNull(label);
        }
    }
}
