package com.ziggfreed.common.feedback.moment;

import java.util.Map;

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
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.codec.ScalarStringCodec;

/**
 * What a server does when one lifecycle moment happens: float a toast, banner the whole server,
 * play a sound, run a command. The FILE NAME is the moment id, so
 * {@code quest.completed.json} answers the moment called {@code quest.completed}.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/FeedbackMoments/<moment id>.json} (this codec IS the
 * schema):
 * <pre>{@code
 * // Server/ZiggfreedCommon/FeedbackMoments/quest.completed.json
 * { "Toast":  { "Title": { "Key": "notify.quest_complete", "Args": ["title"],
 *                          "Color": "#FFFF00" } },
 *   "Sound":  { "Id": "SFX_Discovery_Z2_Short" } }
 * }</pre>
 *
 * <p><b>Every group is optional and they compose.</b> A file with only a {@code Sound} plays a
 * jingle and shows nothing; one with a {@code Broadcast} tells the whole server as well as the
 * player. There is no mode and no type: switch a part off by deleting it.
 *
 * <p><b>{@code Args} names what fills the blanks.</b> The moment hands over what was in scope keyed
 * by name - {@code title}, {@code quest}, {@code points}, {@code icon}, whatever that moment
 * carries - and {@code Args} lists which of those go into the line, in order. They fill both the
 * numbered {@code {0}}, {@code {1}} slots and same-named {@code {title}} slots, so a lang value
 * written either way works. <b>A line naming an argument the moment did not carry is not shown at
 * all</b>, because a sentence with a hole in it reads worse than silence. The name {@code player}
 * always answers with the name of whoever the moment is about.
 *
 * <p><b>{@code Variants} let ONE file say different things for different cases.</b> A moment often
 * arrives with a value that changes what should be said - a quest parked because the bags were full
 * versus one parked to be collected somewhere - and each variant names, under {@code When}, the
 * argument values it applies to and restates only the groups that differ. The first variant whose
 * {@code When} matches overlays its groups on the file's own; nothing else changes.
 *
 * <p>Which arguments a moment carries is the moment's own business, and a moment nobody authored a
 * file for simply does nothing. This library ships a neutral default file for each moment its own
 * engines announce, so a bare server gets feedback out of the box; a mod that wants its own words,
 * colours or sounds ships a same-named file, and a server owner turns a piece of it off by
 * overriding the file with one that leaves that group out.
 *
 * <p>Override a moment by dropping a same-named file in a later pack or in the owner layer (a pack
 * that lists this library as a dependency loads after it, so its file wins); reuse one with a
 * top-level {@code "Parent": "<moment id>"}, which inherits group by group.
 */
public final class FeedbackMomentAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, FeedbackMomentAsset>> {

    /** Where these are authored. One folder, one file per moment, the file name being the id. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/FeedbackMoments";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Toast toast;
    @Nullable private Broadcast broadcast;
    @Nullable private Sound sound;
    @Nullable private Command command;
    @Nullable private Variant[] variants;

    public static final AssetBuilderCodec<String, FeedbackMomentAsset> CODEC = AssetBuilderCodec.builder(
                    FeedbackMomentAsset.class,
                    FeedbackMomentAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Toast", Toast.CODEC, false),
                    (a, v) -> a.toast = v, a -> a.toast, (a, p) -> a.toast = p.toast)
            .documentation("A corner notification for the one player the moment is about. Without it the "
                    + "moment shows them nothing.")
            .add()
            .appendInherited(new KeyedCodec<>("Broadcast", Broadcast.CODEC, false),
                    (a, v) -> a.broadcast = v, a -> a.broadcast, (a, p) -> a.broadcast = p.broadcast)
            .documentation("A centered banner every player online sees. Each of them is sent it "
                    + "individually, so everyone reads it in their own language. Save it for the rare "
                    + "moment worth interrupting a whole server for.")
            .add()
            .appendInherited(new KeyedCodec<>("Sound", Sound.CODEC, false),
                    (a, v) -> a.sound = v, a -> a.sound, (a, p) -> a.sound = p.sound)
            .documentation("A jingle at the player's own position.")
            .add()
            .appendInherited(new KeyedCodec<>("Command", Command.CODEC, false),
                    (a, v) -> a.command = v, a -> a.command, (a, p) -> a.command = p.command)
            .documentation("A console command to run, for anything the three above cannot do.")
            .add()
            .appendInherited(new KeyedCodec<>("Variants",
                            new ArrayCodec<>(Variant.CODEC, Variant[]::new), false),
                    (a, v) -> a.variants = v, a -> a.variants, (a, p) -> a.variants = p.variants)
            .documentation("Different words for different cases of the same moment. Each entry names, under "
                    + "When, the argument values it applies to and restates only the groups that differ; "
                    + "the first entry that matches overlays those groups on the ones above. Unauthored, the "
                    + "moment reads the same whatever it carries.")
            .add()
            .build();

    public FeedbackMomentAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The player's own notification, or null when this moment shows them none. */
    @Nullable
    public Toast getToast() {
        return toast;
    }

