package com.ziggfreed.common.objectives.store;

import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
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
 * <p><b>Where the component comes from.</b> A {@link ZigProgressComponent} the subject's handle
 * answers for DIRECTLY is used first, and a {@link ProgressHandle} is the fallback. The direct
 * answer is what lets a consumer supplying its own subject source keep these stores as THE store:
 * its handle declares {@link Subject.HandleFacets} and offers the component, and everything below
 * reads it without knowing whose handle it came out of. Two stores would be two versions of one
 * player's state, so the seam that avoids needing a second one is worth the extra lookup - which is
 * paid only when there is nothing to find, since a handle that answers with a component answers on
 * the first ask.
 *
 * <p>{@code markDirty} / {@code flush} write nothing themselves - the component's own codec persists
 * a live component at tick end - and instead FAN OUT to whatever registered through
 * {@link ProgressionDefaults#onProgressDirty} / {@link ProgressionDefaults#onProgressFlush}. That is
 * the seam a consumer holding a copy of this state somewhere else (a fleet database, a write-behind
 * cache) fills, again so that it never has to bring a second store.
 *
 * <p>Every OTHER default on the interface is inherited on purpose, {@code usesReservedDelimiter}
 * included: {@link ProgressBlob} reserves exactly the characters that default already rejects.
 */
public final class ZigQuestStore implements QuestProgressStore {

    /** The one instance, registered as the LIBRARY DEFAULT and outranked by any consumer's own. */
    public static final ZigQuestStore INSTANCE = new ZigQuestStore();

    private ZigQuestStore() {
    }

    @Nullable
    private static ZigProgressComponent componentOf(@Nonnull Subject subject) {
        ZigProgressComponent direct = subject.handleAs(ZigProgressComponent.class);
        if (direct != null) {
            return direct;
        }
        ProgressHandle handle = subject.handleAs(ProgressHandle.class);
        return handle == null ? null : handle.component();
    }

    private static void dropped(@Nonnull Subject subject, @Nonnull String what) {
        SafeLog.fine("[progression] dropped a quest " + what + " for '" + subject.name()
                + "': no progress component");
    }

    /** Fan the change out to every registered dirty listener. This store persists nothing itself. */
    @Override
    public void markDirty(@Nonnull Subject subject) {
        ProgressionDefaults.fireProgressDirty(subject);
    }

    /** Fan the transaction boundary out to every registered flush listener. */
    @Override
    public void flush(@Nonnull Subject subject) {
        ProgressionDefaults.fireProgressFlush(subject);
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
