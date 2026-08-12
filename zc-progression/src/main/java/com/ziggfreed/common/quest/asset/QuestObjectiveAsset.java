package com.ziggfreed.common.quest.asset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.asset.ObjectiveLeafAsset;

/**
 * One entry of a quest's {@code Objectives} map: the shared objective leaves
 * ({@link ObjectiveLeafAsset} - what counts, which one, how many, where, what the player reads) plus
 * the two a quest adds, since only a quest has an order to run its steps in and a place to hand
 * things in at.
 *
 * <p>The map KEY is the objective id, so this record carries only the objective itself:
 * <pre>{@code
 * "Objectives": {
 *   "collect": { "Kind": "PICKUP_ITEM", "Target": "Copper_Ore", "MatchMode": "EXACT", "Amount": 10 },
 *   "hand_in": { "Kind": "TURN_IN", "Target": "Copper_Ore", "Amount": 10, "Order": 2,
 *                "TurnInNpcId": "giver" } }
 * }</pre>
 *
 * <p>Every leaf is {@code appendInherited} and the map merges per key, so a quest inheriting from a
 * {@code Parent} can retune ONE objective's {@code Amount} and keep every sibling objective, and
 * every sibling leaf of the one it touched, exactly as the parent authored them.
 *
 * <p>An id the map does not already carry is ADDED, which is how a child adds a step to an
 * inherited quest. There is no way to remove an inherited objective; author the shared part of the
 * quest as a parent that carries only what every child wants.
 */
public final class QuestObjectiveAsset extends ObjectiveLeafAsset {

    /** The turn-in id that means "wherever this quest came from" - see {@link #getTurnInNpcId()}. */
    public static final String GIVER_SENTINEL = "giver";

    /** The reserved kind id that makes a step a hand-in, matching the engine's own reading. */
    public static final String HAND_IN_KIND = "TURN_IN";

    @Nullable protected Integer order;
    @Nullable protected String turnInNpcId;

    public static final BuilderCodec<QuestObjectiveAsset> CODEC =
            appendLeaves(BuilderCodec.builder(QuestObjectiveAsset.class, QuestObjectiveAsset::new))
                    .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                            (o, v) -> o.order = v, o -> o.order, (o, p) -> o.order = p.order)
                    .documentation("Sequencing group. An objective unlocks once every objective with a strictly "
                            + "lower non-zero Order is done; equal numbers run side by side. 0 or unauthored means "
                            + "no constraint, and a quest that authors none of them can still use Flow.Sequential.").add()
                    .appendInherited(new KeyedCodec<>("TurnInNpcId", Codec.STRING, false),
                            (o, v) -> o.turnInNpcId = v, o -> o.turnInNpcId, (o, p) -> o.turnInNpcId = p.turnInNpcId)
                    .documentation("For a TURN_IN step: the one place it may be handed in at. The literal 'giver' "
                            + "means the quest's own Npc.ViewId, so a moved quest giver needs no objective edit. "
                            + "Unauthored means any hand-in surface will do.").add()
                    .build();

    public QuestObjectiveAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static QuestObjectiveAsset of(@Nullable String kind, @Nullable String target, @Nullable Long amount) {
        QuestObjectiveAsset o = new QuestObjectiveAsset();
        o.kind = kind;
        o.target = target;
        o.amount = amount;
        return o;
    }

    @Nullable
    public Integer getOrder() {
        return order;
    }

    /**
     * The one hand-in surface this step accepts, or null for any. The value {@code "giver"}
     * (case-insensitive) is a SENTINEL resolved against the quest's own {@code Npc.ViewId} when the
     * definition is folded, so it is still {@code "giver"} here.
     */
    @Nullable
    public String getTurnInNpcId() {
        return turnInNpcId;
    }

    /** Is {@code turnInNpcId} the "wherever this quest came from" sentinel? */
    public boolean turnsInAtGiver() {
        return turnInNpcId != null && turnInNpcId.trim().equalsIgnoreCase(GIVER_SENTINEL);
    }

    /**
     * Is this a hand-in step? It is decided by the reserved kind id, the same way the engine decides
     * it, so the two can never disagree about which steps a hand-in surface applies to.
     */
    public boolean isHandIn() {
        return kind != null && HAND_IN_KIND.equalsIgnoreCase(kind.trim());
    }

    /** As {@link #toDef(String, String, String)} with no quest-level hand-in surface to fall back on. */
    @Nonnull
    public ObjectiveDef toDef(@Nonnull String objectiveId, @Nullable String giverId) {
        return toDef(objectiveId, giverId, null);
    }

    /**
     * Build the engine's objective under {@code objectiveId}, resolving where it may be handed in:
     * <ul>
     *   <li>no {@code TurnInNpcId} authored, on a hand-in step - the quest's own {@code Npc.TurnInId}
     *       ({@code questTurnInId}), so the quest says it once. A step of any other kind is left
     *       alone: the place a quest is handed in says nothing about where a block is broken;</li>
     *   <li>{@code "giver"} - {@code giverId}, the quest's {@code Npc.ViewId} (a null giver leaves
     *       the step open to any surface, which is the honest reading of "hand it back where you got
     *       it" when nobody gave it to you);</li>
     *   <li>an empty string - anywhere, the way one step opts out of a quest-level hand-in;</li>
     *   <li>anything else - that id.</li>
     * </ul>
     */
    @Nonnull
    public ObjectiveDef toDef(@Nonnull String objectiveId, @Nullable String giverId,
            @Nullable String questTurnInId) {
        String lock;
        if (turnInNpcId == null) {
            lock = isHandIn() ? questTurnInId : null;
        } else if (turnsInAtGiver()) {
            lock = giverId;
        } else {
            lock = turnInNpcId;
        }
        return toDefBuilder(objectiveId)
                .order(order == null ? 0 : order)
                .turnInLockId(lock)
                .build();
    }
}
