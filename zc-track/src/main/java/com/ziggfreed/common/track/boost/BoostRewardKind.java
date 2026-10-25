package com.ziggfreed.common.track.boost;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.reward.DeferredRewards;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardChips;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.track.command.ZigBoostCommand;
import com.ziggfreed.common.util.SafeLog;

/**
 * The reward kind that starts a boost: {@code {"Kind": "Boost", "Params": {"Boost": "<Id>"}}}, with an optional
 * {@code Percent} and {@code Seconds} in place of the boost's own, and a {@code Track} pointing it at one track of
 * those the boost lifts. UNPREFIXED because the library owns the starter
 * behind it, and registered once into the shared vocabulary, so a quest, a board contract, an achievement, a shop
 * entry, a lootable and a track's unlock row all pay it, and each of those sites parks it as a Claim (M428): the
 * boost starts when the player collects it, never before.
 *
 * <p>It starts through {@link Boosts#start}, so the limits, the wait, the season and the {@code Requires} all apply.
 * A boost that could start later but not now (a limit reached, a wait not over) is never lost: it goes back to the
 * player's Claims as one waiting Claim, and the player is told why, the way a refused boost item stays in the bag.
 * With no live player it fails, and {@link #retryCommand} answers the {@code /zigboost start} line a Claim keeps
 * beside the reward, which runs only where this kind could not.
 */
final class BoostRewardKind implements RewardHandler {

    /** The kind id content writes. */
    public static final String KIND = "Boost";

    /** Who this registration is attributed to. */
    public static final String OWNER = "ziggfreedcommon";

    static final String PARAM_BOOST = "boost";
    static final String PARAM_PERCENT = "percent";
    static final String PARAM_SECONDS = "seconds";
    static final String PARAM_TRACK = "track";

    /** What a waiting Claim's id and source start with. */
    private static final String WAITING_SOURCE = "boost:";

    /** The name a deferred command keeps for whoever presses Claim. */
    private static final String CLAIMER = "{player}";

    /** Keeps two waiting Claims parked in one millisecond apart. */
    private static final AtomicLong WAITING = new AtomicLong();

    private BoostRewardKind() {
    }

    /** Register the kind into {@code kinds}. */
    public static void registerInto(@Nonnull RewardKindRegistry kinds) {
        kinds.register(KIND, OWNER, new BoostRewardKind());
    }

    /** How a Boost reward reads in a reward list, for {@code RewardChips.contribute}. */
    @Nonnull
    public static RewardChips.Source chips() {
        return BoostRewardKind::chipFor;
    }

    /**
     * "50% more on Mining for 30m" beside the boost's own {@code Icon}, hovering it says the boost's name; null for
     * another kind or a boost this server does not have.
     */
    @Nullable
    public static RewardChip chipFor(@Nonnull RewardSpec spec) {
        if (!KIND.equalsIgnoreCase(spec.kind())) {
            return null;
        }
        BoostAsset boost = BoostConfig.getInstance().live(boostOf(spec));
        if (boost == null) {
            return null;
        }
        Double percent = percentOf(spec);
        Long seconds = secondsOf(spec);
        return RewardChip.picture(boost.icon(), BoostText.rewardLine(boost,
                percent == null ? boost.percent() : percent, seconds == null ? boost.seconds() : seconds,
                trackOf(spec)), BoostText.name(boost));
    }

    /** The one track it points the boost at, trimmed, or null for all the boost lifts. */
    @Nullable
    public static String trackOf(@Nonnull RewardSpec spec) {
        return BoostAsset.blankToNull(spec.param(PARAM_TRACK));
    }

    /** Which boost {@code spec} starts, trimmed; empty when it names none. */
    @Nonnull
    public static String boostOf(@Nonnull RewardSpec spec) {
        return spec.paramOr(PARAM_BOOST, "").trim();
    }

    /** The percent it asks for in place of the boost's own, or null for the boost's own. */
    @Nullable
    public static Double percentOf(@Nonnull RewardSpec spec) {
        String written = spec.param(PARAM_PERCENT);
        if (written == null || written.isBlank()) {
            return null;
        }
        double percent = spec.doubleParam(PARAM_PERCENT, Double.NaN);
        return Double.isFinite(percent) ? percent : null;
    }

