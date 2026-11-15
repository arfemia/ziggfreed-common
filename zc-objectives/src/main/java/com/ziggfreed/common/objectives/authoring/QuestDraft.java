package com.ziggfreed.common.objectives.authoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.quest.asset.QuestObjectiveAsset;

/**
 * The quest file the editor writes, and the one it reads back: an ordinary {@code Server/ZiggfreedCommon/Quests}
 * document in the shape {@link QuestAsset} decodes, written over the file it edits so every leaf the editor has no
 * row for (an {@code Indicator}, a step's {@code Drop}, a {@code Meta} namespace, a fourth Claim row) stays exactly as
 * it was. Pure.
 *
 * <p><b>Keys never move, and never come back.</b> The steps are {@link KeyedRows} ({@link #STEPS}): a step's key is
 * minted once ({@link #stepKey}) and written under on every save after; a step whose kind changes takes a new key in
 * the old one's place, keeping its {@code Order}, {@code Drop} and {@code Indicator}; every key taken out is retired in
 * the file's own {@code Meta} ({@link #RETIRED_STEPS} under {@link #META_NAMESPACE}:
 * {@code "Meta": {"ziggfreedcommon": {"RetiredSteps": ["kill_skeleton"]}}}), and {@link #taken} hands it to every mint.
 *
 * <p>Every reward row the editor writes is a {@code Claim} ({@link ClaimSlots}), which the player collects: it writes
 * no {@code Auto} row, and leaves one the file already holds as written.
 */
public final class QuestDraft {

    /** The library's namespace in a quest's {@code Meta}. */
    public static final String META_NAMESPACE = KeyedRows.META_NAMESPACE;

    /** The {@code Meta} leaf listing every step key the editor took out of the file, which none is minted again. */
    public static final String RETIRED_STEPS = "RetiredSteps";

    /** {@code Repeat.cooldownHours} that leaves the file's {@code Cooldown} as written (not whole hours). */
    public static final int KEEP_COOLDOWN = -1;

    /**
     * The quest's steps: its {@code Objectives}, the report-back key never minted, and a successor step keeping the
     * place in the quest of the step it replaces ({@code Order}, its {@code Drop} and {@code Indicator}). Nothing else
     * in a quest names a step by its key, so the file's position and these leaves are the whole of a step's place.
     */
    static final KeyedRows STEPS = new KeyedRows("Objectives", RETIRED_STEPS, QuestAsset.REPORT_BACK_ID,
            List.of("Order", "Drop", "Indicator"));

    private static final long HOUR_MS = 3_600_000L;

    private QuestDraft() {
    }

    // ---------------------------------------------------------------- keys

    /**
     * A step key from {@code typed} that {@code taken} does not hold (asked with the candidate lower-cased): the
     * server content pack's mint lower-cased ({@code "Kill Skeleton"} is {@code kill_skeleton}), else the first free
     * {@code _2}, {@code _3}. The report-back step's key, {@code turn_in}, is never minted.
     */
    @Nonnull
    public static String stepKey(@Nullable String typed, @Nonnull Predicate<String> taken) {
        return STEPS.mint(typed, taken);
    }

    /**
     * The key a step slot saves under: its {@code savedKey} while the step is still of the kind it was saved as
     * ({@code savedKind}, matched without regard to case), so a new count, target or line keeps it; else a new key
     * minted from {@code typed}, never one {@code taken} holds. A changed kind is a new step, and the caller retires
     * the old key. Pure.
     */
    @Nonnull
    public static String keyFor(@Nullable String savedKey, @Nullable String savedKind, @Nonnull String kind,
            @Nullable String typed, @Nonnull Predicate<String> taken) {
        return STEPS.keyFor(savedKey, savedKind, kind, typed, taken);
    }

    /** Every key a new step may not take in {@code loaded}'s file: its steps' keys and its retired ones, lower-cased. */
    @Nonnull
    public static Set<String> taken(@Nonnull Loaded loaded) {
        return KeyedRows.taken(loaded.stepKeys(), loaded.retiredSteps());
    }

