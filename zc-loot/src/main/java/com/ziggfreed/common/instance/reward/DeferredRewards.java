package com.ziggfreed.common.instance.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.ziggfreed.common.loot.LootEngine;
import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.reward.CommandRewardKind;
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

    private DeferredRewards() {
    }

    /**
     * Every reward a whole pass decided on, in decision order.
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
            out.addAll(from(entry.grants(), kinds, subject, sourceId, warn));
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
     * (nothing registered the kind, the kind offers no replay, or resolving one threw).
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
        RewardKindAsset authored = authoredKind(handler);
        return InstanceReward.command(command, quantityOf(spec),
                firstWritten(spec.param(PARAM_NAME_KEY),
                        authored == null ? null : authored.presentationNameKey(spec)),
                firstWritten(spec.param(PARAM_ICON),
                        authored == null ? null : authored.presentationIcon(spec)));
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

    /** The number the chip shows: the reward's own amount, else one. */
    private static int quantityOf(@Nonnull RewardSpec spec) {
        long amount = spec.longParam(PARAM_AMOUNT,
                spec.longParam("Count", spec.longParam("Quantity", 1L)));
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
