package com.ziggfreed.common.objectives.authoring;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.ziggfreed.common.content.ServerContent;
import com.ziggfreed.common.quest.asset.QuestAsset;
import com.ziggfreed.common.quest.asset.QuestObjectiveAsset;

/**
 * The quest file the editor writes, and the one it reads back: an ordinary {@code Server/ZiggfreedCommon/Quests}
 * document in the shape {@link QuestAsset} decodes, written over the file it edits so every leaf the editor has no
 * row for (an {@code Indicator}, a step's {@code Drop}, a {@code Meta} namespace, a fourth Claim row) stays exactly as
 * it was. Pure.
 *
 * <p><b>Keys never move, and never come back.</b> A step's key is what a player's progress on it is saved under, so it
 * is minted once from the words the admin typed ({@link #stepKey}) and written under that key on every save after: a
 * new count, target or line keeps it. A step whose kind changes is a new step: {@link #keyFor} mints it a new key,
 * which takes the old one's place in the file ({@link QuestForm.Step#replaces}). An emptied step is taken out by its
 * key. Every key taken out of the file is kept in the file's own {@code Meta} ({@link #RETIRED_STEPS} under
 * {@link #META_NAMESPACE}: {@code "Meta": {"ziggfreedcommon": {"RetiredSteps": ["kill_skeleton"]}}}), which the quest
 * store carries untouched, and {@link #taken} hands it to every mint, so a later step never lands on a player's old
 * progress. A step that names no {@code Kind} is no step the editor shows: it is kept as written.
 *
 * <p>Every reward row the editor writes is a {@code Claim}, which the player collects: it writes no {@code Auto} row,
 * and leaves one the file already holds as written.
 */
public final class QuestDraft {

    /** The library's namespace in a quest's {@code Meta}. */
    public static final String META_NAMESPACE = "ziggfreedcommon";

    /** The {@code Meta} leaf listing every step key the editor took out of the file, which none is minted again. */
    public static final String RETIRED_STEPS = "RetiredSteps";

