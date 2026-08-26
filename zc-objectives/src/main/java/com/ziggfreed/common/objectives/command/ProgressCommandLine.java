package com.ziggfreed.common.objectives.command;

/**
 * What this family is CALLED: the family, its three groups, and every verb under each.
 *
 * <p>A leaf on purpose: it imports nothing, so anything that needs to spell one of these names - a
 * consumer's alias, a help line, a test - can name it without dragging a command implementation
 * into the layer below. Each name has exactly one owner, which is the command registered under it,
 * and every producer of that name asks here rather than spelling a second copy that drifts.
 *
 * <p><b>The groups are nested collections, and the nesting is what makes the family readable.</b>
 * {@code /zigprogress quest reset} and {@code /zigprogress achievement reset} are two different
 * things with the same verb, and a flat family would have had to invent {@code questreset} and
 * {@code achievementreset} to keep them apart. A verb's help key and its permission node both carry
 * the group for the same reason: {@code desc.quest.reset} and
 * {@code ...zigprogress.quest.reset}.
 *
 * <p><b>The named-arg form is not a style choice.</b> The engine's parser binds arguments by NAME,
 * so a positional line silently binds nothing.
 */
public final class ProgressCommandLine {

    /** The command family every progression admin verb hangs off. */
    public static final String FAMILY = "zigprogress";

    /** Publish the shared quest and achievement assets again. */
    public static final String RELOAD = "reload";

    /** The one word {@code --quest} accepts in place of an id where a verb takes "every quest". */
    public static final String ALL = "all";

    /** The quest group: the catalogue, one player's log, and the admin moves on it. */
    public static final class Quest {

        /** The group's own name, which is also the segment its help keys and nodes carry. */
        public static final String GROUP = "quest";

        /** List the shared catalogue. */
        public static final String LIST = "list";

        /** Start a quest for a player whether or not they qualify. */
        public static final String GIVE = "give";

        /** Wipe a player's record of one quest, or of every quest. */
        public static final String RESET = "reset";

        /** Close a quest out for a player and pay it. */
        public static final String COMPLETE = "complete";

        /** Where every quest stands for one player. */
        public static final String STATUS = "status";

        /** Take a quest on, if the player qualifies. */
        public static final String ACCEPT = "accept";

        /** Collect a finished quest's rewards. */
        public static final String CLAIM = "claim";

        /** Give up a quest the player is carrying. */
        public static final String ABANDON = "abandon";

        private Quest() {
        }
    }

    /** The achievement group: the catalogue, one player's record, and the admin moves on it. */
    public static final class Achievement {

        /** The group's own name, which is also the segment its help keys and nodes carry. */
        public static final String GROUP = "achievement";

        /** List the shared catalogue. */
        public static final String LIST = "list";

        /** Where every achievement stands for one player. */
        public static final String STATUS = "status";

        /** Earn an achievement for a player whether or not its criteria are met. */
        public static final String GIVE = "give";

        /** Collect an earned achievement's waiting rewards. */
        public static final String CLAIM = "claim";

        /** Wipe a player's record of one achievement, or of every achievement. */
        public static final String RESET = "reset";

        private Achievement() {
        }
    }

    /** The memory group: what conversations remember about a player. */
    public static final class Memory {

        /** The group's own name, which is also the segment its help keys and nodes carry. */
        public static final String GROUP = "memory";

        /** Forget everything every conversation remembers about a player. */
        public static final String FORGET = "forget";

        private Memory() {
        }
    }

    private ProgressCommandLine() {
    }
}
