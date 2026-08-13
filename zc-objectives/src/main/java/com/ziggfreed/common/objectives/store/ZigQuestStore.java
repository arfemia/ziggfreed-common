package com.ziggfreed.common.objectives.store;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.quest.QuestProgressStore;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The library's DEFAULT quest persistence: resolve the subject's
 * {@link ZigProgressComponent} and delegate.
 *
 * <p><b>A read never creates.</b> A subject with no handle or no component reads neutral -
 * {@code NOT_STARTED}, no payload, no cooldown, no pins - and a WRITE is dropped with one fine-level
 * line. The component is created once, at connect, by the library's own player hook; a store that created one
 * on demand would stamp a component onto anything that so much as asked a question.
 *
 * <p>{@code markDirty} / {@code flush} stay the interface no-ops: the component's own codec persists
 * a live component at tick end, so there is no transaction boundary to report.
 *
 * <p>Every default on the interface is inherited on purpose, {@code usesReservedDelimiter} included:
 * {@link ProgressBlob} reserves exactly the characters that default already rejects.
 */
public final class ZigQuestStore implements QuestProgressStore {

    /** The one instance, registered as the LIBRARY DEFAULT and outranked by any consumer's own. */
    public static final ZigQuestStore INSTANCE = new ZigQuestStore();

    private ZigQuestStore() {
    }

    @Nullable
    private static ZigProgressComponent componentOf(@Nonnull Subject subject) {
        ProgressHandle handle = subject.handleAs(ProgressHandle.class);
        return handle == null ? null : handle.component();
    }

    private static void dropped(@Nonnull Subject subject, @Nonnull String what) {
        SafeLog.fine("[progression] dropped a quest " + what + " for '" + subject.name()
                + "': no progress component");
    }

    @Nonnull
    @Override
    public QuestStatus status(@Nonnull Subject subject, @Nonnull String questId) {
        ZigProgressComponent component = componentOf(subject);
        return component == null ? QuestStatus.NOT_STARTED : component.questStatus(questId);
    }

    @Override
    public void setStatus(@Nonnull Subject subject, @Nonnull String questId,
            @Nonnull QuestStatus status) {
        ZigProgressComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "status");
            return;
        }
        component.setQuestStatus(questId, status);
    }

    @Nullable
    @Override
    public String progressPayload(@Nonnull Subject subject, @Nonnull String questId) {
        ZigProgressComponent component = componentOf(subject);
        return component == null ? null : component.questPayload(questId);
    }

    @Override
    public void putProgressPayload(@Nonnull Subject subject, @Nonnull String questId,
            @Nonnull String payload) {
        ZigProgressComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "progress payload");
            return;
        }
        component.putQuestPayload(questId, payload);
    }

    @Override
    public long cooldownStamp(@Nonnull Subject subject, @Nonnull String questId) {
        ZigProgressComponent component = componentOf(subject);
        return component == null ? 0L : component.questCooldown(questId);
    }

    @Override
    public void setCooldownStamp(@Nonnull Subject subject, @Nonnull String questId, long epochMs) {
        ZigProgressComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "cooldown stamp");
            return;
        }
        component.setQuestCooldown(questId, epochMs);
    }

    @Nonnull
    @Override
    public CompletionRecord completions(@Nonnull Subject subject, @Nonnull String questId) {
        ZigProgressComponent component = componentOf(subject);
        return component == null ? CompletionRecord.NONE : component.questCompletions(questId);
    }

    @Override
    public void setCompletions(@Nonnull Subject subject, @Nonnull String questId,
            @Nonnull CompletionRecord record) {
        ZigProgressComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "completion record");
            return;
        }
        component.setQuestCompletions(questId, record);
    }

    @Override
    public boolean recordsCompletions() {
        return true;
    }

    @Nonnull
    @Override
    public Set<String> knownQuestIds(@Nonnull Subject subject) {
        ZigProgressComponent component = componentOf(subject);
        return component == null ? Set.of() : component.knownQuestIds();
    }

    @Override
    public void clearQuest(@Nonnull Subject subject, @Nonnull String questId) {
        ZigProgressComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "clear");
            return;
        }
        component.clearQuest(questId);
    }

    @Nonnull
    @Override
    public Map<String, Long> trackedPins(@Nonnull Subject subject) {
        ZigProgressComponent component = componentOf(subject);
        return component == null ? Map.of() : component.trackedPins();
    }

    @Override
    public void setTrackedPin(@Nonnull Subject subject, @Nonnull String questId, long pinnedAtMs) {
        ZigProgressComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "pin");
            return;
        }
        component.setTrackedPin(questId, pinnedAtMs);
    }

    @Override
    public boolean clearTrackedPin(@Nonnull Subject subject, @Nonnull String questId) {
        ZigProgressComponent component = componentOf(subject);
        if (component == null) {
            dropped(subject, "unpin");
            return false;
        }
        return component.clearTrackedPin(questId);
    }
}
