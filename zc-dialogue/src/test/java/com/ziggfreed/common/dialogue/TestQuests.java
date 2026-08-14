package com.ziggfreed.common.dialogue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.dialogue.quest.DialogueQuests;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.quest.NpcOffer;
import com.ziggfreed.common.quest.NpcOfferProvider;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.QuestStateReader;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

/**
 * A quest runtime made of maps, plus a record of what was actually done to it.
 *
 * <p>ONE double for the whole package: the conversation vocabulary and the opening ladder ask the
 * same two seams (a reader and an answer set), and two doubles would drift into two different ideas
 * of what "ready to hand in here" means - which is precisely what a test of either would then fail
 * to catch.
 */
final class TestQuests implements DialogueQuests, QuestStateReader {

    final Map<String, QuestStatus> status = new LinkedHashMap<>();
    final Set<String> deliverableAt = new LinkedHashSet<>();
    final Map<String, Collection<String>> aliases = new LinkedHashMap<>();
    final List<String> accepted = new ArrayList<>();
    final List<String> acceptedAt = new ArrayList<>();
    final List<String> handedIn = new ArrayList<>();
    boolean acceptSucceeds = true;

    /** Ids this character is holding out right now, published through the shared offer table. */
    void offer(@Nonnull String... questIds) {
        List<NpcOffer> offers = new ArrayList<>();
        for (String id : questIds) {
            offers.add(NpcOffer.available(id, null));
        }
        NpcOfferProviders.register("test", "test", new NpcOfferProvider() {

            @Nonnull
            @Override
            public List<NpcOffer> offersAt(@Nonnull Subject subject, @Nonnull Collection<String> answersTo) {
                return offers;
            }
        });
    }

    /** An offer the player can SEE but not take yet, which must never open a conversation. */
    void offerLocked(@Nonnull String questId) {
        List<NpcOffer> offers = List.of(NpcOffer.locked(questId, null, List.of("gate.level")));
        NpcOfferProviders.register("test", "test", new NpcOfferProvider() {

            @Nonnull
            @Override
            public List<NpcOffer> offersAt(@Nonnull Subject subject, @Nonnull Collection<String> answersTo) {
                return offers;
            }
        });
    }

    @Nonnull
    @Override
    public QuestStateReader reader() {
        return this;
    }

    @Nonnull
    @Override
    public Subject subject(@Nonnull DialogueContext ctx) {
        return Subject.of(UUID.nameUUIDFromBytes("tester".getBytes()), "Tester");
    }

    @Nonnull
    @Override
    public Collection<String> answersTo(@Nullable String contextId) {
        if (contextId == null) {
            return List.of();
        }
        return aliases.getOrDefault(contextId, List.of(contextId));
    }

    @Override
    public boolean accept(@Nonnull Subject subject, @Nonnull String questId, @Nullable String siteId) {
        accepted.add(questId);
        acceptedAt.add(questId + "@" + siteId);
        return acceptSucceeds;
    }

    @Override
    public boolean turnIn(@Nonnull Subject subject, @Nonnull String questId, @Nullable String atId) {
        handedIn.add(questId + "@" + atId);
        return deliverableAt.contains(questId + "@" + atId);
    }

    @Nonnull
    @Override
    public QuestStatus status(@Nonnull Subject subject, @Nonnull String questId) {
        return status.getOrDefault(questId, QuestStatus.NOT_STARTED);
    }

    @Nullable
    @Override
    public ObjectiveProgressState objectiveProgress(@Nonnull Subject subject, @Nonnull String questId,
                                                    @Nonnull String objectiveId) {
        return null;
    }

    @Nonnull
    @Override
    public List<String> activeAndUnclaimedIds(@Nonnull Subject subject) {
        return List.copyOf(status.keySet());
    }

    @Override
    public boolean canDeliverTurnInAt(@Nonnull Subject subject, @Nonnull String questId,
                                      @Nullable String atId) {
        return deliverableAt.contains(questId + "@" + atId);
    }

    @Override
    public boolean hasDeliverableTurnInAt(@Nonnull Subject subject, @Nullable String atId) {
        return deliverableAt.stream().anyMatch(entry -> entry.endsWith("@" + atId));
    }
}
