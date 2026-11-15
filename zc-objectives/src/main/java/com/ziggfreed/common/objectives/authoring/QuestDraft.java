package com.ziggfreed.common.objectives.authoring;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

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
 * <p><b>Keys never move.</b> A step's key is what a player's progress on it is saved under, so it is minted once from
 * the words the admin typed ({@link #stepKey}) and written under that key on every save after; an edit changes the
 * step's leaves, never its key. An emptied step is taken out by its key, and a new one goes after the rest.
 *
 * <p>Every reward row the editor writes is a {@code Claim}, which the player collects: it writes no {@code Auto} row,
 * and leaves one the file already holds as written.
 */
public final class QuestDraft {

    /** The longest step key minted, before any suffix. */
    static final int MAX_KEY_LENGTH = 40;

    /** The longest asset id minted, before any suffix. */
    static final int MAX_ID_LENGTH = 48;

    /** A step key minted from words with nothing usable in them. */
    static final String FALLBACK_KEY = "step";

    /** An asset id minted from words with nothing usable in them. */
    static final String FALLBACK_ID = "Custom";

    /** {@code Repeat.cooldownHours} that leaves the file's {@code Cooldown} as written (not whole hours). */
    public static final int KEEP_COOLDOWN = -1;

    private static final Pattern WORD_BREAK = Pattern.compile("[^A-Za-z0-9]+");
    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern APOSTROPHES = Pattern.compile("['`\\x{2019}\\x{02BC}]");

    private static final long HOUR_MS = 3_600_000L;

    private QuestDraft() {
    }

    // ---------------------------------------------------------------- keys and ids

    /**
     * A step key from {@code typed} that {@code taken} does not hold: its words lower-cased and joined by {@code _}
     * ({@code "Kill Skeleton"} is {@code kill_skeleton}), else the first free {@code _2}, {@code _3}. The report-back
     * step's key, {@code turn_in}, is never minted.
     */
    @Nonnull
    public static String stepKey(@Nullable String typed, @Nonnull Predicate<String> taken) {
        List<String> words = words(typed);
        StringBuilder key = new StringBuilder();
        for (String word : words) {
            append(key, word.toLowerCase(Locale.ROOT), MAX_KEY_LENGTH);
        }
        String base = key.length() == 0 ? FALLBACK_KEY : key.toString();
        Predicate<String> reserved = candidate -> taken.test(candidate)
                || QuestAsset.REPORT_BACK_ID.equalsIgnoreCase(candidate);
        return free(base, reserved);
    }

    /**
     * An {@code Is_Like_This} id from {@code typed} that {@code taken} does not hold, as the server content pack mints
     * an asset's: each word capitalised, joined by {@code _}, else the first free {@code _2}, {@code _3}.
     */
    @Nonnull
    public static String assetId(@Nullable String typed, @Nonnull Predicate<String> taken) {
        StringBuilder id = new StringBuilder();
        for (String word : words(typed)) {
            append(id, word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT),
                    MAX_ID_LENGTH);
        }
        return free(id.length() == 0 ? FALLBACK_ID : id.toString(), taken);
    }

    /** The letters and digits of {@code typed}, accents and apostrophes dropped, split at anything else. */
    @Nonnull
    private static List<String> words(@Nullable String typed) {
        String plain = typed == null ? ""
                : APOSTROPHES.matcher(MARKS.matcher(Normalizer.normalize(typed, Normalizer.Form.NFD)).replaceAll(""))
                        .replaceAll("");
        List<String> words = new ArrayList<>();
        for (String word : WORD_BREAK.split(plain)) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        return words;
    }

    private static void append(@Nonnull StringBuilder into, @Nonnull String word, int max) {
        int room = max - into.length() - (into.length() == 0 ? 0 : 1);
        if (room <= 0) {
            return;
        }
        if (into.length() > 0) {
            into.append('_');
        }
        into.append(word, 0, Math.min(word.length(), room));
    }

    @Nonnull
    private static String free(@Nonnull String base, @Nonnull Predicate<String> taken) {
        if (!taken.test(base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String candidate = base + "_" + n;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
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
     * The steps, in the file's own order: each step the editor holds is written over its row by key, an emptied one
     * is taken out, one the editor has no slot for is kept, and a new key goes after the rest in slot order.
     */
    private static void writeObjectives(@Nonnull JsonObject doc, @Nonnull QuestForm form) {
        JsonObject before = doc.has("Objectives") && doc.get("Objectives").isJsonObject()
                ? doc.getAsJsonObject("Objectives") : new JsonObject();
        Map<String, QuestForm.Step> byKey = new LinkedHashMap<>();
        for (QuestForm.Step step : form.steps()) {
            byKey.put(step.key(), step);
        }
        Set<String> dropped = new HashSet<>(form.droppedSteps());
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : before.entrySet()) {
            QuestForm.Step step = byKey.remove(e.getKey());
            if (step != null) {
                out.add(e.getKey(), stepRow(e.getValue(), step));
            } else if (!dropped.contains(e.getKey())) {
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
     * @param stepKeys       every step key the file holds, the ones past the slots included
     */
    public record Loaded(@Nullable String titleKey, @Nullable String flavorKey, @Nullable String displayName,
            boolean inherits, @Nullable String giver, boolean returnToGiver, boolean sequential, boolean autoTrack,
            @Nonnull List<QuestForm.Step> steps, @Nonnull List<QuestForm.Reward> rewards,
            @Nullable QuestForm.Requirements requires, @Nonnull QuestForm.Repeat repeat,
            @Nonnull Set<String> stepKeys) {
    }

    /** Read {@code doc} as the editor shows it. */
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
                if (steps.size() < stepSlots && e.getValue().isJsonObject()) {
                    JsonObject row = e.getValue().getAsJsonObject();
                    Long amount = whole(row.get("Amount"));
                    steps.add(new QuestForm.Step(e.getKey(), nonNull(text(row, "Kind")), text(row, "Target"),
                            amount == null ? 1L : amount, text(row, "MatchMode"), text(row, "TextKey")));
                }
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
                steps, rewards, simpleRequires(doc.get("Requires")), readRepeat(object(doc, "Repeat")), keys);
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
