package com.ziggfreed.common.instance.dungeon.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zigdungeon} - the admin surface over this server's dungeons and their open copies.
 *
 * <pre>
 * /zigdungeon list            every dungeon and how it is set up
 * /zigdungeon status          every copy open now: its areas, its players, its tick time
 * /zigdungeon close &lt;copy&gt;    close a copy now; everyone inside goes back where they came in
 * /zigdungeon enter &lt;id&gt;      go into a dungeon yourself, in an area of your own
 * /zigdungeon layout &lt;id&gt; &lt;seed&gt;   the rooms a generated dungeon's plan makes from one seed
 * </pre>
 *
 * <p>No permission check is written here: the engine derives one node per verb
 * ({@code ziggfreed.ziggfreedcommon.command.zigdungeon.list}, ...) that nobody holds until a server grants
 * it, and the console holds everything.
 */
public final class ZigDungeonCommand extends AbstractCommandCollection {

    /** The family's name. */
    public static final String FAMILY = "zigdungeon";

    static final String LIST = "list";
    static final String STATUS = "status";
    static final String CLOSE = "close";
    static final String ENTER = "enter";
    static final String LAYOUT = "layout";

    /** {@code close}'s one argument: the copy's world name. */
    static final String ARG_COPY = "copy";

    /** {@code enter}'s and {@code layout}'s first argument: the dungeon's id. */
    static final String ARG_DUNGEON = "dungeon";

    /** {@code layout}'s second argument: the run seed, a whole number. */
    static final String ARG_SEED = "seed";

    public ZigDungeonCommand() {
        super(FAMILY, DungeonCommandMessages.desc("family"));
        addSubCommand(new DungeonListCommand());
        addSubCommand(new DungeonStatusCommand());
        addSubCommand(new DungeonCloseCommand());
        addSubCommand(new DungeonEnterCommand());
        addSubCommand(new DungeonLayoutCommand());
    }
}
