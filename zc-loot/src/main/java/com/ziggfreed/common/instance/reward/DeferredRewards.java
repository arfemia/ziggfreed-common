package com.ziggfreed.common.instance.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.reward.CommandRewardKind;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindAsset;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * Turns what a loot pass DECIDED into rewards that can be shown now and handed over later.
 *
 * <p>The ordinary way to pay out loot is {@code LootEngine.rollAndGrant}, which applies each grant
 * through a sink the moment it is decided. An end-of-run payout cannot work that way: the roll has
 * to happen while the score and the outcome are known, the player has to SEE what they earned on a
 * results screen, and the spoils only actually land when they press Claim - possibly after a
 * disconnect, a restart, and a walk back to town. Rolling again at claim time would hand over
 * something other than what was shown, so the decision is made once and kept.
 *
 * <p>{@link InstanceReward} is what it is kept as: a small, durable descriptor the claim store
 * writes to disk, the results screen renders as a chip, and {@link InstanceRewardGranter} delivers
 * with the full-inventory guard. This class is the one translation between the two vocabularies.
 *
 * <h2>What each grant leaf becomes</h2>
 *
 * <ul>
 *   <li><b>Items</b> become item rewards, so the inventory guard still applies and a full bag holds
 *       the stack rather than eating it.</li>
 *   <li><b>DropLists</b> are rolled HERE, at decision time, and each resolved stack becomes an item
 *       reward. A native table decides what it produced once, at the moment everything else was
 *       decided, rather than at claim time when the number on the screen has already been read.</li>
 *   <li><b>Commands</b> become command rewards, run by the consumer's sink at claim time.</li>
 *   <li><b>Rewards</b> are asked for their REPLAYABLE form - the console line the kind's own handler
 *       would run - and become command rewards too. A kind that offers none (its payout is decided
 *       at grant time, so replaying it would hand over something different) is dropped and reported,
 *       because a payout that cannot be deferred must not be silently promised.</li>
 * </ul>
 *
 * <h2>How a deferred reward pays</h2>
 *
 * <p>A row deferred from a registered kind keeps the reward itself ({@link InstanceReward#spec}), and at the
 * press {@link InstanceRewardGranter} pays that reward through its kind's own handler
 * ({@code payableSpec}), at the share the row shows, so whatever the kind does beside the line (a
 * reputation's worn gain, its Cap, its bar and rank notice) happens at the press. The line is what pays when
 * the kind cannot: no live claimer, a handler that throws, a line a site rewrote after the deferral, or an
 * item kind, whose handover a Claim keeps on its own guard.
 *
 * <h2>How a chip gets its label and its art</h2>
 *
 * <p>A deferred reward has to be SHOWN before it is granted, and a console line reads terribly on a
 * results chip. Three answers are tried in order, and the first one that exists wins:
 *
 * <ol>
 *   <li>the reward's OWN {@link #PARAM_NAME_KEY} / {@link #PARAM_ICON} - a localization key for the
 *       label and an item id for the art, written on that one reward beside whatever its kind
 *       reads;</li>
 *   <li>the kind's own default presentation, when the kind is one written as a file
 *       ({@code RewardKindAsset.Presentation}): its {@code NameKey} template filled in from this
 *       reward's parameters, and its {@code Icon} rule answered the same way. This is what stops
 *       every reward of a kind repeating the same two lines;</li>
 *   <li>nothing, which leaves the chip to work out a label from the reward itself.</li>
 * </ol>
 *
 * <p>The per-reward pair stays first because a one-off deserves to say so, and it belongs to this
 * layer rather than to any kind's schema - a kind decides what it PAYS, not how somebody else's
 * screen draws it. Both are ignored anywhere a reward is granted outright.
 *
 * <p>The deferred row also keeps the reward itself ({@link InstanceReward#spec}), so the last rung
 * has something to read: a chip asks the shared reward reading ({@code RewardChips}) of that spec,
 * at the row's own quantity, which names a Java-registered kind through what its owner contributed
 * ("+10 Sweets" for a wallet) rather than painting the line that pays it.
 *
 * <p>The chip's quantity comes from {@link #PARAM_AMOUNT} (or {@code Count} / {@code Quantity}),
 * which is the number most kinds already carry, so "+500 Mining XP" renders without the loot layer
 * knowing what a skill is.
 */
public final class DeferredRewards {

    /** A localization key for the chip's label, read off any reward entry and beating its kind's. */
    public static final String PARAM_NAME_KEY = "NameKey";

    /** An item id for the chip's art, read off any reward entry and beating its kind's. */
    public static final String PARAM_ICON = "Icon";

    /** The parameter the chip's quantity is read from first. */
    public static final String PARAM_AMOUNT = "Amount";

    /** The parameters the chip's quantity is read from, in order: the first one written as a number wins. */
    private static final List<String> QUANTITY_PARAMS = List.of(PARAM_AMOUNT, "Count", "Quantity");

    /**
     * The name a deferred line is resolved for ({@link #fromSelection}'s {@code subject}), which the claiming
     * site fills with the claimer's own; {@link #payableSpec} resolves the kind's line the same way.
     */
    static final String CLAIMANT = "{player}";

    private DeferredRewards() {
    }

    /**
     * Every reward a whole pass decided on, in decision order, each carrying the chance of the payout that decided it
     * ({@link LootEngine.Selected#chance}, as {@link InstanceReward#dropChance}); a native drop list's stacks take
     * the chance of the payout that named the list.
     *
     * @param selected what {@code LootEngine.select} answered
     * @param kinds    the vocabulary a registered reward entry is looked up in; null drops them all
     * @param subject  who the payout is for - its name fills the {@code player} placeholder, so pass
     *                 a subject named {@code "{player}"} to leave that for the claiming site to fill
     * @param sourceId what labels this payout in logs, conventionally {@code "<site>:<id>"}
     * @param warn     where a reward that could not be deferred is reported; may be null
     */
    @Nonnull
    public static List<InstanceReward> fromSelection(@Nonnull List<LootEngine.Selected> selected,
            @Nullable RewardKindRegistry kinds, @Nullable Subject subject, @Nonnull String sourceId,
            @Nullable Consumer<String> warn) {
        List<InstanceReward> out = new ArrayList<>();
        for (LootEngine.Selected entry : selected) {
            for (InstanceReward reward : from(entry.grants(), kinds, subject, sourceId, warn)) {
                out.add(entry.chance() == null ? reward : reward.withDropChance(entry.chance()));
            }
        }
        return out;
    }

    /** The rewards ONE grants group defers to, in authored leaf order. */
    @Nonnull
    public static List<InstanceReward> from(@Nullable LootGrants grants,
            @Nullable RewardKindRegistry kinds, @Nullable Subject subject, @Nonnull String sourceId,
            @Nullable Consumer<String> warn) {
        List<InstanceReward> out = new ArrayList<>();
        if (grants == null) {
            return out;
        }
        for (LootGrants.Item item : grants.itemsOrEmpty()) {
            out.add(InstanceReward.item(item.getItem(), item.effectiveCount(), null));
        }
        String[] dropLists = grants.getDropLists();
        if (dropLists != null) {
            for (String dropListId : dropLists) {
                if (dropListId == null || dropListId.isBlank()) {
                    continue;
                }
                for (ItemStack stack : NativeLootService.rollNative(dropListId)) {
                    out.add(InstanceReward.item(stack.getItemId(), stack.getQuantity(), null));
                }
            }
        }
        String[] commands = grants.getCommands();
        if (commands != null) {
            for (String command : commands) {
                if (command != null && !command.isBlank()) {
                    out.add(InstanceReward.command(command, null));
                }
            }
        }
        for (RewardSpec spec : grants.rewardSpecs()) {
            InstanceReward deferred = deferSpec(spec, kinds, subject, sourceId, warn);
            if (deferred != null) {
                out.add(deferred);
            }
        }
        return out;
    }

    /**
     * One registered-kind reward as its replayable console line, or null when it cannot be deferred
     * (nothing registered the kind, the kind offers no replay, or resolving one threw). The line is the
     * kind's scalable one where it has one ({@link #keptLine}), so a payout scaled before it runs pays
     * the share its chip shows. The row keeps {@code spec} for its chip to read and its kind to pay
     * ({@link #payableSpec}).
     */
    @Nullable
    private static InstanceReward deferSpec(@Nonnull RewardSpec spec, @Nullable RewardKindRegistry kinds,
            @Nullable Subject subject, @Nonnull String sourceId, @Nullable Consumer<String> warn) {
        RewardHandler handler = kinds == null ? null : kinds.handler(spec.kind());
        if (handler == null || subject == null) {
            report(warn, "reward kind '" + spec.kind() + "' pays out nothing here, so it was dropped from "
                    + sourceId);
            return null;
        }
        String command;
        try {
            command = handler.retryCommand(spec, subject, sourceId);
        } catch (Throwable t) {
            command = null;
        }
        if (command == null || command.isBlank()) {
            report(warn, "reward kind '" + spec.kind() + "' cannot be handed over later, so it was dropped "
                    + "from " + sourceId + ". A payout decided at grant time has to be granted there.");
            return null;
        }
        int quantity = quantityOf(spec);
        RewardKindAsset authored = authoredKind(handler);
        return InstanceReward.command(keptLine(handler, spec, subject, sourceId, command, quantity), quantity,
                firstWritten(spec.param(PARAM_NAME_KEY),
                        authored == null ? null : authored.presentationNameKey(spec)),
                firstWritten(spec.param(PARAM_ICON),
                        authored == null ? null : authored.presentationIcon(spec)),
                spec);
    }

    /**
     * The line a deferred reward is kept as: the kind's scalable line
     * ({@link RewardHandler#scalableRetryCommand}), whose {@code {amount}} the payout fills from the
     * quantity it shows, where that line filled with {@code quantity} reads exactly as {@code retry};
     * otherwise {@code retry}, its amount written in. The check is what keeps a scaled line honest at
     * the full amount: a kind whose two lines disagree (a count the quantity does not read, a number
     * the replay spells otherwise) keeps the line it would replay.
     */
    @Nonnull
    private static String keptLine(@Nonnull RewardHandler handler, @Nonnull RewardSpec spec,
            @Nonnull Subject subject, @Nonnull String sourceId, @Nonnull String retry, int quantity) {
        String scalable;
        try {
            scalable = handler.scalableRetryCommand(spec, subject, sourceId);
        } catch (Throwable t) {
            return retry;
        }
        if (scalable == null || !scalable.contains(InstanceReward.AMOUNT_PLACEHOLDER)) {
            return retry;
        }
        String atFull = scalable.replace(InstanceReward.AMOUNT_PLACEHOLDER, Integer.toString(quantity));
        return atFull.equals(retry) ? scalable : retry;
    }

    /**
     * The reward a deferred row pays through {@code handler}, its kind's, at the press, or null when the row's
     * line must pay instead. It is the row's kept {@link InstanceReward#spec}, its amount
     * ({@link #quantityParam}) set to the row's own quantity where the line fills {@code {amount}} from it, so
     * a diminished Claim's share reaches the kind as it reaches the line; a line that pays whole keeps the
     * spec as it was.
     *
     * <p>It answers only where the kind's own line for that reward ({@link RewardHandler#retryCommand},
     * resolved for {@link #CLAIMANT} as the deferral resolved it) reads exactly as {@code line}, the row's line
     * as it would run: the kind then pays the very reward the line would. A line a site rewrote after the
     * deferral (a fight's {@code {encounter}} filled in), or one the kind now spells otherwise, keeps paying
     * as the line, and so does an item kind's ({@link LootRewardKinds#isItemKind}): a Claim hands an item
     * over through the bag guard, never the pickup delivery that kind's handler uses.
     *
     * @param claimant who pays it (their id fills a kind's {@code {uuid}} as the deferral's did)
     * @param sourceId what paid it, as the deferral named it
     * @param line     the row's line with its {@code {amount}} filled, {@code {player}} still standing
     */
    @Nullable
    static RewardSpec payableSpec(@Nonnull InstanceReward row, @Nonnull RewardHandler handler,
            @Nonnull UUID claimant, @Nonnull String sourceId, @Nonnull String line) {
        RewardSpec spec = row.spec();
        if (spec == null || row.kind() != InstanceReward.Kind.COMMAND || LootRewardKinds.isItemKind(spec.kind())) {
            return null;
        }
        String amount = row.scalesWithQuantity() ? quantityParam(spec) : null;
        RewardSpec payable = amount == null ? spec : spec.with(amount, Integer.toString(row.quantity()));
        String own;
        try {
            own = handler.retryCommand(payable, Subject.of(claimant, CLAIMANT), sourceId);
        } catch (Throwable t) {
            return null;
        }
        return line.equals(own) ? payable : null;
    }

    /**
     * The kind FILE behind {@code handler}, or null when the kind was registered in Java.
     *
     * <p>Only a kind written as a file carries a default presentation, which is the whole of what
     * this layer asks it for. A Java kind knows how to pay out and nothing about how it reads, so
     * there is nothing to ask it.
     */
    @Nullable
    private static RewardKindAsset authoredKind(@Nullable RewardHandler handler) {
        return handler instanceof CommandRewardKind authored ? authored.kind() : null;
    }

    /** The first of the two that was actually written, or null when neither was. */
    @Nullable
    private static String firstWritten(@Nullable String own, @Nullable String fromKind) {
        if (own != null && !own.isBlank()) {
            return own;
        }
        return fromKind != null && !fromKind.isBlank() ? fromKind : null;
    }

    /**
     * The parameter the chip's quantity is read from: the first of {@code Amount}, {@code Count} and
     * {@code Quantity} that {@code spec} writes as a number, or null when it writes none (the chip then
     * shows one). Public so a kind's scalable line leaves standing exactly the parameter the quantity
     * reads ({@link RewardHandler#scalableRetryCommand}).
     */
    @Nullable
    public static String quantityParam(@Nonnull RewardSpec spec) {
        for (String name : QUANTITY_PARAMS) {
            // A parameter that reads as a number answers the same whatever the fallback; one unset or
            // unreadable answers the fallback, so the two reads differ.
            if (spec.longParam(name, 0L) == spec.longParam(name, 1L)) {
                return name;
            }
        }
        return null;
    }

    /** The number the chip shows: the reward's own amount ({@link #quantityParam}), else one. */
    private static int quantityOf(@Nonnull RewardSpec spec) {
        String param = quantityParam(spec);
        long amount = param == null ? 1L : spec.longParam(param, 1L);
        return amount <= 0 ? 1 : (int) Math.min(amount, Integer.MAX_VALUE);
    }

    private static void report(@Nullable Consumer<String> warn, @Nonnull String message) {
        if (warn == null) {
            return;
        }
        try {
            warn.accept(message);
        } catch (Throwable ignored) {
            // A sink that throws costs its own line, never the payout that called it.
        }
    }
}
