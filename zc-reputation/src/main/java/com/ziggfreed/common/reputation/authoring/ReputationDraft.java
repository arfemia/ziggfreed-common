package com.ziggfreed.common.reputation.authoring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.ziggfreed.common.content.ServerContent;

/**
 * The reputation editor's form and the three files one save writes into the server content pack: the native
 * {@code ReputationGroup} ({@code NPC/Reputation/Groups/<Id>.json}), the native {@code NPCGroup} holding its
 * characters' roles ({@code NPC/Groups/<Id>.json}, which the group names), and the library's companion
 * ({@code ZiggfreedCommon/Reputations/<Id>.json}). Pure: the form is its raw values by field id, the files are
 * Gson documents, and the engine's answers come in as predicates.
 *
 * <p><b>An edit keeps what the form does not show.</b> A save starts from the files as the pack holds them
 * ({@link Saved}) and replaces only the leaves the form owns: a kill row with one NPC group and no season, a How
 * to earn line, a rank of the reputation's own whose name was typed here, an item or loot table payout past the
 * top rank, and the member group's {@code IncludeRoles}. Every other row, rank and leaf (a seasonal kill row, a
 * shared rank's own name, a boost payout, {@code Cap}, {@code Prestige}, the group's {@code InitialReputationValue})
 * is written back as it was, so a file finished in the Asset Editor survives the editor.
 *
 * <p><b>The group never names what the engine lacks.</b> The written group always carries {@code NPCGroups} (an
 * empty list when nobody belongs), names the member group first, and keeps only the other entries the engine has
 * loaded ({@link ReputationMembers#loadedGroups}); the member group includes only roles the engine knows
 * ({@link ReputationMembers#knownRoles}). The editor refuses a save whose roles are wrong before it gets here; this
 * is the second guard.
 *
 * <p><b>Text is keys.</b> Every typed word is saved under {@code reputations.<id>.<field>} ({@code name},
 * {@code description}, {@code earn.<n>}, {@code rank.<RankId>}) and the files name its stored {@code server.} key.
 */
public final class ReputationDraft {

    /** The reputation being changed, by id; blank for a new one. */
    public static final String EDIT = "edit";
    public static final String NAME = "name";
    public static final String DESCRIPTION = "description";
    public static final String ICON = "icon";
    public static final String EVERY = "beyondEvery";
    public static final String LOOT = "beyondLoot";
    public static final String ADD_MEMBER = "addMember";
    public static final String REMOVE_MEMBER = "removeMember";

    public static final int KILL_ROWS = 3;
    public static final int EARN_LINES = 3;
    public static final int RANK_ROWS = 3;
    public static final int ITEM_REWARDS = 2;

    /** The text keys' family: the companion store's last folder ({@code reputations.<id>.<field>}). */
    static final String TEXT_STORE = "Reputations";

    private static final String NPC_GROUPS = "NPCGroups";
    private static final String INCLUDE_ROLES = "IncludeRoles";
    private static final String KIND_ITEM = "Item";
    private static final String KIND_LOOTABLE = "Lootable";

    /** The three files as the pack holds them, which an edit starts from. {@code memberGroupId} is null for none. */
    public record Saved(@Nonnull String id, @Nonnull JsonObject group, @Nonnull JsonObject companion,
            @Nullable String memberGroupId, @Nullable JsonObject memberGroup) {
    }

    /** The form filled from a saved reputation: its raw values by field id and its characters' roles. */
    public record Form(@Nonnull Map<String, String> values, @Nonnull List<String> members) {
    }

    /**
     * What one save writes: the member group (null: none to write), the group and the companion, the English by
     * text key, the field values the save minted ({@code rank<n>Id}, blank for a slot it emptied), and whether the
     * reputation's own ranks changed (the engine's caches read them at start).
     */
    public record Plan(@Nonnull String id, @Nullable String memberGroupId, @Nullable JsonObject memberGroup,
            @Nonnull JsonObject group, @Nonnull JsonObject companion, @Nonnull Map<String, String> texts,
            @Nonnull Map<String, String> minted, boolean ranksChanged) {

        /** The files as this plan writes them, for the next save of the same editor to start from. */
        @Nonnull
        public Saved asSaved() {
            return new Saved(id, group, companion, memberGroupId, memberGroup);
        }
    }

