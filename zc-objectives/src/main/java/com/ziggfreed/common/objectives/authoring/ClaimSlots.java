package com.ziggfreed.common.objectives.authoring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The {@code Rewards.Claim} rows an editor shows in its reward slots, the rows a player collects (M428). Pure: each
 * slot's row is written over the row at its place (a row whose kind changed starts afresh), an emptied slot's row is
 * taken out, and every row past the editor's slots is kept. {@code Auto} is never touched here.
 */
public final class ClaimSlots {

    private ClaimSlots() {
    }

    /** One reward row an editor fills, always a Claim. */
    public interface Row {

        /** Its place among the editor's reward rows, from 0. */
        int slot();

        @Nonnull
        String kind();

        /** The parameters to write, by name. */
        @Nonnull
        Map<String, String> params();

        /** The parameter names the editor showed: each is written from {@link #params} or taken out, and any other kept. */
        @Nonnull
        Set<String> managed();

        /** True when {@link #params} replaces the row's whole {@code Params} (a kind that describes none). */
        boolean whole();
    }

    /** A reward row as {@link #read} finds it, or as an editor collects it. */
    public record Slot(int slot, @Nonnull String kind, @Nonnull Map<String, String> params,
            @Nonnull Set<String> managed, boolean whole) implements Row {

        public Slot {
            params = new LinkedHashMap<>(params);
            managed = new LinkedHashSet<>(managed);
        }
    }

    /** Write {@code rows} over the first {@code slots} Claim rows of {@code doc}, keeping every row past them. */
    public static void write(@Nonnull JsonObject doc, int slots, @Nonnull List<? extends Row> rows) {
        JsonObject rewards = DraftJson.object(doc, "Rewards");
        JsonArray before = rewards != null && rewards.has("Claim") && rewards.get("Claim").isJsonArray()
                ? rewards.getAsJsonArray("Claim") : new JsonArray();
        Map<Integer, Row> bySlot = new LinkedHashMap<>();
        for (Row row : rows) {
            bySlot.put(row.slot(), row);
        }
        JsonArray claim = new JsonArray();
        for (int slot = 0; slot < slots; slot++) {
            Row row = bySlot.get(slot);
            if (row != null) {
                claim.add(written(slot < before.size() ? before.get(slot) : null, row));
            }
        }
        for (int i = slots; i < before.size(); i++) {
            claim.add(before.get(i).deepCopy());
        }
        if (claim.size() > 0) {
            DraftJson.group(doc, "Rewards").add("Claim", claim);
        } else if (rewards != null) {
            rewards.remove("Claim");
            DraftJson.dropIfEmpty(doc, "Rewards");
        }
    }

    @Nonnull
    private static JsonObject written(@Nullable JsonElement before, @Nonnull Row reward) {
        JsonObject row = before != null && before.isJsonObject()
                && reward.kind().equalsIgnoreCase(DraftJson.text(before.getAsJsonObject(), "Kind"))
                ? before.getAsJsonObject().deepCopy() : new JsonObject();
        row.addProperty("Kind", reward.kind());
        JsonObject params = reward.whole() || !row.has("Params") || !row.get("Params").isJsonObject()
                ? new JsonObject() : row.getAsJsonObject("Params");
        if (!reward.whole()) {
            for (String name : reward.managed()) {
                DraftJson.removeIgnoringCase(params, name);
            }
        }
        for (Map.Entry<String, String> e : reward.params().entrySet()) {
            DraftJson.removeIgnoringCase(params, e.getKey());
            params.addProperty(e.getKey(), e.getValue());
        }
        if (params.size() == 0) {
            row.remove("Params");
        } else {
            row.add("Params", params);
        }
        return row;
    }

    /**
     * The first {@code slots} Claim rows of {@code doc}, each at its place, with every parameter it writes (a
     * {@code $}-marked one left out); a slot holding no object is skipped.
     */
    @Nonnull
    public static List<Slot> read(@Nonnull JsonObject doc, int slots) {
        List<Slot> out = new ArrayList<>();
        JsonObject pay = DraftJson.object(doc, "Rewards");
        JsonArray claim = pay != null && pay.has("Claim") && pay.get("Claim").isJsonArray()
                ? pay.getAsJsonArray("Claim") : new JsonArray();
        for (int slot = 0; slot < Math.min(slots, claim.size()); slot++) {
            if (!claim.get(slot).isJsonObject()) {
                continue;
            }
            JsonObject row = claim.get(slot).getAsJsonObject();
            Map<String, String> params = new LinkedHashMap<>();
            JsonObject written = DraftJson.object(row, "Params");
            if (written != null) {
                for (Map.Entry<String, JsonElement> p : written.entrySet()) {
                    String value = DraftJson.scalar(p.getValue());
                    if (value != null && !p.getKey().startsWith("$")) {
                        params.put(p.getKey(), value);
                    }
                }
            }
            out.add(new Slot(slot, DraftJson.nonNull(DraftJson.text(row, "Kind")), params, params.keySet(), false));
        }
        return out;
    }
}
