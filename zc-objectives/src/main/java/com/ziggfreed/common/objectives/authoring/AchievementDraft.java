package com.ziggfreed.common.objectives.authoring;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ziggfreed.common.achievement.asset.AchievementAsset;
import com.ziggfreed.common.content.ServerContent;
import com.ziggfreed.common.objectives.title.TitleRewardKind;

/**
 * The achievement file the editor writes, and the one it reads back: an ordinary
 * {@code Server/ZiggfreedCommon/Achievements} document in the shape {@link AchievementAsset} decodes, written over the
 * file it edits so every leaf the editor has no row for ({@code Requires}, {@code Listing.Hidden}, a criterion's
 * {@code Zone} or {@code Targets}, another mod's {@code Meta}, an {@code Auto} row that is no title) stays exactly as
 * it was. It also builds the title and category documents the editor creates beside it. Pure.
 *
 * <p><b>Keys never move, and never come back.</b> The criteria are {@link KeyedRows} ({@link #CRITERIA}): a
 * criterion's key is minted once and written under on every save after; one whose kind changes takes a new key in
 * the old one's place; every key taken out is retired in the file's own {@code Meta}
 * ({@code "Meta": {"ziggfreedcommon": {"RetiredCriteria": ["break_stone"]}}}) and never minted again.
 *
 * <p><b>Rewards are collected</b> (M428): every reward row the editor writes is a {@code Claim} ({@link ClaimSlots}).
 * The one {@code Auto} row it writes is the achievement's title, which lands the moment it is earned; every other
 * {@code Auto} row the file holds is kept as written.
 */
public final class AchievementDraft {

    /** The {@code Meta} leaf listing every criterion key the editor took out of the file. */
    public static final String RETIRED_CRITERIA = "RetiredCriteria";

    /**
     * The achievement's criteria: its {@code Criteria}, no key reserved, and a successor criterion keeping the
     * {@code Zone} of the one it replaces (where it counts, which the editor does not show).
     */
    static final KeyedRows CRITERIA = new KeyedRows("Criteria", RETIRED_CRITERIA, null, List.of("Zone"));

    /** The title reward's parameter naming the title, as shipped files write it. */
    static final String TITLE_PARAM = "Title";

    /** The other spelling of that parameter, read the same way. */
    private static final String TITLE_ID_PARAM = "TitleId";

    /** Why the editor will not open a file: much of it lives elsewhere or follows rules no page shows. */
    public enum Refusal {
        /** {@code Parent} or {@code Abstract}: it is built on another file, or exists to be built on. */
        INHERITS,
        /** {@code Occurrence}: a copy is minted every year. */
        YEARLY,
        /** {@code MetaChildren} or {@code MetaSelector}: it is earned by earning others. */
        CAPSTONE,
        /**
         * A {@code PerSkill} block in a mod's {@code Meta}: a stencil that mod stamps out once per skill, so its one
         * written criterion stands for many and an edit here would change every stamped copy.
         */
        STENCIL
    }

    /** Where a title sits around a player's name. */
    public enum TitlePlace {
        /** After the name: {@code Ziggy the Mason}. */
        AFTER,
        /** After the name and a comma, the shared line: {@code Ziggy, the Mason}. No display line of its own. */
        COMMA,
        /** Before the name: {@code the Mason Ziggy}. */
        BEFORE;

        /** The place an admin's choice names, matched without regard to case; {@link #AFTER} for none. */
        @Nonnull
        public static TitlePlace of(@Nullable String chosen) {
            for (TitlePlace place : values()) {
                if (place.name().equalsIgnoreCase(chosen == null ? "" : chosen.trim())) {
                    return place;
                }
            }
            return AFTER;
        }

        /** The title's display line, {@code {0}} the player's name, or null when the shared line places it. */
        @Nullable
        public String displayLine(@Nonnull String titleName) {
            String name = titleName.trim();
            return switch (this) {
                case AFTER -> "{0} " + name;
                case BEFORE -> name + " {0}";
                case COMMA -> null;
            };
        }
    }

    private AchievementDraft() {
    }

