package com.ziggfreed.common.objectives.hytale;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.adventure.objectives.Objective;
import com.hypixel.hytale.builtin.adventure.objectives.config.ObjectiveAsset;
import com.hypixel.hytale.builtin.adventure.objectives.config.completion.ObjectiveCompletionAsset;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.instance.reward.DeferredRewards;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.interaction.InteractionRewards;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackRewardKind;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.ZigTrackReward;
import com.ziggfreed.common.util.SafeLog;

/**
 * How a completed native objective pays through CommonLib's reward kinds: one Claim per participant (M428), which
 * the player takes on their Claims tab. Both completion Types pay through here ({@code ZigRewards} and
 * {@code ZigTrack}), so neither keeps a payout of its own.
 *
 * <p>Every participant the objective lists is paid, online or not, since a Claim waits for its player. The Claim
 * is the objective run's: its id is the objective's id and run ({@code objective:<id>:<uuid>}), and it pays every
 * {@code ZigRewards} and {@code ZigTrack} completion the objective authors at once, written by whichever of them
 * the engine runs first. It is written only while the player holds no Claim by that id ({@link ClaimStore#award}),
 * so the next completion Type on the same objective, or the same completion run again (a crash after the Claim
 * was written, before the objective left disk), writes nothing and never pays twice. It is titled by the first of
 * those completions that pays anything: its {@code TitleKey}, else the objective's own title when a line ships for it, else that
 * completion's fallback (a track's own reward line for a {@code ZigTrack}, the tab's own word for a reward
 * otherwise).
 */
final class ObjectiveClaims {

    /** What every Claim a native objective pays starts its id and source with. */
    static final String SOURCE_PREFIX = "objective:";

    /** The name a deferred line keeps for whoever presses Claim. */
    private static final String CLAIMER = "{player}";

    private ObjectiveClaims() {
    }

    /**
     * Park the objective's whole CommonLib payout as its Claim for each of its participants, unless they hold it
     * already. Never throws: a participant whose Claim fails costs that Claim and a warning.
     *
     * @param type the completion Type paying, for the log
     */
    static void park(@Nonnull Objective objective, @Nonnull String type) {
        if (objective.getPlayerUUIDs() == null) {
            return;
        }
        Payout payout = payout(objective);
        if (payout.rewards().isEmpty()) {
            return;
        }
        String source = SOURCE_PREFIX + objective.getObjectiveId();
        String id = source + ":" + objective.getObjectiveUUID();
        Title title = title(objective, payout.titleKey(), payout.fallbackTitle(), payout.fallbackArgs());
        long now = System.currentTimeMillis();
        for (UUID player : List.copyOf(objective.getPlayerUUIDs())) {
            try {
                List<InstanceReward> deferred = DeferredRewards.fromSpecs(payout.rewards(), RewardKinds.shared(),
                        Subject.of(player, CLAIMER), source, message -> SafeLog.warn("[objectives] " + message));
                ClaimStore.shared().award(player,
                        new Claim(id, source, title.key(), title.args(), null, deferred, now), null, source, now);
            } catch (Throwable t) {
                SafeLog.warn("[objectives] the " + type + " Claim of objective '" + objective.getObjectiveId()
                        + "' could not be written for " + player + ": " + t.getMessage());
            }
        }
    }

    /**
     * What the objective's {@code ZigRewards} and {@code ZigTrack} completions pay together, in authored order,
     * and the title the first of them that pays anything gives the Claim. A {@code ZigTrack} naming no usable
     * track or no amount adds nothing.
     */
    @Nonnull
    static Payout payout(@Nonnull Objective objective) {
        ObjectiveAsset asset = objective.getObjectiveAsset();
        ObjectiveCompletionAsset[] completions = asset == null ? null : asset.getCompletionHandlers();
        List<RewardSpec> rewards = new ArrayList<>();
        Payout first = null;
        for (ObjectiveCompletionAsset completion : completions == null ? new ObjectiveCompletionAsset[0]
                : completions) {
            Payout part = part(completion);
            if (part == null) {
                continue;
            }
            rewards.addAll(part.rewards());
            if (first == null && !part.rewards().isEmpty()) {
                first = part;
            }
        }
        return first == null ? new Payout(List.of(), null, null, List.of())
                : new Payout(List.copyOf(rewards), first.titleKey(), first.fallbackTitle(), first.fallbackArgs());
    }

    /** One completion's share and its title, or null for a completion that is not one of the library's. */
    @Nullable
    private static Payout part(@Nullable ObjectiveCompletionAsset completion) {
        if (completion instanceof ZigRewardsCompletionAsset rewards) {
            return new Payout(InteractionRewards.specs(rewards.getRewards()), rewards.getTitleKey(), null, List.of());
        }
        if (completion instanceof TrackCompletionAsset track) {
            TrackDef def = TrackService.def(track.trackId());
            List<RewardSpec> specs = def == null || track.amount() == 0 ? List.of()
                    : List.of(TrackRewardKind.spec(def.asset().getId(), track.amount()));
            return new Payout(specs, track.getTitleKey(), ZigTrackReward.CLAIM_TITLE_KEY,
                    def == null ? List.of() : List.of(TrackText.claimNameArg(def.asset())));
        }
        return null;
    }

    /**
     * An objective's payout: the reward rows, and the title its Claim takes.
     *
     * @param titleKey      the first completion's authored {@code TitleKey}, or null
     * @param fallbackTitle its title when neither that nor the objective's own title ships, or null for the
     *                      Claims tab's own word for a reward
     * @param fallbackArgs  the fallback title's arguments
     */
    record Payout(@Nonnull List<RewardSpec> rewards, @Nullable String titleKey, @Nullable String fallbackTitle,
            @Nonnull List<String> fallbackArgs) {
    }

    /** A Claim's title key (null for the tab's own word) and the arguments it binds. */
    private record Title(@Nullable String key, @Nonnull List<String> args) {
    }

    /** The authored key; else the objective's own title, when a line ships for it; else the fallback. */
    @Nonnull
    private static Title title(@Nonnull Objective objective, @Nullable String titleKey, @Nullable String fallbackTitle,
            @Nonnull List<String> fallbackArgs) {
        if (titleKey != null && !titleKey.isBlank()) {
            return new Title(titleKey.trim(), List.of());
        }
        ObjectiveAsset asset = objective.getObjectiveAsset();
        String own = asset == null ? null : asset.getTitleKey();
        if (own != null && LangCatalog.has(own)) {
            return new Title(own, List.of());
        }
        return fallbackTitle == null || fallbackTitle.isBlank() ? new Title(null, List.of())
                : new Title(fallbackTitle.trim(), List.copyOf(fallbackArgs));
    }
}
