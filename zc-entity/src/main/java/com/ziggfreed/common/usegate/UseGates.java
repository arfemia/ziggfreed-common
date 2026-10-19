package com.ziggfreed.common.usegate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.event.NativeEventSeam;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateRuntime;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.RefusalLines;
import com.ziggfreed.common.util.SafeLog;

/**
 * The use-gate engine's one question: does any gate this moment is about refuse this player? Asked by the break
 * and damage systems and the armor-slot filter ({@link UseGateBreakSystem}, {@link UseGateDamageSystem},
 * {@link UseGateWornFilter}), through the server's one requirement evaluator.
 *
 * <p>For each axis the moment is about (the block, the creature, then the held or worn item), the gates whose
 * patterns match are walked most specific first ({@link UseGateIndex}); in each {@code Group} the first gate that
 * is present right now ({@link UseGateAsset#isPresent}) is the one that applies, so a switched-off or
 * feature-hidden gate never shadows a broader one. On an item axis the item's own {@code ZigLevel} tag
 * ({@link ItemLevelTags}) comes last in the {@value ItemLevelTags#GROUP} group: an authored gate there naming the
 * item applies instead, and one switched off there switches the tag off. Each applying gate's live
 * {@code Requires} is then asked in that order, a stack kept for its holder in that gate's group
 * ({@link UseGateKeeps}) passing the item axes, and the first refusal is answered with its best line
 * ({@link RefusalLines#bestLineOf}). With no gate authored and no item tagged the whole question costs two
 * volatile reads.
 */
public final class UseGates {

    private static final NativeEventSeam EVENTS = new NativeEventSeam("[usegate]");

    /** One moment's subject matter: each leaf null when the moment is not about it. */
    private record Moment(@Nullable ItemStack held, @Nullable ItemStack worn, @Nullable String blockId,
            @Nullable String mobId, @Nullable Ref<EntityStore> target) {

        @Nullable
        String idOn(@Nonnull UseGateAxis axis) {
            return switch (axis) {
                case BLOCK -> blockId;
                case MOB -> mobId;
                case HELD -> idOf(held);
                case WORN -> idOf(worn);
            };
        }

        @Nullable
        ItemStack stackOn(@Nonnull UseGateAxis axis) {
            return axis == UseGateAxis.HELD ? held : axis == UseGateAxis.WORN ? worn : null;
        }
    }

    /** One gate that applies, and the axis it applies on. */
    private record Applying(@Nonnull UseGateAsset gate, @Nonnull UseGateAxis axis) {
    }

    private UseGates() {
    }

    /**
     * Is there nothing for any moment to ask: no switched-on gate authored and no loaded item tagged
     * {@value ItemLevelTags#TAG}? The read every refusal path pays first.
     */
    public static boolean nothingToAsk() {
        return UseGateConfig.getInstance().index().isEmpty() && !ItemLevelTags.any();
    }

    /** {@link #nothingToAsk()} for one axis alone (the armor slots ask only {@link UseGateAxis#WORN}). */
    public static boolean nothingToAsk(@Nonnull UseGateAxis axis) {
        return UseGateConfig.getInstance().index().isEmpty(axis) && !(axis.readsItem() && ItemLevelTags.any());
    }

    /**
     * The refusal for breaking {@code blockId} while holding {@code held}, or null when every gate the break is
     * about passes. World thread.
     */
    @Nullable
    public static UseGateRefusal breakRefusal(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nullable ItemStack held, @Nullable String blockId) {
        return walk(store, ref, new Moment(held, null, blockId, null, null), null, null);
    }

    /**
     * The refusal for hitting {@code target} (an NPC role {@code mobId}, null for anything that is not a creature)
     * while holding {@code held}, or null when every gate the hit is about passes. World thread.
     */
    @Nullable
    public static UseGateRefusal hitRefusal(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nullable ItemStack held, @Nullable Ref<EntityStore> target, @Nullable String mobId) {
        return walk(store, ref, new Moment(held, null, null, mobId, target), null, null);
    }

    /**
     * The refusal of one item on one item axis: holding {@code stack} ({@link UseGateAxis#HELD}) or putting it on
     * ({@link UseGateAxis#WORN}), asking only {@code group}'s gates when it is not null. Null when nothing refuses.
     * World thread.
     */
    @Nullable
    public static UseGateRefusal itemRefusal(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull UseGateAxis axis, @Nullable ItemStack stack, @Nullable String group) {
        Moment moment = itemMoment(axis, stack);
        return moment == null ? null : walk(store, ref, moment, groupFilter(group), null);
    }

    /**
     * Every group whose applying gate refuses this player {@code stack} on either item axis right now (holding it,
     * or putting it on), only {@code group}'s when it is not null; a group the stack is already kept for passes.
     * In walk order, folded. What {@link UseGateKeeps#keepCarried} stamps a stack for. World thread.
     */
    @Nonnull
    public static Set<String> refusingGroups(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nullable ItemStack stack, @Nullable String group) {
        Set<String> out = new LinkedHashSet<>();
        for (UseGateAxis axis : List.of(UseGateAxis.HELD, UseGateAxis.WORN)) {
            Moment moment = itemMoment(axis, stack);
            if (moment != null) {
                walk(store, ref, moment, groupFilter(group), out);
            }
        }
        return out;
    }