    // ---------------------------------------------------------------- keys

    /** A criterion key from {@code typed} that {@code taken} does not hold ({@link KeyedRows#mint}). */
    @Nonnull
    public static String criterionKey(@Nullable String typed, @Nonnull Predicate<String> taken) {
        return CRITERIA.mint(typed, taken);
    }

    /** The key a criterion slot saves under ({@link KeyedRows#keyFor}). */
    @Nonnull
    public static String keyFor(@Nullable String savedKey, @Nullable String savedKind, @Nonnull String kind,
            @Nullable String typed, @Nonnull Predicate<String> taken) {
        return CRITERIA.keyFor(savedKey, savedKind, kind, typed, taken);
    }

    /** Every key a new criterion may not take in {@code loaded}'s file, lower-cased. */
    @Nonnull
    public static Set<String> taken(@Nonnull Loaded loaded) {
        return KeyedRows.taken(loaded.criterionKeys(), loaded.retiredCriteria());
    }

    // ---------------------------------------------------------------- writing

    /**
     * The achievement document for {@code form}: a new one, or {@code original} (left untouched) with the editor's
     * leaves written over a copy of it.
     */
    @Nonnull
    public static JsonObject write(@Nonnull AchievementForm form, @Nullable JsonObject original) {
        JsonObject doc = original == null ? new JsonObject() : original.deepCopy();
        writeEnabled(doc, form.enabled());
        JsonObject text = DraftJson.group(doc, "Text");
        DraftJson.setOrRemove(text, "TitleKey", form.titleKey());
        DraftJson.setOrRemove(text, "FlavorKey", form.flavorKey());
        DraftJson.dropIfEmpty(doc, "Text");
        JsonObject listing = DraftJson.group(doc, "Listing");
        if (form.category() != null && !form.category().isBlank()) {
            listing.addProperty("Category", form.category().trim());
        }
        DraftJson.setOrRemove(listing, "Subcategory", form.subcategory());
        DraftJson.dropIfEmpty(doc, "Listing");
        DraftJson.group(doc, "Scoring").addProperty("Points", Math.max(0, form.points()));
        CRITERIA.write(doc, form.criteria(), form.droppedCriteria());
        ClaimSlots.write(doc, form.rewardSlots(), form.rewards());
        writeTitle(doc, form.title());
        return doc;
    }

    /** In circulation leaves an unwritten switch unwritten (it reads as on); out of circulation writes false. */
    private static void writeEnabled(@Nonnull JsonObject doc, boolean enabled) {
        if (!enabled) {
            doc.addProperty("Enabled", false);
        } else if (doc.has("Enabled")) {
            doc.addProperty("Enabled", true);
        }
    }

    /**
     * The title row: the file's first {@code Auto} row of the title kind names {@code title}, or one is added for it;
     * with no title that row is taken out. Every other {@code Auto} row stays as written.
     */
    private static void writeTitle(@Nonnull JsonObject doc, @Nullable String title) {
        JsonObject rewards = DraftJson.object(doc, "Rewards");
        JsonArray before = rewards != null && rewards.has("Auto") && rewards.get("Auto").isJsonArray()
                ? rewards.getAsJsonArray("Auto") : new JsonArray();
        int at = titleRow(before);
        JsonArray auto = new JsonArray();
        for (int i = 0; i < before.size(); i++) {
            if (i == at) {
                if (title != null) {
                    auto.add(titleRow(before.get(i).getAsJsonObject().deepCopy(), title));
                }
                continue;
            }
            auto.add(before.get(i).deepCopy());
        }
        if (at < 0 && title != null) {
            auto.add(titleRow(new JsonObject(), title));
        }
        if (auto.size() > 0) {
            DraftJson.group(doc, "Rewards").add("Auto", auto);
        } else if (rewards != null) {
            rewards.remove("Auto");
            DraftJson.dropIfEmpty(doc, "Rewards");
        }
    }

