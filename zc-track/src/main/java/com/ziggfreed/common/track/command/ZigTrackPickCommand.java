package com.ziggfreed.common.track.command;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.pick.PickService;

/**
 * {@code pick [--player=<name>] --track=<id> (--option=<id> [--level=<n>] | --pick=<id>)}: make a pick for an online
 * player as the track's own page would ({@code PickService}): with {@code --option}, choose it at the lowest
 * waiting slot that offers it (or the slot at {@code --level}); with {@code --pick} alone, take that pick bought
 * outright. Every rule applies (the slot open, the option offered, Requires, prerequisites, points, the spend cap),
 * the grants are rebuilt, and the pick can be undone ({@code undo}). Left out, the sender is the player.
 */
final class ZigTrackPickCommand extends TrackTargetCommand {

    private final OptionalArg<String> optionArg;
    private final OptionalArg<String> pickArg;
    private final OptionalArg<Integer> levelArg;

    ZigTrackPickCommand() {
        super(ZigTrackCommand.PICK, false);
        this.optionArg = withOptionalArg("option", TrackCommandMessages.desc("arg.option"), ArgTypes.STRING);
        this.pickArg = withOptionalArg("pick", TrackCommandMessages.desc("arg.pick"), ArgTypes.STRING);
        this.levelArg = withOptionalArg("level", TrackCommandMessages.desc("arg.level"), ArgTypes.INTEGER);
    }

    @Override
    protected void execute(@Nonnull CommandContext ctx, @Nonnull TrackTarget target) {
        TrackDef def = namedTrack(ctx);
        if (def == null) {
            return;
        }
        String option = typed(ctx, optionArg);
        String pick = typed(ctx, pickArg);
        if (option == null && pick == null) {
            TrackCommandMessages.refused(ctx, "pick.needed");
            return;
        }
        PickService.Result result;
        if (option != null) {
            Integer level = levelArg.provided(ctx) ? levelArg.get(ctx) : null;
            String slot = slotFor(target, def, option, pick, level);
            if (slot == null) {
                TrackCommandMessages.refused(ctx, "pick.not_offered", TrackText.name(def.asset()), target.name(),
                        TrackText.pickName(option));
                return;
            }
            result = PickService.choose(target.id(), def.id(), slot, List.of(option), null);
        } else {
            result = PickService.take(target.id(), def.id(), pick, null);
        }
        String named = option != null ? option : pick;
        if (result.done()) {
            TrackCommandMessages.done(ctx, "pick.done", TrackText.name(def.asset()), target.name(),
                    TrackText.pickName(named));
            return;
        }
        TrackCommandMessages.refused(ctx, "pick." + TrackKey.normalize(result.status().name()),
                TrackText.name(def.asset()), target.name(), TrackText.pickName(named));
    }

    /**
     * The waiting slot to choose {@code option} at: the one at {@code level} (and of {@code pick}, when named), else
     * the lowest whose offer holds it; null when no waiting slot offers it.
     */
    @Nullable
    private static String slotFor(@Nonnull TrackTarget target, @Nonnull TrackDef def, @Nonnull String option,
            @Nullable String pick, @Nullable Integer level) {
        String wanted = TrackKey.normalize(option);
        String pickId = pick == null ? null : TrackKey.normalize(pick);
        for (PickService.Owed owed : PickService.waiting(target.id(), def.id()).owed()) {
            if (!owed.isChoice() || (level != null && owed.level() != level)
                    || (pickId != null && !owed.pickId().equals(pickId))) {
                continue;
            }
            if (owed.offer().contains(wanted)) {
                return owed.slot();
            }
        }
        return null;
    }

    @Nullable
    private static String typed(@Nonnull CommandContext ctx, @Nonnull OptionalArg<String> arg) {
        String value = arg.provided(ctx) ? arg.get(ctx) : null;
        return value == null || value.isBlank() ? null : value.trim();
    }
}
