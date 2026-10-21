package com.ziggfreed.common.loot.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.FactorLookup;
import com.ziggfreed.common.loot.FactorSnapshot;
import com.ziggfreed.common.loot.GroundSpillSinks;
import com.ziggfreed.common.loot.LootCues;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.stamp.StampCapEngine;
import com.ziggfreed.common.loot.stamp.StampInspection;
import com.ziggfreed.common.loot.stamp.StampPlan;
import com.ziggfreed.common.loot.stamp.RollPoolConfig;
import com.ziggfreed.common.loot.stamp.StampIdentity;
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
 * <h2>Every item lands the one way, and a full inventory does not eat it</h2>
 *
 * <p>Every item a reward hands straight to a player, an {@code Item}, a {@code Stamped_Item} or
 * whatever a {@code Lootable} rolled, goes through ONE delivery, {@link #handToPlayer}: the way a
 * picked-up item lands ({@link GroundSpillSinks.Builder#pickedUpFirst}), where the player's own
 * pickup settings route it, with the game's own pickup notice and sound for what went in, and
 * whatever does not fit to the {@link #overflow(Overflow) overflow policy}. Its receipt rows are
 * {@link RewardSpec#pickupNoticed() pickup-noticed}, so the corner feed does not repeat them. A payout
 * that must land in the bag or not at all (a capped item currency) takes its bag-only form,
 * {@link #handIntoBag}, the same pickup with no overflow.
 *
 * <p>The library installs a default policy at boot ({@link FeetDropOverflow}: the rest lands on the
 * ground at the player's feet, through the same tick-safe spawn seam every ground drop uses), so out
 * of the box a full bag means a pickup. A consumer replaces the policy with its own
 * {@link #overflow(Overflow)} call, or clears it with null. With no policy, an {@code Item} or
 * {@code Stamped_Item} that does not fit WHOLE is not handed over at all and the grant FAILS, as it
 * does when the policy could not land any of it. Failing is the useful outcome: the payout layer then
 * asks for a replayable command and parks it for the player's next connect, so a reward earned with
 * a full bag arrives later instead of vanishing quietly. A part the bag took is never parked again
 * (a replay would pay it twice): if the policy refuses only the rest, that rest is warned as lost.
 *
 * <p>A {@code Lootable}'s rest goes to the policy as ONE pile per hand-over
 * ({@link Overflow#handleAll}), so a drop list that overflows lands together. Those contents cannot
 * be parked: a rolled table has no replayable form, since a replay would roll differently. So with no
 * policy, or a pile the policy could not land, they are LOST and the loss is warned, naming the table
 * and the stacks, never dropped silently.
 *
 * <p>The room probes ({@link #canAdd}, {@link #canAddAll}) ask the question that delivery answers
 * ({@link GroundSpillSinks#pickupFits}), for a payout that refuses rather than spills.
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

        /**
         * Land several stacks that came out of ONE hand-over (everything a rolled drop list put
         * past a full bag), answering true only when every one of them landed.
         *
         * <p>All or nothing, because the ground-spill preset counts a pile whole: a true answer
         * reports every stack in it as found, so a pile that landed in part must answer false
         * rather than claim the part that went nowhere. Every stack is still offered, and a stack
         * that fails or throws never costs the ones after it. An empty pile answers true.
         *
         * <p>This default offers each stack to {@link #handle} in order. A policy that can land a
         * pile in one go overrides it, so the pile arrives together rather than as one landing per
         * stack.
         */
        default boolean handleAll(@Nonnull Subject subject, @Nonnull List<ItemStack> stacks) {
            boolean all = true;
            for (ItemStack stack : stacks) {
                try {
                    all &= stack != null && handle(subject, stack);
                } catch (Throwable t) {
                    all = false;
                }
            }
            return all;
        }
    }

    /** The overflow policy installed right now, or null when there is none. Read at hand-over time. */
    @Nullable
    static Overflow installedOverflow() {
        return OVERFLOW.get();
    }

    /**
     * Install the server's overflow policy. The wiring root installs the default at boot
     * ({@link FeetDropOverflow}: drop it on the ground at the player's feet), and a consumer's own
     * call REPLACES it - consumer setup runs after the library's, so the last policy installed
     * wins. Passing null clears the sink entirely, restoring fail-and-park: an item that does not
     * fit fails its grant and is queued for a later attempt instead.
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

    /**
     * The factor vocabulary the rolling kinds read, or null before the wiring root installs one.
     * Anything else that rolls loot on the same terms (the library's bonus rows) reads it too, so a
     * gate means one thing on every roll.
     */
    @Nullable
    public static FactorRegistry installedFactors() {
        return FACTORS.get();
    }

    /** Register all four kinds into {@code kinds}. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND_ITEM, OWNER, new ItemHandler());
        // The Lootable handler keeps the registry it was registered into: a rolled table's own
        // Rewards entries are paid through the same vocabulary the outer reward came from.
        kinds.register(KIND_LOOTABLE, OWNER, new LootableHandler(kinds));
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
     * mod's business, unless it is a kind FILE whose {@code Hands} names the item its command hands
     * over ({@link RewardKindAsset.Hands}). So a false answer always means a specific, named item
     * that specifically will not fit - never a guess. The room is the delivery's own
     * ({@link #fits}): what the player's pickup routing could take.
     */
    public static boolean canAdd(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
        Handover handover = roomFor(spec);
        ItemStack stack = handover != null ? stackOf(handover) : commandStackFor(spec, subject, "");
        if (handover == null && stack == null) {
            return true;
        }
        Player player = playerOf(subject);
        return player != null && stack != null && fits(player, List.of(stack));
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
     * {@link #stackFor} and asked as one batch of the room the delivery fills
     * ({@link GroundSpillSinks#pickupFits}, each stack seeing what the ones before it left), so the
     * answer is what the payout will actually do.
     *
     * <p>Rewards that need no room are skipped rather than refused, exactly as {@link #canAdd}
     * skips them: a {@code Lootable} rolls its contents at grant time and another mod's kind is
     * that mod's business, unless its kind file's {@code Hands} names the item it hands over. A
     * payout with no player behind it is not blocked, since it never reaches an inventory. So a
     * false answer always names specific items that specifically will not fit.
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
        return canAddAll(rewards, subject, sourceId, LootRewardKinds::bagTakes);
    }

    /**
     * As {@link #canAddAll(List, Subject, String)}, asking {@code bag} whether the one batch fits.
     * The seam a unit test drives, since a unit JVM has no live player whose inventory could answer;
     * the public forms ask the subject's own inventory.
     */
    static boolean canAddAll(@Nonnull List<RewardSpec> rewards, @Nonnull Subject subject,
            @Nonnull String sourceId, @Nonnull BiPredicate<Subject, List<ItemStack>> bag) {
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
        return incoming.isEmpty() || bag.test(subject, incoming);
    }

    /**
     * Would {@code subject}'s inventory take every stack in {@code incoming} at once? A payout with
     * no player behind it never reaches an inventory, so it is not blocked; a probe that throws
     * answers false.
     */
    private static boolean bagTakes(@Nonnull Subject subject, @Nonnull List<ItemStack> incoming) {
        Player player = playerOf(subject);
        return player == null || fits(player, incoming);
    }

    /**
     * Would {@code player}'s pickup routing take every one of {@code stacks} together, granting
     * nothing ({@link GroundSpillSinks#pickupFits})? The one room question the probes and the
     * no-policy delivery ask, so a "yes" means the delivery puts it all in the bag. False for a player
     * with no live reference, or when the bag cannot be asked.
     */
    private static boolean fits(@Nonnull Player player, @Nonnull List<ItemStack> stacks) {
        Ref<EntityStore> ref = player.getReference();
        return ref != null && GroundSpillSinks.pickupFits(ref, ref.getStore(), stacks);
    }

    /** What an item-shaped reward would hand over, or null when it needs no inventory room. */
    @Nullable
    private static Handover roomFor(@Nonnull RewardSpec spec) {
        if (!isItemKind(spec.kind())) {
            return handsOf(spec);
        }
        String itemId = itemIdOf(spec);
        int count = countOf(spec);
        return (itemId == null || count <= 0) ? null : new Handover(itemId, count);
    }

    /**
     * What a reward of a kind FILE hands over by its file's own word ({@link RewardKindAsset.Hands}),
     * or null when the kind names no item or is not a kind file. Read off the handler the kind fold
     * registered into the shared vocabulary, which carries the file it pays, so the probe asks the
     * same kind that will pay.
     */
    @Nullable
    private static Handover handsOf(@Nonnull RewardSpec spec) {
        RewardHandler handler = RewardKinds.shared().handler(spec.kind());
        RewardKindAsset.Hands hands = handler instanceof CommandRewardKind authored
                ? authored.kind().getHands() : null;
        String itemId = hands == null ? null : hands.itemId();
        return itemId == null ? null : new Handover(itemId, hands.effectiveCount());
    }

    /** One item-shaped reward's payload, resolved once and read by both the probe and the batch. */
    private record Handover(@Nonnull String itemId, int count) {
    }

    /**
     * The stack an item-shaped reward would hand over, or null when this reward needs no inventory
     * room (a rolling kind, another mod's kind, or a spec naming no item). A reward of a kind FILE
     * whose {@code Hands} names an item is item-shaped too: {@code Count} of that item
     * ({@link RewardKindAsset.Hands}).
     *
     * <p>For a caller checking SEVERAL rewards at once. Asking {@link #canAdd} per reward asks each
     * one about the same empty slot, so three rewards each answer yes and the third still lands on
     * the floor; collecting the stacks and asking {@link GroundSpillSinks#pickupFits} about them as
     * ONE batch is the only form that answers what the payout will actually do. Reading the id and count
     * here rather than at the caller keeps the batch and the grant reading the authored fields the
     * same way.
     */
    @Nullable
    public static ItemStack stackFor(@Nonnull RewardSpec spec) {
        return stackOf(roomFor(spec));
    }

    /**
     * The same, for a caller that can also say WHO the payout is for: the item arms as above (a kind
     * file's {@code Hands} among them), plus a {@code Command} reward whose line is a {@code give}.
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

    /**
     * {@code {"Item": "<id>", "Count": "<n>"}} - the plain, exact payout, handed over through the one
     * delivery ({@link #deliver}). {@code StackNameKey} and {@code StackNameArg} name the stack
     * ({@link StackNames}).
     */
    private static final class ItemHandler implements RewardHandler {

        @Override
        public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
            grant(spec, subject, "", row -> { });
        }

        @Override
        public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId,
                @Nonnull Consumer<RewardSpec> receipt) throws Exception {
            int count = countOf(spec);
            String itemId = requirePayable(KIND_ITEM, itemIdOf(spec), count);
            int landed = deliver(subject, StackNames.stamp(new ItemStack(itemId, count), spec), sourceId);
            receipt.accept(landedRow(spec, landed));
        }

        @Override
        @Nullable
        public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                @Nonnull String sourceId) {
            return replayGive(spec, subject);
        }

        @Override
        @Nullable
        public String scalableRetryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                @Nonnull String sourceId) {
            return replayGive(spec, subject, InstanceReward.AMOUNT_PLACEHOLDER);
        }
    }

    // ==================== Lootable ====================

    /**
     * The table a {@code Lootable} reward names ({@code Lootable}, else {@code id}), trimmed; empty when it
     * names none. The one read of it, shared by the payout and the chip's preview ({@link LootablePreview}).
     */
    @Nonnull
    static String tableIdOf(@Nonnull RewardSpec spec) {
        return spec.paramOr("lootable", spec.paramOr("id", "")).trim();
    }

    /**
     * {@code {"Lootable": "<id>"}} - roll a shared table and hand over whatever it produced: its
     * rolls, then its pool's draws ({@link #resolveTable}, {@link #rollTable}), so a table authored
     * as a pool alone pays the same here as at any other site.
     *
     * <p>The whole grants vocabulary pays here, exactly as it does at a station: {@code Items} land
     * as a pickup (the player's pickup routing, the game's own notice), {@code DropLists} roll their
     * native tables and land the same way, {@code Commands} run as
     * the console with the same placeholders a {@code Command} reward reads, and {@code Rewards}
     * pay through the registry this handler was registered into - so a table pays the same whether
     * a station, a mob drop or a quest reward rolled it. The pass's EARNED cues are forwarded to
     * {@link LootCues}, which is where a server's presenter hears about them.
     *
     * <p><b>Its receipt is what the roll produced, never the table's name.</b> The reward only
     * learns what it pays while paying it, so it reports one {@code Item} spec per item that landed
     * (a native drop list's stacks included) plus whatever the table's own {@code Rewards} reported
     * in turn - and nothing at all for a roll that produced nothing. A toast raised after the payout
     * then lists the items the player actually got, and an empty hand adds no row. Those item rows
     * are {@link RewardSpec#pickupNoticed() pickup-noticed}, since the game's own notice said them.
     *
     * <p>Every row it reports points back at this reward ({@link RewardSpec#withOrigin}), replacing
     * whatever origin a nested table stamped on it, so a table inside a table folds into the
     * OUTERMOST lootable's section of a toast.
     */
    private static final class LootableHandler implements RewardHandler {

        /**
         * How deep a table may nest before the grant refuses. A table whose {@code Rewards} grant
         * another {@code Lootable} is legitimate composition; a chain that leads back to itself
         * would roll forever, and four levels is already more composition than any authored table
         * needs.
         */
        static final int MAX_NESTED = 4;

        private static final ThreadLocal<Integer> NESTED = ThreadLocal.withInitial(() -> 0);

        /** The vocabulary a rolled table's own {@code Rewards} entries are paid through. */
        private final RewardKindRegistry kinds;

        LootableHandler(@Nonnull RewardKindRegistry kinds) {
            this.kinds = kinds;
        }

        @Override
        public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
            grant(spec, subject, "", handedOver -> { });
        }

        @Override
        public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                @Nonnull String sourceId, @Nonnull Consumer<RewardSpec> receipt) throws Exception {
            String tableId = tableIdOf(spec);
            if (tableId.isEmpty()) {
                throw new IllegalStateException("a reward of kind '" + KIND_LOOTABLE
                        + "' named no table - it needs a 'Lootable' parameter");
            }
            Player player = playerOf(subject);
            if (player == null) {
                throw new IllegalStateException("no player to grant lootable '" + tableId + "' to");
            }
            LootEngine.Resolved table = resolveTable(tableId);
            int depth = NESTED.get();
            if (depth >= MAX_NESTED) {
                throw new IllegalStateException("lootable '" + tableId + "' sits " + depth
                        + " Lootable rewards deep - a chain of tables granting each other that runs"
                        + " this long is almost certainly a loop, so the grant refuses rather than"
                        + " rolling forever");
            }
            String trigger = spec.param("trigger");
            // Labelled by the TABLE rather than by what paid the outer reward: the roll's logs, its
            // retry commands and its cues all name the table, which is what an owner tuning it
            // looks for.
            String tableSource = "reward:" + tableId;
            NESTED.set(depth + 1);
            LootEngine.Result result;
            try {
                result = rollTable(table, trigger, lookupFor(subject),
                        () -> ThreadLocalRandom.current().nextDouble(),
                        lootableSinks(spec, subject, kinds, tableSource));
            } finally {
                NESTED.set(depth);
            }
            LootCues.presentAll(result.getCues(), subject, tableSource);
            for (RewardSpec landed : handedOver(result)) {
                receipt.accept(landed.withOrigin(spec));
            }
        }
    }

    /**
     * Everything the table {@code tableId} evaluates when a {@code Lootable} reward rolls it: its
     * rolls AND its pool ({@link LootEngine#resolve}), with whatever other packs and roll sources
     * added to it. A table authored as a pool alone pays from that pool. Throws when the table has
     * neither, which is either no table answering to the id or an empty one, so the reward fails
     * loud rather than reporting a payout of nothing. Package-private so the seam is pinned with no
     * server behind it.
     */
    @Nonnull
    static LootEngine.Resolved resolveTable(@Nonnull String tableId) {
        LootEngine.Resolved table = LootEngine.resolve(LootRef.of(new String[] {tableId}, null), null);
        if (table.rolls().isEmpty() && table.pools().isEmpty()) {
            throw new IllegalStateException("lootable '" + tableId
                    + "' has no rolls or pool to grant - either no table answers to that id"
                    + " (is the pack that owns it installed?) or the table is empty");
        }
        return table;
    }

    /**
     * One pass over a resolved table, its rolls then its pool's draws, through {@code sinks}: the
     * pass a {@code Lootable} reward makes. Package-private so the seam is pinned with no server
     * behind it.
     */
    @Nonnull
    static LootEngine.Result rollTable(@Nonnull LootEngine.Resolved table, @Nullable String trigger,
            @Nonnull FactorLookup lookup, @Nonnull DoubleSupplier sample, @Nonnull LootEngine.Sinks sinks) {
        return LootEngine.rollAndGrant(table.rolls(), table.pools(), trigger, lookup, sample, sinks);
    }

    /**
     * What one rolled pass put in the player's hands, as the specs a receipt lists: one
     * {@code Item} per item that landed ({@link LootEngine.Result#getItems()}, native drop-list
     * stacks already merged in), then whatever the table's own {@code Rewards} reported in turn
     * ({@link LootEngine.Result#getRewardReceipt()}). Empty for a roll that produced nothing.
     *
     * <p>Each item row is {@link RewardSpec#asPickupNoticed() pickup-noticed}: the pass's items
     * landed as a pickup ({@link #lootableSinks}), so the game's own notice already said them, or
     * will when the player picks up a part that dropped at their feet. A row a nested reward
     * reported keeps whatever its own handler said. Package-private so the fold is pinned with no
     * server behind it.
     */
    @Nonnull
    static List<RewardSpec> handedOver(@Nonnull LootEngine.Result result) {
        List<RewardSpec> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : result.getItems().entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0) {
                continue;
            }
            out.add(RewardSpec.of(KIND_ITEM, Map.of(
                    "Item", entry.getKey(),
                    "Count", Integer.toString(entry.getValue()))).asPickupNoticed());
        }
        out.addAll(result.getRewardReceipt());
        return out;
    }

    /**
     * Every sink a rolled table can pay through, wired to what the reward path already has: the one
     * delivery for items and drop lists ({@link #handToPlayer}: the bag first, the way a picked-up
     * item lands, then the installed overflow policy as its ground), the console dispatcher with the same
     * placeholder vocabulary a {@code Command} reward reads ({@code {player}}, {@code {uuid}},
     * {@code {source}}, and the reward's own parameters), and the registry the {@code Lootable}
     * kind itself lives in. Package-private so a test can prove the wiring without a live server
     * behind it.
     */
    @Nonnull
    static LootEngine.Sinks lootableSinks(@Nonnull RewardSpec spec, @Nonnull Subject subject,
            @Nonnull RewardKindRegistry kinds, @Nonnull String sourceId) {
        return lootableSinks(spec, subject, kinds, sourceId, SafeLog::warn);
    }

    /**
     * As above, reporting to {@code warn}, so a test can read the line a lost pile leaves.
     *
     * <p>The ground is the player's own overflow policy, never an outer pass's ground: a table a
     * reward rolls pays the player wherever the reward was earned. The policy is read when a pile
     * actually spills, so a consumer's replacement installed after this pass was built still
     * decides. A rolled item has no replayable form, so a pile the policy could not land (or a
     * server that cleared the policy) cannot be parked like a top-level item reward: it is lost,
     * and the warn says so, naming the table and the stacks.
     */
    @Nonnull
    static LootEngine.Sinks lootableSinks(@Nonnull RewardSpec spec, @Nonnull Subject subject,
            @Nonnull RewardKindRegistry kinds, @Nonnull String sourceId, @Nonnull Consumer<String> warn) {
        Map<String, String> placeholders = CommandRewardKind.placeholders(spec, subject);
        placeholders.putIfAbsent(P_SOURCE, sourceId);
        return handToPlayer(subject, sourceId, warn)
                .build()
                .into(LootEngine.Sinks.builder())
                .commands(CommandRunner.CONSOLE, placeholders)
                .rewards(kinds, subject)
                .sourceId(sourceId)
                .warn(warn)
                .build();
    }

    /**
     * THE one way the library hands items straight to a player, as a ground-spill preset: each
     * stack goes where the player's own pickup settings route it, with the game's own pickup notice
     * and sound for what went in ({@link GroundSpillSinks.Builder#pickedUpFirst}), and whatever does
     * not fit goes to the installed overflow policy as one pile (by default the ground at their feet,
     * where picking it up notices it the normal way). The {@code Item} and {@code Stamped_Item} kinds
     * ({@link #deliver}), a {@code Lootable}'s roll ({@link #lootableSinks}) and a harvest's bonus
     * (the bonus table's harvest landing) all hand over through it, so there is no second way.
     *
     * <p>{@code sourceId} names the payout in the line a lost pile leaves on {@code warn}, which also
     * hears a stack the hand-over could not place. A subject with no live player gets no bag step,
     * so everything goes to the policy, which answers for a player it cannot find.
     */
    @Nonnull
    public static GroundSpillSinks.Builder handToPlayer(@Nonnull Subject subject, @Nonnull String sourceId,
            @Nonnull Consumer<String> warn) {
        return GroundSpillSinks.at(overflowGround(subject, sourceId, warn))
                .pickedUpFirst(playerOf(subject))
                .warn(warn);
    }

    /**
     * {@link #handToPlayer} with no overflow: each stack goes where the player's own pickup settings
     * route it, with the game's own pickup notice and sound for what went in, and what the bag cannot
     * take is not handed over at all, so {@link GroundSpillSinks#spill} answers only what went in. The
     * form for a payout that must land in the bag or not at all: a capped item currency, whose cap
     * reads the bag and so cannot see a pile at the player's feet. A subject with no live player gets
     * nothing. {@code warn} hears a stack the hand-over could not place.
     */
    @Nonnull
    public static GroundSpillSinks.Builder handIntoBag(@Nonnull Subject subject, @Nonnull Consumer<String> warn) {
        return GroundSpillSinks.at(NO_GROUND)
                .pickedUpFirst(playerOf(subject))
                .warn(warn);
    }

    /** {@link #handIntoBag}'s ground: it lands nothing, so the part the bag would not take is never counted. */
    private static final GroundSpillSinks.Ground NO_GROUND = pile -> false;

    /** The installed overflow policy as a ground-spill ground, warning about a pile it lost. */
    @Nonnull
    private static GroundSpillSinks.Ground overflowGround(@Nonnull Subject subject, @Nonnull String sourceId,
            @Nonnull Consumer<String> warn) {
        return pile -> {
            Overflow policy = installedOverflow();
            String why;
            if (policy == null) {
                why = "no overflow policy is installed";
            } else {
                try {
                    if (policy.handleAll(subject, pile)) {
                        return true;
                    }
                    why = "the overflow policy could not land it";
                } catch (Throwable t) {
                    why = "the overflow policy failed: " + t;
                }
            }
            warn.accept("'" + sourceId + "' could not hand over " + describe(pile)
                    + ": it did not fit the bag and " + why + ", so it is lost");
            return false;
        };
    }

    /** {@code "Coin_Gold x3, Bone x1"}: what a warn names when a pile went nowhere. */
    @Nonnull
    private static String describe(@Nonnull List<ItemStack> pile) {
        StringBuilder out = new StringBuilder();
        for (ItemStack stack : pile) {
            if (stack == null) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(stack.getItemId()).append(" x").append(stack.getQuantity());
        }
        return out.toString();
    }

    // ==================== Stamped_Item ====================

    /**
     * {@code {"Item": "<id>", "Pool": "<rollPoolId>"}} to roll the stats fresh, or
     * {@code {"Item": "<id>", "Stats": "Damage:5,Speed:2"}} to hand over exactly those.
     *
     * <p>Both routes write through whatever {@link Stamper} the server registered, so a stamped
     * reward carries the same format - and counts against the same budgets - as one earned at an
     * anvil. With no stamper registered the item is still handed over, just bare: an unstamped
     * reward is a smaller disappointment than no reward. Either way it goes through the one
     * delivery ({@link #deliver}).
     */
    private static final class StampedItemHandler implements RewardHandler {

        @Override
        public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
            grant(spec, subject, "", row -> { });
        }

        @Override
        public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId,
                @Nonnull Consumer<RewardSpec> receipt) throws Exception {
            int count = countOf(spec);
            String itemId = requirePayable(KIND_STAMPED_ITEM, itemIdOf(spec), count);
            ItemStack stack = new ItemStack(itemId, count);
            Stamper stamper = StamperRegistry.get();
            List<StatRoll> rolls = rollsFor(spec, subject);
            if (stamper != null && !rolls.isEmpty()) {
                stack = stamper.apply(stack, rolls, identityFor(spec));
            }
            receipt.accept(landedRow(spec, deliver(subject, stack, sourceId)));
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

        @Override
        @Nullable
        public String scalableRetryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                @Nonnull String sourceId) {
            return replayGive(spec, subject, InstanceReward.AMOUNT_PLACEHOLDER);
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

    /**
     * The rename and rarity a stamped reward carries: whatever the named pool authored. A reward
     * writing its stats out by hand names no pool and so carries no identity, which is right - it
     * asked for exactly those stats and nothing else.
     */
    @Nullable
    private static StampIdentity identityFor(@Nonnull RewardSpec spec) {
        String poolId = spec.paramOr("pool", "").trim();
        if (poolId.isEmpty()) {
            return null;
        }
        return StampIdentity.resolve(null, RollPoolConfig.getInstance().resolve(poolId));
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
    private static final String P_SOURCE = RewardGrants.P_SOURCE;

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

        @Override
        @Nullable
        public String scalableRetryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
                @Nonnull String sourceId) {
            try {
                return resolveScalableCommand(spec, subject, sourceId);
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
        return resolveLine(commandTemplate(spec), commandPlaceholders(spec, subject, sourceId));
    }

    /**
     * The same line with the reward's amount left standing as {@code {amount}} for a payout scaled before
     * it runs ({@link CommandRewardKind#scalablePlaceholders}), null when the line cannot pay a scaled
     * amount, or a THROW where {@link #resolveCommand} throws.
     */
    @Nullable
    private static String resolveScalableCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject,
            @Nonnull String sourceId) {
        String template = commandTemplate(spec);
        Map<String, String> scaled = CommandRewardKind.scalablePlaceholders(
                commandPlaceholders(spec, subject, sourceId), template, spec);
        return scaled == null ? null : resolveLine(template, scaled);
    }

    /** The reward's own command template, trimmed, or a THROW when it names none. */
    @Nonnull
    private static String commandTemplate(@Nonnull RewardSpec spec) {
        String template = spec.paramOr(P_COMMAND, "").trim();
        if (template.isEmpty()) {
            throw new IllegalStateException("a reward of kind '" + KIND_COMMAND
                    + "' named no command - it needs a 'Command' parameter");
        }
        return template;
    }

    /** What the template's placeholders stand for: the reward's own parameters, then {@code {source}}. */
    @Nonnull
    private static Map<String, String> commandPlaceholders(@Nonnull RewardSpec spec, @Nonnull Subject subject,
            @Nonnull String sourceId) {
        Map<String, String> placeholders = CommandRewardKind.placeholders(spec, subject);
        placeholders.putIfAbsent(P_SOURCE, sourceId);
        return placeholders;
    }

    /** {@code template} resolved against {@code placeholders}, or a THROW when that leaves no line. */
    @Nonnull
    private static String resolveLine(@Nonnull String template, @Nonnull Map<String, String> placeholders) {
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

    /**
     * The item a reward row names: its {@code Item}, or for the two item kinds ({@code Item},
     * {@code Stamped_Item}) its {@code Id} too; null when it names none. The one reading the item
     * kinds pay by and {@link RewardDeliveries} announces by, so an item is announced exactly as it
     * was paid. Only the item kinds read {@code Id} as an item: another kind names its own thing by
     * it (an effect, a drop list, a table).
     */
    @Nullable
    static String itemIdOf(@Nonnull RewardSpec spec) {
        String id = isItemKind(spec.kind()) ? spec.paramOr("id", "") : "";
        String itemId = spec.paramOr("item", id).trim();
        return itemId.isEmpty() ? null : itemId;
    }

    /**
     * How many of its item a reward row hands over: {@code Count}, else {@code Quantity}, else one;
     * 0 for none. Read by the item kinds and by {@link RewardDeliveries} alike, beside
     * {@link #itemIdOf}.
     */
    static int countOf(@Nonnull RewardSpec spec) {
        long count = spec.longParam("count", spec.longParam("quantity", 1L));
        return count <= 0 ? 0 : (int) Math.min(count, Integer.MAX_VALUE);
    }

    /**
     * Is {@code kind} one of the two kinds that hand over the item they name? Their handler lands it through the
     * pickup delivery ({@link #handToPlayer}), which a Claim never pays through, so a Claim's deferred row of
     * either kind keeps paying as its line ({@code InstanceRewardGranter}).
     */
    public static boolean isItemKind(@Nonnull String kind) {
        return KIND_ITEM.equalsIgnoreCase(kind) || KIND_STAMPED_ITEM.equalsIgnoreCase(kind);
    }

    @Nullable
    private static Player playerOf(@Nonnull Subject subject) {
        return subject.handleAs(Player.class);
    }

    /**
     * The question a rolled reward, and a reward row's own conditions ({@link RewardGrants}), are asked:
     * the installed vocabulary about the subject's live player, none when the player is not here.
     */
    @Nonnull
    static FactorLookup lookupFor(@Nonnull Subject subject) {
        FactorRegistry factors = FACTORS.get();
        if (factors == null) {
            return FactorLookup.none();
        }
        Player player = playerOf(subject);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        return new FactorSnapshot(factors, FactorContext.about(ref == null ? null : ref.getStore(), ref));
    }

    /**
     * Hand {@code stack} over through the one delivery ({@link #handToPlayer}), answering how many
     * landed, or THROW when none did. Throwing is the point: the payout layer catches it, asks for a
     * replayable command, and parks the reward for the player's next connect.
     *
     * <p>With no overflow policy installed, a stack the bag would not take WHOLE ({@link #fits}) is not
     * handed over at all and throws, so the park replays all of it rather than part of it twice. With
     * a policy, a part the bag took stays taken: should the policy refuse only the rest, that rest is
     * warned as lost and the answer counts what landed, never parked again.
     */
    private static int deliver(@Nonnull Subject subject, @Nonnull ItemStack stack, @Nonnull String sourceId)
            throws Exception {
        Player player = playerOf(subject);
        if (player == null) {
            throw new IllegalStateException("no player to grant '" + stack.getItemId() + "' to");
        }
        if (installedOverflow() == null && !fits(player, List.of(stack))) {
            throw new IllegalStateException("'" + stack.getItemId() + "' did not fit and no overflow policy is"
                    + " installed");
        }
        // A pile that went nowhere is parked, not lost, so its warning is kept back unless part landed.
        List<String> notes = new ArrayList<>();
        int landed = 0;
        for (int quantity : handToPlayer(subject, sourceId, notes::add).build().spill(List.of(stack)).values()) {
            landed += quantity;
        }
        if (landed <= 0) {
            throw new IllegalStateException("'" + stack.getItemId() + "' did not fit and nowhere to put it");
        }
        notes.forEach(SafeLog::warn);
        return landed;
    }

    /**
     * The receipt row for an item reward of which {@code landed} reached the player: the reward itself,
     * {@link RewardSpec#asPickupNoticed() pickup-noticed} since it landed through the one delivery, its
     * {@code Count} cut to what landed when the rest was lost.
     */
    @Nonnull
    private static RewardSpec landedRow(@Nonnull RewardSpec spec, int landed) {
        RewardSpec row = landed < countOf(spec) ? spec.with("Count", Integer.toString(landed)) : spec;
        return row.asPickupNoticed();
    }

    /**
     * The command that would hand this reward over later, or null when the reward describes nothing
     * to hand over. Null is the honest answer for a spec with no item or a count of none: a retry
     * that rounded either of those up to one would pay out something nobody authored, and quietly
     * turn an authoring mistake into a live payout instead of a reported one.
     */
    @Nullable
    private static String replayGive(@Nonnull RewardSpec spec, @Nonnull Subject subject) {
        return replayGive(spec, subject, null);
    }

    /**
     * {@link #replayGive(RewardSpec, Subject)} with {@code quantity} written where the count goes when it is
     * given ({@code {amount}} for the scalable line, {@link RewardHandler#scalableRetryCommand}), so the two
     * lines are one spelling; null exactly where the plain one is.
     */
    @Nullable
    private static String replayGive(@Nonnull RewardSpec spec, @Nonnull Subject subject,
            @Nullable String quantity) {
        String itemId = itemIdOf(spec);
        int count = countOf(spec);
        if (itemId == null || count <= 0) {
            return null;
        }
        return "give " + subject.name() + " " + itemId + " --quantity=" + (quantity == null ? count : quantity);
    }

    /**
     * Replay one parked reward line for {@code subject}, who is back in a world: the drain of a retry
     * queue a consumer keeps. A plain give line naming them, the {@code give <player> <item>
     * --quantity=<n>} a parked {@code Item} or {@code Stamped_Item} reward is written as, pays as that
     * {@code Item} reward through the shared grant pass ({@link RewardGrants#grantAll}), so it lands
     * through the one delivery ({@link #handToPlayer}) with the game's own pickup notice, is announced
     * as received, and parks again through {@code retryQueue} on exactly the terms a live grant parks
     * (no live player, or no overflow policy and no room). A parked {@code Command} reward whose line
     * is such a plain give pays the same way, since the queue keeps only the line. Any other line (a
     * kind file's, a {@code Command} reward's, a give carrying more than a count) runs as the console,
     * as a replay always did, and is warned about when it does not run.
     *
     * <p>World thread, since an item replay touches the player's inventory.
     *
     * @return true when the line was handed over or ran; false when it parked again or failed
     */
    public static boolean replayParked(@Nonnull String line, @Nonnull Subject subject, @Nonnull String sourceId,
            @Nullable BiConsumer<Subject, String> retryQueue, @Nonnull Consumer<String> warn) {
        RewardSpec item = parkedItem(line, subject);
        if (item == null) {
            return CommandRunner.run(line, null, warn);
        }
        return RewardGrants.grantAll(List.of(item), subject, sourceId, RewardKinds.shared(), retryQueue, warn)
                .granted() > 0;
    }

    /**
     * The {@code Item} reward a parked line hands over, or null when the line is anything but the plain
     * give a parked item reward is written as ({@link #replayGive}): the verb, {@code subject}'s own
     * name, an item and at most a {@code --quantity=<n>} over zero. The count is read through
     * {@link CommandRunner#readGive}, the one reading of a give line.
     */
    @Nullable
    private static RewardSpec parkedItem(@Nonnull String line, @Nonnull Subject subject) {
        CommandRunner.Give give = CommandRunner.readGive(line);
        if (give == null || give.quantity() <= 0 || !give.target().equalsIgnoreCase(subject.name())) {
            return null;
        }
        String[] parts = line.trim().split("\\s+");
        boolean plain = parts.length == 3
                || (parts.length == 4 && parts[3].toLowerCase(Locale.ROOT).startsWith("--quantity="));
        return plain ? RewardSpec.of(KIND_ITEM, Map.of("Item", give.itemId(),
                "Count", Integer.toString(give.quantity()))) : null;
    }

    /** Every parameter key these kinds read, for a validator that wants to warn about a typo. */
    @Nonnull
    public static Map<String, List<String>> parameterKeys() {
        return Map.of(
                KIND_ITEM, List.of("item", "count", "stacknamekey", "stacknamearg"),
                KIND_LOOTABLE, List.of("lootable", "trigger"),
                KIND_STAMPED_ITEM, List.of("item", "count", "pool", "stats", "picks"),
                KIND_COMMAND, List.of(P_COMMAND, P_RUN_AS, P_DELAY_TICKS));
    }
}
