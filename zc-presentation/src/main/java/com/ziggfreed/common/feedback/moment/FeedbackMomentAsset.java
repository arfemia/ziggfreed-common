package com.ziggfreed.common.feedback.moment;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

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
 * all</b>, because a sentence with a hole in it reads worse than silence.
 *
 * <p>Which arguments a moment carries is the moment's own business, and a moment nobody authored a
 * file for simply does nothing - which is how an engine ships this capability with no content of its
 * own and a server owner turns a piece of it off by deleting a file.
 *
 * <p>Override a moment by dropping a same-named file in a later pack or in the owner layer; reuse
 * one with a top-level {@code "Parent": "<moment id>"}, which inherits group by group.
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

    // ==================== Line ====================

    /**
     * One line of text: which localization key it reads, what fills that key's blanks, and what
     * colour it paints.
     *
     * <p>The same three leaves wherever a line appears, so the title of a toast and the body of a
     * banner are authored identically and a colour never has to be spelled differently in two
     * places.
     */
    public static final class Line {

        @Nullable protected String key;
        @Nullable protected String[] args;
        @Nullable protected String color;

        public static final BuilderCodec<Line> CODEC = BuilderCodec.builder(Line.class, Line::new)
                .appendInherited(new KeyedCodec<>("Key", Codec.STRING, false),
                        (o, v) -> o.key = v, o -> o.key, (o, p) -> o.key = p.key)
                .documentation("The localization key this line reads, written WITHOUT your lang file's own "
                        + "namespace, exactly as you wrote it in that file. Without it the line is not "
                        + "shown. A key no installed mod ships is sent to the client as written, which "
                        + "is how you point at one of the game's own.")
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

        /** The localization key, or null when this line is not authored. */
        @Nullable
        public String getKey() {
            return key == null || key.isBlank() ? null : key.trim();
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

        public static final BuilderCodec<Toast> CODEC = BuilderCodec.builder(Toast.class, Toast::new)
                .appendInherited(new KeyedCodec<>("Title", Line.CODEC, false),
                        (o, v) -> o.title = v, o -> o.title, (o, p) -> o.title = p.title)
                .documentation("The headline. Without it there is no toast.")
                .add()
                .appendInherited(new KeyedCodec<>("Secondary", Line.CODEC, false),
                        (o, v) -> o.secondary = v, o -> o.secondary, (o, p) -> o.secondary = p.secondary)
                .documentation("The smaller line under the headline; leave it out for a one-line toast.")
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
}
