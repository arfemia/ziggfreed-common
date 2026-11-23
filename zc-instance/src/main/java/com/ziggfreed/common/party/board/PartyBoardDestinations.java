package com.ziggfreed.common.party.board;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;

/**
 * The {@code PartyBoard} destination: {@code "Open": {"Type": "PartyBoard", "Dungeon": "<id>", "Place": "<lang key>"}}
 * opens the party board ({@link PartyBoardPage}), from a block's {@code ZigOpen}, a placed character's press-F or a
 * conversation's answer. {@code Dungeon} keeps the board for one dungeon (its notices only, and the depths and daily of
 * that dungeon to post for); with none it shows every notice and offers only "anything". {@code Place} names where the
 * board stands, which an announcement says ("Answer at the Party Board in the Dungeon Hall").
 */
public final class PartyBoardDestinations {

    public static final String OWNER = "ziggfreedcommon";
    public static final String TYPE = "PartyBoard";

    private PartyBoardDestinations() {
    }

    /** Seed the type, at setup, before any asset decodes. */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(TYPE, Board.class, Board.CODEC, PartyBoardDestinations::open));
    }

    /** Open the board on the player's own ref. */
    private static boolean open(@Nonnull Board destination, @Nonnull DestinationContext ctx) {
        PlayerRef viewer = ctx.playerRef();
        if (viewer == null) {
            return false;
        }
        ctx.player().getPageManager().openCustomPage(ctx.playerReference(), ctx.store(),
                new PartyBoardPage(viewer, destination.getDungeon(), destination.getPlace()));
        return true;
    }

    /** A party board. */
    public static final class Board extends Destination {

        @Nullable protected String dungeon;
        @Nullable protected String place;

        public static final BuilderCodec<Board> CODEC = BuilderCodec.builder(Board.class, Board::new)
                .append(new KeyedCodec<>("Dungeon", Codec.STRING, false),
                        (d, v) -> d.dungeon = v, d -> d.dungeon)
                .documentation("The dungeon this board is kept for, by its id (the file name under "
                        + "Server/ZiggfreedCommon/Dungeons/): it shows that dungeon's notices only, and a poster "
                        + "picks one of its depths they have opened, its daily, or anything. With none, the board "
                        + "shows every notice and a poster puts one up for anything.").add()
                .append(new KeyedCodec<>("Place", Codec.STRING, false),
                        (d, v) -> d.place = v, d -> d.place)
                .documentation("Where the board stands, a lang key (the name on its map marker, say): an announced "
                        + "notice tells everyone online to answer at the Party Board there. With none it says only "
                        + "to answer at the Party Board.").add()
                .build();

        public Board() {
        }

        /** Java-side construction. */
        @Nonnull
        public static Board of(@Nullable String dungeonId, @Nullable String placeKey) {
            Board b = new Board();
            b.dungeon = dungeonId;
            b.place = placeKey;
            return b;
        }

        /** The dungeon id, trimmed, or null when none is written. */
        @Nullable
        public String getDungeon() {
            return dungeon == null || dungeon.isBlank() ? null : dungeon.trim();
        }

        /** The place's lang key, trimmed, or null when none is written. */
        @Nullable
        public String getPlace() {
            return place == null || place.isBlank() ? null : place.trim();
        }
    }
}
