package com.ziggfreed.common.objectives.drop;

import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import com.ziggfreed.common.inventory.ItemIds;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.GroundSpillSinks;
import com.ziggfreed.common.objectives.producer.ProgressDispatch;
import com.ziggfreed.common.objectives.producer.ZigEncounterProducer;
import com.ziggfreed.common.objectives.producer.ZigMobKillProducer;
import com.ziggfreed.common.progress.DispatchOptions;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentListener;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
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
 * whose steps ask for nothing from it gets nothing, and one already carrying what a step owes gets no
 * more.
 *
 * <p><b>Why this delivery.</b> The item goes straight into the bag, the way the engine gives a picked-up
 * item (the container the player's pickup settings choose, partial stacks topped up first,
 * {@link GroundSpillSinks#pickupRouted}), and NEVER onto the ground. A ground drop at the corpse would
 * need a pickup filter the engine does not have (a dropped item carries no owner), so in a group fight
 * another player could take it. And a pile spilled at a full bag's feet is out of every count: a hand-in
 * step reads only the bag, so each later kill would spill another, and a pickup step would be credited
 * for an item the player may never take. So what the bag cannot take is not handed over at all, and the
 * player is told their bag is full; the step still wants it, so the next kill or win with room drops it.
 * A corner notice names what landed and the quest it is for ("Quest item: ..."), with the pickup sound.
 *
 * <p>It is an OBJECTIVE item, what the step asks the player to bring, not a reward: rewards are
 * collected (a quest's {@code Claim}, a Claim), but this lands as it drops and the quest's hand-in
 * takes it back.
 *
 * <p>A step that counts the item ARRIVING ({@code PICKUP_ITEM}) is credited with what went into the
 * bag, fed to the engines alone ({@link DispatchOptions#OBJECTIVES_ONLY}: no listener and no tap sees a
 * pickup that never happened), since a granted item fires no native pickup.
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

    /** The warning when the bag had no room, {0} the item's name: nothing was handed over. */
    static final String BAG_FULL_KEY = "ziggfreedcommon.progression.quest.drop.bag_full";

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
            if (subject == null) {
                return;
            }
            List<ItemDropDue> due = ProgressionRuntime.quests().itemDropsDue(subject, source, sourceId);
            for (ItemDropDue drop : due) {
                // One roll per owed step.
                if (drop.drop().lands(roll.getAsDouble())) {
                    give(store, ref, drop);
                }
            }
        } catch (Throwable t) {
            SafeLog.warn("[quest-drop] quest items from '" + sourceId + "' could not be handed over", t);
        }
    }

    /** Hand one owed drop over into the bag, as much as it takes, then the notice. */
    private static void give(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull ItemDropDue due) {
        String itemId = due.itemId();
        if (!ItemIds.exists(itemId)) {
            if (WARNED_ITEMS.add(itemId.toLowerCase(Locale.ROOT))) {
                SafeLog.warn("[quest-drop] quest '" + due.quest().id() + "' step '" + due.step().id()
                        + "' drops '" + itemId + "', which is no item on this server, so nothing drops");
            }
            return;
        }
        ItemStack rest = GroundSpillSinks.pickupRouted(ref, store).offer(new ItemStack(itemId, due.upTo()));
        int bagged = due.upTo() - leftOver(rest, due.upTo());
        if (bagged <= 0) {
            bagFull(store, ref, itemId);
            return;
        }
        if (due.countsOnArrival()) {
            ProgressDispatch.fire(store, ref, ItemDropDue.ARRIVAL_KIND, itemId, null, bagged,
                    DispatchOptions.OBJECTIVES_ONLY);
        }
        notice(store, ref, due, bagged);
    }

    /** How much of {@code offered} the bag did not take, from what its offer answered. */
    static int leftOver(@Nullable ItemStack rest, int offered) {
        if (rest == null || rest.getItemId() == null) {
            return 0;
        }
        return Math.max(0, Math.min(offered, rest.getQuantity()));
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

    /** The bag took none of it: say so, so the player makes room before the next try. */
    private static void bagFull(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String itemId) {
        PlayerRef playerRef = PlayerAccess.playerRef(store, ref);
        if (playerRef != null) {
            Notify.warning(playerRef, Msg.key(BAG_FULL_KEY, NativeNames.itemNameMsg(itemId)));
        }
    }
}