    /** The server-wide banner, or null when this moment tells nobody else. */
    @Nullable
    public Broadcast getBroadcast() {
        return broadcast;
    }

    /** The jingle, or null when this moment is silent. */
    @Nullable
    public Sound getSound() {
        return sound;
    }

    /** The console command, or null when this moment runs none. */
    @Nullable
    public Command getCommand() {
        return command;
    }

    /** The authored variants in order; empty when this moment reads the same for every case. */
    @Nonnull
    public Variant[] getVariants() {
        return variants == null ? new Variant[0] : variants;
    }

    /**
     * What this moment does for {@code args}: the file's own groups, with the first matching
     * variant's authored groups laid over them. The four answers are read off the result and never
     * off the file directly, so a variant is honoured wherever the moment is drawn.
     */
    @Nonnull
    public Resolved resolve(@Nonnull Map<String, Object> args) {
        for (Variant variant : getVariants()) {
            if (variant != null && variant.matches(args)) {
                return new Resolved(
                        variant.toast != null ? variant.toast : toast,
                        variant.broadcast != null ? variant.broadcast : broadcast,
                        variant.sound != null ? variant.sound : sound,
                        variant.command != null ? variant.command : command);
            }
        }
        return new Resolved(toast, broadcast, sound, command);
    }

    /** The four groups a moment resolves to for one set of arguments. */
    public record Resolved(@Nullable Toast toast, @Nullable Broadcast broadcast,
                           @Nullable Sound sound, @Nullable Command command) {
    }

    // ==================== Line ====================

    /**
     * One line of text: which localization key it reads, what fills that key's blanks, and what
     * colour it paints.
     *
     * <p>The same leaves wherever a line appears, so the title of a toast and the body of a banner
     * are authored identically and a colour never has to be spelled differently in two places.
     */
    public static final class Line {

        @Nullable protected String key;
        @Nullable protected String keyArg;
        @Nullable protected String[] args;
        @Nullable protected String color;

        public static final BuilderCodec<Line> CODEC = BuilderCodec.builder(Line.class, Line::new)
                .appendInherited(new KeyedCodec<>("Key", Codec.STRING, false),
                        (o, v) -> o.key = v, o -> o.key, (o, p) -> o.key = p.key)
                .documentation("The localization key this line reads, written WITHOUT your lang file's own "
                        + "namespace, exactly as you wrote it in that file (a full registered id works too "
                        + "and passes through untouched). Without it, and without a KeyArg the moment "
                        + "answers, the line is not shown. A key no installed mod ships is sent to the "
                        + "client as written, which is how you point at one of the game's own.")
                .add()
                .appendInherited(new KeyedCodec<>("KeyArg", Codec.STRING, false),
                        (o, v) -> o.keyArg = v, o -> o.keyArg, (o, p) -> o.keyArg = p.keyArg)
                .documentation("Read the key from one of the moment's own values instead, named here, for a "
                        + "line whose wording is decided per piece of content rather than once per moment "
                        + "(an achievement's own announcement, say). When the moment carries that value it "
                        + "is the key; when it does not, Key is used, and with no Key the line is skipped, "
                        + "which is how a line shows only for the content that authored one.")
                .add()
                .appendInherited(new KeyedCodec<>("Args", Codec.STRING_ARRAY, false),
                        (o, v) -> o.args = v, o -> o.args, (o, p) -> o.args = p.args)
                .documentation("Which of the moment's own values fill the key's blanks, in order. They fill "
                        + "the numbered {0}, {1} slots and same-named {title} slots alike. Name one the "
                        + "moment does not carry and the line is skipped rather than shown with a gap.")
                .add()
                .appendInherited(new KeyedCodec<>("Color", Codec.STRING, false),
                        (o, v) -> o.color = v, o -> o.color, (o, p) -> o.color = p.color)
                .documentation("A hex colour for the whole line, for instance \"#FFFF00\". Unauthored leaves "
                        + "it whatever the screen paints by default.")
                .add()
                .build();

        public Line() {
        }

        /** The fixed localization key, or null when this line names none. */
        @Nullable
        public String getKey() {
            return key == null || key.isBlank() ? null : key.trim();
        }

        /** The argument the key is read from, or null when the key is the fixed one. */
        @Nullable
        public String getKeyArg() {
            return keyArg == null || keyArg.isBlank() ? null : keyArg.trim();
        }