    /** The length it asks for in place of the boost's own, in seconds, or null for the boost's own. */
    @Nullable
    public static Long secondsOf(@Nonnull RewardSpec spec) {
        String written = spec.param(PARAM_SECONDS);
        if (written == null || written.isBlank()) {
            return null;
        }
        long seconds = spec.longParam(PARAM_SECONDS, 0L);
        return seconds > 0L ? seconds : null;
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
        String boostId = boostOf(spec);
        if (boostId.isEmpty()) {
            throw new IllegalStateException("a reward of kind '" + KIND + "' needs a 'Boost'");
        }
        Boosts.Start start = Boosts.start(subject.id(), boostId, percentOf(spec), secondsOf(spec), trackOf(spec),
                sourceId);
        if (start.started()) {
            receipt.accept(spec);
            return;
        }
        BoostAsset boost = start.boost();
        if (boost == null || !start.outcome().couldStartLater()) {
            throw new IllegalStateException("the boost '" + boostId + "' could not start ("
                    + start.outcome().name() + ")");
        }
        if (!waitAsClaim(subject.id(), spec, sourceId, boost)) {
            throw new IllegalStateException("the boost '" + boostId + "' could not start (" + start.outcome().name()
                    + ") and could not wait as a Claim");
        }
        tell(subject.id(), BoostText.waits(boost), BoostText.refusal(start));
    }

    @Override
    @Nullable
    public String retryCommand(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId) {
        BoostAsset boost = BoostConfig.getInstance().live(boostOf(spec));
        if (boost == null) {
            return null;
        }
        String line = ZigBoostCommand.FAMILY + " " + ZigBoostCommand.START + " --player=" + subject.name()
                + " --boost=" + boost.getId();
        String percent = spec.param(PARAM_PERCENT);
        if (percentOf(spec) != null && percent != null) {
            line += " --percent=" + percent.trim();
        }
        String seconds = spec.param(PARAM_SECONDS);
        if (secondsOf(spec) != null && seconds != null) {
            line += " --seconds=" + seconds.trim();
        }
        String track = trackOf(spec);
        if (track != null) {
            line += " --track=" + track;
        }
        return line;
    }

    /** Park {@code spec} back on {@code player}'s Claims, one waiting Claim titled by the boost. */
    private static boolean waitAsClaim(@Nonnull UUID player, @Nonnull RewardSpec spec, @Nonnull String sourceId,
            @Nonnull BoostAsset boost) {
        try {
            String source = sourceId.startsWith(WAITING_SOURCE) ? sourceId : WAITING_SOURCE + boost.getId();
            List<InstanceReward> rewards = DeferredRewards.fromSpecs(List.of(spec), RewardKinds.shared(),
                    Subject.of(player, CLAIMER), source, message -> SafeLog.warn("[boost] " + message));
            if (rewards.isEmpty()) {
                return false;
            }
            long now = System.currentTimeMillis();
            Claim claim = new Claim(source + ":" + player + ":" + now + ":" + WAITING.incrementAndGet(), source,
                    BoostText.PREFIX + "claim.waiting", List.of(BoostText.claimNameArg(boost)), null, rewards, now);
            return ClaimStore.shared().award(player, claim, null, source, now);
        } catch (Throwable t) {
            SafeLog.warn("[boost] a boost that could not start could not wait as a Claim: " + t.getMessage());
            return false;
        }
    }

    /** Tell the live player {@code player} each line given. */
    private static void tell(@Nonnull UUID player, @Nonnull Message... lines) {
        try {
            Ref<EntityStore> ref = PlayerAccess.liveRef(player);
            PlayerRef target = ref == null ? null : ref.getStore().getComponent(ref, PlayerRef.getComponentType());
            if (target == null) {
                return;
            }
            for (Message line : lines) {
                if (line != null) {
                    target.sendMessage(line);
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("[boost] could not tell a player their boost waits: " + t.getMessage());
        }
    }
}
