package com.ziggfreed.common.objectives.command;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.AbstractTargetPlayerCommand;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;

/**
 * The objectives family's fill of the shared target-player walk ({@link
 * AbstractTargetPlayerCommand}): the resolved online player becomes a {@link Target}, the handles a
 * verb needs to ask the shared runtime for a subject later rather than building one here.
 *
 * <p><b>The subject comes from the RUNTIME, never from here.</b> A store reaches a player's
 * persisted progress through whatever handle its own owner attached to the subject, so a subject
 * built by anybody else reads NEUTRAL through it - no status, no progress - and silently drops every
 * write. {@link Target#questSubject()} and {@link Target#achievementSubject()} ask
 * {@link ProgressionRuntime#subjects()}, so a verb here always speaks to whichever stores are
 * registered, on a bare server and on one where a consumer mod owns them alike.
 */
abstract class TargetPlayerSubCommand extends AbstractTargetPlayerCommand<TargetPlayerSubCommand.Target> {

    /** The player a verb was pointed at, resolved on their own world thread. */
    record Target(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                  @Nonnull PlayerRef playerRef) {

        /** The name the answers speak, which is DATA and never translated. */
        @Nonnull
        String name() {
            return playerRef.getUsername();
        }

        /** The subject the ACTIVE quest store understands, or null when there is nothing to build one from. */
        @Nullable
        Subject questSubject() {
            return ProgressionRuntime.subjects().questSubject(store, ref);
        }

        /** The subject the ACTIVE achievement store understands, or null on the same terms. */
        @Nullable
        Subject achievementSubject() {
            return ProgressionRuntime.subjects().achievementSubject(store, ref);
        }
    }

    TargetPlayerSubCommand(@Nonnull String group, @Nonnull String verb) {
        super(verb, ProgressAdminMessages.desc(group + "." + verb),
                ProgressAdminMessages.desc("arg.player"), ProgressAdminMessages::refused);
    }

    @Override
    @Nonnull
    protected Target buildTarget(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef) {
        return new Target(store, ref, playerRef);
    }

    /**
     * The target's quest subject, or null after telling the sender that this player has no
     * progress record the active store can read.
     */
    @Nullable
    protected static Subject questSubjectOf(@Nonnull CommandContext ctx, @Nonnull Target target) {
        Subject subject = target.questSubject();
        if (subject == null) {
            ProgressAdminMessages.refused(ctx, "player.no_record", target.name());
        }
        return subject;
    }

    /** The achievement twin of {@link #questSubjectOf}. */
    @Nullable
    protected static Subject achievementSubjectOf(@Nonnull CommandContext ctx, @Nonnull Target target) {
        Subject subject = target.achievementSubject();
        if (subject == null) {
            ProgressAdminMessages.refused(ctx, "player.no_record", target.name());
        }
        return subject;
    }
}
