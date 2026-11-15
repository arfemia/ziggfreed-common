package com.ziggfreed.common.progress.asset;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.loot.LootEditorDataSets;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.progress.ObjectiveKind;
import com.ziggfreed.common.progress.ObjectiveKindRegistry;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;

/**
 * The pick lists the in-game Asset Editor offers on any authored {@code Kind} field in this module,
 * so an author chooses from the vocabulary actually present on this server instead of typing an id
 * and hoping. ONE set of lists for every lifecycle engine here, so what an author may write cannot
 * drift between two kinds of content.
 *
 * <p><b>The library lists its own vocabularies by default</b> (M513): the one objective vocabulary
 * both engines dispatch against ({@link ProgressionRuntime#objectiveKinds()}) and the one reward
 * vocabulary every payout shares ({@link RewardKinds#shared()}), read live at each request, so a kind
 * a mod registers into either at setup, or a kind file that folds into either, is listed with nothing
 * more to do. A consumer that keeps a vocabulary of its OWN beside those (a private registry its own
 * engine reads) ADVERTISES only that one here at setup:
 * <pre>{@code
 * ProgressEditorDataSets.advertise(myPrivateObjectiveKinds, null);
 * }</pre>
 * Advertising is additive and never needed for the shared registries, which are always listed.
 *
 * <p>An objective list holds what content may author: every producible kind and every alias (sugar a
 * mod registers for another kind), as the vocabulary spells them; a kind registered unproducible has
 * no producer, so it is left off. A reward list holds every registered kind as its owner spells it.
 *
 * <p><b>A dropdown is authoring convenience, never validation.</b> A hand-written JSON file never
 * passes through the editor, so a content validator stays the real check and a free-typed id still
 * works.
 */
public final class ProgressEditorDataSets {

    /** The objective kinds an authored objective's {@code Kind} may name. */
    public static final String OBJECTIVE_KINDS = EditorDataSets.OBJECTIVE_KINDS;

    /**
     * The reward kinds an authored {@code Rewards[].Kind} may name: the one reward-kind list, the same id
     * the loot layer serves under ({@link LootEditorDataSets#REWARD_KINDS}).
     */
    public static final String REWARD_KINDS = LootEditorDataSets.REWARD_KINDS;

    private static final List<ObjectiveKindRegistry> OBJECTIVE_SOURCES = new CopyOnWriteArrayList<>();
    private static final List<RewardKindRegistry> REWARD_SOURCES = new CopyOnWriteArrayList<>();

    private ProgressEditorDataSets() {
    }

    /**
     * Offer a consumer's own vocabularies in the editor's pick lists, beside the shared ones the library
     * always lists. Either may be null. Registering the same registry twice, or a shared one, is
     * harmless: the lists are a union.
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

    /**
     * Every objective kind an author may name here: the shared vocabulary's producible kinds and aliases,
     * plus every advertised registry's, upper-cased as the vocabulary spells them, sorted.
     */
    @Nonnull
    public static Collection<String> objectiveKindIds() {
        Map<String, String> ids = new TreeMap<>();
        authorable(ProgressionRuntime.objectiveKinds(), ids);
        for (ObjectiveKindRegistry registry : OBJECTIVE_SOURCES) {
            authorable(registry, ids);
        }
        return List.copyOf(ids.values());
    }

    /**
     * Every reward kind an author may name here: the shared vocabulary's, plus every advertised registry's,
     * each spelled as its owner writes it, sorted without regard to case.
     */
    @Nonnull
    public static Collection<String> rewardKindIds() {
        Map<String, String> ids = new TreeMap<>();
        spelled(RewardKinds.shared(), ids);
        for (RewardKindRegistry registry : REWARD_SOURCES) {
            spelled(registry, ids);
        }
        return List.copyOf(ids.values());
    }

    /** {@code registry}'s producible kinds and aliases into {@code into}, keyed by the lower-cased id. */
    private static void authorable(@Nonnull ObjectiveKindRegistry registry, @Nonnull Map<String, String> into) {
        for (String id : registry.ids()) {
            ObjectiveKind kind = registry.kind(id);
            if (kind != null && kind.producible()) {
                into.putIfAbsent(kind.id().toLowerCase(Locale.ROOT), kind.id());
            }
        }
        for (String alias : registry.aliasIds()) {
            into.putIfAbsent(alias.toLowerCase(Locale.ROOT), alias);
        }
    }

    private static void spelled(@Nonnull RewardKindRegistry registry, @Nonnull Map<String, String> into) {
        for (String id : registry.spelledIds()) {
            into.putIfAbsent(id.toLowerCase(Locale.ROOT), id);
        }
    }

    /** Forget every advertised registry (a test harness, a full reload); the shared ones stay listed. */
    public static void clear() {
        OBJECTIVE_SOURCES.clear();
        REWARD_SOURCES.clear();
    }
}
