package com.ziggfreed.common.objectives.store;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.AchievementProgressStore;
import com.ziggfreed.common.achievement.AchievementStatus;
import com.ziggfreed.common.quest.QuestProgressStore.CompletionRecord;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.util.SafeLog;

/**
 * The persisted per-player state behind the library's DEFAULT progression stores: one component
 * holding everything both shared engines know about a player, plus everything the shared dialogue
 * engine remembers about them for good.
 *
 * <p><b>Why a conversation's memory lives here.</b> It is the library's one persistent per-player
 * record, and a dialogue memory declared without {@code Session} means "survives a restart", so it
 * needs one. The dialogue module cannot hold it - this module depends on THAT one, and the edge
 * runs one way - so the state lives here and {@code DialogueMemories} reaches it through the seam
 * the wiring root fills with {@link ZigProgressDialogueStore}. That is also why the component is
 * attached to every player rather than only where these progression stores are the active ones: a
 * conversation remembers things on a server whose quests belong to somebody else.
 *
 * <p><b>Twelve packed string leaves, not twelve collections.</b> Each one travels as a single
 * string through {@link ProgressBlob}, which is the shape a codec-persisted ECS component reliably
 * supports; the component is the thing that is saved into every world, so the wire format is a
 * contract and a plain string is the least surprising one to keep.
 *
 * <p><b>The maps live here; the two store adapters are thin.</b>
 * {@link com.ziggfreed.common.quest.QuestProgressStore} and {@link AchievementProgressStore}
 * declare methods with the same erasure and different return types, so one class cannot implement
 * both. Two adapters resolve the component and delegate to the methods below, which keeps the real
 * logic in one place and unit-testable with no server anywhere near it.
 *
 * <p><b>Registration.</b> A library component has no plugin of its own, so
 * {@code ZiggfreedCommonPlugin} registers it once at {@code setup()} via
 * {@link #register(ComponentRegistryProxy)}, whether or not these stores end up being the active
 * ones: a component type registered after a world has loaded cannot be read off entities saved
 * carrying it, so it cannot wait to find out. Registering an unused type costs nothing - no entity
 * carries one unless the player hook attaches it. Every read and write site guards on
 * {@code TYPE != null}.
 */
public final class ZigProgressComponent implements Component<EntityStore> {

    /** The registration id (namespaced, stable - it is persisted in every saved world). */
    public static final String REGISTRY_ID = "ZiggfreedCommon:Progress";

    /** The registered type, or {@code null} until {@link #register} runs. */
    @Nullable
    public static ComponentType<EntityStore, ZigProgressComponent> TYPE;

