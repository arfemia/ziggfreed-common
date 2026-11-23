package com.ziggfreed.common.party.board;

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
import com.ziggfreed.common.asset.EditorSchema;

/**
 * The server's one word on its party boards ({@link PartyBoard}): what a notice's announcement does when its poster
 * ticks the box, and how long a notice stays up. The FILE NAME is the record's id, and the record every board reads is
 * {@value #SHARED_ID}.
 *
 * <p>Authored at {@code Server/ZiggfreedCommon/PartyBoard/Default.json}, which this library ships; a pack's same-id
 * file wins by pack order, and the owner layer is {@code mods/ziggfreedcommon/party-board.json}:
 * <pre>{@code
 * { "Default": { "Announce": { "Message": false, "Commands": ["say {player} wants {wants} more for {for}"] } } }
 * }</pre>
 *
 * <p>An announcement runs only for a notice whose poster ticked its box, which starts unticked, and at most once per
 * poster per {@code Notices.Minutes}, so a notice taken down and put up again is not announced twice. {@code Message}
 * sends every player online the board's own line, each in their own language; {@code Commands} runs each line as the server
 * console through the library's one runner of authored lines ({@code CommandRunner}), its placeholders filled from the
 * notice ({@link PartyBoardAnnounce#PLACEHOLDERS}), each made safe first, so a player's name never adds a word to a
 * line. The two are separate switches: an owner who wants only their own lines turns {@code Message} off.
 */
public final class PartyBoardAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, PartyBoardAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/PartyBoard";

    /** The id of the record every board reads (the file is {@code Default.json}; ids fold lower-case). */
    public static final String SHARED_ID = "default";

    /** How long a notice stays up, in minutes, unless the record says otherwise. */
    public static final int DEFAULT_MINUTES = 30;

    /** The longest a notice may be kept up, in minutes: a day. */
    public static final int MAX_MINUTES = 24 * 60;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Announce announce;
    @Nullable protected Notices notices;

    public static final AssetBuilderCodec<String, PartyBoardAsset> CODEC = AssetBuilderCodec.builder(
                    PartyBoardAsset.class,
                    PartyBoardAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Announce", Announce.CODEC, false),
                    (a, v) -> a.announce = v, a -> a.announce, (a, p) -> a.announce = p.announce)
            .documentation("What happens when a player puts up a notice with its Announce box ticked (it starts "
                    + "unticked, and an unticked notice only shows on the board).").add()
            .appendInherited(new KeyedCodec<>("Notices", Notices.CODEC, false),
                    (a, v) -> a.notices = v, a -> a.notices, (a, p) -> a.notices = p.notices)
            .documentation("How long a notice stays up. Notices are kept in memory only: none is kept over a "
                    + "restart.").add()
            .build();

    public PartyBoardAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** Does an announced notice send every player online the board's own line? */
    public boolean announceMessage() {
        return announce == null || announce.message == null || announce.message;
    }

    /** The owner's announce lines, in the order written, blanks left out. */
    @Nonnull
    public List<String> announceCommands() {
        String[] lines = announce == null ? null : announce.commands;
        List<String> out = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                if (line != null && !line.isBlank()) {
                    out.add(line.trim());
                }
            }
        }
        return out;
    }

    /** Every announce line as written, blanks included, for the audit. */
    @Nonnull
    public List<String> announceCommandsAsWritten() {
        String[] lines = announce == null ? null : announce.commands;
        List<String> out = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                out.add(line == null ? "" : line);
            }
        }
        return out;
    }

    /** The minutes as written, or null when unauthored, for the audit. */
    @Nullable
    public Integer minutesAsWritten() {
        return notices == null ? null : notices.minutes;
    }

    /** How long a notice stays up, in minutes, held to [1, {@value #MAX_MINUTES}]. */
    public int minutes() {
        Integer minutes = minutesAsWritten();
        return minutes == null ? DEFAULT_MINUTES : Math.max(1, Math.min(MAX_MINUTES, minutes));
    }

    /** The Announce group. */
    public static final class Announce {

        @Nullable protected Boolean message;
        @Nullable protected String[] commands;

        public static final BuilderCodec<Announce> CODEC = BuilderCodec.builder(Announce.class, Announce::new)
                .appendInherited(new KeyedCodec<>("Message", Codec.BOOLEAN, false),
                        (o, v) -> o.message = v, o -> o.message, (o, p) -> o.message = p.message)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether every player online reads the board's own line about the notice, in their "
                        + "own language: who wants how many more, for what, and where to answer. Unauthored means "
                        + "true.").add()
                .appendInherited(new KeyedCodec<>("Commands", Codec.STRING_ARRAY, false),
                        (o, v) -> o.commands = v, o -> o.commands, (o, p) -> o.commands = p.commands)
                .documentation("Command lines run as the server console, in order, for an announced notice. "
                        + "Placeholders: {player} (the poster's login name), {uuid}, {wants} (how many more, 1 to "
                        + "4), {dungeon} and {tier} (ids, empty when the notice names none), {dungeon_name}, {for} "
                        + "and {place} (words in the server's language), and {notice} (the notice as the board "
                        + "reads it). A name that is not a plain login name runs no line. Under Parent this list "
                        + "replaces the parent's whole.").add()
                .build();

        public Announce() {
        }
    }

    /** The Notices group. */
    public static final class Notices {

        @Nullable protected Integer minutes;

        public static final BuilderCodec<Notices> CODEC = BuilderCodec.builder(Notices.class, Notices::new)
                .appendInherited(new KeyedCodec<>("Minutes", Codec.INTEGER, false),
                        (o, v) -> o.minutes = v, o -> o.minutes, (o, p) -> o.minutes = p.minutes)
                .metadata(EditorSchema.defaultValue(DEFAULT_MINUTES))
                .documentation("How many minutes a notice stays up before it comes down on its own, 1 to 1440. 30 "
                        + "when unauthored.").add()
                .build();

        public Notices() {
        }
    }
}
