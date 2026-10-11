package com.ziggfreed.common.objectives.producer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.objectives.book.ObjectiveBookDeps;
import com.ziggfreed.common.objectives.book.ObjectiveBookPages;
import com.ziggfreed.common.objectives.book.achievement.AchievementReader;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.GatedContent;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.reputation.ReputationFactors;
import com.ziggfreed.common.util.SafeLog;

/**
 * What holding a reputation's rank earns in the book, for {@link GatedContent}: every achievement a player who
 * has not earned it could find in the book (in circulation, no feat, not hidden, not hidden until its
 * {@code Requires} passes) with a {@code REPUTATION_RANK} criterion naming its reputation as the Qualifier (the
 * moment {@link ZigReputationProducer} fires). Each is gated on the rank reading,
 * {@code ziggfreedcommon:reputation_rank} {@code <Qualifier>/<Target>} Min 1, so the Reputation page lists it
 * under that rank; it is named by its title and what it pays (its Auto and Claim rewards, each as the book's
 * Rewards block names it), pictured by its own icon or else its first reward's picture, and placed at the book's
 * Achievements.
 *
 * <p>The catalogue is read off the shared achievement engine on every ask, so a reload lands on the next read
 * and a consumer's own achievements list beside the library's. Registered by
 * {@code ProgressionDefaults.install}, so a server running the library alone has it.
 */
public final class RankAchievementGatedContent {

    /** The id this source registers under. */
    public static final String ID = "ziggfreedcommon:rank_achievements";

    /** "{0} ({1})": the achievement's name, then what it pays. */
    static final String NAME_KEY = "reputation.rank_achievement";

    /** Where it is found: the book's Achievements, by the name its tab and the menu give it. */
    static final String PLACE_KEY = "book.tab.achievements";

    private static final String PREFIX = "ziggfreedcommon.";
    private static final String DOMAIN = "progression.";

    /** The glue between two rewards in the pay list; punctuation, never words. */
    private static final String LIST_GLUE = ", ";

    /** The rank reading's Min: the rank is held. */
    private static final double HELD = 1.0;

    private RankAchievementGatedContent() {
    }

    /** Every listed rank achievement as the catalogue stands; none before the runtime is built. */
    @Nonnull
    public static List<GatedContent.Entry> entries() {
        if (!ProgressionRuntime.isBuilt()) {
            return List.of();
        }
        List<Achievement> listed = new ArrayList<>(ProgressionRuntime.achievements().achievements());
        listed.sort(Comparator.comparingInt(Achievement::sortOrder).thenComparing(Achievement::id));
        ObjectiveBookDeps deps = ObjectiveBookPages.resolvedDeps();
        Message place = Msg.tr(PREFIX, DOMAIN + PLACE_KEY);
        List<GatedContent.Entry> out = new ArrayList<>();
        for (Achievement achievement : listed) {
            try {
                GatedContent.Entry entry = entry(achievement, deps, place);
                if (entry != null) {
                    out.add(entry);
                }
            } catch (Throwable t) {
                // One achievement that cannot be read costs its own line, never every rank's.
                SafeLog.fine("[progression] a rank achievement could not be listed: " + achievement.id(), t);
            }
        }
        return out;
    }

    @Nullable
    private static GatedContent.Entry entry(@Nonnull Achievement achievement, @Nonnull ObjectiveBookDeps deps,
            @Nonnull Message place) {
        if (!achievement.available() || achievement.featOfStrength() || achievement.hidden()
                || achievement.requirePrerequisites()) {
            return null;
        }
        GateSpec lock = requires(achievement);
        if (lock == null) {
            return null;
        }
        List<RewardSpec> pays = new ArrayList<>(achievement.autoRewards());
        pays.addAll(achievement.claimRewards());
        List<RewardChip> chips = AchievementReader.rewardChips(pays, deps);
        RewardChip first = chips.isEmpty() ? null : chips.get(0);
        String own = AchievementReader.iconOf(achievement);
        String icon = own != null && !own.isBlank() ? own : first == null ? null : first.iconItemId();
        // The picture is the item itself only when it is the first reward's own item handed over.
        boolean item = icon != null && first != null && first.showsItem() && icon.equals(first.iconItemId());
        return new GatedContent.Entry(lock, icon, item, name(achievement, chips), place);
    }

    /** "Guest of Honor (Title: Guest of Honor)": its title, then each reward it pays; the title alone with none. */
    @Nonnull
    private static Message name(@Nonnull Achievement achievement, @Nonnull List<RewardChip> chips) {
        Message title = ProgressionTexts.titleOrUntitled(achievement.id());
        if (chips.isEmpty()) {
            return title;
        }
        Message[] parts = new Message[chips.size() * 2 - 1];
        for (int i = 0; i < chips.size(); i++) {
            parts[i * 2] = chips.get(i).label();
            if (i > 0) {
                parts[i * 2 - 1] = Msg.raw(LIST_GLUE);
            }
        }
        return Msg.tr(PREFIX, DOMAIN + NAME_KEY, title, Msg.cat(parts));
    }

    /**
     * The block a rank achievement stands behind, or null when no criterion asks for a rank with a named
     * reputation. A criterion naming one rank of one reputation is a plain leaf (every criterion is needed);
     * one naming several ({@code Targets}, {@code Qualifiers}) offers each pair as an {@code AnyOf} route, the
     * one level of grouping a block allows.
     */
    @Nullable
    static GateSpec requires(@Nonnull Achievement achievement) {
        List<FactorCondition> leaves = new ArrayList<>();
        List<GateClause> routes = new ArrayList<>();
        for (ObjectiveDef criterion : achievement.criteria()) {
            if (criterion == null || !ZigReputationProducer.KIND.equalsIgnoreCase(criterion.kind())) {
                continue;
            }
            List<FactorCondition> ranks = ranksAsked(criterion);
            if (ranks.size() == 1) {
                leaves.add(ranks.get(0));
            } else {
                for (FactorCondition rank : ranks) {
                    routes.add(GateClause.of(new FactorCondition[] {rank}, null, null, null));
                }
            }
        }
        if (leaves.isEmpty() && routes.isEmpty()) {
            return null;
        }
        return GateSpec.of(leaves.isEmpty() ? null : leaves.toArray(new FactorCondition[0]), null, null, null,
                null, routes.isEmpty() ? null : routes.toArray(new GateClause[0]), null);
    }

    /** Each rank one criterion credits, as the rank reading: every named reputation with every named rank. */
    @Nonnull
    static List<FactorCondition> ranksAsked(@Nonnull ObjectiveDef criterion) {
        List<String> reputations = named(criterion.qualifier(), criterion.qualifiers());
        List<String> ranks = named(criterion.target(), criterion.targets());
        List<FactorCondition> out = new ArrayList<>();
        for (String reputation : reputations) {
            for (String rank : ranks) {
                out.add(FactorCondition.of(ReputationFactors.RANK, reputation + "/" + rank, HELD, null));
            }
        }
        return out;
    }

    /** The single value and the list entries, trimmed, blanks dropped, each once in any case. */
    @Nonnull
    private static List<String> named(@Nullable String one, @Nonnull List<String> more) {
        List<String> out = new ArrayList<>();
        add(out, one);
        for (String value : more) {
            add(out, value);
        }
        return out;
    }

    private static void add(@Nonnull List<String> out, @Nullable String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String trimmed = value.trim();
        for (String held : out) {
            if (held.equalsIgnoreCase(trimmed)) {
                return;
            }
        }
        out.add(trimmed);
    }
}