    /** A step as the shared keyed rows hold it. */
    @Nonnull
    static KeyedRows.Row row(@Nonnull QuestForm.Step step) {
        return new KeyedRows.Row(step.key(), step.kind(), step.target(), step.amount(), step.matchMode(),
                step.textKey(), step.replaces());
    }

    /** A keyed row as the quest form holds it. */
    @Nonnull
    static QuestForm.Step step(@Nonnull KeyedRows.Row row) {
        return new QuestForm.Step(row.key(), row.kind(), row.target(), row.amount(), row.matchMode(), row.textKey(),
                row.replaces());
    }

    // ---------------------------------------------------------------- writing

    /**
     * The quest document for {@code form}: a new one, or {@code original} (left untouched) with the editor's leaves
     * written over a copy of it.
     */
    @Nonnull
    public static JsonObject write(@Nonnull QuestForm form, @Nullable JsonObject original) {
        JsonObject doc = original == null ? new JsonObject() : original.deepCopy();
        writeText(doc, form);
        writeFlow(doc, form);
        writeRepeat(doc, form.repeat());
        writeGiver(doc, form);
        if (form.requires() != null) {
            writeRequires(doc, form.requires());
        }
        List<KeyedRows.Row> steps = new ArrayList<>();
        for (QuestForm.Step step : form.steps()) {
            steps.add(row(step));
        }
        STEPS.write(doc, steps, form.droppedSteps());
        ClaimSlots.write(doc, form.rewardSlots(), form.rewards());
        return doc;
    }

    private static void writeText(@Nonnull JsonObject doc, @Nonnull QuestForm form) {
        JsonObject text = DraftJson.group(doc, "Text");
        text.addProperty("TitleKey", form.titleKey());
        DraftJson.setOrRemove(text, "FlavorKey", form.flavorKey());
    }

    private static void writeFlow(@Nonnull JsonObject doc, @Nonnull QuestForm form) {
        JsonObject flow = DraftJson.group(doc, "Flow");
        DraftJson.setFlag(flow, "AutoTrack", form.autoTrack());
        DraftJson.setFlag(flow, "Sequential", form.sequential());
        DraftJson.dropIfEmpty(doc, "Flow");
    }

    private static void writeRepeat(@Nonnull JsonObject doc, @Nonnull QuestForm.Repeat repeat) {
        if (!repeat.on()) {
            doc.remove("Repeat");
            return;
        }
        JsonObject block = DraftJson.group(doc, "Repeat");
        if (repeat.cooldownHours() > 0) {
            JsonObject cooldown = new JsonObject();
            cooldown.addProperty("Hours", repeat.cooldownHours());
            block.add("Cooldown", cooldown);
        } else if (repeat.cooldownHours() == 0) {
            block.remove("Cooldown");
        }
        if (repeat.period() != null) {
            DraftJson.group(block, "Reset").addProperty("Period", repeat.period());
        } else if (block.has("Reset") && block.get("Reset").isJsonObject()) {
            block.getAsJsonObject("Reset").remove("Period");
            DraftJson.dropIfEmpty(block, "Reset");
        }
        if (repeat.maxCompletions() > 0) {
            block.addProperty("MaxCompletions", repeat.maxCompletions());
        } else {
            block.remove("MaxCompletions");
        }
    }

    private static void writeGiver(@Nonnull JsonObject doc, @Nonnull QuestForm form) {
        if (form.giver() != null) {
            DraftJson.group(doc, "Npc").addProperty("ViewId", form.giver());
        } else if (doc.has("Npc") && doc.get("Npc").isJsonObject()) {
            doc.getAsJsonObject("Npc").remove("ViewId");
            DraftJson.dropIfEmpty(doc, "Npc");
        }
        if (form.returnToGiver() && form.giver() != null) {
            doc.addProperty("TurnInAt", QuestObjectiveAsset.GIVER_SENTINEL);
        } else if (turnsInAtGiver(doc.get("TurnInAt"))) {
            doc.remove("TurnInAt");
        }
    }