    @Nonnull
    private static JsonObject titleRow(@Nonnull JsonObject row, @Nonnull String title) {
        if (!row.has("Kind")) {
            row.addProperty("Kind", TitleRewardKind.KIND);
        }
        JsonObject params = DraftJson.group(row, "Params");
        DraftJson.removeIgnoringCase(params, TITLE_PARAM);
        DraftJson.removeIgnoringCase(params, TITLE_ID_PARAM);
        params.addProperty(TITLE_PARAM, title.trim());
        return row;
    }

    /** The place of the first {@code Auto} row of the title kind, or -1. */
    private static int titleRow(@Nonnull JsonArray auto) {
        for (int i = 0; i < auto.size(); i++) {
            JsonElement row = auto.get(i);
            if (row.isJsonObject() && TitleRewardKind.KIND.equalsIgnoreCase(DraftJson.text(row.getAsJsonObject(), "Kind"))) {
                return i;
            }
        }
        return -1;
    }

    /** The title a row of the title kind names, in either spelling, or null. */
    @Nullable
    private static String titleOf(@Nonnull JsonObject row) {
        JsonObject params = DraftJson.object(row, "Params");
        if (params == null) {
            return null;
        }
        for (String key : params.keySet()) {
            if (key.equalsIgnoreCase(TITLE_PARAM) || key.equalsIgnoreCase(TITLE_ID_PARAM)) {
                String value = DraftJson.text(params, key);
                if (value != null) {
                    return value.trim();
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- the title and category files

    /**
     * A title file ({@code Server/ZiggfreedCommon/Titles}) naming the title by {@code nameKey} and saying how it is
     * earned by {@code flavorKey} (none when null), written over {@code original} when the pack already holds one.
     */
    @Nonnull
    public static JsonObject titleDocument(@Nonnull String nameKey, @Nullable String flavorKey,
            @Nullable JsonObject original) {
        JsonObject doc = original == null ? new JsonObject() : original.deepCopy();
        JsonObject text = DraftJson.group(doc, "Text");
        text.addProperty("TitleKey", nameKey);
        DraftJson.setOrRemove(text, "FlavorKey", flavorKey);
        return doc;
    }

    /**
     * A category file ({@code Server/ZiggfreedCommon/AchievementCategories}) naming the category by {@code nameKey},
     * written over {@code original} when the pack already holds one.
     */
    @Nonnull
    public static JsonObject categoryDocument(@Nonnull String nameKey, @Nullable JsonObject original) {
        JsonObject doc = original == null ? new JsonObject() : original.deepCopy();
        doc.addProperty("TitleKey", nameKey);
        return doc;
    }

    // ---------------------------------------------------------------- reading

    /**
     * What an achievement file says, as the editor shows it.
     *
     * @param titleKey        {@code Text.TitleKey}, or null (the convention line names it)
     * @param flavorKey       {@code Text.FlavorKey}, or null
     * @param displayName     {@code Text.DisplayName}, or null
     * @param refusal         why the editor will not open it, or null when it will
     * @param points          {@code Scoring.Points}, or the default when unwritten
     * @param enabled         false when the file writes {@code Enabled: false}
     * @param criteria        its first criteria that name a {@code Kind}, up to the slots
     * @param criterionKeys   every criterion key the file holds
     * @param retiredCriteria every criterion key the file's {@code Meta} retires
     * @param rewards         its first Claim rows, up to the slots
     * @param title           the title its first {@code Auto} title row gives, or null
     */
    public record Loaded(@Nullable String titleKey, @Nullable String flavorKey, @Nullable String displayName,
            @Nullable Refusal refusal, @Nullable String category, @Nullable String subcategory, int points,
            boolean enabled, @Nonnull List<KeyedRows.Row> criteria, @Nonnull Set<String> criterionKeys,
            @Nonnull List<String> retiredCriteria, @Nonnull List<ClaimSlots.Slot> rewards, @Nullable String title) {
    }

    /** Read {@code doc} as the editor shows it: its first {@code criterionSlots} criteria and {@code rewardSlots} Claim rows. */
    @Nonnull
    public static Loaded read(@Nonnull JsonObject doc, int criterionSlots, int rewardSlots) {
        JsonObject text = DraftJson.object(doc, "Text");
        JsonObject listing = DraftJson.object(doc, "Listing");
        JsonObject scoring = DraftJson.object(doc, "Scoring");
        Long points = scoring == null ? null : DraftJson.whole(scoring.get("Points"));
        KeyedRows.Read criteria = CRITERIA.read(doc, criterionSlots);

        String title = null;
        JsonObject rewards = DraftJson.object(doc, "Rewards");
        if (rewards != null && rewards.has("Auto") && rewards.get("Auto").isJsonArray()) {
            JsonArray auto = rewards.getAsJsonArray("Auto");
            int at = titleRow(auto);
            title = at < 0 ? null : titleOf(auto.get(at).getAsJsonObject());
        }

        JsonElement enabled = doc.get("Enabled");
        return new Loaded(text == null ? null : DraftJson.text(text, "TitleKey"),
                text == null ? null : DraftJson.text(text, "FlavorKey"),
                text == null ? null : DraftJson.text(text, "DisplayName"), refusal(doc),
                listing == null ? null : DraftJson.text(listing, "Category"),
                listing == null ? null : DraftJson.text(listing, "Subcategory"),
                points == null ? AchievementAsset.Scoring.DEFAULT_POINTS
                        : (int) Math.max(0L, Math.min(Integer.MAX_VALUE, points)),
                enabled == null || !enabled.isJsonPrimitive() || !enabled.getAsJsonPrimitive().isBoolean()
                        || enabled.getAsBoolean(),
                criteria.rows(), criteria.keys(), criteria.retired(), ClaimSlots.read(doc, rewardSlots), title);
    }

    /** Why the editor will not open {@code doc}, or null when it will. */
    @Nullable
    private static Refusal refusal(@Nonnull JsonObject doc) {
        if (doc.has("Parent") || DraftJson.isTrue(doc.get("Abstract"))) {
            return Refusal.INHERITS;
        }
        if (doc.has("Occurrence")) {
            return Refusal.YEARLY;
        }
        if (doc.has("MetaChildren") || doc.has("MetaSelector")) {
            return Refusal.CAPSTONE;
        }
        if (doc.get("Meta") instanceof JsonObject meta) {
            for (var owner : meta.entrySet()) {
                if (owner.getValue() instanceof JsonObject block && block.has("PerSkill")) {
                    return Refusal.STENCIL;
                }
            }
        }
        return null;
    }

    /**
     * The key a title's display line is saved under: the convention key {@code title.<id lower case>.display}, the
     * only key the line placing a title around a name is read from.
     */
    @Nonnull
    static String displayKey(@Nonnull String titleId) {
        return ServerContent.textKey("title", titleId.trim().toLowerCase(Locale.ROOT), "display");
    }

    // ---------------------------------------------------------------- text keys

    /**
     * The key typed words save under, keeping the key the file already holds wherever it can, so a reopened
     * achievement never moves its words: words the server content pack saved ({@code ours}) are rewritten under
     * their own key; words a pack ships that the admin left as they were keep the shipped key (and its
     * translations), or no key at all when the convention line names them; words retyped over a shipped key, or
     * typed new, take the server key {@code fresh} gives. Blank words keep a key whose words could not be shown, and
     * drop one that showed. Every key given words goes into {@code texts}.
     *
     * @param fileKey the key the file holds, or null
     * @param shown   the words the editor showed for it, blank for none
     * @param typed   the words as typed, trimmed
     */
    @Nullable
    static String keptKey(@Nullable String fileKey, @Nonnull String shown, @Nonnull String typed,
            @Nonnull Predicate<String> ours, @Nonnull Supplier<String> fresh, @Nonnull Map<String, String> texts) {
        if (typed.isEmpty()) {
            return shown.isEmpty() ? fileKey : null;
        }
        if (fileKey != null && ours.test(fileKey)) {
            texts.put(fileKey, typed);
            return fileKey;
        }
        if (typed.equals(shown)) {
            return fileKey;
        }
        String key = fresh.get();
        texts.put(key, typed);
        return key;
    }
}
