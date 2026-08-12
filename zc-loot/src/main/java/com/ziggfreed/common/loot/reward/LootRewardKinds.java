package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.inventory.InventoryGrant;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.FactorSnapshot;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.loot.stamp.StampCapEngine;
import com.ziggfreed.common.loot.stamp.StampInspection;
import com.ziggfreed.common.loot.stamp.StampPlan;
import com.ziggfreed.common.loot.stamp.StampSpec;
import com.ziggfreed.common.loot.stamp.StatRoll;
import com.ziggfreed.common.loot.stamp.Stamper;
import com.ziggfreed.common.loot.stamp.StamperRegistry;
import com.ziggfreed.common.subject.Subject;

/**
 * The three reward kinds the framework itself can pay out, ready to register into a
 * {@link RewardKindRegistry}.
 *
 * <table>
 *   <caption>What each kind reads</caption>
 *   <tr><th>Kind</th><th>Parameters</th></tr>
 *   <tr><td>{@code item}</td><td>{@code Item}, {@code Count}</td></tr>
 *   <tr><td>{@code lootable}</td><td>{@code Lootable}, {@code Trigger}</td></tr>
 *   <tr><td>{@code stamped_item}</td>
 *       <td>{@code Item}, {@code Count}, and EITHER {@code Pool} (roll it) or {@code Stats}
 *           (written out, {@code "Damage:5,Speed:2"})</td></tr>
 * </table>
 *
 * <p>These are deliberately the only ones. Everything else a payout could mean - currency, a level,
 * a title - belongs to the mod that owns the concept; the framework knowing about them would just be
 * the framework guessing.
 *
 * <h2>A full inventory does not eat a reward</h2>
 *
 * <p>An item that does not fit goes to the {@link #overflow(Overflow) overflow sink}, and if there
 * is no sink the grant FAILS rather than pretending. Failing is the useful outcome: the payout layer
 * then asks for a replayable command and parks it for the player's next connect, so a reward earned
 * with a full bag arrives later instead of vanishing quietly.
 *
 * <h2>A reward that cannot name what it pays FAILS</h2>
 *
 * <p>The same rule covers a reward whose parameters do not describe anything payable - no {@code Item},
 * a {@code Count} of zero, a {@code Lootable} no table answers to. Every one of those throws, naming
 * the parameter at fault. Returning quietly instead would report the reward as PAID, and a payout site
 * that charged a price or spent a completion first would then have no reason to refund it: the player
 * pays, the log stays empty, and nothing anywhere knows the reward never existed.
 */
public final class LootRewardKinds {

    /** Hands over an exact item: {@code {"Item": "Coin_Gold", "Count": "5"}}. */
    public static final String KIND_ITEM = "item";

    /** Rolls a named loot table for the player: {@code {"Lootable": "forestfinds"}}. */
    public static final String KIND_LOOTABLE = "lootable";

    /** Hands over an item with stats already stamped on it. */
    public static final String KIND_STAMPED_ITEM = "stamped_item";

    /** Who these registrations are attributed to in the registry ledger. */
    public static final String OWNER = "ziggfreedcommon";

    private static final AtomicReference<Overflow> OVERFLOW = new AtomicReference<>();

    private static final AtomicReference<FactorRegistry> FACTORS = new AtomicReference<>();

    private LootRewardKinds() {
    }

    /** Where an item that will not fit in the player's inventory goes instead. */
    @FunctionalInterface
    public interface Overflow {
        /** Answer true once the stack has genuinely landed somewhere (usually on the ground). */
        boolean handle(@Nonnull Subject subject, @Nonnull ItemStack stack);
    }

    /**
     * Install the server's overflow policy - usually "drop it at the player's feet". Without one,
     * an item that does not fit fails its grant and is queued for a later attempt instead.
     */
    public static void overflow(@Nullable Overflow sink) {
        OVERFLOW.set(sink);
    }