    /** Tell whoever listens that {@code refusal} refused this player (the library's listener tells the player). */
    public static void announce(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull UseGateRefusal refusal) {
        try {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                return;
            }
            EVENTS.fire("refused", ZigUseGateRefusedEvent.class, () -> new ZigUseGateRefusedEvent(playerRef, refusal));
        } catch (Throwable t) {
            SafeLog.fine("[usegate] announcing a refusal failed: " + t.getMessage());
        }
    }

    /** Where this family's events go: a host outside a server, or a test, observes them here. */
    @Nonnull
    public static NativeEventSeam events() {
        return EVENTS;
    }

    // ==================== the one walk ====================

    @Nullable
    private static Moment itemMoment(@Nonnull UseGateAxis axis, @Nullable ItemStack stack) {
        if (!axis.readsItem()) {
            return null;
        }
        return axis == UseGateAxis.HELD
                ? new Moment(stack, null, null, null, null)
                : new Moment(null, stack, null, null, null);
    }

    @Nullable
    private static String groupFilter(@Nullable String group) {
        return group == null ? null : group.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The walk every question shares. With {@code refusing} null it answers the first refusal; otherwise it adds
     * every refusing gate's group to {@code refusing}, walks on, and answers null.
     */
    @Nullable
    private static UseGateRefusal walk(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Moment moment, @Nullable String onlyGroup, @Nullable Set<String> refusing) {
        if (nothingToAsk()) {
            return null;
        }
        List<Applying> applying = applying(UseGateConfig.getInstance().index(), moment, onlyGroup);
        if (applying.isEmpty()) {
            return null;
        }
        UUID playerId = null;
        GateQuery query = null;
        GateEvaluator evaluator = GateRuntime.evaluator();
        for (Applying gate : applying) {
            GateSpec live = gate.gate().liveRequires();
            if (live == null) {
                continue;
            }
            String group = gate.gate().group();
            if (gate.axis().readsItem()) {
                if (playerId == null) {
                    playerId = playerIdOf(store, ref);
                }
                if (UseGateKeeps.keptFor(moment.stackOn(gate.axis()), playerId, group)) {
                    continue;
                }
            }
            if (query == null) {
                query = queryOf(store, ref, moment);
            }
            if (evaluator.check(live, query).passed()) {
                continue;
            }
            if (refusing != null) {
                refusing.add(group);
                continue;
            }
            Message line = RefusalLines.bestLineOf(evaluator.refusals(live, query));
            return new UseGateRefusal(gate.gate().getId(), group, gate.axis(), line);
        }
        return null;
    }

    /**
     * The gates that apply to {@code moment}, target axes first: per axis and group, the most specific gate
     * present right now, and on an item axis the item's own tag last in its group; a gate already applying on an
     * earlier axis is asked once, on that axis.
     */
    @Nonnull
    private static List<Applying> applying(@Nonnull UseGateIndex index, @Nonnull Moment moment,
            @Nullable String onlyGroup) {
        List<Applying> out = new ArrayList<>(2);
        for (UseGateAxis axis : UseGateAxis.values()) {
            String id = moment.idOn(axis);
            if (id == null) {
                continue;
            }
            Set<String> taken = new HashSet<>();
            for (UseGateIndex.Candidate candidate : index.candidates(axis, id)) {
                UseGateAsset gate = candidate.gate();
                String group = gate.group();
                if ((onlyGroup != null && !onlyGroup.equals(group)) || taken.contains(group) || !gate.isPresent()) {
                    continue;
                }
                taken.add(group);
                if (!applies(out, gate)) {
                    out.add(new Applying(gate, axis));
                }
            }
            if (axis.readsItem() && !taken.contains(ItemLevelTags.GROUP_KEY)
                    && (onlyGroup == null || onlyGroup.equals(ItemLevelTags.GROUP_KEY))) {
                UseGateAsset tag = tagGate(index, axis, id);
                if (tag != null && !applies(out, tag)) {
                    out.add(new Applying(tag, axis));
                }
            }
        }
        return out;
    }

    /**
     * The item's own {@code ZigLevel} tag as the gate it is on {@code axis} right now, or null: no tag, an armor
     * piece held, a switched-off {@value ItemLevelTags#GROUP} gate naming the item, no value naming a usable track,
     * or a switch it sits behind that is off.
     */
    @Nullable
    private static UseGateAsset tagGate(@Nonnull UseGateIndex index, @Nonnull UseGateAxis axis, @Nonnull String id) {
        ItemLevelTags.Tagged tagged = ItemLevelTags.of(id);
        if (tagged == null || (axis == UseGateAxis.HELD && tagged.armor()) || index.switchesOffTag(axis, id)) {
            return null;
        }
        UseGateAsset gate = tagged.gate();
        return gate != null && gate.isPresent() ? gate : null;
    }

    private static boolean applies(@Nonnull List<Applying> already, @Nonnull UseGateAsset gate) {
        for (Applying a : already) {
            if (a.gate() == gate) {
                return true;
            }
        }
        return false;
    }

    @Nonnull
    private static GateQuery queryOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Moment moment) {
        return GateQuery.builder()
                .context(FactorContext.about(store, ref))
                .held(moment.held())
                .item(moment.worn())
                .block(moment.blockId())
                .target(moment.target())
                .build();
    }

    @Nullable
    private static UUID playerIdOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            return playerRef == null ? null : playerRef.getUuid();
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static String idOf(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        String id = stack.getItemId();
        return id == null || id.isBlank() ? null : id;
    }
}