    @Nonnull
    public static final BuilderCodec<ZigProgressComponent> CODEC = BuilderCodec
            .builder(ZigProgressComponent.class, ZigProgressComponent::new)
            .append(new KeyedCodec<>("QuestStates", Codec.STRING),
                    (c, v) -> c.questStates = ProgressBlob.deserializeStrings(v),
                    c -> ProgressBlob.serializeStrings(ProgressBlob.ordered(c.questStates))).add()
            .append(new KeyedCodec<>("QuestProgress", Codec.STRING),
                    (c, v) -> c.questProgress = ProgressBlob.deserializeBase64Values(v),
                    c -> ProgressBlob.serializeBase64Values(ProgressBlob.ordered(c.questProgress))).add()
            .append(new KeyedCodec<>("QuestCooldowns", Codec.STRING),
                    (c, v) -> c.questCooldowns = ProgressBlob.deserializeLongs(v),
                    c -> ProgressBlob.serializeLongs(ProgressBlob.ordered(c.questCooldowns))).add()
            .append(new KeyedCodec<>("TrackedQuests", Codec.STRING),
                    (c, v) -> c.trackedQuests = ProgressBlob.deserializeLongs(v),
                    c -> ProgressBlob.serializeLongs(ProgressBlob.ordered(c.trackedQuests))).add()
            .append(new KeyedCodec<>("AchievementProgress", Codec.STRING),
                    (c, v) -> c.achievementProgress = ProgressBlob.deserializeLongs(v),
                    c -> ProgressBlob.serializeLongs(ProgressBlob.ordered(c.achievementProgress))).add()
            .append(new KeyedCodec<>("AchievementStates", Codec.STRING),
                    (c, v) -> c.achievementStates = ProgressBlob.deserializeStrings(v),
                    c -> ProgressBlob.serializeStrings(ProgressBlob.ordered(c.achievementStates))).add()
            .append(new KeyedCodec<>("AchievementUnlockedAt", Codec.STRING),
                    (c, v) -> c.achievementUnlockedAt = ProgressBlob.deserializeLongs(v),
                    c -> ProgressBlob.serializeLongs(ProgressBlob.ordered(c.achievementUnlockedAt))).add()
            .append(new KeyedCodec<>("MilestoneStates", Codec.STRING),
                    (c, v) -> c.milestoneStates = ProgressBlob.deserializeStrings(v),
                    c -> ProgressBlob.serializeStrings(ProgressBlob.ordered(c.milestoneStates))).add()
            .append(new KeyedCodec<>("AchievementPins", Codec.STRING),
                    (c, v) -> c.achievementPins = ProgressBlob.deserializeLongs(v),
                    c -> ProgressBlob.serializeLongs(ProgressBlob.ordered(c.achievementPins))).add()
            // APPENDED, never inserted: a blob saved before this leaf existed simply has no value
            // for it and decodes to an empty map, which reads as "this player has finished nothing"
            // everywhere. That is why every new leaf goes on the end.
            .append(new KeyedCodec<>("QuestCompletions", Codec.STRING),
                    (c, v) -> c.questCompletions = decodeCompletions(v),
                    c -> ProgressBlob.serializeStrings(
                            ProgressBlob.ordered(encodeCompletions(c.questCompletions)))).add()
            // The two SET leaves, appended on the end like every other new one. Each entry travels
            // base64-encoded because neither holds ids: a dialogue state key is composed from
            // content ids and may legitimately carry the characters the pair format reserves.
            .append(new KeyedCodec<>("DialogueMemories", Codec.STRING),
                    (c, v) -> c.dialogueMemories = ProgressBlob.deserializeSet(v),
                    c -> ProgressBlob.serializeSet(ProgressBlob.ordered(c.dialogueMemories))).add()
            .append(new KeyedCodec<>("Migrations", Codec.STRING),
                    (c, v) -> c.migrations = ProgressBlob.deserializeSet(v),
                    c -> ProgressBlob.serializeSet(ProgressBlob.ordered(c.migrations))).add()
            .build();

    /** questId -> {@link QuestStatus#name()}. */
    @Nonnull
    private Map<String, String> questStates = new ConcurrentHashMap<>();

    /** questId -> the engine's opaque packed payload. */
    @Nonnull
    private Map<String, String> questProgress = new ConcurrentHashMap<>();

    /** questId -> the cooldown stamp in epoch milliseconds. */
    @Nonnull
    private Map<String, Long> questCooldowns = new ConcurrentHashMap<>();

    /** questId -> the instant it was pinned, in epoch milliseconds. */
    @Nonnull
    private Map<String, Long> trackedQuests = new ConcurrentHashMap<>();

    /** questId -> how often the player has finished it, and when they last did. */
    @Nonnull
    private Map<String, CompletionRecord> questCompletions = new ConcurrentHashMap<>();

    /** {@code "<achievementId>#<criterionIndex>"} -> the tally. */
    @Nonnull
    private Map<String, Long> achievementProgress = new ConcurrentHashMap<>();

    /** achievementId -> {@link AchievementStatus#name()}. */
    @Nonnull
    private Map<String, String> achievementStates = new ConcurrentHashMap<>();

    /** achievementId -> when it was earned, in epoch milliseconds. */
    @Nonnull
    private Map<String, Long> achievementUnlockedAt = new ConcurrentHashMap<>();

    /** The points threshold as a decimal string -> {@link AchievementStatus#name()}. */
    @Nonnull
    private Map<String, String> milestoneStates = new ConcurrentHashMap<>();

    /** achievementId -> the instant it was pinned, in epoch milliseconds. */
    @Nonnull
    private Map<String, Long> achievementPins = new ConcurrentHashMap<>();

    /**
     * The dialogue engine's own opaque state keys - a spent {@code Once}, a remembered
     * {@code Memories} entry, a claimed one-shot gift - for every memory whose author did NOT
     * declare it {@code Session}. Opaque here on purpose: this component keeps them, and
     * {@code DialogueMemories} is what knows what any of them mean.
     */
    @Nonnull
    private Set<String> dialogueMemories = ConcurrentHashMap.newKeySet();

    /** One-time moves already performed for this player, by their own namespaced ids. */
    @Nonnull
    private Set<String> migrations = ConcurrentHashMap.newKeySet();

    public ZigProgressComponent() {
    }

