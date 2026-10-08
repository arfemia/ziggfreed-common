package com.ziggfreed.common.objectives.marker;

import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.entity.overhead.IndicatorAudience;
import com.ziggfreed.common.entity.overhead.OverheadIndicators;
import com.ziggfreed.common.npc.NpcIdentities;
import com.ziggfreed.common.objectives.indicator.QuestIndicators;
import com.ziggfreed.common.objectives.indicator.QuestMarkYield;
import com.ziggfreed.common.objectives.questlist.NpcQuestPages;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * The mark over a placed character's head, per viewer: a scroll for a quest they could take, a gold
 * bar for an errand they can hand in, coins for a reward waiting, as the looks say. The answer is
 * {@link QuestIndicators}' over the same alias set the NPC quest page asks
 * ({@link NpcQuestPages#resolvedDeps}), so the mark and the page always agree.
 *
 * <p>Stands down whole while a consumer draws its own marks ({@link QuestMarkYield}): it shows
 * nothing and hides nothing, since that consumer's sweep owns those entries now.
 */
final class QuestOverheads implements QuestMarkerListener {

    static final QuestOverheads INSTANCE = new QuestOverheads();

    private QuestOverheads() {
    }

    @Override
    public void evaluate(@Nonnull QuestMarkerScope scope) {
        if (QuestMarkYield.consumerDraws()) {
            return;
        }
        QuestEngine engine = ProgressionRuntime.quests();
        IndicatorAudience viewer = IndicatorAudience.of(scope.viewerId());
        for (Ref<EntityStore> host : scope.hosts()) {
            if (!host.isValid()) {
                continue;
            }
            String state = stateAt(scope.store(), engine, scope.subject(), host);
            if (state == null) {
                OverheadIndicators.hide(host, viewer);
            } else {
                OverheadIndicators.show(scope.accessor(), host, state, viewer);
            }
        }
    }

    @Override
    public void forget(@Nonnull UUID viewerId) {
        OverheadIndicators.forgetViewer(viewerId);
    }

    @Nullable
    private static String stateAt(@Nonnull Store<EntityStore> store, @Nonnull QuestEngine engine,
            @Nonnull Subject subject, @Nonnull Ref<EntityStore> host) {
        String npcId = NpcIdentities.npcIdOfEntity(store, host);
        return npcId == null ? null : stateFor(engine, subject, NpcQuestPages.resolvedDeps().answerSetOrOwn(npcId));
    }

    /** The state the character answering to {@code answersTo} floats for {@code subject}, or null for none. */
    @Nullable
    static String stateFor(@Nonnull QuestEngine engine, @Nonnull Subject subject, @Nonnull Set<String> answersTo) {
        QuestIndicators.Reading reading = QuestIndicators.overheadFor(engine, subject, answersTo);
        return reading == null ? null : reading.knob().state();
    }
}