    private ReputationDraft() {
    }

    // ---------------------------------------------------------------- field ids

    @Nonnull
    public static String killGroup(int n) {
        return "kill" + n + "Group";
    }

    @Nonnull
    public static String killAmount(int n) {
        return "kill" + n + "Amount";
    }

    @Nonnull
    public static String earn(int n) {
        return "earn" + n;
    }

    @Nonnull
    public static String rankName(int n) {
        return "rank" + n + "Name";
    }

    @Nonnull
    public static String rankFrom(int n) {
        return "rank" + n + "From";
    }

    /** A rank slot's id, minted once from its first name and never shown: the form keeps it, never the admin. */
    @Nonnull
    public static String rankId(int n) {
        return "rank" + n + "Id";
    }

    @Nonnull
    public static String item(int n) {
        return "beyondItem" + n;
    }

    @Nonnull
    public static String count(int n) {
        return "beyondCount" + n;
    }

    /** Every value the form fills from a saved reputation and clears for a new one ({@link #EDIT} aside). */
    @Nonnull
    public static List<String> formIds() {
        List<String> ids = new ArrayList<>(List.of(NAME, DESCRIPTION, ICON, EVERY, LOOT, ADD_MEMBER, REMOVE_MEMBER));
        for (int n = 1; n <= KILL_ROWS; n++) {
            ids.add(killGroup(n));
            ids.add(killAmount(n));
        }
        for (int n = 1; n <= EARN_LINES; n++) {
            ids.add(earn(n));
        }
        for (int n = 1; n <= RANK_ROWS; n++) {
            ids.add(rankName(n));
            ids.add(rankFrom(n));
            ids.add(rankId(n));
        }
        for (int n = 1; n <= ITEM_REWARDS; n++) {
            ids.add(item(n));
            ids.add(count(n));
        }
        return ids;
    }

    // ---------------------------------------------------------------- text keys

    /** The text key of {@code field} for reputation {@code id}: {@code reputations.<id>.<field>}. */
    @Nonnull
    public static String textKey(@Nonnull String id, @Nonnull String field) {
        return ServerContent.textKey(TEXT_STORE, id, field);
    }

    /** The registered key a file names for {@link #textKey}. */
    @Nonnull
    static String storedKey(@Nonnull String id, @Nonnull String field) {
        return ServerContent.storedKey(textKey(id, field));
    }

