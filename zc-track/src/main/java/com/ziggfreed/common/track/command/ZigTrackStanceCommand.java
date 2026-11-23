package com.ziggfreed.common.track.command;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.permissions.provider.HytalePermissionsProvider;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.asset.SlotRow;
import com.ziggfreed.common.track.stance.StanceText;
import com.ziggfreed.common.track.stance.StanceView;
import com.ziggfreed.common.track.stance.Stances;
import com.ziggfreed.common.track.stance.TrackStanceAsset;
import com.ziggfreed.common.track.stance.TrackStanceConfig;

/**
 * {@code /zigtrack stance} - a player's faces held in their stances, the library's own surface beside the stance page
 * (M513).
 *
 * <pre>
 * /zigtrack stance list [--player=&lt;name&gt;] [--stance=&lt;id&gt;]               the slots and what each holds
 * /zigtrack stance hold [--player=&lt;name&gt;] --stance=&lt;id&gt; --face=&lt;pick&gt;    hold a face in the lowest open slot
 * /zigtrack stance release [--player=&lt;name&gt;] --stance=&lt;id&gt; --face=&lt;pick&gt; release a held face
 * </pre>
 *
 * <p>Every verb sits in the engine's adventurer group: a player acts on themselves by the stance's own rules, and an
 * operator naming another player acts as an admin ({@link StanceVerbCommand}).
 */
final class ZigTrackStanceCommand extends AbstractCommandCollection {

    /** The family's name under {@code /zigtrack}. */
    static final String NAME = "stance";

    ZigTrackStanceCommand() {
        super(NAME, TrackCommandMessages.desc("stance"));
        setPermissionGroups(HytalePermissionsProvider.GROUP_ADVENTURER);
        addSubCommand(new ListVerb());
        addSubCommand(new HoldVerb());
        addSubCommand(new ReleaseVerb());
    }

    /** {@code list}: each stance's slots, open or closed, what each holds, and the faces owned but not held. */
    static final class ListVerb extends StanceVerbCommand {

        ListVerb() {
            super("list");
        }

        @Override
        protected void run(@Nonnull CommandContext ctx, @Nonnull TrackTarget target, boolean admin) {
            String typed = typedStance(ctx);
            List<TrackStanceAsset> stances = typed == null ? TrackStanceConfig.getInstance().liveStances()
                    : namedOnly(ctx);
            if (stances.isEmpty()) {
                if (typed == null) {
                    TrackCommandMessages.refused(ctx, "stance.none");
                }
                return;
            }
            StanceView view = Stances.view(target.id());
            for (TrackStanceAsset stance : stances) {
                List<String> open = Stances.openSlots(target.id(), stance.getId());
                TrackCommandMessages.done(ctx, "stance.list.head", StanceText.name(stance), target.name(),
                        open.size(), stance.slots().size());
                List<StanceView.HeldFace> held = view.faces(stance.getId());
                for (SlotRow slot : stance.slots()) {
                    String face = null;
                    for (StanceView.HeldFace one : held) {
                        if (one.slotId().equals(slot.key())) {
                            face = one.faceId();
                        }
                    }
                    if (face != null) {
                        TrackCommandMessages.detail(ctx, "stance.list.held", StanceText.slotName(stance, slot.key()),
                                TrackText.pickName(face));
                    } else {
                        TrackCommandMessages.detail(ctx, open.contains(slot.key()) ? "stance.list.empty"
                                : "stance.list.closed", StanceText.slotName(stance, slot.key()));
                    }
                }
            }
        }

        /** The one stance {@code --stance} names, or none after refusing. */
        @Nonnull
        private List<TrackStanceAsset> namedOnly(@Nonnull CommandContext ctx) {
            TrackStanceAsset stance = namedStance(ctx);
            return stance == null ? List.of() : List.of(stance);
        }
    }

    /** {@code hold}: hold a face in the lowest open slot. */
    static final class HoldVerb extends FaceVerb {

        HoldVerb() {
            super("hold");
        }

        @Override
        protected void act(@Nonnull CommandContext ctx, @Nonnull TrackTarget target, @Nonnull TrackStanceAsset stance,
                @Nonnull String face, boolean admin) {
            Stances.Result result = admin ? Stances.holdAsAdmin(target.id(), stance.getId(), face)
                    : Stances.hold(target.id(), stance.getId(), face, null);
            if (result.done()) {
                TrackCommandMessages.done(ctx, "stance.hold.done", StanceText.name(stance), target.name(),
                        TrackText.pickName(face), StanceText.slotName(stance, result.slotId()));
                return;
            }
            TrackCommandMessages.refused(ctx, "stance.hold.refused", StanceText.name(stance), target.name(),
                    TrackText.pickName(face), TrackText.refusal(result.status()));
        }
    }

    /** {@code release}: release a held face, at the stance's price for a player's own. */
    static final class ReleaseVerb extends FaceVerb {

        ReleaseVerb() {
            super("release");
        }

        @Override
        protected void act(@Nonnull CommandContext ctx, @Nonnull TrackTarget target, @Nonnull TrackStanceAsset stance,
                @Nonnull String face, boolean admin) {
            Stances.Result result = admin ? Stances.releaseAsAdmin(target.id(), stance.getId(), face)
                    : Stances.release(target.id(), stance.getId(), face, null);
            if (result.done()) {
                TrackCommandMessages.done(ctx, "stance.release.done", StanceText.name(stance), target.name(),
                        TrackText.pickName(face));
                return;
            }
            TrackCommandMessages.refused(ctx, "stance.release.refused", StanceText.name(stance), target.name(),
                    TrackText.pickName(face), TrackText.refusal(result.status()));
        }
    }

    /** A verb naming one face of one stance. */
    abstract static class FaceVerb extends StanceVerbCommand {

        private final OptionalArg<String> faceArg;

        FaceVerb(@Nonnull String verb) {
            super(verb);
            this.faceArg = withOptionalArg("face", TrackCommandMessages.desc("arg.face"), ArgTypes.STRING);
        }

        @Override
        protected final void run(@Nonnull CommandContext ctx, @Nonnull TrackTarget target, boolean admin) {
            TrackStanceAsset stance = namedStance(ctx);
            if (stance == null) {
                return;
            }
            String face = TrackTargetCommand.typed(ctx, faceArg);
            if (face == null) {
                TrackCommandMessages.refused(ctx, "stance.face.needed");
                return;
            }
            act(ctx, target, stance, face, admin);
        }

        protected abstract void act(@Nonnull CommandContext ctx, @Nonnull TrackTarget target,
                @Nonnull TrackStanceAsset stance, @Nonnull String face, boolean admin);
    }
}