    /**
     * Point the two rolling kinds at the factor vocabulary their conditions and chances read.
     *
     * <p>Until something calls this, every factor is unanswerable, which SHUTS every gate rather
     * than opening it - a rolled reward on a server that wired no factors pays out only its
     * ungated rolls. Set it once at plugin setup; it is read at grant time, so registration order
     * between this and {@link #registerInto} does not matter.
     */
    public static void factors(@Nullable FactorRegistry registry) {
        FACTORS.set(registry);
    }

    /** Register all three kinds into {@code kinds}. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND_ITEM, OWNER, new ItemHandler());
        kinds.register(KIND_LOOTABLE, OWNER, new LootableHandler());
        kinds.register(KIND_STAMPED_ITEM, OWNER, new StampedItemHandler());
    }

    /**
     * Would this reward's item fit in {@code subject}'s inventory right now, granting nothing?
     *
     * <p>The question to ask BEFORE the moment that pays out: a payout layer that charges a price,
     * spends a completion, or announces a reward and only then discovers a full bag has already
     * done the irreversible half. Probing first turns that into "come back with room", which is a
     * message a player can act on.
     *
     * <p>A spec that needs no room answers TRUE, and that includes the two it cannot know about: a
     * {@code lootable} rolls its contents at grant time, and a kind another mod registered is that
     * mod's business. So a false answer always means a specific, named item that specifically will
     * not fit - never a guess.
     */
    public static boolean canAdd(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
        Handover handover = roomFor(spec);
        if (handover == null) {
            return true;
        }
        Player player = playerOf(subject);
        ItemStack stack = stackOf(handover);
        if (player == null || stack == null) {
            return false;
        }
        try {
            return InventoryGrant.canAdd(player, stack);
        } catch (Throwable t) {
            return false;
        }
    }

    /** What an item-shaped reward would hand over, or null when it needs no inventory room. */
    @Nullable
    private static Handover roomFor(@Nonnull RewardSpec spec) {
        String kind = spec.kind().toLowerCase(Locale.ROOT);
        if (!KIND_ITEM.equals(kind) && !KIND_STAMPED_ITEM.equals(kind)) {
            return null;
        }
        String itemId = itemIdOf(spec);
        int count = countOf(spec);
        return (itemId == null || count <= 0) ? null : new Handover(itemId, count);
    }

    /** One item-shaped reward's payload, resolved once and read by both the probe and the batch. */
    private record Handover(@Nonnull String itemId, int count) {
    }

    /**
     * The stack an item-shaped reward would hand over, or null when this reward needs no inventory
     * room (a rolling kind, another mod's kind, or a spec naming no item).
     *
     * <p>For a caller checking SEVERAL rewards at once. Asking {@link #canAdd} per reward asks each
     * one about the same empty slot, so three rewards each answer yes and the third still lands on
     * the floor; collecting the stacks and asking {@link InventoryGrant#canAddAll} about them as ONE
     * batch is the only form that answers what the payout will actually do. Reading the id and count
     * here rather than at the caller keeps the batch and the grant reading the authored fields the
     * same way.
     */
    @Nullable
    public static ItemStack stackFor(@Nonnull RewardSpec spec) {
        return stackOf(roomFor(spec));
    }

    /** The stack for a resolved handover, or null when there is none or the item cannot be built. */
    @Nullable
    private static ItemStack stackOf(@Nullable Handover handover) {
        if (handover == null) {
            return null;
        }
        try {
            return new ItemStack(handover.itemId(), handover.count());
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== item ====================

    /** {@code {"Item": "<id>", "Count": "<n>"}} - the plain, exact payout. */
    private static final class ItemHandler implements RewardHandler {

        @Override
        public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
            int count = countOf(spec);
            String itemId = requirePayable(KIND_ITEM, itemIdOf(spec), count);
            deliver(subject, new ItemStack(itemId, count));
        }

        @Override
        @Nullable
        public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                @Nonnull String sourceId) {
            return replayGive(spec, subject);
        }
    }

    // ==================== lootable ====================

    /** {@code {"Lootable": "<id>"}} - roll a shared table and hand over whatever it produced. */
    private static final class LootableHandler implements RewardHandler {