    /**
     * Register this component type on {@code registry}. Call ONCE at plugin {@code setup()}.
     * Never throws: a failure logs and leaves {@link #TYPE} unset.
     *
     * @return the registered type, or {@code null} on failure
     */
    @Nullable
    public static ComponentType<EntityStore, ZigProgressComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(ZigProgressComponent.class, REGISTRY_ID, CODEC);
            return TYPE;
        } catch (Throwable t) {
            SafeLog.warn("[progression] ZigProgressComponent register failed", t);
            return null;
        }
    }

    /** The registered type, or {@code null} when not yet registered. */
    @Nullable
    public static ComponentType<EntityStore, ZigProgressComponent> getComponentType() {
        return TYPE;
    }

    // ==================== quest state ====================

    /** The recorded status, or {@link QuestStatus#NOT_STARTED} when there is none. */
    @Nonnull
    public QuestStatus questStatus(@Nonnull String questId) {
        return QuestStatus.fromString(questStates.get(questId));
    }

    /** Record a status. The default status is stored as absence, so a reset leaves nothing behind. */
    public void setQuestStatus(@Nonnull String questId, @Nonnull QuestStatus status) {
        if (status == QuestStatus.NOT_STARTED) {
            questStates.remove(questId);
            return;
        }
        questStates.put(questId, status.name());
    }

    /** The packed progress payload, or null when there is none. */
    @Nullable
    public String questPayload(@Nonnull String questId) {
        return questProgress.get(questId);
    }

    /** Store a packed progress payload verbatim. */
    public void putQuestPayload(@Nonnull String questId, @Nonnull String payload) {
        questProgress.put(questId, payload);
    }

    /** The cooldown stamp in epoch milliseconds, or {@code 0}. */
    public long questCooldown(@Nonnull String questId) {
        Long stamp = questCooldowns.get(questId);
        return stamp == null ? 0L : stamp;
    }

    /** Record a cooldown stamp. A non-positive stamp is stored as absence. */
    public void setQuestCooldown(@Nonnull String questId, long epochMs) {
        if (epochMs <= 0L) {
            questCooldowns.remove(questId);
            return;
        }
        questCooldowns.put(questId, Long.valueOf(epochMs));
    }

    /** This player's completions of a quest, or {@link CompletionRecord#NONE}. */
    @Nonnull
    public CompletionRecord questCompletions(@Nonnull String questId) {
        CompletionRecord record = questCompletions.get(questId);
        return record == null ? CompletionRecord.NONE : record;
    }

    /** Record them. An empty record is stored as absence, so a wipe leaves nothing behind. */
    public void setQuestCompletions(@Nonnull String questId, @Nonnull CompletionRecord record) {
        if (record.isEmpty()) {
            questCompletions.remove(questId);
            return;
        }
        questCompletions.put(questId, record);
    }

    /** Every quest id with ANY recorded state. */
    @Nonnull
    public Set<String> knownQuestIds() {
        Set<String> ids = new HashSet<>(questStates.keySet());
        ids.addAll(questProgress.keySet());
        ids.addAll(questCooldowns.keySet());
        ids.addAll(trackedQuests.keySet());
        ids.addAll(questCompletions.keySet());
        return ids;
    }

    /**
     * Re-arm a quest - status, progress, cooldown stamp, and pin all go.
     *
     * <p>The completion record deliberately SURVIVES: this runs when a quest is abandoned and when a
     * repeatable comes back around, and a lifetime cap that either of those wiped would be a cap
     * nobody could ever reach. {@link #setQuestCompletions} with an empty record is the wipe.
     */
    public void clearQuest(@Nonnull String questId) {
        questStates.remove(questId);
        questProgress.remove(questId);
        questCooldowns.remove(questId);
        trackedQuests.remove(questId);
    }

    /** The pins as {@code questId -> the instant it was pinned}. */
    @Nonnull
    public Map<String, Long> trackedPins() {
        return Map.copyOf(trackedQuests);
    }

    /** Pin a quest at {@code pinnedAtMs}. */
    public void setTrackedPin(@Nonnull String questId, long pinnedAtMs) {
        trackedQuests.put(questId, Long.valueOf(pinnedAtMs));
    }

    /** Drop a pin. Returns true when one was actually there. */
    public boolean clearTrackedPin(@Nonnull String questId) {
        return trackedQuests.remove(questId) != null;
    }

    // ==================== achievement state ====================

    /** The raw stored tally under {@code key}, or {@code 0}. */
    public long achievementProgress(@Nonnull String key) {
        Long value = achievementProgress.get(key);
        return value == null ? 0L : value;
    }

    /** Store a raw tally. A value of {@code 0} REMOVES the key, per the store contract. */
    public void putAchievementProgress(@Nonnull String key, long value) {
        if (value == 0L) {
            achievementProgress.remove(key);
            return;
        }
        achievementProgress.put(key, Long.valueOf(value));
    }

    /** Every raw progress key held. */
    @Nonnull
    public Set<String> achievementProgressKeys() {
        return Set.copyOf(achievementProgress.keySet());
    }

    /** The recorded status, or {@link AchievementStatus#LOCKED} when there is none. */
    @Nonnull
    public AchievementStatus achievementStatus(@Nonnull String achievementId) {
        String recorded = achievementStates.get(achievementId);
        if (recorded == null) {
            return AchievementStatus.LOCKED;
        }
        try {
            return AchievementStatus.valueOf(recorded);
        } catch (IllegalArgumentException unknown) {
            return AchievementStatus.LOCKED;
        }
    }

    /** Record a status. The default status is stored as absence. */
    public void setAchievementStatus(@Nonnull String achievementId, @Nonnull AchievementStatus status) {
        if (status == AchievementStatus.LOCKED) {
            achievementStates.remove(achievementId);
            return;
        }
        achievementStates.put(achievementId, status.name());
    }

    /** Every achievement id with ANY recorded state. */
    @Nonnull
    public Set<String> knownAchievementIds() {
        Set<String> ids = new HashSet<>(achievementStates.keySet());
        ids.addAll(achievementUnlockedAt.keySet());
        ids.addAll(achievementPins.keySet());
        for (String key : achievementProgress.keySet()) {
            int split = key.indexOf(AchievementProgressStore.CRITERION_SEPARATOR);
            ids.add(split > 0 ? key.substring(0, split) : key);
        }
        return ids;
    }

    /** When it was earned, in epoch milliseconds, or {@code 0}. */
    public long achievementUnlockedAt(@Nonnull String achievementId) {
        Long stamp = achievementUnlockedAt.get(achievementId);
        return stamp == null ? 0L : stamp;
    }

    /** Record when it was earned. A non-positive stamp is stored as absence. */
    public void setAchievementUnlockedAt(@Nonnull String achievementId, long epochMs) {
        if (epochMs <= 0L) {
            achievementUnlockedAt.remove(achievementId);
            return;
        }
        achievementUnlockedAt.put(achievementId, Long.valueOf(epochMs));
    }

    /** The recorded status of a points milestone, or {@link AchievementStatus#LOCKED}. */
    @Nonnull
    public AchievementStatus milestoneStatus(int threshold) {
        String recorded = milestoneStates.get(Integer.toString(threshold));
        if (recorded == null) {
            return AchievementStatus.LOCKED;
        }
        try {
            return AchievementStatus.valueOf(recorded);
        } catch (IllegalArgumentException unknown) {
            return AchievementStatus.LOCKED;
        }
    }

    /** Record a milestone's status. */
    public void setMilestoneStatus(int threshold, @Nonnull AchievementStatus status) {
        String key = Integer.toString(threshold);
        if (status == AchievementStatus.LOCKED) {
            milestoneStates.remove(key);
            return;
        }
        milestoneStates.put(key, status.name());
    }

    /** Every milestone threshold with a recorded status. */
    @Nonnull
    public Set<Integer> knownMilestones() {
        Set<Integer> thresholds = new HashSet<>();
        for (String key : milestoneStates.keySet()) {
            try {
                thresholds.add(Integer.valueOf(Integer.parseInt(key)));
            } catch (NumberFormatException ignored) {
                // A threshold that is not a number is not a threshold; skip it silently.
            }
        }
        return thresholds;
    }

    /** The pins as {@code achievementId -> the instant it was pinned}. */
    @Nonnull
    public Map<String, Long> achievementPins() {
        return Map.copyOf(achievementPins);
    }

    /** Pin an achievement at {@code pinnedAtMs}. */
    public void setAchievementPin(@Nonnull String achievementId, long pinnedAtMs) {
        achievementPins.put(achievementId, Long.valueOf(pinnedAtMs));
    }

    /** Drop a pin. Returns true when one was actually there. */
    public boolean clearAchievementPin(@Nonnull String achievementId) {
        return achievementPins.remove(achievementId) != null;
    }

    // ==================== dialogue state ====================

    /** True when this player currently holds the dialogue state key {@code flag}. */
    public boolean hasDialogueMemory(@Nonnull String flag) {
        return dialogueMemories.contains(flag);
    }

    /** Record one. Idempotent, since a set is what a spent {@code Once} needs. */
    public void setDialogueMemory(@Nonnull String flag) {
        if (!flag.isEmpty()) {
            dialogueMemories.add(flag);
        }
    }

    /** Drop one. Returns true when it was actually held. */
    public boolean clearDialogueMemory(@Nonnull String flag) {
        return dialogueMemories.remove(flag);
    }

    /** Drop every key filed under {@code prefix} - what a quest reset forgetting its memories is. */
    public void clearDialogueMemoriesWithPrefix(@Nonnull String prefix) {
        dialogueMemories.removeIf(flag -> flag.startsWith(prefix));
    }

    /** Drop the lot (an administrator's start-over). */
    public void clearDialogueMemories() {
        dialogueMemories.clear();
    }

    /** How many keys are held, for a diagnostic and for a test. */
    public int dialogueMemoryCount() {
        return dialogueMemories.size();
    }

    // ==================== one-time migrations ====================

    /**
     * Claim {@code migrationId} for this player: true the first time, false ever after, with the
     * claim recorded before the caller acts. The claim lives beside the state it guards, so a
     * component that was never attached cannot answer true and hand out a move with nowhere to
     * write it.
     */
    public synchronized boolean claimMigration(@Nonnull String migrationId) {
        return migrations.add(migrationId);
    }

    /** True when {@code migrationId} has already been claimed for this player. */
    public boolean hasMigrated(@Nonnull String migrationId) {
        return migrations.contains(migrationId);
    }

    @Override
    @SuppressWarnings("CloneDeclaresCloneNotSupported")
    public ZigProgressComponent clone() {
        ZigProgressComponent c = new ZigProgressComponent();
        c.questStates = ProgressBlob.copy(this.questStates);
        c.questProgress = ProgressBlob.copy(this.questProgress);
        c.questCooldowns = ProgressBlob.copy(this.questCooldowns);
        c.trackedQuests = ProgressBlob.copy(this.trackedQuests);
        c.achievementProgress = ProgressBlob.copy(this.achievementProgress);
        c.achievementStates = ProgressBlob.copy(this.achievementStates);
        c.achievementUnlockedAt = ProgressBlob.copy(this.achievementUnlockedAt);
        c.milestoneStates = ProgressBlob.copy(this.milestoneStates);
        c.achievementPins = ProgressBlob.copy(this.achievementPins);
        c.questCompletions = ProgressBlob.copy(this.questCompletions);
        c.dialogueMemories = ProgressBlob.copySet(this.dialogueMemories);
        c.migrations = ProgressBlob.copySet(this.migrations);
        return c;
    }

    // ==================== completion packing ====================

    /**
     * One record travels as {@code last,period,total} inside the same {@code key=value|key=value}
     * frame every other leaf uses. A comma collides with neither reserved character, so the wire
     * contract and both adapters' inherited id hygiene are untouched.
     */
    private static final char FIELD_SEPARATOR = ',';

    /** Package-visible so the packing can be exercised without an asset registry anywhere near it. */
    @Nonnull
    static Map<String, CompletionRecord> decodeCompletions(@Nullable String blob) {
        Map<String, CompletionRecord> out = new ConcurrentHashMap<>();
        for (Map.Entry<String, String> entry : ProgressBlob.deserializeStrings(blob).entrySet()) {
            CompletionRecord record = parseRecord(entry.getValue());
            if (record != null) {
                out.put(entry.getKey(), record);
            }
        }
        return out;
    }

    @Nonnull
    static Map<String, String> encodeCompletions(
            @Nonnull Map<String, CompletionRecord> records) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, CompletionRecord> entry : records.entrySet()) {
            CompletionRecord record = entry.getValue();
            out.put(entry.getKey(), record.lastCompletionMs() + "" + FIELD_SEPARATOR
                    + record.periodCount() + FIELD_SEPARATOR + record.totalCount());
        }
        return out;
    }

    /** A malformed triple costs that entry alone, never the login. */
    @Nullable
    private static CompletionRecord parseRecord(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String[] fields = value.split(String.valueOf(FIELD_SEPARATOR), -1);
        if (fields.length != 3) {
            return null;
        }
        try {
            return new CompletionRecord(Long.parseLong(fields[0].trim()),
                    Integer.parseInt(fields[1].trim()), Integer.parseInt(fields[2].trim()));
        } catch (NumberFormatException malformed) {
            return null;
        }
    }
}
