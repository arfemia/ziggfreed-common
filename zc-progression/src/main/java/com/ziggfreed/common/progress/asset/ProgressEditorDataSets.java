package com.ziggfreed.common.progress.asset;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;

/**
 * The pick lists the in-game Asset Editor offers on any authored {@code Kind} field in this module,
 * so an author chooses from the vocabulary actually present on this server instead of typing an id
 * and hoping. ONE set of lists for every lifecycle engine here, so what an author may write cannot
 * drift between two kinds of content.
 *
 * <p>The vocabularies themselves are per-consumer (each mod builds its own registries and hands
 * them to its own engine), so a consumer that wants its kinds offered ADVERTISES its registries
 * here at setup:
 * <pre>{@code
 * ProgressEditorDataSets.advertise(myObjectiveKinds, myRewardKinds);
 * }</pre>
 * Advertising is optional and additive: several consumers each add their own, and the built-in
 * objective kinds are always listed because every engine has them.
 *
 * <p><b>A dropdown is authoring convenience, never validation.</b> A hand-written JSON file never
 * passes through the editor, so a content validator stays the real check and a free-typed id still
 * works.
 */
public final class ProgressEditorDataSets {

    /** The objective kinds an authored objective's {@code Kind} may name. */
    public static final String OBJECTIVE_KINDS = "ziggfreedcommon:objective_kinds";

    /** The reward kinds an authored {@code Rewards[].Kind} may name. */
    public static final String REWARD_KINDS = "ziggfreedcommon:reward_kinds";

    /** The engine-generic objective vocabulary, always offered even before anyone advertises. */
    private static final ObjectiveKindRegistry BUILT_INS = new ObjectiveKindRegistry();

    private static final List<ObjectiveKindRegistry> OBJECTIVE_SOURCES = new CopyOnWriteArrayList<>();
    private static final List<RewardKindRegistry> REWARD_SOURCES = new CopyOnWriteArrayList<>();

    private ProgressEditorDataSets() {
    }

    /**
     * Offer a consumer's vocabularies in the editor's pick lists. Either may be null. Registering
     * the same registry twice is harmless: the lists are a union.
     */
    public static void advertise(@Nullable ObjectiveKindRegistry objectiveKinds,
            @Nullable RewardKindRegistry rewardKinds) {
        if (objectiveKinds != null && !OBJECTIVE_SOURCES.contains(objectiveKinds)) {
            OBJECTIVE_SOURCES.add(objectiveKinds);
        }
        if (rewardKinds != null && !REWARD_SOURCES.contains(rewardKinds)) {
            REWARD_SOURCES.add(rewardKinds);
        }
    }

    /** Every objective kind an author may name here: the built-ins plus every advertised registry. */
    @Nonnull
    public static Collection<String> objectiveKindIds() {
        Set<String> ids = new TreeSet<>(BUILT_INS.ids());
        for (ObjectiveKindRegistry registry : OBJECTIVE_SOURCES) {
            ids.addAll(registry.ids());
        }
        return ids;
    }

    /** Every reward kind an author may name here. Empty until a consumer advertises one. */
    @Nonnull
    public static Collection<String> rewardKindIds() {
        Set<String> ids = new TreeSet<>();
        for (RewardKindRegistry registry : REWARD_SOURCES) {
            ids.addAll(registry.ids());
        }
        return ids;
    }

    /** Forget every advertised registry (a test harness, a full reload). */
    public static void clear() {
        OBJECTIVE_SOURCES.clear();
        REWARD_SOURCES.clear();
    }
}
