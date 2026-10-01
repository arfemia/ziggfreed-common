package com.ziggfreed.common.loot.reward;

import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.validation.Finding;

/**
 * A reward kind that pays into the PASS it is granted in rather than to the player: each grant hands
 * its spec to a collector the pass carries, and the pass settles what was collected once it is over.
 *
 * <p>The collector rides the {@link Subject}. A pass layers it beside the player with
 * {@link Subject#withFacets} and grants through the ONE shared vocabulary; every reward of the pass,
 * and every reward of a nested table the pass rolls, is handed that same subject, so this kind finds
 * the collector by its type ({@link Subject#handleAs}) at any depth. That is why such a kind is
 * registered once into {@link RewardKinds#shared()} like every other kind, and never into a registry
 * copied per pass.
 *
 * <p>It is not a new {@code LootEngine.Sinks} seam either, though it is a side effect. A sink is a
 * capability every pass may or may not wire (an inventory, a command dispatcher); this is a reward
 * KIND some content authors and some passes collect, so it belongs to the reward vocabulary, and the
 * loot core stays as it is.
 *
 * <h2>Outside a pass it pays nothing, loudly</h2>
 *
 * <p>A grant whose subject carries no collector of the named type THROWS, naming the kind and the
 * collector it needed. {@link RewardGrants} then counts the reward lost, warns, and records the
 * failure against the kind's owner on the registry's ledger, exactly as it does for any failing kind.
 * There is no retry command, since what was collected only means something to the pass that is
 * already gone, so a deferred payout drops it as a kind that cannot be handed over later.
 *
 * <h2>Nothing on the receipt</h2>
 *
 * <p>A collected reward has not been handed to anyone yet: the pass decides what it becomes. So a
 * successful grant reports nothing on the receipt, and a toast listing what a payout handed over
 * never shows it.
 *
 * <h2>The site marker</h2>
 *
 * <p>Registering such a kind makes it KNOWN everywhere, which would silence the warning a content
 * validator gives for a kind nothing pays. The kind therefore names its collector type
 * ({@link #collector()}, read through {@link #collectorOf}), and a validator auditing a site no pass
 * ever carries a collector into (a quest, an achievement, a shop offer, a board contract) reports it
 * through {@link #siteWarning}. A loot table stays silent: it cannot know where it will be rolled.
 *
 * @param <C> the collector type a pass layers onto its subject
 */
public final class CollectingRewardKind<C> implements RewardHandler {

    /**
     * The finding code every site validator reports a collecting kind under, in its own domain:
     * the reward pays only inside a pass carrying its collector, so authored at that site it would
     * always count lost.
     */
    public static final String SITE_CODE = "PASS_ONLY_REWARD_KIND";

    /** What a collecting kind does with one reward once it has found the pass's collector. */
    @FunctionalInterface
    public interface Collect<C> {

        /** Add {@code spec} to {@code collector}. May throw; the payout layer reports it lost. */
        void collect(@Nonnull C collector, @Nonnull RewardSpec spec) throws Exception;
    }

    @Nonnull
    private final String kindId;

    @Nonnull
    private final Class<C> collector;

    @Nonnull
    private final Collect<? super C> collect;

    private CollectingRewardKind(@Nonnull String kindId, @Nonnull Class<C> collector,
            @Nonnull Collect<? super C> collect) {
        this.kindId = kindId;
        this.collector = collector;
        this.collect = collect;
    }

    /**
     * A kind that hands each reward of {@code kindId} to the pass's {@code collector}. Register it
     * once, under the same id, into {@link RewardKinds#shared()}.
     *
     * @throws IllegalArgumentException when the id is blank or either other argument is missing
     */
    @Nonnull
    public static <C> CollectingRewardKind<C> of(@Nonnull String kindId, @Nonnull Class<C> collector,
            @Nonnull Collect<? super C> collect) {
        if (kindId == null || kindId.isBlank()) {
            throw new IllegalArgumentException("a collecting reward kind needs an id");
        }
        if (collector == null || collect == null) {
            throw new IllegalArgumentException("the collecting reward kind '" + kindId
                    + "' needs a collector type and something to do with each reward");
        }
        return new CollectingRewardKind<>(kindId.trim(), collector, collect);
    }

    /** The id this kind was built for, which is the id it is registered under. */
    @Nonnull
    public String kindId() {
        return kindId;
    }

    /** The collector type a pass must carry on its subject for this kind to pay. */
    @Nonnull
    public Class<C> collector() {
        return collector;
    }

    /**
     * The collector type of the kind {@code kinds} registers under {@code kindId}, or null when that
     * kind is not a collecting one (or nothing is registered, or there is no registry). The one read
     * every validator asks, so none of them has to know how a collecting kind is built.
     */
    @Nullable
    public static Class<?> collectorOf(@Nullable RewardKindRegistry kinds, @Nullable String kindId) {
        if (kinds == null) {
            return null;
        }
        return kinds.handler(kindId) instanceof CollectingRewardKind<?> collecting
                ? collecting.collector()
                : null;
    }

    /**
     * The warning a site validator reports when {@code kindId}, authored at a site no pass ever
     * carries a collector into, is a collecting kind in {@code kinds}; null when it is not one.
     *
     * @param domain   the reporting validator's own domain
     * @param kinds    the vocabulary the site pays through
     * @param kindId   the kind the reward names
     * @param where    the authored field the reward sits in, as the author reads it ({@code "Rewards"},
     *                 {@code "Rewards.Claim"})
     * @param sourceId the content id the finding is filed under
     */
    @Nullable
    public static Finding siteWarning(@Nonnull String domain, @Nullable RewardKindRegistry kinds,
            @Nullable String kindId, @Nonnull String where, @Nonnull String sourceId) {
        Class<?> type = collectorOf(kinds, kindId);
        if (type == null) {
            return null;
        }
        return Finding.warning(domain, SITE_CODE,
                where + " names '" + kindId + "', a reward that pays only inside a pass carrying a "
                        + type.getSimpleName() + "; nothing here carries one, so it would always count "
                        + "as lost", sourceId);
    }

    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject) throws Exception {
        C target = subject.handleAs(collector);
        if (target == null) {
            throw new IllegalStateException("a reward of kind '" + kindId + "' collects onto a pass that"
                    + " carries a " + collector.getSimpleName() + "; this payout carries none, so"
                    + " nothing was paid");
        }
        collect.collect(target, spec);
    }

    /**
     * The receipt form reports NOTHING: what was collected has not been handed to anyone yet, and
     * the pass that owns the collector decides what it becomes.
     */
    @Override
    public void grant(@Nonnull RewardSpec spec, @Nonnull Subject subject, @Nonnull String sourceId,
            @Nonnull Consumer<RewardSpec> receipt) throws Exception {
        grant(spec, subject, sourceId);
    }
}
