package com.ziggfreed.common.reputation;

import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * The reward kind that moves a player's standing:
 * {@code {"Kind": "Reputation", "Params": {"Reputation": "<Id>", "Amount": 250}}} (a negative Amount
 * costs standing). UNPREFIXED because the library owns the writer behind it, and registered once into the
 * shared vocabulary, so quests, contracts, shop offers, achievements, lootables, a dialogue {@code Grant}
 * and {@code ZigGrantReward} all pay it.
 *
 * <p>It writes through {@link ReputationService#change}, so the Cap, the event, the bar and the rank checks
 * all apply. A gain the Cap cuts to nothing is no failure (and nothing is receipted). With no live player
 * it fails, and {@link #retryCommand} answers the engine's own {@code /reputation add} line for a consumer's
 * retry queue; that replay writes the native value directly, so it skips the Cap, the event, the bar and the
 * Beyond payout. The line is null for exactly the rewards that could never pay (no id, no Amount, a
 * reputation this server does not have switched on).
 */
public final class ReputationRewardKind implements RewardHandler {

    /** The kind id content writes. */
    public static final String KIND = "Reputation";

    /** Who this registration is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    static final String PARAM_REPUTATION = "reputation";
    static final String PARAM_AMOUNT = "amount";

    private final ReputationService service;

    ReputationRewardKind(@Nonnull ReputationService service) {
        this.service = service;
    }

    /** Register the kind into {@code kinds}. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds, @Nonnull ReputationService service) {
        kinds.register(KIND, OWNER, new ReputationRewardKind(service));
    }

    /** How a Reputation reward reads in a reward list, for {@code RewardChips.contribute}. */
    @Nonnull
    public static RewardChips.Source chips(@Nonnull ReputationService service) {
        return spec -> chipFor(service, spec);
    }

    /**
     * "+250 Old Jack's Favor", the amount a typed number and the name the companion's own, beside the
     * companion's own {@code Icon}; null for another kind or one nothing names. The icon is a picture that
     * stands for the reputation, never an item handed over ({@link RewardChip#picture}), so hovering it
     * names the reputation rather than the item it borrows; a companion with no Icon reads as the line alone.
     */
    @Nullable
    public static RewardChip chipFor(@Nonnull ReputationService service, @Nonnull RewardSpec spec) {
        if (!KIND.equalsIgnoreCase(spec.kind())) {
            return null;
        }
        int amount = amountOf(spec);
        ReputationDef def = service.known(reputationOf(spec));
        if (def == null || amount == 0) {
            return null;
        }
        Message name = ReputationText.name(def);
        return RewardChip.picture(def.icon(), ReputationText.line(amount > 0 ? "reward.gain" : "reward.loss",
                amount, name), name);
    }

    /** Which reputation {@code spec} moves, trimmed; empty when it names none. */
    @Nonnull
    public static String reputationOf(@Nonnull RewardSpec spec) {
        return spec.paramOr(PARAM_REPUTATION, "").trim();
    }

    /** How much it moves, held to the int range; 0 when unauthored or unreadable. */
    public static int amountOf(@Nonnull RewardSpec spec) {
        long amount = spec.longParam(PARAM_AMOUNT, 0L);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, amount));
    }

    /** The engine's own console line moving {@code id} by {@code amount} for {@code player}, without the slash. */
    @Nonnull
    public static String retryLine(@Nonnull String id, int amount, @Nonnull String player) {
        return "reputation add " + id + " " + amount + " --player=" + player;
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
        grant(spec, subject, KIND, ignored -> {
        });
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId) throws Exception {
        grant(spec, subject, sourceId, ignored -> {
        });
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId,
            @Nonnull Consumer<RewardSpec> receipt) throws Exception {
        String id = requireReputation(spec);
        requireAmount(spec, id);
        Player player = subject.handleAs(Player.class);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        if (ref == null || !ref.isValid()) {
            throw new IllegalStateException("no live player to move the reputation '" + id + "' for");
        }
        pay(ref.getStore(), ref, spec, sourceId, receipt);
    }

    /** The payout over a resolved player: receipted only when standing actually moved. World thread. */
    void pay(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull RewardSpec spec,
            @Nonnull String sourceId, @Nonnull Consumer<RewardSpec> receipt) {
        String id = requireReputation(spec);
        int amount = requireAmount(spec, id);
        ReputationService.Result result = service.change(store, ref, id, amount, sourceId);
        switch (result.status()) {
            case CHANGED -> receipt.accept(spec);
            case UNCHANGED -> {
                // A gain the Cap or the engine's clamp cut to nothing: paid as authored, nothing moved.
            }
            case UNKNOWN -> throw new IllegalStateException("'" + id + "' is no reputation this server has "
                    + "switched on: no native ReputationGroup of that id, or its companion or the owner switched it off");
            case ZERO -> throw new IllegalStateException("a reward of kind '" + KIND + "' for '" + id
                    + "' moves nothing");
            case NOT_LIVE -> throw new IllegalStateException("no live player to move the reputation '" + id + "' for");
            case NOT_WRITTEN -> throw new IllegalStateException("the engine wrote nothing for the reputation '"
                    + id + "'");
        }
    }

    @Override
    @Nullable
    public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId) {
        ReputationDef def = service.known(reputationOf(spec));
        int amount = amountOf(spec);
        return def == null || amount == 0 ? null : retryLine(def.id(), amount, subject.name());
    }

    @Nonnull
    private static String requireReputation(@Nonnull RewardSpec spec) {
        String id = reputationOf(spec);
        if (id.isEmpty()) {
            throw new IllegalStateException("a reward of kind '" + KIND
                    + "' named no reputation; it needs a 'Reputation' parameter");
        }
        return id;
    }

    private static int requireAmount(@Nonnull RewardSpec spec, @Nonnull String id) {
        int amount = amountOf(spec);
        if (amount == 0) {
            throw new IllegalStateException("a reward of kind '" + KIND + "' for '" + id
                    + "' needs a whole, non-zero 'Amount'");
        }
        return amount;
    }
}