    /** A form number: null for blank or anything that is no whole number. */
    @Nullable
    public static Integer whole(@Nullable String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** True for a blank value or a whole number: what a number field may hold. */
    public static boolean blankOrWhole(@Nullable String raw) {
        return raw == null || raw.isBlank() || whole(raw) != null;
    }

    // ---------------------------------------------------------------- reading a saved reputation

    /** The form for {@code saved}: every value the editor shows, read back, and its characters. */
    @Nonnull
    public static Form read(@Nonnull Saved saved, @Nonnull Function<String, String> wordsOf) {
        String id = saved.id();
        Map<String, String> values = new LinkedHashMap<>();
        values.put(EDIT, id);
        JsonObject companion = saved.companion();
        JsonObject text = object(companion.get("Text"));
        putWords(values, NAME, text == null ? null : string(text.get("TitleKey")), storedKey(id, "name"), wordsOf);
        putWords(values, DESCRIPTION, text == null ? null : string(text.get("FlavorKey")),
                storedKey(id, "description"), wordsOf);
        putIfSet(values, ICON, string(companion.get("Icon")));

        Split split = split(saved, wordsOf);
        for (int i = 0; i < split.kills.size(); i++) {
            JsonObject row = split.kills.get(i);
            values.put(killGroup(i + 1), strings(row.get(NPC_GROUPS)).get(0));
            values.put(killAmount(i + 1), Integer.toString(number(row.get("Amount"))));
        }
        for (int n = 1; n <= EARN_LINES; n++) {
            String key = split.earnSlots[n - 1];
            if (key != null) {
                values.put(earn(n), wordsOf.apply(key));
            }
        }
        for (int i = 0; i < split.ranks.size(); i++) {
            Map.Entry<String, JsonObject> rank = split.ranks.get(i);
            String name = string(rank.getValue().get("Name"));
            values.put(rankName(i + 1), name == null ? "" : nonNull(wordsOf.apply(name)));
            values.put(rankFrom(i + 1), Integer.toString(number(rank.getValue().get("From"))));
            values.put(rankId(i + 1), rank.getKey());
        }
        JsonObject beyond = object(companion.get("Beyond"));
        if (beyond != null && isWholeNumber(beyond.get("Every"))) {
            values.put(EVERY, Integer.toString(number(beyond.get("Every"))));
        }
        for (int i = 0; i < split.items.size(); i++) {
            JsonObject params = object(split.items.get(i).get("Params"));
            values.put(item(i + 1), nonNull(string(params == null ? null : params.get("Item"))));
            Integer count = params == null ? null : countOf(params.get("Count"));
            values.put(count(i + 1), Integer.toString(count == null ? 1 : count));
        }
        if (split.loot != null) {
            JsonObject params = object(split.loot.get("Params"));
            putIfSet(values, LOOT, params == null ? null : string(params.get("Lootable")));
        }
        List<String> members = saved.memberGroup() == null ? List.of()
                : strings(saved.memberGroup().get(INCLUDE_ROLES));
        return new Form(values, new ArrayList<>(members));
    }

    /** The {@code NPCGroups} a native group's document names, trimmed, blanks dropped. */
    @Nonnull
    public static List<String> npcGroupsOf(@Nonnull JsonObject group) {
        return strings(group.get(NPC_GROUPS));
    }

    /**
     * Is {@code npcGroup} the name the editor gives reputation {@code id}'s member group: the id itself, or the id
     * with the {@code _2}, {@code _3} suffix a mint adds when another mod's NPC group holds the plain one (any case)?
     */
    public static boolean isMemberGroupOf(@Nonnull String id, @Nullable String npcGroup) {
        if (npcGroup == null) {
            return false;
        }
        String entry = npcGroup.trim().toLowerCase(Locale.ROOT);
        String own = id.trim().toLowerCase(Locale.ROOT);
        return entry.equals(own) || (entry.startsWith(own + "_") && entry.length() > own.length() + 1
                && entry.substring(own.length() + 1).chars().allMatch(Character::isDigit));
    }

    /** The saved companion's title key when it is another file's (so a blank name keeps it), else null. */
    @Nullable
    public static String foreignTitle(@Nonnull Saved saved) {
        JsonObject text = object(saved.companion().get("Text"));
        String key = text == null ? null : string(text.get("TitleKey"));
        return key == null || key.isBlank() || storedKey(saved.id(), "name").equals(key) ? null : key;
    }

    /** How many rows, lines and ranks of {@code saved} the form does not show and a save keeps as they are. */
    public static int keptCount(@Nonnull Saved saved, @Nonnull Function<String, String> wordsOf) {
        Split split = split(saved, wordsOf);
        return split.keptKills.size() + split.keptLines.size() + split.keptRanks + split.keptRewards.size();
    }

    // ---------------------------------------------------------------- planning a save

    /**
     * The files a save of the form writes for reputation {@code id}. {@code members} are the roles chosen;
     * {@code newMemberGroupId} names the member group to make when {@code saved} has none and somebody belongs.
     * {@code sharedRankIds} are the server's ranks, which a rank of its own never takes the id of.
     */
    @Nonnull
    public static Plan plan(@Nonnull String id, @Nonnull Map<String, String> values, @Nonnull Collection<String> members,
            @Nullable Saved saved, @Nullable String newMemberGroupId, @Nonnull Predicate<String> isRole,
            @Nonnull Predicate<String> npcGroupKnown, @Nonnull Collection<String> sharedRankIds,
            @Nonnull Function<String, String> wordsOf) {
        Map<String, String> texts = new LinkedHashMap<>();
        Map<String, String> minted = new LinkedHashMap<>();
        Split split = saved == null ? new Split() : split(saved, wordsOf);
        JsonObject companion = saved == null ? new JsonObject() : saved.companion().deepCopy();

        writeText(companion, id, values, texts);
        String icon = value(values, ICON);
        if (icon.isEmpty()) {
            companion.remove("Icon");
        } else {
            companion.addProperty("Icon", icon);
        }
        writeKills(companion, values, split, npcGroupKnown);
        writeEarn(companion, id, values, texts, split);
        Map<String, Integer> before = floors(object(companion.get("Ranks")));
        writeRanks(companion, id, values, texts, minted, split, sharedRankIds);
        boolean ranksChanged = !before.equals(floors(object(companion.get("Ranks"))));
        writeBeyond(companion, values, split);

        List<String> roles = ReputationMembers.knownRoles(members, isRole);
        String memberGroupId = saved != null && saved.memberGroupId() != null ? saved.memberGroupId()
                : roles.isEmpty() ? null : newMemberGroupId;
        JsonObject memberGroup = null;
        if (memberGroupId != null) {
            memberGroup = saved != null && saved.memberGroup() != null ? saved.memberGroup().deepCopy()
                    : new JsonObject();
            memberGroup.add(INCLUDE_ROLES, array(roles));
        }
        JsonObject group = saved == null ? new JsonObject() : saved.group().deepCopy();
        List<String> others = new ArrayList<>(strings(group.get(NPC_GROUPS)));
        if (memberGroupId != null) {
            others.removeIf(entry -> entry.trim().equalsIgnoreCase(memberGroupId));
        }
        List<String> named = new ArrayList<>();
        if (memberGroupId != null) {
            named.add(memberGroupId);
        }
        named.addAll(ReputationMembers.loadedGroups(others, npcGroupKnown));
        group.add(NPC_GROUPS, array(named));
        if (!group.has("InitialReputationValue")) {
            group.addProperty("InitialReputationValue", 0);
        }
        return new Plan(id, memberGroupId, memberGroup, group, companion, texts, minted, ranksChanged);
    }

    /** {@code Text}: the typed name and description as keys; a blank one drops a key of ours and keeps another's. */
    private static void writeText(@Nonnull JsonObject companion, @Nonnull String id, @Nonnull Map<String, String> values,
            @Nonnull Map<String, String> texts) {
        JsonObject text = object(companion.get("Text"));
        text = text == null ? new JsonObject() : text;
        textLeaf(text, "TitleKey", id, "name", value(values, NAME), texts);
        textLeaf(text, "FlavorKey", id, "description", value(values, DESCRIPTION), texts);
        if (text.size() == 0) {
            companion.remove("Text");
        } else {
            companion.add("Text", text);
        }
    }

    private static void textLeaf(@Nonnull JsonObject text, @Nonnull String leaf, @Nonnull String id,
            @Nonnull String field, @Nonnull String typed, @Nonnull Map<String, String> texts) {
        if (!typed.isEmpty()) {
            texts.put(textKey(id, field), typed);
            text.addProperty(leaf, storedKey(id, field));
        } else if (storedKey(id, field).equals(string(text.get(leaf)))) {
            text.remove(leaf);
        }
    }

    private static void writeKills(@Nonnull JsonObject companion, @Nonnull Map<String, String> values,
            @Nonnull Split split, @Nonnull Predicate<String> npcGroupKnown) {
        JsonArray rows = new JsonArray();
        for (int n = 1; n <= KILL_ROWS; n++) {
            String group = value(values, killGroup(n));
            Integer amount = whole(values.get(killAmount(n)));
            if (!group.isEmpty() && amount != null && amount != 0 && npcGroupKnown.test(group)) {
                JsonObject row = new JsonObject();
                row.add(NPC_GROUPS, array(List.of(group)));
                row.addProperty("Amount", amount);
                rows.add(row);
            }
        }
        split.keptKills.forEach(rows::add);
        if (rows.isEmpty()) {
            companion.remove("Kills");
        } else {
            companion.add("Kills", rows);
        }
    }

    private static void writeEarn(@Nonnull JsonObject companion, @Nonnull String id, @Nonnull Map<String, String> values,
            @Nonnull Map<String, String> texts, @Nonnull Split split) {
        JsonArray lines = new JsonArray();
        for (int n = 1; n <= EARN_LINES; n++) {
            String typed = value(values, earn(n));
            if (!typed.isEmpty()) {
                String field = "earn." + n;
                texts.put(textKey(id, field), typed);
                lines.add(storedKey(id, field));
            }
        }
        split.keptLines.forEach(lines::add);
        JsonObject earn = object(companion.get("Earn"));
        earn = earn == null ? new JsonObject() : earn;
        if (lines.isEmpty()) {
            earn.remove("Lines");
        } else {
            earn.add("Lines", lines);
        }
        if (earn.size() == 0) {
            companion.remove("Earn");
        } else {
            companion.add("Earn", earn);
        }
    }

    private static void writeRanks(@Nonnull JsonObject companion, @Nonnull String id, @Nonnull Map<String, String> values,
            @Nonnull Map<String, String> texts, @Nonnull Map<String, String> minted, @Nonnull Split split,
            @Nonnull Collection<String> sharedRankIds) {
        JsonObject ranks = object(companion.get("Ranks"));
        ranks = ranks == null ? new JsonObject() : ranks;
        for (Map.Entry<String, JsonObject> shown : split.ranks) {
            ranks.remove(shown.getKey());
        }
        Set<String> taken = new HashSet<>();
        for (String shared : sharedRankIds) {
            taken.add(shared.toLowerCase(Locale.ROOT));
        }
        for (String key : ranks.keySet()) {
            taken.add(key.toLowerCase(Locale.ROOT));
        }
        for (int n = 1; n <= RANK_ROWS; n++) {
            String name = value(values, rankName(n));
            Integer from = whole(values.get(rankFrom(n)));
            String rankId = value(values, rankId(n));
            if (from == null || (name.isEmpty() && rankId.isEmpty())) {
                if (!rankId.isEmpty()) {
                    minted.put(rankId(n), "");
                }
                continue;
            }
            if (rankId.isEmpty() || taken.contains(rankId.toLowerCase(Locale.ROOT))) {
                rankId = ServerContent.mintId(name, candidate -> taken.contains(candidate.toLowerCase(Locale.ROOT)));
                minted.put(rankId(n), rankId);
            }
            taken.add(rankId.toLowerCase(Locale.ROOT));
            JsonObject entry = new JsonObject();
            if (!name.isEmpty()) {
                String field = "rank." + rankId;
                texts.put(textKey(id, field), name);
                entry.addProperty("Name", storedKey(id, field));
            }
            entry.addProperty("From", from);
            ranks.add(rankId, entry);
        }
        if (ranks.size() == 0) {
            companion.remove("Ranks");
        } else {
            companion.add("Ranks", ranks);
        }
    }

    private static void writeBeyond(@Nonnull JsonObject companion, @Nonnull Map<String, String> values,
            @Nonnull Split split) {
        JsonObject beyond = object(companion.get("Beyond"));
        beyond = beyond == null ? new JsonObject() : beyond;
        Integer every = whole(values.get(EVERY));
        if (every == null || every <= 0) {
            beyond.remove("Every");
        } else {
            beyond.addProperty("Every", every);
        }
        JsonArray rewards = new JsonArray();
        for (int n = 1; n <= ITEM_REWARDS; n++) {
            String item = value(values, item(n));
            if (!item.isEmpty()) {
                Integer count = whole(values.get(count(n)));
                JsonObject params = new JsonObject();
                params.addProperty("Item", item);
                params.addProperty("Count", count == null || count < 1 ? 1 : count);
                rewards.add(reward(KIND_ITEM, params));
            }
        }
        String loot = value(values, LOOT);
        if (!loot.isEmpty()) {
            JsonObject params = new JsonObject();
            params.addProperty("Lootable", loot);
            rewards.add(reward(KIND_LOOTABLE, params));
        }
        split.keptRewards.forEach(rewards::add);
        if (rewards.isEmpty()) {
            beyond.remove("Rewards");
        } else {
            beyond.add("Rewards", rewards);
        }
        if (beyond.size() == 0) {
            companion.remove("Beyond");
        } else {
            companion.add("Beyond", beyond);
        }
    }

    @Nonnull
    private static JsonObject reward(@Nonnull String kind, @Nonnull JsonObject params) {
        JsonObject row = new JsonObject();
        row.addProperty("Kind", kind);
        row.add("Params", params);
        return row;
    }

    /** Each rank of its own in {@code ranks} (an entry with a whole {@code From}), lower-cased id to floor. */
    @Nonnull
    private static Map<String, Integer> floors(@Nullable JsonObject ranks) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (ranks != null) {
            for (Map.Entry<String, JsonElement> e : ranks.entrySet()) {
                JsonObject entry = object(e.getValue());
                if (entry != null && isWholeNumber(entry.get("From"))) {
                    out.put(e.getKey().toLowerCase(Locale.ROOT), number(entry.get("From")));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- what the form shows of a saved file

    /** A saved companion cut into what the form shows and what a save writes back as it was. */
    private static final class Split {

        final List<JsonObject> kills = new ArrayList<>();
        final List<JsonElement> keptKills = new ArrayList<>();
        final String[] earnSlots = new String[EARN_LINES];
        final List<JsonElement> keptLines = new ArrayList<>();
        final List<Map.Entry<String, JsonObject>> ranks = new ArrayList<>();
        int keptRanks;
        final List<JsonObject> items = new ArrayList<>();
        @Nullable JsonObject loot;
        final List<JsonElement> keptRewards = new ArrayList<>();
    }

    @Nonnull
    private static Split split(@Nonnull Saved saved, @Nonnull Function<String, String> wordsOf) {
        Split split = new Split();
        String id = saved.id();
        JsonObject companion = saved.companion();
        for (JsonElement row : elements(companion.get("Kills"))) {
            JsonObject kill = object(row);
            if (kill != null && split.kills.size() < KILL_ROWS && onlyKeys(kill, NPC_GROUPS, "Amount")
                    && strings(kill.get(NPC_GROUPS)).size() == 1 && elements(kill.get(NPC_GROUPS)).size() == 1
                    && isWholeNumber(kill.get("Amount"))) {
                split.kills.add(kill);
            } else {
                split.keptKills.add(row);
            }
        }
        JsonObject earn = object(companion.get("Earn"));
        for (JsonElement line : elements(earn == null ? null : earn.get("Lines"))) {
            int slot = earnSlot(id, string(line), wordsOf);
            if (slot > 0 && split.earnSlots[slot - 1] == null) {
                split.earnSlots[slot - 1] = string(line);
            } else {
                split.keptLines.add(line);
            }
        }
        JsonObject ranks = object(companion.get("Ranks"));
        if (ranks != null) {
            for (Map.Entry<String, JsonElement> e : ranks.entrySet()) {
                JsonObject rank = object(e.getValue());
                if (rank != null && split.ranks.size() < RANK_ROWS && shownRank(id, e.getKey(), rank, wordsOf)) {
                    split.ranks.add(Map.entry(e.getKey(), rank));
                } else if (!e.getKey().startsWith("$")) {
                    split.keptRanks++;
                }
            }
        }
        JsonObject beyond = object(companion.get("Beyond"));
        for (JsonElement row : elements(beyond == null ? null : beyond.get("Rewards"))) {
            JsonObject reward = object(row);
            if (reward != null && split.items.size() < ITEM_REWARDS && shownReward(reward, KIND_ITEM, "Item", "Count")) {
                split.items.add(reward);
            } else if (reward != null && split.loot == null && shownReward(reward, KIND_LOOTABLE, "Lootable")) {
                split.loot = reward;
            } else {
                split.keptRewards.add(row);
            }
        }
        return split;
    }

    /** The slot (1 to {@link #EARN_LINES}) a How to earn line of ours with words sits in, else 0. */
    private static int earnSlot(@Nonnull String id, @Nullable String line, @Nonnull Function<String, String> wordsOf) {
        if (line == null || wordsOf.apply(line) == null) {
            return 0;
        }
        for (int n = 1; n <= EARN_LINES; n++) {
            if (storedKey(id, "earn." + n).equals(line)) {
                return n;
            }
        }
        return 0;
    }

    /** A rank of its own the form shows: a whole {@code From}, and no name or a name of ours with words. */
    private static boolean shownRank(@Nonnull String id, @Nonnull String rankId, @Nonnull JsonObject rank,
            @Nonnull Function<String, String> wordsOf) {
        if (!onlyKeys(rank, "Name", "From") || !isWholeNumber(rank.get("From"))) {
            return false;
        }
        String name = string(rank.get("Name"));
        return name == null || (storedKey(id, "rank." + rankId).equals(name) && wordsOf.apply(name) != null);
    }

    /** A payout row the form shows: of {@code kind}, its {@code Params} holding only {@code params} with the first set. */
    private static boolean shownReward(@Nonnull JsonObject reward, @Nonnull String kind, @Nonnull String... params) {
        if (!onlyKeys(reward, "Kind", "Params") || !kind.equalsIgnoreCase(nonNull(string(reward.get("Kind"))))) {
            return false;
        }
        JsonObject p = object(reward.get("Params"));
        if (p == null || !onlyKeys(p, params) || nonNull(string(p.get(params[0]))).isBlank()) {
            return false;
        }
        return params.length < 2 || !p.has(params[1]) || countOf(p.get(params[1])) != null;
    }

    // ---------------------------------------------------------------- Gson helpers

    private static void putWords(@Nonnull Map<String, String> values, @Nonnull String field, @Nullable String key,
            @Nonnull String ours, @Nonnull Function<String, String> wordsOf) {
        if (ours.equals(key)) {
            putIfSet(values, field, wordsOf.apply(key));
        }
    }

    private static void putIfSet(@Nonnull Map<String, String> values, @Nonnull String field, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            values.put(field, value);
        }
    }

    @Nonnull
    private static String value(@Nonnull Map<String, String> values, @Nonnull String field) {
        String v = values.get(field);
        return v == null ? "" : v.trim();
    }

    @Nonnull
    private static String nonNull(@Nullable String s) {
        return s == null ? "" : s;
    }

    private static boolean onlyKeys(@Nonnull JsonObject object, @Nonnull String... allowed) {
        Set<String> ok = Set.of(allowed);
        for (String key : object.keySet()) {
            if (!ok.contains(key)) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static JsonObject object(@Nullable JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    @Nullable
    private static String string(@Nullable JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    @Nonnull
    private static List<JsonElement> elements(@Nullable JsonElement e) {
        List<JsonElement> out = new ArrayList<>();
        if (e != null && e.isJsonArray()) {
            e.getAsJsonArray().forEach(out::add);
        }
        return out;
    }

    /** The non-blank strings of an array, trimmed. */
    @Nonnull
    private static List<String> strings(@Nullable JsonElement e) {
        List<String> out = new ArrayList<>();
        for (JsonElement item : elements(e)) {
            String s = string(item);
            if (s != null && !s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static boolean isWholeNumber(@Nullable JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        double d = e.getAsDouble();
        return d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE;
    }

    private static int number(@Nullable JsonElement e) {
        return isWholeNumber(e) ? (int) e.getAsDouble() : 0;
    }

    /** A reward's {@code Count}, bare or in quotes as a reward's params decode it; null when it is not one of at least 1. */
    @Nullable
    private static Integer countOf(@Nullable JsonElement e) {
        Integer count = isWholeNumber(e) ? Integer.valueOf(number(e)) : whole(string(e));
        return count == null || count < 1 ? null : count;
    }

    @Nonnull
    private static JsonArray array(@Nonnull Collection<String> values) {
        JsonArray out = new JsonArray();
        for (String v : values) {
            out.add(new JsonPrimitive(v));
        }
        return out;
    }
}
