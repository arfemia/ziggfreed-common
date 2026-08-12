package com.ziggfreed.common.quest.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;

/**
 * Every quest a consumer should run, folded and ready: the authored files plus whatever the
 * generators produced, with inheritance already resolved.
 *
 * <p>The usual shape of a load is one line:
 * <pre>{@code
 * QuestPool pool = QuestAssetStore.getInstance().resolveAll("yourmod", myEnumerators, warn);
 * engine.setQuests(pool.quests());
 * }</pre>
 * and then the consumer's UI reads {@link #definition} for the text and gates while the engine runs
 * the quests. Rebuild the pool on every content reload; it is an immutable snapshot.
 */
public final class QuestPool {

    /** An empty pool, for a consumer whose content has not loaded yet. */
    public static final QuestPool EMPTY = new QuestPool(Map.of());

    private final Map<String, QuestDefinition> definitions;

    /** Wrap an already-folded {@code id -> definition} map (ids are lower-cased). */
    public QuestPool(@Nonnull Map<String, QuestDefinition> definitions) {
        Map<String, QuestDefinition> copy = new LinkedHashMap<>();
        for (Map.Entry<String, QuestDefinition> e : definitions.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                copy.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        this.definitions = Collections.unmodifiableMap(copy);
    }

    /** Every folded quest, keyed by id, in resolution order. */
    @Nonnull
    public Map<String, QuestDefinition> definitions() {
        return definitions;
    }

    /** The folded quest under {@code questId} (case-insensitive), or null. */
    @Nullable
    public QuestDefinition definition(@Nullable String questId) {
        return questId == null ? null : definitions.get(questId.trim().toLowerCase(Locale.ROOT));
    }

    /** The engine-side definitions, ready for {@link QuestEngine#setQuests}. */
    @Nonnull
    public Collection<Quest> quests() {
        List<Quest> out = new ArrayList<>(definitions.size());
        for (QuestDefinition definition : definitions.values()) {
            out.add(definition.quest());
        }
        return out;
    }

    /** Every quest id, in resolution order. */
    @Nonnull
    public List<String> ids() {
        return List.copyOf(definitions.keySet());
    }

    /** The ids a generator produced, in resolution order. */
    @Nonnull
    public List<String> generatedIds() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, QuestDefinition> e : definitions.entrySet()) {
            if (e.getValue().isGenerated()) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /** How many quests this pool carries. */
    public int size() {
        return definitions.size();
    }

    /**
     * Wipe the progress of every quest {@code questId} declares in its {@code Repeat
     * .ResetsOnComplete}, so a chain can come round again. Call it when that quest completes.
     *
     * <p>It clears status, progress, cooldown stamp and tracker pin through the engine's own store,
     * which is the same thing abandoning a quest does; a quest id nothing knows about is skipped.
     *
     * @return how many quests were reset
     */
    public int applyCompletionResets(@Nonnull QuestEngine engine, @Nonnull Subject subject,
            @Nullable String questId) {
        QuestDefinition definition = definition(questId);
        if (definition == null) {
            return 0;
        }
        int reset = 0;
        for (String other : definition.resetsOnComplete()) {
            if (definition(other) == null && engine.quest(other) == null) {
                continue;
            }
            engine.store().clearQuest(subject, other);
            reset++;
        }
        return reset;
    }
}
