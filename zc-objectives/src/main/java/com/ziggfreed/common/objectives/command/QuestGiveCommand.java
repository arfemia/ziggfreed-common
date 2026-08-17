package com.ziggfreed.common.objectives.command;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.FlagArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * {@code quest give}: start a quest for a player whether or not they qualify - a scripted start, a
 * tester skipping ahead, an administrator handing something out.
 *
 * <p>It is the engine's {@link QuestEngine#accept} with the eligibility check deliberately left out,
 * which is what that method documents itself as being for. The one thing it still refuses is a
 * repeatable whose own repeat rules say no, because that is the quest's rule about itself rather than
 * about the player.
 *
 * <p>{@code --everyone} does the same for every player online in every world, each on their own
 * world thread, and reports one count when the last world has answered. It is the one verb here
 * that steps outside the per-player base, so it says so in code rather than by teaching the base
 * about broadcasts nothing else wants.
 */
final class QuestGiveCommand extends TargetPlayerSubCommand {

    private final OptionalArg<String> questArg;
    private final FlagArg everyoneArg;

    QuestGiveCommand() {
        super(ProgressCommandLine.Quest.GROUP, ProgressCommandLine.Quest.GIVE);
        this.questArg = withOptionalArg("quest", ProgressAdminMessages.desc("arg.quest"),
                ArgTypes.STRING);
        this.everyoneArg = withFlagArg("everyone", ProgressAdminMessages.desc("arg.everyone"));
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        if (!everyoneArg.provided(ctx)) {
            return super.executeAsync(ctx);
        }
        Quest quest = ContentArgs.quest(ctx, questArg);
        if (quest == null) {
            return CompletableFuture.completedFuture(null);
        }
        AtomicInteger started = new AtomicInteger();
        List<CompletableFuture<Void>> perWorld = new ArrayList<>();
        for (World world : Universe.get().getWorlds().values()) {
            perWorld.add(runAsync(ctx, () -> started.addAndGet(giveInWorld(world, quest)), world));
        }
        return CompletableFuture.allOf(perWorld.toArray(new CompletableFuture[0])).thenRun(() ->
                ProgressAdminMessages.done(ctx, "quest.given.everyone", quest.id(), started.get()));
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        Quest quest = ContentArgs.quest(ctx, questArg);
        if (quest == null) {
            return;
        }
        Subject subject = questSubjectOf(ctx, target);
        if (subject == null) {
            return;
        }
        if (give(subject, quest)) {
            ProgressAdminMessages.done(ctx, "quest.given", quest.id(), target.name());
        } else {
            ProgressAdminMessages.refused(ctx, "quest.give.refused", quest.id(), target.name());
        }
    }

    /** On {@code world}'s own thread: give the quest to every player standing in it. */
    private static int giveInWorld(@Nonnull World world, @Nonnull Quest quest) {
        int started = 0;
        for (PlayerRef playerRef : world.getPlayerRefs()) {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null || !ref.isValid()) {
                continue;
            }
            Store<EntityStore> store = ref.getStore();
            Subject subject = ProgressionRuntime.subjects().questSubject(store, ref);
            if (subject != null && give(subject, quest)) {
                started++;
            }
        }
        return started;
    }

    /** The one grant, under the registered scope so the owning mod's listeners see it. */
    private static boolean give(@Nonnull Subject subject, @Nonnull Quest quest) {
        QuestEngine engine = ProgressionRuntime.quests();
        return Boolean.TRUE.equals(ProgressionRuntime.questScope().around(subject, s -> {
            boolean accepted = engine.accept(s, quest);
            if (accepted) {
                // A step the player already satisfies is settled at once, so a quest given to
                // somebody past its finish line pays or parks now rather than on their next move.
                engine.checkCompletion(s, quest);
            }
            return Boolean.valueOf(accepted);
        }));
    }
}