    private static void writeRequires(@Nonnull JsonObject doc, @Nonnull QuestForm.Requirements requires) {
        if (requires.isEmpty()) {
            doc.remove("Requires");
            return;
        }
        JsonObject block = new JsonObject();
        if (requires.factor() != null) {
            JsonObject row = new JsonObject();
            row.addProperty("Factor", requires.factor());
            if (requires.param() != null) {
                row.addProperty("Param", requires.param());
            }
            if (requires.min() != null) {
                row.add("Min", DraftJson.number(requires.min()));
            }
            JsonArray factors = new JsonArray();
            factors.add(row);
            block.add("Factors", factors);
        }
        if (requires.permission() != null) {
            block.addProperty("Permission", requires.permission());
        }
        if (requires.quest() != null) {
            JsonArray quests = new JsonArray();
            quests.add(requires.quest());
            block.add("Quests", quests);
        }
        doc.add("Requires", block);
    }

    // ---------------------------------------------------------------- reading

    /**
     * What a quest file says, as the editor shows it: its first {@code stepSlots} steps and {@code rewardSlots} Claim
     * rows, and its requirements when the editor can show them whole.
     *
     * @param titleKey       {@code Text.TitleKey}, or null
     * @param flavorKey      {@code Text.FlavorKey}, or null
     * @param displayName    {@code Text.DisplayName}, or null
     * @param inherits       true for a file with a {@code Parent} or {@code Abstract}: much of it lives in another file
     * @param requires       the requirements, or null when the file asks for more than the editor shows
     * @param stepKeys       every step key the file holds, the ones past the slots and with no kind included
     * @param retiredSteps   every step key the file's {@code Meta} retires
     */
    public record Loaded(@Nullable String titleKey, @Nullable String flavorKey, @Nullable String displayName,
            boolean inherits, @Nullable String giver, boolean returnToGiver, boolean sequential, boolean autoTrack,
            @Nonnull List<QuestForm.Step> steps, @Nonnull List<QuestForm.Reward> rewards,
            @Nullable QuestForm.Requirements requires, @Nonnull QuestForm.Repeat repeat,
            @Nonnull Set<String> stepKeys, @Nonnull List<String> retiredSteps) {
    }

    /**
     * Read {@code doc} as the editor shows it: its first {@code stepSlots} steps that name a {@code Kind} (one that
     * names none is no step the editor shows, and the file keeps it as written) and its first {@code rewardSlots}
     * Claim rows.
     */
    @Nonnull
    public static Loaded read(@Nonnull JsonObject doc, int stepSlots, int rewardSlots) {
        JsonObject text = DraftJson.object(doc, "Text");
        JsonObject flow = DraftJson.object(doc, "Flow");
        JsonObject npc = DraftJson.object(doc, "Npc");

        KeyedRows.Read held = STEPS.read(doc, stepSlots);
        List<QuestForm.Step> steps = new ArrayList<>();
        for (KeyedRows.Row row : held.rows()) {
            steps.add(step(row));
        }

        List<QuestForm.Reward> rewards = new ArrayList<>();
        for (ClaimSlots.Slot slot : ClaimSlots.read(doc, rewardSlots)) {
            rewards.add(new QuestForm.Reward(slot.slot(), slot.kind(), slot.params(), slot.params().keySet(), false));
        }

        return new Loaded(text == null ? null : DraftJson.text(text, "TitleKey"),
                text == null ? null : DraftJson.text(text, "FlavorKey"),
                text == null ? null : DraftJson.text(text, "DisplayName"),
                doc.has("Parent") || DraftJson.isTrue(doc.get("Abstract")),
                npc == null ? null : DraftJson.text(npc, "ViewId"), turnsInAtGiver(doc.get("TurnInAt")),
                flow != null && DraftJson.isTrue(flow.get("Sequential")),
                flow != null && DraftJson.isTrue(flow.get("AutoTrack")),
                steps, rewards, simpleRequires(doc.get("Requires")), readRepeat(DraftJson.object(doc, "Repeat")),
                held.keys(), held.retired());
    }