        /**
         * The key this line reads for {@code args}: the moment's value under {@link #getKeyArg()}
         * when it carries one as plain text, else {@link #getKey()}, else null for a line that shows
         * nothing this time.
         */
        @Nullable
        public String keyFor(@Nonnull Map<String, Object> args) {
            String named = getKeyArg();
            if (named != null) {
                Object value = args.get(named);
                if (value != null && !(value instanceof Message)) {
                    String text = value.toString();
                    if (!text.isBlank()) {
                        return text.trim();
                    }
                }
            }
            return getKey();
        }

        /** The argument names filling the key's blanks, in order; empty when it takes none. */
        @Nonnull
        public String[] getArgs() {
            return args == null ? new String[0] : args;
        }

        /** The hex colour, or null for the default. */
        @Nullable
        public String getColor() {
            return color == null || color.isBlank() ? null : color.trim();
        }
    }

    // ==================== Toast ====================

    /**
     * The corner notification the one player it happened to sees.
     *
     * <p>The picture is not authored here. A moment that carries an item id under the value named
     * {@code icon} illustrates its toast with that item, shown without a quantity badge; one that
     * carries none shows no picture. Which moments have a picture to offer is the producer's
     * business, so a file never has to name a value it cannot see.
     */
    public static final class Toast {

        @Nullable protected Line title;
        @Nullable protected Line secondary;
        @Nullable protected Integer everyPercent;

        public static final BuilderCodec<Toast> CODEC = BuilderCodec.builder(Toast.class, Toast::new)
                .appendInherited(new KeyedCodec<>("Title", Line.CODEC, false),
                        (o, v) -> o.title = v, o -> o.title, (o, p) -> o.title = p.title)
                .documentation("The headline. Without it there is no toast.")
                .add()
                .appendInherited(new KeyedCodec<>("Secondary", Line.CODEC, false),
                        (o, v) -> o.secondary = v, o -> o.secondary, (o, p) -> o.secondary = p.secondary)
                .documentation("The smaller line under the headline; leave it out for a one-line toast.")
                .add()
                .appendInherited(new KeyedCodec<>("EveryPercent", Codec.INTEGER, false),
                        (o, v) -> o.everyPercent = v, o -> o.everyPercent,
                        (o, p) -> o.everyPercent = p.everyPercent)
                .documentation("For a moment that reports progress (one carrying 'current' and 'required'): "
                        + "show an ordinary tick only when it crosses a multiple of this many percent of "
                        + "the way, for instance 25 for the quarter marks. The step finishing always shows. "
                        + "Unauthored shows every tick. A mod that lets each player pick how chatty their "
                        + "own screen is decides for them instead, and is told whether the tick crossed "
                        + "this mark.")
                .add()
                .build();

        public Toast() {
        }

        @Nullable
        public Line getTitle() {
            return title;
        }

        @Nullable
        public Line getSecondary() {
            return secondary;
        }

        /** The percent step an ordinary tick has to cross to show, or null to show every tick. */
        @Nullable
        public Integer getEveryPercent() {
            return everyPercent == null || everyPercent <= 0 ? null : everyPercent;
        }
    }

    // ==================== Broadcast ====================

    /** The centered banner every player online is shown, each in their own language. */
    public static final class Broadcast {

        @Nullable protected Line title;
        @Nullable protected Line secondary;
        @Nullable protected Boolean major;

        public static final BuilderCodec<Broadcast> CODEC =
                BuilderCodec.builder(Broadcast.class, Broadcast::new)
                        .appendInherited(new KeyedCodec<>("Title", Line.CODEC, false),
                                (o, v) -> o.title = v, o -> o.title, (o, p) -> o.title = p.title)
                        .documentation("The headline. Without it there is no banner.")
                        .add()
                        .appendInherited(new KeyedCodec<>("Secondary", Line.CODEC, false),
                                (o, v) -> o.secondary = v, o -> o.secondary,
                                (o, p) -> o.secondary = p.secondary)
                        .documentation("The line under the headline.")
                        .add()
                        .appendInherited(new KeyedCodec<>("Major", Codec.BOOLEAN, false),
                                (o, v) -> o.major = v, o -> o.major, (o, p) -> o.major = p.major)
                        .documentation("Render it in the larger style reserved for the big moments. "
                                + "Unauthored means the ordinary size.")
                        .add()
                        .build();

        public Broadcast() {
        }

        @Nullable
        public Line getTitle() {
            return title;
        }

        @Nullable
        public Line getSecondary() {
            return secondary;
        }

        public boolean isMajor() {
            return major != null && major;
        }
    }

