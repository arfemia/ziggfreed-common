package com.ziggfreed.common.objectives.drop;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.feedback.Notify;
import com.ziggfreed.common.feedback.PickupMimic;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.i18n.NativeNames;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.inventory.ItemIds;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.GroundSpillSinks;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimCollectedEvent;
import com.ziggfreed.common.loot.claim.ClaimStore;
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
 * <p><b>Where it goes.</b> One method decides, {@link #bagFirst}: a kill's drop goes straight into the bag,
 * the way the engine gives a picked-up item (the container the player's pickup settings choose, partial
 * stacks topped up first, {@link GroundSpillSinks#pickupRouted}), and whatever the bag cannot take waits in
 * the step's Claim; a won fight's drop always waits in the step's Claim, whether the bag has room or not,
 * since a boss's loot is collected, never handed over unasked (M428), and the quest item is collected with
 * the rest of it. It NEVER goes onto the ground: a ground drop at the corpse would need a pickup filter the
 * engine does not have (a dropped item carries no owner), so in a group fight another player could take it,
 * and a pile spilled at a full bag's feet is out of every count.
 *
 * <p><b>The step's Claim.</b> A player holds at most one Claim per quest and step, {@code
 * quest-drop:<quest>:<step>:<player>} from the source {@code quest-drop:<quest>}, titled with the item's
 * name and the quest's ({@code quest.drop.claim}), holding the item, with no diminishing returns. A later drop
 * while it waits adds to it ({@link ClaimStore#awardOrTopUp}), never a second Claim, and what waits counts as
 * held when the engine reads what a step still wants ({@link QuestEngine.ItemsWaiting}), so the bag and the
 * Claim together never hold more than the step asks for. The player hears of it through the Claims tab's own
 * toast, which names it; collecting it takes room in the bag, and the Claims tab says so when there is none.
 * Like every Claim it never expires, and it still pays its item when the quest was finished or abandoned
 * before it was collected: a stray quest item is harmless.
 *
 * <p>It is an OBJECTIVE item, what the step asks the player to bring, not a reward: the quest's hand-in takes
 * it back. A step that counts the item ARRIVING ({@code PICKUP_ITEM}) is credited with what reaches the bag,
 * a kill's at once and a Claim's when it is collected ({@link #onCollected}, on the
 * {@link ClaimCollectedEvent} a Claim press raises), fed to the engines alone
 * ({@link DispatchOptions#OBJECTIVES_ONLY}: no listener and no tap sees a pickup that never happened), since
 * a granted item fires no native pickup. What lands in the bag raises a corner notice naming the item and
 * the quest it is for ("Quest item: ..."), with the pickup sound.
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
            later.run(moment, () -> handOut(ref, source, sourceId));
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

    /**
     * Where an owed drop goes, the one place it is chosen: true for a kill's, into the bag first like a
     * pickup; false for a won fight's, which waits in the step's Claim with the rest of the fight's loot.
     * Whatever the bag does not take waits in the Claim either way.
     */
    static boolean bagFirst(@Nonnull QuestItemDrop.Source source) {
        return source == QuestItemDrop.Source.ROLE;
    }

    /** On the player's world thread: what their steps are owed from this source, rolled and handed over. */
    private void handOut(@Nonnull Ref<EntityStore> ref, @Nonnull QuestItemDrop.Source source,
            @Nonnull String sourceId) {
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
            for (ItemDropDue drop : due) {
                // One roll per owed step.
                if (drop.drop().lands(roll.getAsDouble())) {
                    give(store, ref, player, source, drop);
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[quest-drop] quest items from '" + sourceId + "' could not be handed over", t);
        }
    }

    /** Hand one owed drop over: into the bag as far as {@link #bagFirst} says and it takes, the rest kept. */
    private static void give(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull UUID player,
            @Nonnull QuestItemDrop.Source source, @Nonnull ItemDropDue due) {
        String itemId = due.itemId();
        if (!ItemIds.exists(itemId)) {
            if (WARNED_ITEMS.add(itemId.toLowerCase(Locale.ROOT))) {
                SafeLog.warn("[quest-drop] quest '" + due.quest().id() + "' step '" + due.step().id()
                        + "' drops '" + itemId + "', which is no item on this server, so nothing drops");
            }
            return;
        }
        int bagged = bagFirst(source) ? intoBag(store, ref, due) : 0;
        if (bagged > 0) {
            if (due.countsOnArrival()) {
                ProgressDispatch.fire(store, ref, ItemDropDue.ARRIVAL_KIND, itemId, null, bagged,
                        DispatchOptions.OBJECTIVES_ONLY);
            }
            notice(store, ref, due, bagged);
        }
        if (due.upTo() > bagged) {
            keep(player, due, due.upTo() - bagged);
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

    /** Keep {@code count} of the step's item for {@code player} in the step's Claim, written or topped up. */
    private static void keep(@Nonnull UUID player, @Nonnull ItemDropDue due, int count) {
        long now = System.currentTimeMillis();
        Claim claim = claimOf(due, player, count, now);
        if (!ClaimStore.shared().awardOrTopUp(player, claim, null, claim.source(), now)) {
            SafeLog.warn("[quest-drop] " + count + " '" + due.itemId() + "' for quest '" + due.quest().id()
                    + "' step '" + due.step().id() + "' could not be kept in a Claim");
        }
    }

    /** The step's Claim holding {@code count} of its item: one per player, quest and step. */
    @Nonnull
    static Claim claimOf(@Nonnull ItemDropDue due, @Nonnull UUID player, int count, long nowMs) {
        Quest quest = due.quest();
        return new Claim(claimId(quest.id(), due.step().id(), player), CLAIM_PREFIX + quest.id(), CLAIM_TITLE_KEY,
                List.of(Claim.itemArg(due.itemId()), questName(quest)), null,
                List.of(InstanceReward.item(due.itemId(), count, null)), nowMs);
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
     * A Claim was collected: when it was a step's Claim and the step counts its item arriving, what reached the bag
     * is credited to it as picked up, on a later task of the player's world (the press is still running). A quest
     * finished, abandoned or gone credits nothing, and the item stays the player's. Registered by
     * {@code ProgressionDefaults.install}; never throws.
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
}
