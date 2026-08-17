package com.ziggfreed.common.objectives.command;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.ziggfreed.common.dialogue.DialogueMemories;

/**
 * {@code memories forget}: forget everything every conversation remembers about a player, without
 * touching quest state - every first-visit beat they have played through, every named memory a
 * conversation set for them, and every one-shot gift they have already taken. Lets a tester re-run
 * a dialogue in isolation.
 *
 * <p>Both lifetimes go, session and persistent alike, because somebody asking to start the
 * conversations over means all of them. It lives in THIS family because the persistent half of what
 * conversations remember rides the same progress component the quest and achievement stores use, so
 * the one place an administrator resets a player's progression is where they reset this too. It is
 * deliberately not folded into {@code quest reset --quest=all}: a greeting a character remembers
 * giving is not quest progress, and wiping it because an administrator reset quests would be
 * answering a question nobody asked.
 */
final class MemoriesForgetCommand extends TargetPlayerSubCommand {

    MemoriesForgetCommand() {
        super(ProgressCommandLine.Memories.GROUP, ProgressCommandLine.Memories.FORGET);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull Target target) {
        // The shared dialogue engine owns both backends and marks the persistent half dirty itself
        // through the store's own view, so nothing here has anything of its own to report.
        DialogueMemories.forgetAll(target.store(), target.ref());
        ProgressAdminMessages.done(ctx, "memories.forgot", target.name());
    }
}
