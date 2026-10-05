package com.ziggfreed.common.reward;

import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.effect.costume.CostumeMessages;
import com.ziggfreed.common.effect.costume.Costumes;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.reward.RewardGrants;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The reward kind that puts a costume on the player it pays:
 *
 * <pre>{@code
 * { "Kind": "Costume", "Params": { "Costume": "Yourpack_Costume_Ghost", "FallbackLootable": "Yourpack_Treats" } }
 * }</pre>
 *
 * <p>The costume is an effect id ({@code Effect} is read when {@code Costume} is absent), and it goes on by
 * the costume's own rules ({@link Costumes#dress}): it changes the model and is no debuff, so the wearer can
 * always take it off with {@code /zigcostume off}, and the wearer is told so the moment it lands.
 *
 * <p>It lives in the wiring root beside {@link EffectRewardKind} for the same reason: the loot layer sits
 * under everything that pays out and must never see the effect module, so the layer that sees both
 * registers the kind.
 *
 * <p><b>It goes on from the wearer's world's task queue, never during the payout.</b> A costume writes the
 * wearer's model through the store, the store refuses a write inside a system's tick, and a payout can run
 * inside one (a quest completing in a producer, an interaction chain rolling a table). The queue runs on
 * the world thread outside every tick, so the costume goes on the same way whatever paid it. The payout
 * therefore reports it paid before it lands, with nothing on the receipt: the wearer is told when it does.
 *
 * <p><b>A costume that does not go on pays its fallback instead.</b> Somebody still there but already in
 * another costume or in this very one, in a form they cannot take off, or offered an id that is no costume
 * or not loaded: the loot table {@code FallbackLootable} names is rolled for the same player, through the
 * vocabulary this kind was registered into, labelled with the same source. With no fallback, or one that
 * cannot be rolled (no table answers to it, or it is empty), the reward is lost with one warn line, and
 * nothing is thrown into the world's task queue; a roll that lands nothing is the table's own quiet
 * business, so a fallback table should always pay. A wearer gone by the time the queue runs (left, or moved
 * to another world) is lost the same way, with no fallback: a treat needs the same live player. There is
 * no replay either way: a costume put on at a later connect would be a trick out of nowhere, so the retry
 * command stays absent.
 *
 * <p><b>A fallback never rolls a fallback.</b> The fallback's roll carries a mark on its subject, which every
 * reward the roll pays receives, and a costume paid with that mark rolls no fallback of its own. A table
 * that pays a costume back (itself, or two tables naming each other) therefore settles after one more
 * dressing, where it would otherwise re-queue for ever: the world drains its task queue in one pass.
 *
 * <p>What fails at the payout fails at once and counts as lost: a reward that names no costume, and nobody
 * live to dress or no world to queue on (a treat needs the same live player). Inside an interaction chain,
 * the chain's own {@code ZigCostume} Type still dresses through the chain's command buffer and can branch
 * on a miss.
 */
public final class CostumeRewardKind implements RewardHandler {

    /** The kind id content writes. */
    public static final String KIND = "Costume";

    /** Who this registration is attributed to in the registry ledger. */
    public static final String OWNER = "ziggfreedcommon";

    /** The parameter naming the loot table rolled for the same player when the costume does not go on. */
    public static final String P_FALLBACK_LOOTABLE = "FallbackLootable";

    /**
     * The mark a fallback roll's subject carries ({@link Subject#withFacets}): every reward the roll pays
     * receives that subject, and a costume paid with the mark rolls no fallback of its own. A marker, never
     * a mode: its one value only says "inside a fallback roll".
     */
    private enum FallbackRoll {
        MARK
    }

    /**
     * Puts a costume on a subject's player from that player's world's task queue: the engine in
     * production, a stand-in in a test.
     */
    @FunctionalInterface
    interface Wardrobe {