    /** {@code Repeat.cooldownHours} that leaves the file's {@code Cooldown} as written (not whole hours). */
    public static final int KEEP_COOLDOWN = -1;

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
        return ServerContent.mintId(typed, candidate -> taken.test(candidate.toLowerCase(Locale.ROOT))
                || QuestAsset.REPORT_BACK_ID.equalsIgnoreCase(candidate)).toLowerCase(Locale.ROOT);
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
        if (savedKey != null && !savedKey.isBlank()
                && (savedKind == null || savedKind.isBlank() || savedKind.trim().equalsIgnoreCase(kind.trim()))) {
            return savedKey.trim();
        }
        return stepKey(typed, taken);
    }

    /** Every key a new step may not take in {@code loaded}'s file: its steps' keys and its retired ones, lower-cased. */
    @Nonnull
    public static Set<String> taken(@Nonnull Loaded loaded) {
        Set<String> taken = new HashSet<>();
        for (String key : loaded.stepKeys()) {
            taken.add(key.toLowerCase(Locale.ROOT));
        }
        for (String key : loaded.retiredSteps()) {
            taken.add(key.toLowerCase(Locale.ROOT));
        }
        return taken;
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
        writeObjectives(doc, form);
        writeRewards(doc, form);
        return doc;
    }

    private static void writeText(@Nonnull JsonObject doc, @Nonnull QuestForm form) {
        JsonObject text = group(doc, "Text");
        text.addProperty("TitleKey", form.titleKey());
        setOrRemove(text, "FlavorKey", form.flavorKey());
    }

    private static void writeFlow(@Nonnull JsonObject doc, @Nonnull QuestForm form) {
        JsonObject flow = group(doc, "Flow");
        setFlag(flow, "AutoTrack", form.autoTrack());
        setFlag(flow, "Sequential", form.sequential());
        dropIfEmpty(doc, "Flow");
    }

    private static void writeRepeat(@Nonnull JsonObject doc, @Nonnull QuestForm.Repeat repeat) {
        if (!repeat.on()) {
            doc.remove("Repeat");
            return;
        }
        JsonObject block = group(doc, "Repeat");
        if (repeat.cooldownHours() > 0) {
            JsonObject cooldown = new JsonObject();
            cooldown.addProperty("Hours", repeat.cooldownHours());
            block.add("Cooldown", cooldown);
        } else if (repeat.cooldownHours() == 0) {
            block.remove("Cooldown");
        }
        if (repeat.period() != null) {
            group(block, "Reset").addProperty("Period", repeat.period());
        } else if (block.has("Reset") && block.get("Reset").isJsonObject()) {
            block.getAsJsonObject("Reset").remove("Period");
            dropIfEmpty(block, "Reset");
        }
        if (repeat.maxCompletions() > 0) {
            block.addProperty("MaxCompletions", repeat.maxCompletions());
        } else {
            block.remove("MaxCompletions");
        }
    }

    private static void writeGiver(@Nonnull JsonObject doc, @Nonnull QuestForm form) {
        if (form.giver() != null) {
            group(doc, "Npc").addProperty("ViewId", form.giver());
        } else if (doc.has("Npc") && doc.get("Npc").isJsonObject()) {
            doc.getAsJsonObject("Npc").remove("ViewId");
            dropIfEmpty(doc, "Npc");
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
                row.add("Min", number(requires.min()));
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

    /**
     * The steps, in the file's own order: each step the editor holds is written over its row by key, a step with a
     * new key in the place of the key it replaces, an emptied one taken out, one the editor has no slot for kept,
     * and any other new key after the rest in slot order. Every key taken out is retired in the file's {@code Meta}.
     */
    private static void writeObjectives(@Nonnull JsonObject doc, @Nonnull QuestForm form) {
        JsonObject before = doc.has("Objectives") && doc.get("Objectives").isJsonObject()
                ? doc.getAsJsonObject("Objectives") : new JsonObject();
        Map<String, QuestForm.Step> byKey = new LinkedHashMap<>();
        Map<String, QuestForm.Step> byReplaced = new LinkedHashMap<>();
        for (QuestForm.Step step : form.steps()) {
            byKey.put(step.key(), step);
            if (step.replaces() != null) {
                byReplaced.put(step.replaces(), step);
            }
        }
        Set<String> dropped = new HashSet<>(form.droppedSteps());
        Set<String> retired = new LinkedHashSet<>();
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : before.entrySet()) {
            QuestForm.Step step = byKey.remove(e.getKey());
            if (step != null) {
                out.add(e.getKey(), stepRow(e.getValue(), step));
                continue;
            }
            QuestForm.Step successor = byReplaced.get(e.getKey());
            if (successor != null && byKey.remove(successor.key()) != null) {
                retired.add(e.getKey());
                out.add(successor.key(), stepRow(null, successor));
            } else if (dropped.contains(e.getKey()) || successor != null) {
                retired.add(e.getKey());
            } else {
                out.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        for (QuestForm.Step step : byKey.values()) {
            out.add(step.key(), stepRow(null, step));
        }
        if (out.size() == 0) {
            doc.remove("Objectives");
        } else {
            doc.add("Objectives", out);
        }
        retire(doc, retired);
    }

    /** Add {@code keys} to the file's retired step keys ({@code Meta.ziggfreedcommon.RetiredSteps}), each once. */
    private static void retire(@Nonnull JsonObject doc, @Nonnull Set<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        Set<String> all = new LinkedHashSet<>(retiredSteps(doc));
        all.addAll(keys);
        JsonArray list = new JsonArray();
        for (String key : all) {
            list.add(key);
        }
        group(group(doc, "Meta"), META_NAMESPACE).add(RETIRED_STEPS, list);
    }

    /** The step keys the file's {@code Meta} retires, as written. */
    @Nonnull
    private static List<String> retiredSteps(@Nonnull JsonObject doc) {
        JsonObject meta = object(doc, "Meta");
        JsonObject ours = meta == null ? null : object(meta, META_NAMESPACE);
        JsonElement listed = ours == null ? null : ours.get(RETIRED_STEPS);
        List<String> keys = new ArrayList<>();
        if (listed != null && listed.isJsonArray()) {
            for (JsonElement key : listed.getAsJsonArray()) {
                String value = scalar(key);
                if (value != null && !value.isBlank()) {
                    keys.add(value.trim());
                }
            }
        }
        return keys;
    }

    @Nonnull
    private static JsonObject stepRow(@Nullable JsonElement before, @Nonnull QuestForm.Step step) {
        JsonObject row = before != null && before.isJsonObject() ? before.getAsJsonObject().deepCopy() : new JsonObject();
        row.addProperty("Kind", step.kind());
        setOrRemove(row, "Target", step.target());
        setOrRemove(row, "MatchMode", step.matchMode());
        row.addProperty("Amount", step.amount());
        setOrRemove(row, "TextKey", step.textKey());
        return row;
    }

    /**
     * The Claim rows: each slot's row written over the row at that place (a row whose kind changed starts afresh),
     * an emptied slot's row taken out, and every row past the editor's slots kept. {@code Auto} is left as written.
     */
    private static void writeRewards(@Nonnull JsonObject doc, @Nonnull QuestForm form) {
        JsonObject rewards = doc.has("Rewards") && doc.get("Rewards").isJsonObject()
                ? doc.getAsJsonObject("Rewards") : null;
        JsonArray before = rewards != null && rewards.has("Claim") && rewards.get("Claim").isJsonArray()
                ? rewards.getAsJsonArray("Claim") : new JsonArray();
        Map<Integer, QuestForm.Reward> bySlot = new LinkedHashMap<>();
        for (QuestForm.Reward reward : form.rewards()) {
            bySlot.put(reward.slot(), reward);
        }
        JsonArray claim = new JsonArray();
        for (int slot = 0; slot < form.rewardSlots(); slot++) {
            QuestForm.Reward reward = bySlot.get(slot);
            if (reward != null) {
                claim.add(rewardRow(slot < before.size() ? before.get(slot) : null, reward));
            }
        }
        for (int i = form.rewardSlots(); i < before.size(); i++) {
            claim.add(before.get(i).deepCopy());
        }
        if (claim.size() > 0) {
            group(doc, "Rewards").add("Claim", claim);
        } else if (rewards != null) {
            rewards.remove("Claim");
            dropIfEmpty(doc, "Rewards");
        }
    }

    @Nonnull
    private static JsonObject rewardRow(@Nullable JsonElement before, @Nonnull QuestForm.Reward reward) {
        JsonObject row = before != null && before.isJsonObject()
                && reward.kind().equalsIgnoreCase(text(before.getAsJsonObject(), "Kind"))
                ? before.getAsJsonObject().deepCopy() : new JsonObject();
        row.addProperty("Kind", reward.kind());
        JsonObject params = reward.whole() || !row.has("Params") || !row.get("Params").isJsonObject()
                ? new JsonObject() : row.getAsJsonObject("Params");
        if (!reward.whole()) {
            for (String name : reward.managed()) {
                removeIgnoringCase(params, name);
            }
        }
        for (Map.Entry<String, String> e : reward.params().entrySet()) {
            removeIgnoringCase(params, e.getKey());
            params.addProperty(e.getKey(), e.getValue());
        }
        if (params.size() == 0) {
            row.remove("Params");
        } else {
            row.add("Params", params);
        }
        return row;
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
        JsonObject text = object(doc, "Text");
        JsonObject flow = object(doc, "Flow");
        JsonObject npc = object(doc, "Npc");

        List<QuestForm.Step> steps = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        JsonObject objectives = object(doc, "Objectives");
        if (objectives != null) {
            for (Map.Entry<String, JsonElement> e : objectives.entrySet()) {
                keys.add(e.getKey());
                if (steps.size() >= stepSlots || !e.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject row = e.getValue().getAsJsonObject();
                String kind = text(row, "Kind");
                if (kind == null) {
                    continue;
                }
                Long amount = whole(row.get("Amount"));
                steps.add(new QuestForm.Step(e.getKey(), kind, text(row, "Target"), amount == null ? 1L : amount,
                        text(row, "MatchMode"), text(row, "TextKey"), null));
            }
        }

        List<QuestForm.Reward> rewards = new ArrayList<>();
        JsonObject pay = object(doc, "Rewards");
        JsonArray claim = pay != null && pay.has("Claim") && pay.get("Claim").isJsonArray()
                ? pay.getAsJsonArray("Claim") : new JsonArray();
        for (int slot = 0; slot < Math.min(rewardSlots, claim.size()); slot++) {
            if (!claim.get(slot).isJsonObject()) {
                continue;
            }
            JsonObject row = claim.get(slot).getAsJsonObject();
            Map<String, String> params = new LinkedHashMap<>();
            JsonObject written = object(row, "Params");
            if (written != null) {
                for (Map.Entry<String, JsonElement> p : written.entrySet()) {
                    String value = scalar(p.getValue());
                    if (value != null && !p.getKey().startsWith("$")) {
                        params.put(p.getKey(), value);
                    }
                }
            }
            rewards.add(new QuestForm.Reward(slot, nonNull(text(row, "Kind")), params, params.keySet(), false));
        }

        return new Loaded(text == null ? null : text(text, "TitleKey"), text == null ? null : text(text, "FlavorKey"),
                text == null ? null : text(text, "DisplayName"), doc.has("Parent") || isTrue(doc.get("Abstract")),
                npc == null ? null : text(npc, "ViewId"), turnsInAtGiver(doc.get("TurnInAt")),
                flow != null && isTrue(flow.get("Sequential")), flow != null && isTrue(flow.get("AutoTrack")),
                steps, rewards, simpleRequires(doc.get("Requires")), readRepeat(object(doc, "Repeat")), keys,
                retiredSteps(doc));
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
                quest = value.getAsJsonArray().size() == 0 ? null : scalar(value.getAsJsonArray().get(0));
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
                factor = text(only.getAsJsonObject(), "Factor");
                param = text(only.getAsJsonObject(), "Param");
                JsonElement least = only.getAsJsonObject().get("Min");
                min = least != null && least.isJsonPrimitive() && least.getAsJsonPrimitive().isNumber()
                        ? least.getAsDouble() : null;
                continue;
            }
            return null;
        }
        return new QuestForm.Requirements(quest, text(block, "Permission"), factor, param, min);
    }

    @Nonnull
    private static QuestForm.Repeat readRepeat(@Nullable JsonObject block) {
        if (block == null) {
            return QuestForm.Repeat.ONCE;
        }
        JsonObject cooldown = object(block, "Cooldown");
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
        JsonObject reset = object(block, "Reset");
        Long max = whole(block.get("MaxCompletions"));
        return new QuestForm.Repeat(true, hours, reset == null ? null : text(reset, "Period"),
                max == null ? 0 : (int) Math.max(0L, Math.min(Integer.MAX_VALUE, max)));
    }

    // ---------------------------------------------------------------- JSON helpers

    /** {@code doc}'s group {@code key}, made (at the end) when it holds none. */
    @Nonnull
    private static JsonObject group(@Nonnull JsonObject doc, @Nonnull String key) {
        JsonElement found = doc.get(key);
        if (found != null && found.isJsonObject()) {
            return found.getAsJsonObject();
        }
        JsonObject made = new JsonObject();
        doc.add(key, made);
        return made;
    }

    @Nullable
    private static JsonObject object(@Nonnull JsonObject doc, @Nonnull String key) {
        JsonElement found = doc.get(key);
        return found != null && found.isJsonObject() ? found.getAsJsonObject() : null;
    }

    private static void dropIfEmpty(@Nonnull JsonObject doc, @Nonnull String key) {
        JsonElement found = doc.get(key);
        if (found != null && found.isJsonObject() && found.getAsJsonObject().size() == 0) {
            doc.remove(key);
        }
    }

    private static void setOrRemove(@Nonnull JsonObject group, @Nonnull String key, @Nullable String value) {
        if (value == null || value.isBlank()) {
            group.remove(key);
        } else {
            group.addProperty(key, value);
        }
    }

    /** True writes the switch; false takes it out, since an unwritten switch is off. */
    private static void setFlag(@Nonnull JsonObject group, @Nonnull String key, boolean on) {
        if (on) {
            group.addProperty(key, true);
        } else {
            group.remove(key);
        }
    }

    private static void removeIgnoringCase(@Nonnull JsonObject group, @Nonnull String name) {
        List<String> found = new ArrayList<>();
        for (String key : group.keySet()) {
            if (key.equalsIgnoreCase(name)) {
                found.add(key);
            }
        }
        for (String key : found) {
            group.remove(key);
        }
    }

    @Nullable
    private static String text(@Nonnull JsonObject group, @Nonnull String key) {
        String value = scalar(group.get(key));
        return value == null || value.isBlank() ? null : value;
    }

    @Nonnull
    private static String nonNull(@Nullable String value) {
        return value == null ? "" : value;
    }

    /** A string, number or true/false as text; null for anything else. */
    @Nullable
    private static String scalar(@Nullable JsonElement value) {
        return value != null && value.isJsonPrimitive() ? value.getAsJsonPrimitive().getAsString() : null;
    }

    private static boolean isTrue(@Nullable JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }

    /** {@code TurnInAt} written as true or as the giver word. */
    private static boolean turnsInAtGiver(@Nullable JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive p = value.getAsJsonPrimitive();
        return p.isBoolean() ? p.getAsBoolean() : QuestObjectiveAsset.GIVER_SENTINEL.equalsIgnoreCase(p.getAsString().trim());
    }

    @Nullable
    private static Long whole(@Nullable JsonElement value) {
        String text = scalar(value);
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long units(@Nonnull JsonObject group, @Nonnull String key) {
        Long value = whole(group.get(key));
        return value == null ? 0L : value;
    }

    /** A number as written: whole when it is whole. */
    @Nonnull
    private static JsonPrimitive number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? new JsonPrimitive((long) value)
                : new JsonPrimitive(value);
    }
}
