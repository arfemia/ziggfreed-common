package com.ziggfreed.common.objectives.drop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.event.EncounterDefeatedEvent;
import com.ziggfreed.common.encounter.payout.EncounterClaims;
import com.ziggfreed.common.feedback.Notify;
import com.ziggfreed.common.feedback.PickupMimic;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.i18n.NativeNames;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.instance.reward.NativeLootService;
import com.ziggfreed.common.inventory.ItemIds;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.GroundSpillSinks;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimCollectedEvent;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.objectives.producer.EncounterPayload;
import com.ziggfreed.common.objectives.producer.EncounterRulePayload;
import com.ziggfreed.common.objectives.producer.MobKillPayload;
import com.ziggfreed.common.objectives.producer.ProgressDispatch;
import com.ziggfreed.common.objectives.producer.ZigEncounterProducer;
import com.ziggfreed.common.objectives.producer.ZigMobKillProducer;
import com.ziggfreed.common.progress.DispatchOptions;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentListener;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestEngine.ItemDropDue;
import com.ziggfreed.common.quest.QuestItemDrop;
import com.ziggfreed.common.quest.QuestItemDrop.Delivery;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * Quest item drops, handed over: a quest step's {@code Drop} block ({@link QuestItemDrop}) met on the
 * moment stream. A kill ({@code KILL_ENTITY}, the player credited with it, by the dead NPC's role id) or
 * a won boss fight ({@code ENCOUNTER_DEFEATED}, once for every participant the fight credited, by the
 * fight's id) asks the quest engine what that player's steps are owed from it
 * ({@link QuestEngine#itemDropsDue}), rolls each owed step's chance and hands the item over. A player
 * whose steps ask for nothing from it gets nothing, and one already holding what a step owes gets no
 * more.
 *
 * <p><b>Where it goes.</b> The step's {@code Delivery} says ({@link QuestItemDrop#deliveryFor}), and one method
 * turns it into a route, {@link #route}:
 * <ul>
 *   <li>{@code Spoils} (a won fight's default) waits in the step's Claim, paid for the fight
 *   ({@link Claim#fight()}: the run id the defeat's {@link EncounterPayload} carries, the id the fight's spoils
 *   chest is keyed by, {@link EncounterClaims#fightOf}), so the chest that fight leaves where the boss fell offers
 *   it with the rest of its loot, and it waits under Claims where no chest stands (one switched off, a fight no
 *   binding row names, a rule's credit, a kill), never handed over unasked (M428);</li>
 *   <li>{@code BagThenClaim} (a kill's default) goes into the bag the way the engine gives a picked-up item (the
 *   container the player's pickup settings choose, partial stacks topped up first,
 *   {@link GroundSpillSinks#pickupRouted}), and whatever the bag cannot take waits in the step's Claim;</li>
 *   <li>{@code Bag} goes into the bag the same way, and what the bag cannot take is not handed over: the player is
 *   told their bag is full, and the step still wants it, so a later drop tries again;</li>
 *   <li>{@code Ground} spills one stack for this player where the creature or the boss fell (the corpse read as
 *   the moment arrives, while it still stands, lifted as the engine lifts a death drop, or the defeat's
 *   {@link EncounterDefeatedEvent#deathPosition}), owned by nobody, as the base game drops a kill's loot, so
 *   anyone nearby can pick it up; with no such place in the player's own world, or a spawn that failed, it waits in
 *   the step's Claim instead.</li>
 * </ul>
 * A Claim a won fight writes names that fight whatever the delivery, a top-up included
 * ({@link ClaimStore#awardOrTopUp} moves the step's Claim to the newest fight), so it raises or joins that fight's
 * chest: the chest goes up at the fight's first Claim and lists a fight's Claims as it opens, and this hand-out runs
 * a task after the defeat, which the chest notes as it is announced.
 *
 * <p><b>The step's Claim.</b> A player holds at most one Claim per quest and step, {@code
 * quest-drop:<quest>:<step>:<player>} from the source {@code quest-drop:<quest>}, titled with the item's
 * name and the quest's ({@code quest.drop.claim}), holding the item, with no diminishing returns. A later drop
 * while it waits adds to it ({@link ClaimStore#awardOrTopUp}), never a second Claim, and what waits counts as
 * held when the engine reads what a step still wants ({@link QuestEngine.ItemsWaiting}), so the bag and the
 * Claim together never hold more than the step asks for. The player hears of it through the Claims tab's own
 * toast, which names it (and the chest, when it stands in one); collecting it takes room in the bag, and the
 * Claims tab says so when there is none. Like every Claim it never expires, and it still pays its item when the
 * quest was finished or abandoned before it was collected: a stray quest item is harmless.
 *
 * <p>It is an OBJECTIVE item, what the step asks the player to bring, not a reward: the quest's hand-in takes
 * it back. A step that counts the item ARRIVING ({@code PICKUP_ITEM}) is credited with what reaches the bag,
 * a drop's at once, a Claim's when it is collected or taken out of a spoils chest ({@link #onCollected}, on the
 * {@link ClaimCollectedEvent} either raises), fed to the engines alone
 * ({@link DispatchOptions#OBJECTIVES_ONLY}: no listener and no tap sees a pickup that never happened), since
 * a granted item fires no native pickup; a ground stack counts through the engine's own pickup. What lands in the
 * bag raises a corner notice naming the item and the quest it is for ("Quest item: ..."), with the pickup sound.
 *
 * <p><b>When.</b> The decision runs on a LATER task of the player's world, never inside the death
 * system that fired the kill, and by then the same moment has reached the quest engine, so a step that
 * very kill or win unlocked (beat the boss, then bring what it dropped) is already open. Data only: a
 * consumer registers nothing, and it works on a server running this library alone. Never throws.
 */
public final class QuestItemDrops implements MomentListener {

    /** When the hand-out runs. */
    @FunctionalInterface
    public interface Later {

        void run(@Nonnull Moment moment, @Nonnull Runnable task);
    }

    /** Production: the player's own world, after whatever fired the moment. */
    public static final Later WORLD_THREAD =
            (moment, task) -> moment.store().getExternalData().getWorld().execute(task);

    /** The one listener the library registers ({@code ProgressionDefaults.install}). */
    public static final QuestItemDrops INSTANCE =
            new QuestItemDrops(WORLD_THREAD, () -> ThreadLocalRandom.current().nextDouble());

    /** The notice's headline: "Quest item: {0}", {0} the item's name. */
    static final String RECEIVED_KEY = "ziggfreedcommon.progression.quest.drop.received";

    /** The notice's second line: "For {0}", {0} the quest's name. */
    static final String FOR_KEY = "ziggfreedcommon.progression.quest.drop.for";

    /** A {@code Bag} drop the bag had no room for, {0} the item's name: nothing more was handed over. */
    static final String BAG_FULL_KEY = "ziggfreedcommon.progression.quest.drop.bag_full";

    /** A step's Claim's title: "{0} for {1}", the item's name and the quest's. */
    static final String CLAIM_TITLE_KEY = "ziggfreedcommon.progression.quest.drop.claim";

    /** What a step's Claim's source and id start with. */
    static final String CLAIM_PREFIX = "quest-drop:";

    /** Item ids a step names that this server does not have, warned once each. */
    private static final Set<String> WARNED_ITEMS = ConcurrentHashMap.newKeySet();

    private final Later later;
    private final DoubleSupplier roll;

    public QuestItemDrops(@Nonnull Later later, @Nonnull DoubleSupplier roll) {
        this.later = later;
        this.roll = roll;
    }

    @Override
    public void react(@Nonnull Moment moment) {
        QuestItemDrop.Source source = sourceOf(moment.kindId());
        if (source == null) {
            return;
        }
        String sourceId = moment.target();
        try {
            // One set lookup: a kill nothing drops from schedules nothing.
            if (!ProgressionRuntime.quests().dropsFrom(source, sourceId)) {
                return;
            }
            Ref<EntityStore> ref = moment.ref();
            // Read now: the corpse stands while its death system runs, and the run id rides only on the moment.
            Origin origin = originOf(moment);
            later.run(moment, () -> handOut(ref, source, sourceId, origin));
        } catch (Throwable t) {
            SafeLog.warn("[quest-drop] a quest item drop for '" + sourceId + "' could not be scheduled", t);
        }
    }

    /** Which source a moment kind is, or null for a moment no drop listens to. */
    @Nullable
    static QuestItemDrop.Source sourceOf(@Nullable String kindId) {
        if (ZigMobKillProducer.KIND.equalsIgnoreCase(kindId)) {
            return QuestItemDrop.Source.ROLE;
        }
        if (ZigEncounterProducer.KIND_DEFEATED.equalsIgnoreCase(kindId)) {
            return QuestItemDrop.Source.ENCOUNTER;
        }
        return null;
    }

    // ==================== the route ====================

    /** Where what the bag does not take goes (all of it, when the bag is not offered it). */
    enum Rest {
        /** The step's Claim, paid for the fight when a fight paid it. */
        CLAIM,
        /** One owner-less stack on the ground where the creature or the boss fell. */
        GROUND,
        /** Nowhere: it is not handed over, and the player is told their bag is full. */
        NOTHING
    }

    /**
     * One owed drop's way, decided before anything moves.
     *
     * @param bagFirst whether the bag is offered the drop first, the way a picked-up item goes in
     * @param rest     where what the bag does not take goes
     */
    record Route(boolean bagFirst, @Nonnull Rest rest) {
    }

    /**
     * Where an owed drop goes, the one place it is chosen: {@code delivery} (the step's own, else its source's
     * default) as a route, {@code groundSpot} whether a place to spill it stands in the player's world. A ground
     * drop with nowhere to land waits in the Claim, so a delivery that cannot be made never loses the item.
     */
    @Nonnull
    static Route route(@Nonnull Delivery delivery, boolean groundSpot) {
        return switch (delivery) {
            case SPOILS -> new Route(false, Rest.CLAIM);
            case BAG_THEN_CLAIM -> new Route(true, Rest.CLAIM);
            case BAG -> new Route(true, Rest.NOTHING);
            case GROUND -> new Route(false, groundSpot ? Rest.GROUND : Rest.CLAIM);
        };
    }

    // ==================== where a drop comes from ====================

    /**
     * What a moment says of where its drops come from, read as it arrives.
     *
     * @param fight    the won fight that pays them ({@link EncounterClaims#fightOf} its run id), or null for a kill
     *                 or a rule's credit
     * @param world    the world {@code groundAt} lies in, or null
     * @param groundAt where a ground spill lands, already lifted (the corpse, or where the boss fell), or null when
     *                 neither could be read
     */
    record Origin(@Nullable String fight, @Nullable UUID world, @Nullable Vector3d groundAt) {

        static final Origin NONE = new Origin(null, null, null);

        /** Where a ground spill lands for a player in {@code worldUuid} (a copy): null when nowhere, or elsewhere. */
        @Nullable
        Vector3d groundIn(@Nullable UUID worldUuid) {
            return groundAt == null || world == null || !world.equals(worldUuid) ? null : new Vector3d(groundAt);
        }
    }

    /**
     * Where {@code moment}'s drops come from: a won run's fight and where its boss fell ({@link EncounterPayload}), or
     * the corpse of a kill or a rule's credited kill ({@link MobKillPayload}, {@link EncounterRulePayload}), read
     * through the death system's buffer while the corpse stands. Never throws: anything unreadable is none.
     */
    @Nonnull
    static Origin originOf(@Nonnull Moment moment) {
        try {
            EncounterPayload won = moment.payload(EncounterPayload.class);
            if (won != null) {
                EncounterDefeatedEvent defeat = won.defeated();
                Vector3d fell = defeat == null ? null : defeat.deathPosition();
                return new Origin(EncounterClaims.fightOf(won.runId()), defeat == null ? null : defeat.worldUuid(),
                        fell == null ? null : NativeLootService.lifted(fell));
            }
            Ref<EntityStore> corpse = corpseOf(moment);
            if (corpse == null) {
                return Origin.NONE;
            }
            ComponentAccessor<EntityStore> accessor;
            if (moment.commandBuffer() != null) {
                accessor = moment.commandBuffer();
            } else {
                accessor = moment.store();
            }
            return new Origin(null, worldOf(moment.store()), NativeLootService.dropPointOf(accessor, corpse));
        } catch (Throwable t) {
            SafeLog.fine("[quest-drop] where a drop came from could not be read: " + t.getMessage());
            return Origin.NONE;
        }
    }

    /** The creature a kill moment or a rule's credited kill names, or null. */
    @Nullable
    private static Ref<EntityStore> corpseOf(@Nonnull Moment moment) {
        MobKillPayload kill = moment.payload(MobKillPayload.class);
        if (kill != null) {
            return kill.victimRef();
        }
        EncounterRulePayload rule = moment.payload(EncounterRulePayload.class);
        return rule == null ? null : rule.victimRef();
    }

    /** The uuid of the world {@code store} belongs to, or null when it cannot be read. */
    @Nullable
    private static UUID worldOf(@Nonnull Store<EntityStore> store) {
        try {
            return store.getExternalData().getWorld().getWorldConfig().getUuid();
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== the hand-out ====================

    /** On the player's world thread: what their steps are owed from this source, rolled and handed over. */
    private void handOut(@Nonnull Ref<EntityStore> ref, @Nonnull QuestItemDrop.Source source,
            @Nonnull String sourceId, @Nonnull Origin origin) {
        try {
            if (!ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            if (!store.isInThread()) {
                // A world transfer landed before this ran; the drop is not written onto another world's thread.
                return;
            }
            Subject subject = ProgressionRuntime.subjects().questSubject(store, ref);
            PlayerRef playerRef = PlayerAccess.playerRef(store, ref);
            UUID player = playerRef == null ? null : playerRef.getUuid();
            if (subject == null || player == null) {
                return;
            }
            // Read once: what already waits in each step's Claim counts as held.
            List<Claim> held = ClaimStore.shared().list(player);
            List<ItemDropDue> due = ProgressionRuntime.quests().itemDropsDue(subject, source, sourceId,
                    (quest, step) -> waiting(held, claimId(quest.id(), step.id(), player), step.target()));
            UUID world = origin.groundAt() == null ? null : worldOf(store);
            for (ItemDropDue drop : due) {
                // One roll per owed step.
                if (drop.drop().lands(roll.getAsDouble())) {
                    give(store, ref, player, source, origin.fight(), origin.groundIn(world), drop);
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[quest-drop] quest items from '" + sourceId + "' could not be handed over", t);
        }
    }

    /**
     * Hand one owed drop over along its {@link #route}: into the bag first when the route says so, the rest to the
     * step's Claim (paid for {@code fight}), to the ground at {@code groundAt}, or nowhere with a word to the player.
     */
    private static void give(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull UUID player,
            @Nonnull QuestItemDrop.Source source, @Nullable String fight, @Nullable Vector3d groundAt,
            @Nonnull ItemDropDue due) {
        String itemId = due.itemId();
        if (!ItemIds.exists(itemId)) {
            if (WARNED_ITEMS.add(itemId.toLowerCase(Locale.ROOT))) {
                SafeLog.warn("[quest-drop] quest '" + due.quest().id() + "' step '" + due.step().id()
                        + "' drops '" + itemId + "', which is no item on this server, so nothing drops");
            }
            return;
        }
        Route route = route(due.drop().deliveryFor(source), groundAt != null);
        int bagged = route.bagFirst() ? intoBag(store, ref, due) : 0;
        if (bagged > 0) {
            if (due.countsOnArrival()) {
                ProgressDispatch.fire(store, ref, ItemDropDue.ARRIVAL_KIND, itemId, null, bagged,
                        DispatchOptions.OBJECTIVES_ONLY);
            }
            notice(store, ref, due, bagged);
        }
        int rest = due.upTo() - bagged;
        if (rest <= 0) {
            return;
        }
        switch (route.rest()) {
            case GROUND -> {
                if (!spill(store, itemId, rest, groundAt)) {
                    keep(player, due, rest, fight);
                }
            }
            case NOTHING -> bagFull(store, ref, itemId);
            case CLAIM -> keep(player, due, rest, fight);
        }
    }

    /** Offer the whole drop to the bag; how many it took. */
    private static int intoBag(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ItemDropDue due) {
        ItemStack rest = GroundSpillSinks.pickupRouted(ref, store).offer(new ItemStack(due.itemId(), due.upTo()));
        return due.upTo() - leftOver(rest, due.upTo());
    }

    /** How much of {@code offered} the bag did not take, from what its offer answered. */
    static int leftOver(@Nullable ItemStack rest, int offered) {
        if (rest == null || rest.getItemId() == null) {
            return 0;
        }
        return Math.max(0, Math.min(offered, rest.getQuantity()));
    }

    /**
     * Spill {@code count} of {@code itemId} as one owner-less stack at {@code at}, outside any tick (the engine's own
     * drop, through the one-accessor spawn); whether it landed or was queued to land.
     */
    private static boolean spill(@Nonnull Store<EntityStore> store, @Nonnull String itemId, int count,
            @Nullable Vector3d at) {
        if (at == null) {
            return false;
        }
        List<ItemStack> stack = new ArrayList<>(1);
        stack.add(new ItemStack(itemId, count));
        return NativeLootService.spawnInWorld(store, at, Rotation3f.IDENTITY, stack);
    }

    /**
     * Keep {@code count} of the step's item for {@code player} in the step's Claim, written or topped up, paid for
     * {@code fight} (null for none).
     */
    private static void keep(@Nonnull UUID player, @Nonnull ItemDropDue due, int count, @Nullable String fight) {
        long now = System.currentTimeMillis();
        Claim claim = claimOf(due, player, count, now, fight);
        if (!ClaimStore.shared().awardOrTopUp(player, claim, null, claim.source(), now)) {
            SafeLog.warn("[quest-drop] " + count + " '" + due.itemId() + "' for quest '" + due.quest().id()
                    + "' step '" + due.step().id() + "' could not be kept in a Claim");
        }
    }

    /**
     * The step's Claim holding {@code count} of its item, paid for {@code fight} (a won run's, so that fight's spoils
     * chest offers it; null for none): one per player, quest and step.
     */
    @Nonnull
    static Claim claimOf(@Nonnull ItemDropDue due, @Nonnull UUID player, int count, long nowMs,
            @Nullable String fight) {
        Quest quest = due.quest();
        return new Claim(claimId(quest.id(), due.step().id(), player), CLAIM_PREFIX + quest.id(), CLAIM_TITLE_KEY,
                List.of(Claim.itemArg(due.itemId()), questName(quest)), null,
                List.of(InstanceReward.item(due.itemId(), count, null)), nowMs, fight);
    }

    /** {@code quest-drop:<quest>:<step>:<player>}: a step's Claim's id. */
    @Nonnull
    static String claimId(@Nonnull String questId, @Nonnull String stepId, @Nonnull UUID player) {
        return CLAIM_PREFIX + questId + ":" + stepId + ":" + player;
    }

    /**
     * The quest's name as a Claim keeps it: its title key, read on the reader's client, when it has one that
     * takes no arguments; else the plain name its title reads as (a typed display name); else its id as written
     * (what a moment naming a quest falls back to).
     */
    @Nonnull
    static String questName(@Nonnull Quest quest) {
        String key = quest.text().resolvableTitleKey();
        if (key != null && quest.text().titleArgs().length == 0) {
            return Claim.keyArg(key);
        }
        String plain = quest.text().titleOr(quest.id()).getRawText();
        return plain == null || plain.isBlank() ? quest.id() : plain;
    }

    /** How many of {@code itemId} the Claim {@code claimId} in {@code held} keeps; 0 when it is not there. */
    static int waiting(@Nonnull List<Claim> held, @Nonnull String claimId, @Nonnull String itemId) {
        int count = 0;
        for (Claim claim : held) {
            if (!claim.is(claimId)) {
                continue;
            }
            for (InstanceReward reward : claim.rewards()) {
                if (reward.isItem() && reward.id().equalsIgnoreCase(itemId)) {
                    count += reward.quantity();
                }
            }
        }
        return count;
    }

    /**
     * A Claim was collected by a press: when it was a step's Claim and the step counts its item arriving, what
     * reached the bag is credited to it as picked up, on a later task of the player's world (the press is still
     * running). A quest finished, abandoned or gone credits nothing,
     * and the item stays the player's. Registered by {@code ProgressionDefaults.install}; never throws.
     */
    public static void onCollected(@Nonnull ClaimCollectedEvent event) {
        try {
            Claim claim = event.claim();
            String source = claim.source();
            if (!source.regionMatches(true, 0, CLAIM_PREFIX, 0, CLAIM_PREFIX.length())) {
                return;
            }
            Quest quest = ProgressionRuntime.quests().quest(source.substring(CLAIM_PREFIX.length()));
            ObjectiveDef step = quest == null ? null : stepOf(quest, claim, event.player());
            if (step == null || !ItemDropDue.countsOnArrival(step)) {
                return;
            }
            int arrived = 0;
            for (InstanceReward reward : event.delivered()) {
                if (reward.isItem() && reward.id().equalsIgnoreCase(step.target())) {
                    arrived += reward.quantity();
                }
            }
            Ref<EntityStore> ref = event.ref();
            if (arrived <= 0 || !ref.isValid()) {
                return;
            }
            int credit = arrived;
            String itemId = step.target();
            ref.getStore().getExternalData().getWorld().execute(() -> credit(ref, itemId, credit));
        } catch (Throwable t) {
            SafeLog.warn("[quest-drop] a collected quest item Claim could not be credited", t);
        }
    }

    /** The step of {@code quest} whose Claim {@code claim} is, or null. */
    @Nullable
    private static ObjectiveDef stepOf(@Nonnull Quest quest, @Nonnull Claim claim, @Nonnull UUID player) {
        for (String stepId : quest.stepDrops().keySet()) {
            if (claim.is(claimId(quest.id(), stepId, player))) {
                return quest.objective(stepId);
            }
        }
        return null;
    }

    /** On the player's world thread: {@code count} of {@code itemId} arrived, for the engines alone. */
    private static void credit(@Nonnull Ref<EntityStore> ref, @Nonnull String itemId, int count) {
        try {
            if (ref.isValid() && ref.getStore().isInThread()) {
                ProgressDispatch.fire(ref.getStore(), ref, ItemDropDue.ARRIVAL_KIND, itemId, null, count,
                        DispatchOptions.OBJECTIVES_ONLY);
            }
        } catch (Throwable t) {
            SafeLog.warn("[quest-drop] '" + itemId + "' collected from a Claim could not be credited", t);
        }
    }

    /** The corner notice and the pickup sound. */
    private static void notice(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ItemDropDue due, int bagged) {
        PlayerRef playerRef = PlayerAccess.playerRef(store, ref);
        if (playerRef == null) {
            return;
        }
        Notify.itemKeyed(playerRef, Msg.key(RECEIVED_KEY, NativeNames.itemNameMsg(due.itemId())),
                Msg.key(FOR_KEY, due.quest().text().titleOr(due.quest().id())), due.itemId(), bagged);
        PickupMimic.playPickupSfx(ref, store, null);
    }

    /** A {@code Bag} drop the bag had no room for: say so, so the player makes room before the next drop. */
    private static void bagFull(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String itemId) {
        PlayerRef playerRef = PlayerAccess.playerRef(store, ref);
        if (playerRef != null) {
            Notify.warning(playerRef, Msg.key(BAG_FULL_KEY, NativeNames.itemNameMsg(itemId)));
        }
    }
}