        @Override
        public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
            String tableId = spec.paramOr("lootable", spec.paramOr("id", "")).trim();
            if (tableId.isEmpty()) {
                throw new IllegalStateException(
                        "a '" + KIND_LOOTABLE + "' reward named no table - it needs a 'Lootable' parameter");
            }
            Player player = playerOf(subject);
            if (player == null) {
                throw new IllegalStateException("no player to grant lootable '" + tableId + "' to");
            }
            List<Roll> rolls = LootEngine.resolveRolls(LootRef.of(new String[] {tableId}, null), null);
            if (rolls.isEmpty()) {
                throw new IllegalStateException("lootable '" + tableId
                        + "' has no rolls to grant - either no table answers to that id"
                        + " (is the pack that owns it installed?) or the table is empty");
            }
            String trigger = spec.param("trigger");
            LootEngine.rollAndGrant(rolls, trigger, lookupFor(subject),
                    () -> ThreadLocalRandom.current().nextDouble(),
                    LootEngine.Sinks.builder()
                            .items((itemId, count) -> deliverQuietly(subject, itemId, count))
                            .sourceId("reward:" + tableId)
                            .build());
        }
    }

    // ==================== stamped item ====================

    /**
     * {@code {"Item": "<id>", "Pool": "<rollPoolId>"}} to roll the stats fresh, or
     * {@code {"Item": "<id>", "Stats": "Damage:5,Speed:2"}} to hand over exactly those.
     *
     * <p>Both routes write through whatever {@link Stamper} the server registered, so a stamped
     * reward carries the same format - and counts against the same budgets - as one earned at an
     * anvil. With no stamper registered the item is still handed over, just bare: an unstamped
     * reward is a smaller disappointment than no reward.
     */
    private static final class StampedItemHandler implements RewardHandler {

        @Override
        public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
            int count = countOf(spec);
            String itemId = requirePayable(KIND_STAMPED_ITEM, itemIdOf(spec), count);
            ItemStack stack = new ItemStack(itemId, count);
            Stamper stamper = StamperRegistry.get();
            List<StatRoll> rolls = rollsFor(spec, subject);
            if (stamper != null && !rolls.isEmpty()) {
                stack = stamper.apply(stack, rolls);
            }
            deliver(subject, stack);
        }

        @Override
        @Nullable
        public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                @Nonnull String sourceId) {
            // A retry hands over the BARE item: the stats were rolled for a moment that has passed,
            // and replaying the roll later would quietly award a different item than the one the
            // player was told about.
            return replayGive(spec, subject);
        }
    }

    /**
     * The stats a stamped-item reward carries: written-out {@code Stats} exactly as authored, or a
     * fresh roll of the named {@code Pool} held inside its own budgets.
     */
    @Nonnull
    private static List<StatRoll> rollsFor(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
        String written = spec.param("stats");
        if (written != null && !written.isBlank()) {
            return parseStats(written);
        }
        String poolId = spec.paramOr("pool", "").trim();
        if (poolId.isEmpty()) {
            return List.of();
        }
        StampSpec stampSpec = StampSpec.of(poolId, null,
                StampSpec.Picks.of((int) spec.longParam("picks", 1), (int) spec.longParam("picks", 1)),
                true, null);
        StampPlan plan = StampCapEngine.resolve(stampSpec, StampInspection.empty(),
                lookupFor(subject), () -> ThreadLocalRandom.current().nextDouble());
        return plan.entries();
    }

    /** {@code "Damage:5,Speed:2"} read as a stat list; a malformed pair is skipped, never fatal. */
    @Nonnull
    static List<StatRoll> parseStats(@Nonnull String written) {
        List<StatRoll> out = new ArrayList<>();
        for (String pair : written.split(",")) {
            int colon = pair.lastIndexOf(':');
            if (colon <= 0 || colon == pair.length() - 1) {
                continue;
            }
            String statId = pair.substring(0, colon).trim();
            if (statId.isEmpty()) {
                continue;
            }
            try {
                int points = Integer.parseInt(pair.substring(colon + 1).trim());
                if (points > 0) {
                    out.add(new StatRoll(statId, points));
                }
            } catch (NumberFormatException ignored) {
                // A mistyped number costs its own stat, never the whole reward.
            }
        }
        return out;
    }

    // ==================== shared plumbing ====================

    /**
     * The item id an item-shaped reward will hand over, or a THROW naming what is missing. Both ways
     * of describing nothing - no {@code Item}, or a {@code Count} that hands over none of it - are
     * authoring mistakes, and a mistake reported loudly is one an owner can fix.
     */
    @Nonnull
    private static String requirePayable(@Nonnull String kind, @Nullable String itemId, int count) {
        if (itemId == null) {
            throw new IllegalStateException(
                    "a '" + kind + "' reward named no item - it needs an 'Item' parameter");
        }
        if (count <= 0) {
            throw new IllegalStateException(
                    "a '" + kind + "' reward for '" + itemId + "' has a 'Count' of " + count
                            + ", so it would hand over nothing");
        }
        return itemId;
    }

    @Nullable
    private static String itemIdOf(@Nonnull RewardSpec spec) {
        String itemId = spec.paramOr("item", spec.paramOr("id", "")).trim();
        return itemId.isEmpty() ? null : itemId;
    }

    private static int countOf(@Nonnull RewardSpec spec) {
        long count = spec.longParam("count", spec.longParam("quantity", 1L));
        return count <= 0 ? 0 : (int) Math.min(count, Integer.MAX_VALUE);
    }

    @Nullable
    private static Player playerOf(@Nonnull Subject subject) {
        return subject.handleAs(Player.class);
    }

    @Nonnull
    private static FactorLookup lookupFor(@Nonnull Subject subject) {
        FactorRegistry factors = FACTORS.get();
        if (factors == null) {
            return FactorLookup.none();
        }
        Player player = playerOf(subject);
        FactorContext.Builder ctx = FactorContext.builder();
        if (player != null && player.getReference() != null && player.getReference().isValid()) {
            ctx.subject(player.getReference()).store(player.getReference().getStore());
        }
        return new FactorSnapshot(factors, ctx.build());
    }

    /**
     * Hand {@code stack} over, or THROW when it went nowhere. Throwing is the point: the payout layer
     * catches it, asks for a replayable command, and parks the reward for the player's next connect.
     */
    private static void deliver(@Nonnull Subject subject, @Nonnull ItemStack stack) throws Exception {
        Player player = playerOf(subject);
        if (player == null) {
            throw new IllegalStateException("no player to grant '" + stack.getItemId() + "' to");
        }
        boolean[] overflowed = {false};
        InventoryGrant.Landed landed = InventoryGrant.grant(player, stack, leftover -> {
            Overflow sink = OVERFLOW.get();
            overflowed[0] = sink != null && sink.handle(subject, leftover);
        });
        if (landed == InventoryGrant.Landed.FALLBACK && !overflowed[0]) {
            throw new IllegalStateException("'" + stack.getItemId() + "' did not fit and nowhere to put it");
        }
    }

    /** The item-sink form: deliver and report how many landed, without throwing. */
    private static int deliverQuietly(@Nonnull Subject subject, @Nonnull String itemId, int count) {
        try {
            deliver(subject, new ItemStack(itemId, count));
            return count;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * The command that would hand this reward over later, or null when the reward describes nothing
     * to hand over. Null is the honest answer for a spec with no item or a count of none: a retry
     * that rounded either of those up to one would pay out something nobody authored, and quietly
     * turn an authoring mistake into a live payout instead of a reported one.
     */
    @Nullable
    private static String replayGive(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
        String itemId = itemIdOf(spec);
        int count = countOf(spec);
        if (itemId == null || count <= 0) {
            return null;
        }
        return "give " + subject.name() + " " + itemId + " --quantity=" + count;
    }

    /** Every parameter key these kinds read, for a validator that wants to warn about a typo. */
    @Nonnull
    public static Map<String, List<String>> parameterKeys() {
        return Map.of(
                KIND_ITEM, List.of("item", "count"),
                KIND_LOOTABLE, List.of("lootable", "trigger"),
                KIND_STAMPED_ITEM, List.of("item", "count", "pool", "stats", "picks"));
    }
}
