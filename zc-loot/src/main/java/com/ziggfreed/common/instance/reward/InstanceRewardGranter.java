package com.ziggfreed.common.instance.reward;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.inventory.InventoryUtil;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.PlayerRefSubjectHandle;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * Grants a list of {@link InstanceReward}s to one player with the BLOCK-FIRST
 * full-inventory guard (the user's "players cannot claim rewards with a full inventory"
 * rule, mirroring hyMMO's paradigm): an {@code ITEM} reward is granted ONLY if it all
 * fits ({@link InventoryUtil#canFit}); otherwise it is NOT delivered and is returned in
 * {@link GrantOutcome#pending()} for the caller to hold / re-claim. Currency and command
 * rewards have no space guard and run through the consumer-supplied {@link Sink} (so
 * common imports no currency engine). Non-throwing, isolate-each.
 *
 * <p>A command row deferred from a registered reward kind ({@link InstanceReward#spec}) pays through that
 * kind's own handler on the shared vocabulary ({@link RewardKinds#shared()}) first, at the share the row
 * shows ({@code DeferredRewards.payableSpec}), so it does everything the kind does beside its line (a
 * reputation's worn gain, Cap, bar and rank notice). Its line runs through the sink only when the kind
 * cannot pay it: no live claimer, a handler that throws, or a row the kind does not pay
 * ({@code payableSpec} answers null). Nothing registers for it. What the kind reports handing over (its
 * receipt: a reputation's gain after the worn gear and the Cap) reaches the caller of
 * {@link #grantAllLanded} beside the row, so a toast raised after the press says what really landed.
 *
 * <p><b>World thread only</b> (item grants touch the {@link Store}). Each reward is
 * guarded; one failing reward never aborts the rest.
 */
public final class InstanceRewardGranter {

    /** What a payout is labelled with for a kind's handler when its caller names none. */
    private static final String DEFAULT_SOURCE = "claim";

    /**
     * The consumer's executor for the non-item reward kinds (common knows no currency/command engine). A command
     * row a reward kind pays at the press never reaches it.
     */
    public interface Sink {
        /** Grant a currency/token reward. Return true if granted. */
        boolean grantCurrency(@Nonnull String currencyId, int amount,
                              @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store);

        /**
         * Run a console command reward. The granter has already substituted the {@code {amount}}
         * placeholder from the reward quantity; the consumer substitutes any {@code {player}} placeholder
         * (the sink reads the player off the ref it is handed). Return true if run.
         */
        boolean runCommand(@Nonnull String command,
                           @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store);
    }

    /**
     * One reward a grant handed over, and what it landed as. {@code receipt} is what the reward kind that paid
     * the row reported handing over ({@link RewardHandler#grant(RewardSpec, Subject, String, Consumer)}): a
     * reputation's gain after the worn gear and the Cap, and empty when the kind moved nothing (a gain the Cap cut
     * to nothing). It is null when the row paid by itself (an item into the bag, a currency through the sink, a
     * line run), which then landed as the row reads, at its own quantity.
     */
    public record Landed(@Nonnull InstanceReward row, @Nullable List<RewardSpec> receipt) {

        public Landed {
            receipt = receipt == null ? null : List.copyOf(receipt);
        }

        /** True when the row's kind paid it, so {@link #receipt} says what landed. */
        public boolean receipted() {
            return receipt != null;
        }
    }

    private InstanceRewardGranter() {
    }

    /**
     * Grant {@code rewards} to the player behind {@code ref}. Item rewards that do not fit are skipped
     * and collected into {@link GrantOutcome#pending()} (never partially delivered).
     *
     * @param sink the consumer executor for currency/command rewards, or {@code null}
     *             (then those kinds count as failed)
     */
    @Nonnull
    public static GrantOutcome grantAll(@Nonnull List<InstanceReward> rewards,
                                        @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                        @Nullable Sink sink) {
        return grantAll(rewards, ref, store, sink, granted -> { });
    }

    /**
     * {@link #grantAll(List, Ref, Store, Sink)}, telling {@code onGranted} each reward as it is handed over (an
     * item that fit, a currency paid, a command run), so a caller knows exactly what reached the player.
     */
    @Nonnull
    public static GrantOutcome grantAll(@Nonnull List<InstanceReward> rewards,
                                        @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                        @Nullable Sink sink, @Nonnull Consumer<InstanceReward> onGranted) {
        return grantAll(rewards, ref, store, sink, DEFAULT_SOURCE, onGranted);
    }

    /**
     * {@link #grantAll(List, Ref, Store, Sink, Consumer)}, telling a kind that pays a deferred row
     * ({@link InstanceReward#spec}) that {@code sourceId} paid it: what the payout came from, conventionally
     * the Claim's own {@code source}, as the deferral named it.
     */
    @Nonnull
    public static GrantOutcome grantAll(@Nonnull List<InstanceReward> rewards,
                                        @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                        @Nullable Sink sink, @Nonnull String sourceId,
                                        @Nonnull Consumer<InstanceReward> onGranted) {
        return grantAllLanded(rewards, ref, store, sink, sourceId, landed -> onGranted.accept(landed.row()));
    }

    /**
     * {@link #grantAll(List, Ref, Store, Sink, String, Consumer)}, telling {@code onLanded} each reward as it is
     * handed over together with what it landed as ({@link Landed}): the receipt of the kind that paid a deferred
     * row, so a press's toast names the amount that really landed (a reputation's worn gain, its Cap) rather than
     * the row's.
     */
    @Nonnull
    public static GrantOutcome grantAllLanded(@Nonnull List<InstanceReward> rewards,
                                              @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                              @Nullable Sink sink, @Nonnull String sourceId,
                                              @Nonnull Consumer<Landed> onLanded) {
        int granted = 0;
        int blocked = 0;
        int failed = 0;
        List<InstanceReward> pending = new ArrayList<>();

        for (InstanceReward r : rewards) {
            try {
                switch (r.kind()) {
                    case ITEM -> {
                        if (!InventoryUtil.canFit(store, ref, r.id(), r.quantity())) {
                            blocked++;
                            pending.add(r);
                        } else if (InventoryUtil.give(store, ref, r.id(), r.quantity()) > 0) {
                            // Raced full between the check and the give: treat as blocked, not partial.
                            blocked++;
                            pending.add(r);
                        } else {
                            granted++;
                            onLanded.accept(new Landed(r, null));
                        }
                    }
                    case CURRENCY -> {
                        if (sink != null && sink.grantCurrency(r.id(), r.quantity(), ref, store)) {
                            granted++;
                            onLanded.accept(new Landed(r, null));
                        } else {
                            failed++;
                        }
                    }
                    case COMMAND -> {
                        // Substitute the {amount} placeholder from the reward quantity before the sink
                        // runs; the consumer's sink substitutes {player}. Common stays currency/XP-agnostic.
                        String command = r.id().replace(InstanceReward.AMOUNT_PLACEHOLDER,
                                Integer.toString(r.quantity()));
                        List<RewardSpec> receipt = paidThroughKind(r, command, ref, store, sourceId);
                        if (receipt != null || (sink != null && sink.runCommand(command, ref, store))) {
                            granted++;
                            onLanded.accept(new Landed(r, receipt));
                        } else {
                            failed++;
                        }
                    }
                    default -> failed++;
                }
            } catch (Throwable t) {
                failed++;
            }
        }
        return new GrantOutcome(granted, blocked, failed, pending);
    }

    /**
     * What the deferred row {@code r} landed as when it was paid through its kind for the live player at
     * {@code ref} (the kind's receipt, empty when it moved nothing), or null when the kind did not pay it.
     */
    @Nullable
    private static List<RewardSpec> paidThroughKind(@Nonnull InstanceReward r, @Nonnull String line,
            @Nullable Ref<EntityStore> ref, @Nullable Store<EntityStore> store, @Nonnull String sourceId) {
        if (r.spec() == null || ref == null || store == null) {
            return null;
        }
        Subject claimant = claimant(ref, store);
        if (claimant == null) {
            return null;
        }
        List<RewardSpec> receipt = new ArrayList<>();
        return payThroughKind(r, line, RewardKinds.shared(), claimant, sourceId, receipt::add) ? receipt : null;
    }

    /** {@link #payThroughKind(InstanceReward, String, RewardKindRegistry, Subject, String, Consumer)}, keeping no receipt. */
    static boolean payThroughKind(@Nonnull InstanceReward r, @Nonnull String line, @Nonnull RewardKindRegistry kinds,
            @Nonnull Subject claimant, @Nonnull String sourceId) {
        return payThroughKind(r, line, kinds, claimant, sourceId, landed -> { });
    }

    /**
     * Pay {@code r} through its kind's handler in {@code kinds}, at the share it shows
     * ({@link DeferredRewards#payableSpec}); true when the handler paid it, and then {@code receipt} hears what it
     * reported handing over, once it returned (a handler that throws has its receipt discarded, as
     * {@link RewardHandler} says). False, and the row's {@code line} then runs, when no handler is registered, the
     * kind does not pay the row, or the handler threw (a reward it could not hand over, so the line still has it to
     * pay).
     */
    static boolean payThroughKind(@Nonnull InstanceReward r, @Nonnull String line, @Nonnull RewardKindRegistry kinds,
            @Nonnull Subject claimant, @Nonnull String sourceId, @Nonnull Consumer<RewardSpec> receipt) {
        RewardSpec spec = r.spec();
        RewardHandler handler = spec == null ? null : kinds.handler(spec.kind());
        RewardSpec payable = handler == null ? null
                : DeferredRewards.payableSpec(r, handler, claimant.id(), sourceId, line);
        if (payable == null) {
            return false;
        }
        List<RewardSpec> landed = new ArrayList<>();
        try {
            handler.grant(payable, claimant, sourceId, each -> {
                if (each != null) {
                    landed.add(each);
                }
            });
        } catch (Throwable t) {
            SafeLog.warn("[claims] a '" + payable.kind() + "' reward from " + sourceId
                    + " could not be paid through its kind, so its line runs instead: " + t.getMessage());
            return false;
        }
        landed.forEach(receipt);
        return true;
    }

    /**
     * The live player at {@code ref} as a kind's handler reads one, or null when there is none to pay (the line
     * then runs, so a read that throws costs the kind's pay, never the reward).
     */
    @Nullable
    private static Subject claimant(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        try {
            if (!ref.isValid()) {
                return null;
            }
            PlayerRef player = PlayerAccess.playerRef(store, ref);
            if (player == null || player.getUuid() == null) {
                return null;
            }
            String name = player.getUsername();
            return PlayerRefSubjectHandle.subjectFor(player, name == null ? "" : name);
        } catch (Throwable t) {
            SafeLog.warn("[claims] could not read the claiming player, so a reward kind's row runs its line instead: "
                    + t.getMessage());
            return null;
        }
    }
}
