package com.ziggfreed.common.quest;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.subject.Subject;

/**
 * A complete {@link QuestProgressStore} that keeps everything in memory, keyed by
 * {@link Subject#id()}.
 *
 * <p>Two real uses: unit tests, and any consumer whose quest state is genuinely meant to die with
 * the session (a round, a match, an instance). A consumer that needs the state to survive a
 * disconnect writes its own store against the same interface instead.
 *
 * <p>Backed by {@link ConcurrentHashMap} so a dispatch and a read from another thread cannot corrupt
 * each other, though the engine's own operations still expect to be serialized by the consumer.
 */
public final class InMemoryQuestProgressStore implements QuestProgressStore {

    private static final class PlayerState {
        private final Map<String, QuestStatus> statuses = new ConcurrentHashMap<>();
        private final Map<String, String> payloads = new ConcurrentHashMap<>();
        private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();
        private final Map<String, Long> pins = new ConcurrentHashMap<>();
    }

    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();

    @Nonnull
    private PlayerState state(@Nonnull Subject subject) {
        return players.computeIfAbsent(subject.id(), key -> new PlayerState());
    }

    @Override
    @Nonnull
    public QuestStatus status(@Nonnull Subject subject, @Nonnull String questId) {
        QuestStatus status = state(subject).statuses.get(questId);
        return status != null ? status : QuestStatus.NOT_STARTED;
    }

    @Override
    public void setStatus(@Nonnull Subject subject, @Nonnull String questId, @Nonnull QuestStatus status) {
        state(subject).statuses.put(questId, status);
    }

    @Override
    @Nullable
    public String progressPayload(@Nonnull Subject subject, @Nonnull String questId) {
        return state(subject).payloads.get(questId);
    }

    @Override
    public void putProgressPayload(@Nonnull Subject subject, @Nonnull String questId, @Nonnull String payload) {
        state(subject).payloads.put(questId, payload);
    }

    @Override
    public long cooldownStamp(@Nonnull Subject subject, @Nonnull String questId) {
        Long stamp = state(subject).cooldowns.get(questId);
        return stamp != null ? stamp : 0L;
    }

    @Override
    public void setCooldownStamp(@Nonnull Subject subject, @Nonnull String questId, long epochMs) {
        state(subject).cooldowns.put(questId, epochMs);
    }

    @Override
    @Nonnull
    public Set<String> knownQuestIds(@Nonnull Subject subject) {
        PlayerState player = state(subject);
        Set<String> ids = new LinkedHashSet<>(player.statuses.keySet());
        ids.addAll(player.payloads.keySet());
        ids.addAll(player.cooldowns.keySet());
        return ids;
    }

    @Override
    public void clearQuest(@Nonnull Subject subject, @Nonnull String questId) {
        PlayerState player = state(subject);
        player.statuses.remove(questId);
        player.payloads.remove(questId);
        player.cooldowns.remove(questId);
        player.pins.remove(questId);
    }

    @Override
    @Nonnull
    public Map<String, Long> trackedPins(@Nonnull Subject subject) {
        return new HashMap<>(state(subject).pins);
    }

    @Override
    public void setTrackedPin(@Nonnull Subject subject, @Nonnull String questId, long pinnedAtMs) {
        state(subject).pins.put(questId, pinnedAtMs);
    }

    @Override
    public boolean clearTrackedPin(@Nonnull Subject subject, @Nonnull String questId) {
        return state(subject).pins.remove(questId) != null;
    }

    /** Forget one player entirely (they left, the round ended). */
    public void forget(@Nonnull Subject subject) {
        players.remove(subject.id());
    }

    /** Forget everybody. */
    public void clear() {
        players.clear();
    }
}
