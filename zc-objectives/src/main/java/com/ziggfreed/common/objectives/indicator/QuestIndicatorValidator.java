package com.ziggfreed.common.objectives.indicator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.entity.overhead.OverheadIndicatorConfig;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.asset.QuestDefinition;
import com.ziggfreed.common.quest.asset.QuestIndicatorSpec;
import com.ziggfreed.common.quest.asset.QuestPool;
import com.ziggfreed.common.quest.asset.QuestPoolValidator;
import com.ziggfreed.common.quest.asset.QuestSituation;
import com.ziggfreed.common.validation.Finding;

/**
 * The audit over every {@code Indicator} block a server carries: the global word, each quest's
 * and each step's. What it catches is the block that loads perfectly and shows nothing: a
 * situation pointed at an overhead state no look file describes. An unknown situation key cannot
 * be written at all, since the block is a structured codec with four named groups, so nothing
 * here has to look for one.
 *
 * <p>Reported in the {@code quest} domain beside the pool audit, at WARNING: the look may belong
 * to a pack the author expects some servers not to install, and a missing look costs a picture,
 * never progress.
 */
public final class QuestIndicatorValidator {

    /** The finding code for a state with no look file behind it. */
    public static final String UNKNOWN_STATE = "UNKNOWN_INDICATOR_STATE";

    private QuestIndicatorValidator() {
    }

    /** Audit {@code pool}'s quests and steps plus the global word against the loaded look files. */
    @Nonnull
    public static List<Finding> validate(@Nonnull QuestPool pool) {
        return validate(pool, QuestIndicatorConfig.getInstance().global(),
                OverheadIndicatorConfig.getInstance()::has);
    }

    /**
     * The piecemeal form: {@code global} is the server's word and {@code lookExists} answers whether
     * a state has a look, so the rule is assertable without a loaded store.
     */
    @Nonnull
    public static List<Finding> validate(@Nonnull QuestPool pool, @Nullable QuestIndicatorSpec global,
            @Nonnull Predicate<String> lookExists) {
        List<Finding> out = new ArrayList<>();
        check(out, global, "Default", "the global Default.json", lookExists);
        for (Map.Entry<String, QuestDefinition> entry : pool.definitions().entrySet()) {
            Quest quest = entry.getValue().quest();
            check(out, quest.indicator(), entry.getKey(), "quest '" + entry.getKey() + "'", lookExists);
            for (Map.Entry<String, QuestIndicatorSpec> step : quest.stepIndicators().entrySet()) {
                check(out, step.getValue(), entry.getKey(),
                        "quest '" + entry.getKey() + "' step '" + step.getKey() + "'", lookExists);
            }
        }
        return out;
    }

    private static void check(@Nonnull List<Finding> out, @Nullable QuestIndicatorSpec spec,
            @Nonnull String sourceId, @Nonnull String where, @Nonnull Predicate<String> lookExists) {
        if (spec == null) {
            return;
        }
        for (QuestSituation situation : QuestSituation.values()) {
            QuestIndicatorSpec.Situation authored = spec.situation(situation);
            String state = authored == null ? null : authored.getState();
            if (state == null || state.isBlank() || lookExists.test(state.trim())) {
                continue;
            }
            out.add(Finding.warning(QuestPoolValidator.DOMAIN, UNKNOWN_STATE,
                    where + " points its " + situation.key() + " indicator at the overhead state '" + state
                            + "', which no look file describes; add Server/ZiggfreedCommon/OverheadIndicators/"
                            + state.trim() + ".json or that situation shows nothing over the character",
                    sourceId));
        }
    }
}