    // ==================== Sound ====================

    /** The jingle, played at the player's own position so anyone nearby hears it too. */
    public static final class Sound {

        @Nullable protected String id;

        public static final BuilderCodec<Sound> CODEC = BuilderCodec.builder(Sound.class, Sound::new)
                .appendInherited(new KeyedCodec<>("Id", Codec.STRING, false),
                        (o, v) -> o.id = v, o -> o.id, (o, p) -> o.id = p.id)
                .documentation("A SoundEvent asset id. An id no pack ships is skipped quietly, so a jingle "
                        + "borrowed from content that is not installed costs nothing.")
                .add()
                .build();

        public Sound() {
        }

        /** The sound event id, or null when none is authored. */
        @Nullable
        public String getId() {
            return id == null || id.isBlank() ? null : id.trim();
        }
    }

    // ==================== Command ====================

    /** A console command, for whatever the toast, the banner and the jingle cannot do. */
    public static final class Command {

        @Nullable protected String line;

        public static final BuilderCodec<Command> CODEC =
                BuilderCodec.builder(Command.class, Command::new)
                        .appendInherited(new KeyedCodec<>("Line", Codec.STRING, false),
                                (o, v) -> o.line = v, o -> o.line, (o, p) -> o.line = p.line)
                        .documentation("The command to run as console, with {player} and any of the "
                                + "moment's own values in braces, for instance "
                                + "\"say {player} finished {quest}\". A brace nothing answers is left as "
                                + "written so a typo is visible rather than silent.")
                        .add()
                        .build();

        public Command() {
        }

        /** The command line, or null when none is authored. */
        @Nullable
        public String getLine() {
            return line == null || line.isBlank() ? null : line.trim();
        }
    }

    // ==================== Variant ====================

    /**
     * One case of a moment: which argument values it applies to, and the groups that read
     * differently for it. A group it does not restate is the file's own.
     */
    public static final class Variant {

        @Nullable protected Map<String, String> when;
        @Nullable protected Toast toast;
        @Nullable protected Broadcast broadcast;
        @Nullable protected Sound sound;
        @Nullable protected Command command;

        public static final BuilderCodec<Variant> CODEC =
                BuilderCodec.builder(Variant.class, Variant::new)
                        .append(new KeyedCodec<>("When",
                                        new InheritMapCodec<>(ScalarStringCodec.INSTANCE), false),
                                (o, v) -> o.when = v, o -> o.when)
                        .documentation("Which values of the moment's own arguments this entry is for, by "
                                + "name: {\"reason\": \"no_space\"} applies when the moment carries "
                                + "reason and it reads no_space, and every named value has to match. A "
                                + "value is compared as text, ignoring case, so true and 25 may be "
                                + "written bare. An empty When matches every time.")
                        .add()
                        .append(new KeyedCodec<>("Toast", Toast.CODEC, false),
                                (o, v) -> o.toast = v, o -> o.toast)
                        .documentation("The toast for this case, replacing the one above; an empty group "
                                + "means no toast for this case.")
                        .add()
                        .append(new KeyedCodec<>("Broadcast", Broadcast.CODEC, false),
                                (o, v) -> o.broadcast = v, o -> o.broadcast)
                        .documentation("The banner for this case, replacing the one above; an empty group "
                                + "means no banner for this case.")
                        .add()
                        .append(new KeyedCodec<>("Sound", Sound.CODEC, false),
                                (o, v) -> o.sound = v, o -> o.sound)
                        .documentation("The jingle for this case, replacing the one above; an empty group "
                                + "means silence for this case.")
                        .add()
                        .append(new KeyedCodec<>("Command", Command.CODEC, false),
                                (o, v) -> o.command = v, o -> o.command)
                        .documentation("The command for this case, replacing the one above; an empty group "
                                + "means none for this case.")
                        .add()
                        .build();

        public Variant() {
        }

        /** The argument values this case is for, by name; empty matches every time. */
        @Nonnull
        public Map<String, String> getWhen() {
            return when == null ? Map.of() : when;
        }

        /**
         * Does this case apply to {@code args}? Every named value has to be carried, as plain data,
         * and read the same ignoring case; a localized value is a sentence and never matches.
         */
        public boolean matches(@Nonnull Map<String, Object> args) {
            for (Map.Entry<String, String> expected : getWhen().entrySet()) {
                Object actual = args.get(expected.getKey());
                if (actual == null || actual instanceof Message) {
                    return false;
                }
                String want = expected.getValue() == null ? "" : expected.getValue().trim();
                if (!actual.toString().trim().equalsIgnoreCase(want)) {
                    return false;
                }
            }
            return true;
        }
    }
}
