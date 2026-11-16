package com.ziggfreed.common.instance.dungeon.kit;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.instance.dungeon.DungeonMilestoneEvent;
import com.ziggfreed.common.instance.dungeon.DungeonRuns;
import com.ziggfreed.common.util.SafeLog;

/**
 * A room telling its dungeon run that a beat happened: the trigger-volume effect {@code ZigRunMilestone}. It counts
 * once per run for its {@code Id} and placement, raising {@link DungeonMilestoneEvent}, which zc-stats counts for
 * every member, the results screen lists ({@code Room_Cleared}, {@code Puzzle_Solved}, {@code Secret_Found}) and a
 * consumer may pay from.
 *
 * <pre>{@code
 * "Effects": [ { "Type": "ZigRunMilestone", "Event": "SIGNAL_RECEIVED", "Id": "Puzzle_Solved" } ]
 * }</pre>
 *
 * <p>A player stepping in counts for their own run; a volume firing with no player (a signal, a mob, a tick of an
 * empty room) counts for the run of the area it stands in. It keeps counting after the run's clear (or its failure)
 * until the party leaves (R1000), so a skill lock opened after the last boss still raises its event, though the
 * results already shown keep their own numbers. Outside a dungeon run, or once its party has left, it does nothing.
 * It grants nothing itself.
 *
 * <p>Registered into the engine's trigger effect map at setup ({@code InstanceBootstrap.registerRoomKit}), the
 * library's manifest depending on the TriggerVolumes plugin that reads it.
 */
public final class ZigRunMilestoneEffect extends TriggerEffect {

    /** The authored {@code "Type"} name. */
    public static final String TYPE_NAME = "ZigRunMilestone";

    /** Volumes already logged for naming no {@code Id}, by volume id. */
    private static final Set<String> WARNED_VOLUMES = ConcurrentHashMap.newKeySet();

    @Nullable
    private String id;
    @Nullable
    private String placement;

    /** Lazy holder, as an interaction Type's: only registration builds the codec. */
    private static final class Holder {
        static final BuilderCodec<ZigRunMilestoneEffect> CODEC = BuilderCodec.builder(ZigRunMilestoneEffect.class,
                        ZigRunMilestoneEffect::new, TriggerEffect.BASE_CODEC)
                .documentation("Tells a dungeon run that a beat happened (a room cleared, a puzzle solved, a secret "
                        + "found), once per run for each placement. Stats count it for every member, and the run's "
                        + "results list rooms, puzzles and secrets. Outside a dungeon run it does nothing.")
                .append(new KeyedCodec<>("Id", Codec.STRING, false), (e, v) -> e.id = v, e -> e.id)
                .documentation("Which beat: Room_Cleared, Puzzle_Solved or Secret_Found, which the results screen "
                        + "lists, or any id a pack names for its own use. Matched without regard to case. "
                        + "Unauthored, the volume counts nothing.").add()
                .append(new KeyedCodec<>("Placement", Codec.STRING, false),
                        (e, v) -> e.placement = v, e -> e.placement)
                .documentation("What the beat counts once per run under. Unauthored means this volume, so every "
                        + "copy of a room counts on its own; give several volumes of one room the same Placement "
                        + "and they count once between them.").add()
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigRunMilestoneEffect> codec() {
        return Holder.CODEC;
    }

    @Override
    public void execute(@Nonnull TriggerContext context) {
        try {
            if (id == null || id.isBlank()) {
                String volume = RoomKitTrigger.placement(context, null);
                if (WARNED_VOLUMES.add(volume)) {
                    SafeLog.warn("[dungeon] the trigger volume " + volume + " has a " + TYPE_NAME
                            + " effect naming no Id, so it counts nothing");
                }
                return;
            }
            UUID player = RoomKitTrigger.playerOf(context);
            DungeonRuns.LiveRun run = RoomKitTrigger.runOf(context, player);
            if (run != null) {
                DungeonRuns.shared().milestone(run, id, RoomKitTrigger.placement(context, placement), player);
            }
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] a run milestone could not be counted", t);
        }
    }
}