        /**
         * Queue putting {@code costumeId} on {@code wearer} onto the wearer's own world, and hand
         * {@code then} what it came to, on that world's thread, when the task runs: null when the wearer
         * was gone by then (left, or moved to another world). False when nothing was queued: nobody live
         * to dress, or a world that takes no more tasks.
         */
        boolean queue(@Nonnull Subject wearer, @Nonnull String costumeId,
                @Nonnull Consumer<Costumes.DressOutcome> then);
    }

    private final Wardrobe wardrobe;
    private final RewardKindRegistry kinds;
    private final Consumer<String> warn;

    /**
     * @param wardrobe puts the costume on from the wearer's world's task queue
     * @param kinds    the registry this kind is registered into: the vocabulary a fallback pays through, and
     *                 the ledger a loss found when the queue runs is counted in
     * @param warn     where a loss found when the queue runs is reported
     */
    CostumeRewardKind(@Nonnull Wardrobe wardrobe, @Nonnull RewardKindRegistry kinds,
            @Nonnull Consumer<String> warn) {
        this.wardrobe = wardrobe;
        this.kinds = kinds;
        this.warn = warn;
    }

    /** Register the costume kind into {@code kinds}, whose own {@code Lootable} kind pays a fallback. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND, OWNER, new CostumeRewardKind(CostumeRewardKind::queueOnWorld, kinds, SafeLog::warn));
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
        grant(spec, subject, spec.paramOr(RewardGrants.P_SOURCE, KIND));
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId)
            throws Exception {
        String costumeId = costumeOf(spec);
        if (costumeId.isEmpty()) {
            throw new IllegalStateException("a reward of kind '" + KIND
                    + "' named no costume - it needs a 'Costume' parameter, the id of a costume effect");
        }
        String fallback = subject.handleAs(FallbackRoll.class) == null ? fallbackOf(spec) : "";
        boolean queued = wardrobe.queue(subject, costumeId,
                outcome -> settle(outcome, costumeId, fallback, subject, sourceId));
        if (!queued) {
            throw new IllegalStateException("costume '" + costumeId + "' was not put on ("
                    + Costumes.DressOutcome.CANNOT_WEAR + "): nobody live to dress, or no world to queue it on");
        }
    }

    /**
     * Reports nothing on the receipt: when this returns the costume is only queued, and a receipt names
     * only what reached the player. The wearer is told when it lands, and a fallback lands as its own
     * table's roll.
     */
    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId,
            @Nonnull Consumer<RewardSpec> receipt) throws Exception {
        grant(spec, subject, sourceId);
    }

    /**
     * What the queued dressing came to, on the wearer's world thread: nothing more for a costume that went
     * on; for a wearer gone by then (a null outcome), one warn line and no fallback, since a treat needs the
     * same live player; for any refusal, the fallback table rolled for the same player instead, under a
     * subject marked {@link FallbackRoll} so a costume it pays rolls no fallback in turn, or, with none
     * named, one warn line. A loss this kind reports itself is counted against this kind; a fallback that
     * cannot be rolled is reported, and counted, by that roll's own payout. Never throws: it runs from the
     * world's task queue, after the payout reported the reward paid.
     */
    private void settle(@Nullable Costumes.DressOutcome outcome, @Nonnull String costumeId,
            @Nonnull String fallback, @Nonnull Subject wearer, @Nonnull String sourceId) {
        if (outcome == null) {
            lost(sourceId, "costume '" + costumeId + "' was not put on: its wearer was gone when the queue ran");
            return;
        }
        if (outcome == Costumes.DressOutcome.DRESSED) {
            return;
        }
        String refused = "costume '" + costumeId + "' was not put on (" + outcome + ")";
        if (fallback.isEmpty()) {
            lost(sourceId, refused + whyNoFallback(wearer));
            return;
        }
        Subject rolling = wearer.withFacets(FallbackRoll.MARK);
        RewardGrants.grantAll(List.of(treat(fallback, sourceId)), rolling, sourceId, kinds, null,
                line -> warn.accept(line + ", as the fallback after " + refused));
    }

    /** Why a refusal rolls nothing: the reward names no fallback, or it was paid from inside a fallback roll. */
    @Nonnull
    private static String whyNoFallback(@Nonnull Subject wearer) {
        if (wearer.handleAs(FallbackRoll.class) != null) {
            return " inside a fallback roll, which rolls no fallback of its own";
        }
        return " and the reward names no " + P_FALLBACK_LOOTABLE;
    }

    /** One warn line for a reward lost after its payout, counted against this kind as a thrown one is. */
    private void lost(@Nonnull String sourceId, @Nonnull String why) {
        kinds.recordFailure(KIND, why);
        warn.accept("[grant] " + sourceId + ": reward lost (" + KIND + "): " + why);
    }

    /** The costume a reward names: {@code Costume}, else {@code Effect}, trimmed; empty when neither. */
    @Nonnull
    static String costumeOf(@Nonnull RewardSpec spec) {
        return spec.paramOr("costume", spec.paramOr("effect", "")).trim();
    }

    /** The table a reward names to roll instead when its costume does not go on, trimmed; empty when none. */
    @Nonnull
    static String fallbackOf(@Nonnull RewardSpec spec) {
        return spec.paramOr(P_FALLBACK_LOOTABLE, "").trim();
    }

    /** The fallback as the {@code Lootable} reward it is paid as, labelled with the costume's own source. */
    @Nonnull
    private static RewardSpec treat(@Nonnull String fallback, @Nonnull String sourceId) {
        return RewardSpec.of(LootRewardKinds.KIND_LOOTABLE, "Lootable", fallback)
                .with(RewardGrants.P_SOURCE, sourceId);
    }

    /**
     * The engine's {@link Wardrobe}: the subject's own live player, with the costume put on from that
     * player's world's task queue. Called on the world thread, like every payout; the task itself runs
     * outside every system's tick.
     */
    static boolean queueOnWorld(@Nonnull Subject subject, @Nonnull String costumeId,
            @Nonnull Consumer<Costumes.DressOutcome> then) {
        Player player = subject.handleAs(Player.class);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        if (ref == null || !ref.isValid()) {
            return false;
        }
        try {
            WorldEvictors.worldOf(ref).execute(() -> then.accept(dressNow(ref, costumeId)));
            return true;
        } catch (RuntimeException closed) {
            return false;
        }
    }

    /**
     * The queued task: the wearer dressed, then told how to take it off. Null when the wearer is gone by
     * the time it runs: the reference no longer answers, or no longer answers on this world's thread (the
     * re-check {@code PlayerMomentDispatch} makes after its own hop). An engine refusal reads as
     * {@code CANNOT_WEAR} and a notice that cannot be sent is logged, so the task never throws into the
     * world's queue.
     */
    @Nullable
    private static Costumes.DressOutcome dressNow(@Nonnull Ref<EntityStore> ref, @Nonnull String costumeId) {
        if (!ref.isValid() || !ref.getStore().isInThread()) {
            return null;
        }
        Store<EntityStore> store = ref.getStore();
        Costumes.DressOutcome outcome;
        try {
            outcome = Costumes.dress(store, ref, costumeId);
        } catch (RuntimeException refused) {
            return Costumes.DressOutcome.CANNOT_WEAR;
        }
        if (outcome != Costumes.DressOutcome.DRESSED) {
            return outcome;
        }
        try {
            PlayerRef wearer = PlayerAccess.playerRef(store, ref);
            if (wearer != null) {
                wearer.sendMessage(CostumeMessages.dressed(null));
            }
        } catch (RuntimeException unsent) {
            SafeLog.fine("[costume] the notice for costume '" + costumeId + "' could not be sent: " + unsent);
        }
        return outcome;
    }
}
