package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.command.CommandRunner;
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
import com.ziggfreed.common.util.CommandExecutor;
import com.ziggfreed.common.util.SafeLog;

/**
 * The four reward kinds the framework itself can pay out, ready to register into a
 * {@link RewardKindRegistry}.
 *
 * <table>
 *   <caption>What each kind reads</caption>
 *   <tr><th>Kind</th><th>Parameters</th></tr>
 *   <tr><td>{@code Item}</td><td>{@code Item}, {@code Count}</td></tr>
 *   <tr><td>{@code Lootable}</td><td>{@code Lootable}, {@code Trigger}</td></tr>
 *   <tr><td>{@code Stamped_Item}</td>
 *       <td>{@code Item}, {@code Count}, and EITHER {@code Pool} (roll it) or {@code Stats}
 *           (written out, {@code "Damage:5,Speed:2"})</td></tr>
 *   <tr><td>{@code Command}</td>
 *       <td>{@code Command}, and optionally {@code RunAs} ({@code Console} or {@code Player}) and
 *           {@code DelayTicks}</td></tr>
 * </table>
 *
 * <p>Ids are native-asset style, PascalCase with underscores, and the framework's own are
 * UNPREFIXED. A kind belonging to one mod carries that mod's prefix instead ({@code Mmo_Xp}), so two
 * mods installed together cannot collide by accident. Whatever a reward spells a kind, it is matched
 * case-insensitively.
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
    public static final String KIND_ITEM = "Item";

    /** Rolls a named loot table for the player: {@code {"Lootable": "forestfinds"}}. */
    public static final String KIND_LOOTABLE = "Lootable";

    /** Hands over an item with stats already stamped on it. */
    public static final String KIND_STAMPED_ITEM = "Stamped_Item";

    /**
     * Runs an authored command line: {@code {"Command": "/give {player} Coin_Gold --quantity=5"}}.
     *
     * <p>Unprefixed like its siblings because running a command is not any one mod's idea: it is the
     * capability every server already has, and the one payout whose behaviour is written per reward
     * rather than per kind. A kind written as a FILE
     * ({@code Server/ZiggfreedCommon/RewardKinds/}) is the other half of the same idea - use that
     * when the same command shape repeats across many rewards and deserves a named schema, and this
     * when the line belongs to the one reward that authored it.
     */
    public static final String KIND_COMMAND = "Command";

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

    /** Register all four kinds into {@code kinds}. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND_ITEM, OWNER, new ItemHandler());
        kinds.register(KIND_LOOTABLE, OWNER, new LootableHandler());
        kinds.register(KIND_STAMPED_ITEM, OWNER, new StampedItemHandler());
        kinds.register(KIND_COMMAND, OWNER, new CommandHandler());
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
     * {@code Lootable} rolls its contents at grant time, and a kind another mod registered is that
     * mod's business. So a false answer always means a specific, named item that specifically will
     * not fit - never a guess.
     */
    public static boolean canAdd(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
        Handover handover = roomFor(spec);
        ItemStack stack = handover != null ? stackOf(handover) : commandStackFor(spec, subject, "");
        if (handover == null && stack == null) {
            return true;
        }
        Player player = playerOf(subject);
        if (player == null || stack == null) {
            return false;
        }
        try {
            return InventoryGrant.canAdd(player, stack);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Would EVERY reward in {@code rewards} have somewhere to go right now, granting nothing? The
     * probe a payout site asks before its irreversible half: charging a price, spending a
     * completion, marking something collected.
     *
     * <p><b>The whole list is asked about ONCE.</b> Calling {@link #canAdd} per reward in a loop
     * asks each of them about the same last free slot, so three item rewards each answer yes and
     * the third still lands on the floor - after the price was charged, which is the exact moment
     * this exists to prevent. Every stack the payout would hand over is collected through
     * {@link #stackFor} and asked of {@link InventoryGrant#canAddAll} as one batch, so the answer
     * is what the payout will actually do.
     *
     * <p>Rewards that need no room are skipped rather than refused, exactly as {@link #canAdd}
     * skips them: a {@code Lootable} rolls its contents at grant time and another mod's kind is
     * that mod's business. A payout with no player behind it is not blocked, since it never
     * reaches an inventory. So a false answer always names specific items that specifically will
     * not fit.
     */
    public static boolean canAddAll(@Nonnull List<RewardSpec> rewards, @Nonnull Subject subject) {
        return canAddAll(rewards, subject, "");
    }

    /**
     * As {@link #canAddAll(List, Subject)}, telling the probe what is paying out so a
     * {@code Command} reward's line reads exactly as it will at grant time (its {@code {source}}
     * placeholder among the rest). Any surface that knows its own label passes it; the two-argument
     * form is the same question asked without one.
     */
    public static boolean canAddAll(@Nonnull List<RewardSpec> rewards, @Nonnull Subject subject,
            @Nonnull String sourceId) {
        if (rewards.isEmpty()) {
            return true;
        }
        List<ItemStack> incoming = new ArrayList<>();
        for (RewardSpec spec : rewards) {
            if (spec == null) {
                continue;
            }
            ItemStack stack = stackFor(spec, subject, sourceId);
            if (stack != null) {
                incoming.add(stack);
            }
        }
        if (incoming.isEmpty()) {
            return true;
        }
        Player player = playerOf(subject);
        if (player == null) {
            return true;
        }
        try {
            return InventoryGrant.canAddAll(player, incoming);
        } catch (Throwable t) {
            return false;
        }
    }

    /** What an item-shaped reward would hand over, or null when it needs no inventory room. */
    @Nullable
    private static Handover roomFor(@Nonnull RewardSpec spec) {
        String kind = spec.kind();
        if (!KIND_ITEM.equalsIgnoreCase(kind) && !KIND_STAMPED_ITEM.equalsIgnoreCase(kind)) {
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

    /**
     * The same, for a caller that can also say WHO the payout is for: the item arms as above, plus
     * a {@code Command} reward whose line is a {@code give}.
     *
     * <p>A command that hands over an item is still an item arriving in a bag, and a probe that
     * could not see that would let a hand-in spend a completion for a reward that lands on the
     * floor - the exact failure the batch exists to prevent. So the line is resolved exactly as the
     * grant will resolve it and read back through {@link CommandRunner#readGive}: one place knows
     * what a give line means, and a preview cannot promise a different count than the payout
     * delivers. A command that gives nothing (a title, a teleport, a script) still answers null and
     * needs no room.
     *
     * <p>Never throws and never runs anything: a line that cannot be resolved at all is read as
     * needing no room rather than as a refusal, because refusing a payout on the strength of an
     * authoring mistake would hide the mistake behind a message about a full bag.
     */
    @Nullable
    public static ItemStack stackFor(@Nonnull RewardSpec spec, @Nonnull Subject subject,
            @Nonnull String sourceId) {
        ItemStack item = stackOf(roomFor(spec));
        return item != null ? item : commandStackFor(spec, subject, sourceId);
    }

    /** What a {@code Command} reward's own line would hand over, or null when it hands over no item. */
    @Nullable
    private static ItemStack commandStackFor(@Nonnull RewardSpec spec, @Nonnull Subject subject,
            @Nonnull String sourceId) {
        if (!KIND_COMMAND.equalsIgnoreCase(spec.kind())) {
            return null;
        }
        try {
            CommandRunner.Give give = CommandRunner.readGive(resolveCommand(spec, subject, sourceId));
            return give == null ? null : new ItemStack(give.itemId(), Math.max(1, give.quantity()));
        } catch (Throwable t) {
            return null;
        }
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

    // ==================== Item ====================

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

    // ==================== Lootable ====================

    /** {@code {"Lootable": "<id>"}} - roll a shared table and hand over whatever it produced. */
    private static final class LootableHandler implements RewardHandler {

        @Override
        public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
            String tableId = spec.paramOr("lootable", spec.paramOr("id", "")).trim();
            if (tableId.isEmpty()) {
                throw new IllegalStateException("a reward of kind '" + KIND_LOOTABLE
                        + "' named no table - it needs a 'Lootable' parameter");
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

    // ==================== Stamped_Item ====================

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

    // ==================== Command ====================

    /** The command line to run, with {@code {player}}, {@code {uuid}} and its own parameters in it. */
    public static final String P_COMMAND = "command";

    /** {@code Console} (the default) or {@code Player}: whose authority the line runs with. */
    public static final String P_RUN_AS = "runas";

    /** How long to wait before running it, in twentieths of a second. Zero runs it now. */
    public static final String P_DELAY_TICKS = "delayticks";

    /** What paid out, offered to the template as {@code {source}} whether or not it was stamped on. */
    private static final String P_SOURCE = "source";

    /** The one {@code RunAs} value that is not the console. */
    private static final String RUN_AS_PLAYER = "player";

    /**
     * {@code {"Command": "<line>"}} - run an authored command line for the player.
     *
     * <p>The template speaks the same vocabulary a kind FILE's does, resolved through the same
     * substitution: {@code {player}}, {@code {uuid}}, {@code {source}}, and every parameter the
     * reward itself carries. That is the whole difference between the two - a file declares its
     * parameters up front and many rewards fill them in, while this one carries its line and its
     * values together.
     *
     * <p>{@code RunAs: Player} runs the line with the player's own authority, which needs a live
     * player; without one the grant FAILS rather than quietly falling back to the console, because
     * console authority is the wider of the two and a reward should never grow permissions by
     * accident. A failed grant is queued as its console form by the payout layer, so nothing is lost.
     */
    private static final class CommandHandler implements RewardHandler {

        @Override
        public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
            grant(spec, subject, "");
        }

        @Override
        public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                @Nonnull String sourceId) throws Exception {
            String line = resolveCommand(spec, subject, sourceId);
            boolean asPlayer = RUN_AS_PLAYER.equalsIgnoreCase(spec.paramOr(P_RUN_AS, "").trim());
            PlayerRef playerRef = subject.handleAs(PlayerRef.class);
            if (asPlayer && playerRef == null) {
                throw new IllegalStateException("a reward of kind '" + KIND_COMMAND + "' asked to run '"
                        + line + "' as the player, and there is no live player to run it as");
            }
            long delayTicks = Math.max(0L, spec.longParam(P_DELAY_TICKS, 0L));
            if (delayTicks <= 0L) {
                List<String> failures = new ArrayList<>();
                if (!dispatch(line, asPlayer, playerRef, failures::add)) {
                    throw new IllegalStateException("a reward of kind '" + KIND_COMMAND
                            + "' could not run '" + line + "'"
                            + (failures.isEmpty() ? "" : ": " + failures.get(0)));
                }
                return;
            }
            // Deferred through the JDK's own delayer, whose thread is a daemon: a reward waiting on
            // an animation must not be the reason a server cannot shut down. A line that fails after
            // the wait has nobody left to throw to, so it reports itself instead of being retried -
            // the payout was already counted as granted the moment the wait started.
            CompletableFuture.runAsync(() -> dispatch(line, asPlayer, playerRef, SafeLog::warn),
                    CompletableFuture.delayedExecutor(delayTicks * 50L, TimeUnit.MILLISECONDS));
        }

        @Override
        @Nullable
        public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                @Nonnull String sourceId) {
            try {
                // The console form on purpose: a retry runs with nobody watching, so there is no
                // player session to borrow authority from.
                return resolveCommand(spec, subject, sourceId);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /**
     * The command line this reward would run, fully substituted, or a THROW naming what is missing.
     * One resolver behind the live run and the retry, so the two can never be different commands.
     */
    @Nonnull
    private static String resolveCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
            @Nonnull String sourceId) {
        String template = spec.paramOr(P_COMMAND, "").trim();
        if (template.isEmpty()) {
            throw new IllegalStateException("a reward of kind '" + KIND_COMMAND
                    + "' named no command - it needs a 'Command' parameter");
        }
        Map<String, String> placeholders = CommandRewardKind.placeholders(spec, subject);
        placeholders.putIfAbsent(P_SOURCE, sourceId);
        List<String> resolved = CommandRunner.resolveAll(List.of(template), placeholders);
        if (resolved.isEmpty()) {
            throw new IllegalStateException("a reward of kind '" + KIND_COMMAND
                    + "' resolved to an empty command line");
        }
        return resolved.get(0);
    }

    /**
     * Run one already-resolved line, as the player when asked and as the console otherwise. False
     * when it did not run, with the reason handed to {@code failures}.
     */
    private static boolean dispatch(@Nonnull String line, boolean asPlayer,
            @Nullable PlayerRef playerRef, @Nonnull Consumer<String> failures) {
        if (asPlayer && playerRef != null) {
            return CommandRunner.runWith(
                    resolved -> CommandExecutor.executeAsPlayer(playerRef, resolved), line, null, failures);
        }
        return CommandRunner.run(line, null, failures);
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
                    "a reward of kind '" + kind + "' named no item - it needs an 'Item' parameter");
        }
        if (count <= 0) {
            throw new IllegalStateException(
                    "a reward of kind '" + kind + "' for '" + itemId + "' has a 'Count' of " + count
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
                KIND_STAMPED_ITEM, List.of("item", "count", "pool", "stats", "picks"),
                KIND_COMMAND, List.of(P_COMMAND, P_RUN_AS, P_DELAY_TICKS));
    }
}
