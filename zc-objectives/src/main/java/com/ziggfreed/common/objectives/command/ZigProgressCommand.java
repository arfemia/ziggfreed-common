package com.ziggfreed.common.objectives.command;

import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;

/**
 * {@code /zigprogress} - the admin surface for the shared progression runtime: what is catalogued,
 * where one player stands, and the few things an administrator puts right by hand.
 *
 * <h2>The verbs</h2>
 *
 * <pre>
 * /zigprogress reload                                                publish the shared quest and achievement assets again
 * /zigprogress quest list [--tag=&lt;tag&gt;]                              the merged quest catalogue
 * /zigprogress quest give --quest=&lt;id&gt; [--player=&lt;name&gt;|--everyone]   start a quest, whether or not they qualify
 * /zigprogress quest reset --quest=&lt;id|all&gt; [--player=&lt;name&gt;]         wipe one quest, or every quest, record included
 * /zigprogress quest complete --quest=&lt;id&gt; [--player=&lt;name&gt;]          close a quest out and pay it
 * /zigprogress quest status [--quest=&lt;id&gt;] [--player=&lt;name&gt;]          where every quest stands, with its steps
 * /zigprogress quest accept --quest=&lt;id&gt; [--player=&lt;name&gt;]            take a quest on, if they qualify
 * /zigprogress quest claim --quest=&lt;id|all&gt; [--player=&lt;name&gt;]         collect a finished quest's rewards
 * /zigprogress quest abandon --quest=&lt;id&gt; [--player=&lt;name&gt;]           give a carried quest up
 * /zigprogress achievement list [--tag=&lt;tag&gt;]                        the merged achievement catalogue
 * /zigprogress achievement status [--achievement=&lt;id&gt;] [--player=&lt;name&gt;] where every achievement stands, and the points
 * /zigprogress achievement give --achievement=&lt;id&gt; [--player=&lt;name&gt;]  earn one, whether or not its criteria are met
 * /zigprogress achievement claim --achievement=&lt;id|all&gt; [--player=&lt;name&gt;] collect an earned achievement's waiting rewards
 * /zigprogress achievement reset --achievement=&lt;id|all&gt; [--player=&lt;name&gt;] wipe one achievement, or the whole record
 * /zigprogress memories forget [--player=&lt;name&gt;]                     forget everything every conversation remembers
 * </pre>
 *
 * <p><b>The two groups conjugate the same way on purpose:</b> {@code give} is the force-it verb on
 * both (start it / earn it, whatever the gates say), {@code claim} collects what waits on both,
 * {@code reset} takes {@code <id|all>} on both, and {@code status} takes the same one-id filter on
 * both. A verb that exists on one side only names a lifecycle the other side does not have
 * ({@code accept}/{@code abandon}/{@code complete} - achievements are always on).
 *
 * <p>Arguments bind by NAME, never by position - that is the engine's parser, not a house style. A
 * verb that acts on a player defaults to the sender when {@code --player} is left out, and needs
 * the player ONLINE: progress lives on their own entity, so an offline edit has nowhere to land.
 *
 * <h2>Permissions</h2>
 *
 * <p>There is no permission check written anywhere in this family, and that is the point: the engine
 * derives one node per command from the plugin and the command name, registers it, and refuses the
 * call before a body runs. The nodes are {@code ziggfreed.ziggfreedcommon.command.zigprogress} for
 * the family, {@code ...zigprogress.<group>} for a group and {@code ...zigprogress.<group>.<verb>}
 * for each verb; a verb needs all of its ancestors, and nobody holds any of them until a server
 * grants it. The console holds everything, which is what makes this usable from a startup script
 * and from a wrapper with no permissions plugin installed at all. Node depth follows nesting:
 * {@code reload}, the one family-level verb (it republishes BOTH catalogues), sits directly under
 * the family, and every group verb sits under its group.
 *
 * <p>One permission question, one answer: a second check inside these bodies would be a second
 * vocabulary a server owner has to discover, and the first one to drift.
 *
 * <h2>Why this family belongs to the library</h2>
 *
 * <p>The module that owns an engine owns the commands that drive it. Everything here reads or writes
 * through THE shared runtime - the one quest engine, the one achievement engine, the subject the
 * registered stores understand, the registered call scope around every mutating call - so a
 * consumer mod wanting {@code /myquests} registers an alias that calls straight through, rather
 * than a second implementation that can disagree with this one. What a verb prints is the runtime's
 * own vocabulary: ids, counts, and the statuses the engines answer in.
 *
 * <p>Two things are deliberately NOT here, because the runtime does not own them. Content only a
 * consumer folds is that consumer's to reload, so {@link ProgressReloadCommand} publishes the SHARED
 * assets and says so. And a reward owed to somebody who is offline needs a spool keyed by an
 * identity the runtime does not keep, so {@code quest complete} needs the player online, like every
 * other per-player verb.
 */
public final class ZigProgressCommand extends AbstractCommandCollection {

    public ZigProgressCommand() {
        super(ProgressCommandLine.FAMILY, ProgressAdminMessages.desc("family"));
        addSubCommand(new ProgressReloadCommand());
        addSubCommand(new QuestGroup());
        addSubCommand(new AchievementGroup());
        addSubCommand(new MemoriesGroup());
    }

    /** {@code /zigprogress quest ...} - the one place the quest verbs are listed. */
    private static final class QuestGroup extends AbstractCommandCollection {

        QuestGroup() {
            super(ProgressCommandLine.Quest.GROUP,
                    ProgressAdminMessages.desc(ProgressCommandLine.Quest.GROUP));
            addSubCommand(new QuestListCommand());
            addSubCommand(new QuestGiveCommand());
            addSubCommand(new QuestResetCommand());
            addSubCommand(new QuestCompleteCommand());
            addSubCommand(new QuestStatusCommand());
            addSubCommand(new QuestLogCommand(QuestLogCommand.Move.ACCEPT));
            addSubCommand(new QuestLogCommand(QuestLogCommand.Move.CLAIM));
            addSubCommand(new QuestLogCommand(QuestLogCommand.Move.ABANDON));
        }
    }

    /** {@code /zigprogress achievement ...} - the one place the achievement verbs are listed. */
    private static final class AchievementGroup extends AbstractCommandCollection {

        AchievementGroup() {
            super(ProgressCommandLine.Achievement.GROUP,
                    ProgressAdminMessages.desc(ProgressCommandLine.Achievement.GROUP));
            addSubCommand(new AchievementListCommand());
            addSubCommand(new AchievementStatusCommand());
            addSubCommand(new AchievementGiveCommand());
            addSubCommand(new AchievementClaimCommand());
            addSubCommand(new AchievementResetCommand());
        }
    }

    /** {@code /zigprogress memories ...} - what conversations remember, and how to make them forget. */
    private static final class MemoriesGroup extends AbstractCommandCollection {

        MemoriesGroup() {
            super(ProgressCommandLine.Memories.GROUP,
                    ProgressAdminMessages.desc(ProgressCommandLine.Memories.GROUP));
            addSubCommand(new MemoriesForgetCommand());
        }
    }
}