    /**
     * The requirements when the editor can show them whole (at most one quest, a permission, at most one factor row
     * with a factor, a parameter and a least value, and nothing else), {@code QuestForm.Requirements} asking nothing
     * for none, else null.
     */
    @Nullable
    static QuestForm.Requirements simpleRequires(@Nullable JsonElement written) {
        if (written == null || written.isJsonNull()) {
            return new QuestForm.Requirements(null, null, null, null, null);
        }
        if (!written.isJsonObject()) {
            return null;
        }
        JsonObject block = written.getAsJsonObject();
        String quest = null;
        String factor = null;
        String param = null;
        Double min = null;
        for (Map.Entry<String, JsonElement> e : block.entrySet()) {
            String key = e.getKey();
            JsonElement value = e.getValue();
            if (key.startsWith("$") || "Permission".equals(key)) {
                continue;
            }
            if ("Quests".equals(key) && value.isJsonArray() && value.getAsJsonArray().size() <= 1) {
                quest = value.getAsJsonArray().size() == 0 ? null : DraftJson.scalar(value.getAsJsonArray().get(0));
                continue;
            }
            if ("Factors".equals(key) && value.isJsonArray() && value.getAsJsonArray().size() <= 1) {
                if (value.getAsJsonArray().size() == 0) {
                    continue;
                }
                JsonElement only = value.getAsJsonArray().get(0);
                if (!only.isJsonObject()) {
                    return null;
                }
                for (String leaf : only.getAsJsonObject().keySet()) {
                    if (!leaf.startsWith("$") && !Set.of("Factor", "Param", "Min").contains(leaf)) {
                        return null;
                    }
                }
                factor = DraftJson.text(only.getAsJsonObject(), "Factor");
                param = DraftJson.text(only.getAsJsonObject(), "Param");
                JsonElement least = only.getAsJsonObject().get("Min");
                min = least != null && least.isJsonPrimitive() && least.getAsJsonPrimitive().isNumber()
                        ? least.getAsDouble() : null;
                continue;
            }
            return null;
        }
        return new QuestForm.Requirements(quest, DraftJson.text(block, "Permission"), factor, param, min);
    }

    @Nonnull
    private static QuestForm.Repeat readRepeat(@Nullable JsonObject block) {
        if (block == null) {
            return QuestForm.Repeat.ONCE;
        }
        JsonObject cooldown = DraftJson.object(block, "Cooldown");
        int hours = 0;
        if (cooldown != null) {
            long ms = 0L;
            ms += units(cooldown, "Weeks") * 7L * 24L * HOUR_MS;
            ms += units(cooldown, "Days") * 24L * HOUR_MS;
            ms += units(cooldown, "Hours") * HOUR_MS;
            ms += units(cooldown, "Minutes") * 60_000L;
            ms += units(cooldown, "Seconds") * 1_000L;
            hours = ms % HOUR_MS == 0 && ms / HOUR_MS <= Integer.MAX_VALUE ? (int) (ms / HOUR_MS) : KEEP_COOLDOWN;
        }
        JsonObject reset = DraftJson.object(block, "Reset");
        Long max = DraftJson.whole(block.get("MaxCompletions"));
        return new QuestForm.Repeat(true, hours, reset == null ? null : DraftJson.text(reset, "Period"),
                max == null ? 0 : (int) Math.max(0L, Math.min(Integer.MAX_VALUE, max)));
    }

    /** {@code TurnInAt} written as true or as the giver word. */
    private static boolean turnsInAtGiver(@Nullable JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive p = value.getAsJsonPrimitive();
        return p.isBoolean() ? p.getAsBoolean() : QuestObjectiveAsset.GIVER_SENTINEL.equalsIgnoreCase(p.getAsString().trim());
    }

    private static long units(@Nonnull JsonObject group, @Nonnull String key) {
        Long value = DraftJson.whole(group.get(key));
        return value == null ? 0L : value;
    }
}
