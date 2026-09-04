package com.ziggfreed.common.objectives.producer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.encounter.asset.EncounterBindingAsset;
import com.ziggfreed.common.encounter.asset.EncounterBindingConfig;
import com.ziggfreed.common.quest.asset.QuestAxisRow;
import com.ziggfreed.common.quest.asset.QuestEnumeratorRegistry;

/**
 * The list of BOSSES a generated family may fan out over: every bound encounter on this server,
 * read live off the folded binding rows at the moment a generator is expanded, so a slay-every-boss
 * achievement chain or a rotating boss shelf stays right as packs add fights with no edit anywhere.
 *
 * <p>Published as {@value #SOURCE_ENCOUNTERS}. A row binds the axis's own token to the encounter
 * SCRIPT id (what an {@code ENCOUNTER_DEFEATED} step targets) and, under {@value #TOKEN_NAME_KEY},
 * the row's {@code NameKey} (blank when the row authored none), so a generated title can use the
 * boss's own name with no second lookup. The one filter is {@code {"Difficulty": "hard"}}, matched
 * against the row's {@code Progression.Difficulty}; a binding switched off ({@code Enabled: false})
 * is not listed, because a family generated over it would name a fight this server has taken out
 * of rotation.
 *
 * <p>Installed into a consumer's own registry rather than one of this library's: axes are a
 * per-consumer vocabulary, and the same helper answers the same list to whichever registry asks.
 */
public final class EncounterQuestAxes {

    /** Every bound, enabled encounter on this server, by id. */
    public static final String SOURCE_ENCOUNTERS = "ziggfreedcommon:encounters";

    /** The token each row also binds: the binding row's {@code NameKey}, or blank. */
    public static final String TOKEN_NAME_KEY = "encounter_name_key";

    /** Filter key: {@code {"Difficulty": "hard"}} narrows the list to rows authored with that label. */
    private static final String FILTER_DIFFICULTY = "Difficulty";

    private EncounterQuestAxes() {
    }

    /** Publish the encounters axis into {@code registry}, attributed to {@code owner}. */
    public static void install(@Nonnull QuestEnumeratorRegistry registry, @Nullable String owner) {
        registry.register(SOURCE_ENCOUNTERS, owner,
                filter -> rows(EncounterBindingConfig.getInstance().all().values(), filter));
    }

    /** One row per enabled binding, narrowed to the authored {@code Difficulty} when there is one. */
    @Nonnull
    static List<QuestAxisRow> rows(@Nonnull Collection<EncounterBindingAsset> bindings,
            @Nonnull Map<String, String> filter) {
        String difficulty = filter.get(FILTER_DIFFICULTY);
        String wanted = difficulty == null || difficulty.isBlank() ? null : difficulty.trim();
        List<EncounterBindingAsset> listed = new ArrayList<>();
        for (EncounterBindingAsset row : bindings) {
            if (row == null || !row.isEnabled()) {
                continue;
            }
            if (wanted != null && !wanted.equalsIgnoreCase(difficultyOf(row))) {
                continue;
            }
            listed.add(row);
        }
        listed.sort((a, b) -> a.encounterAsset().compareToIgnoreCase(b.encounterAsset()));
        List<QuestAxisRow> out = new ArrayList<>(listed.size());
        for (EncounterBindingAsset row : listed) {
            String nameKey = row.getNameKey();
            out.add(QuestAxisRow.builder()
                    .value(row.encounterAsset())
                    .put(TOKEN_NAME_KEY, nameKey == null ? "" : nameKey)
                    .build());
        }
        return out;
    }

    @Nullable
    private static String difficultyOf(@Nonnull EncounterBindingAsset row) {
        EncounterBindingAsset.Progression progression = row.getProgression();
        return progression == null ? null : progression.getDifficulty();
    }
}
