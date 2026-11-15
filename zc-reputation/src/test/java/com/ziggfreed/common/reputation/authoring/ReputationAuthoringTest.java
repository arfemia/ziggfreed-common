package com.ziggfreed.common.reputation.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ziggfreed.common.reputation.ReputationFixtures;
import com.ziggfreed.common.reputation.ReputationLadder;
import com.ziggfreed.common.reputation.ReputationNative;
import com.ziggfreed.common.reputation.asset.ReputationAsset;

/**
 * The reputation editor's two gates: a role the engine does not know, or one another reputation holds, never
 * reaches a written group, and the group always names only NPC groups the engine loaded (a bad entry throws on
 * every NPC spawn); and the files one save writes decode as the form says and keep what the form does not show.
 */
class ReputationAuthoringTest {

    private static final String ID = "Miners_Guild";
    private static final Set<String> ROLES = Set.of("Guard_Miner", "Miner_Foreman", "Test_Old_Jack_Role");
    private static final Set<String> LOADED_GROUPS = Set.of("Goblin", "Test_Old_Jack", ID);
    private static final Predicate<String> IS_ROLE = ROLES::contains;
    private static final Predicate<String> LOADED = LOADED_GROUPS::contains;
    private static final List<String> SHARED = ReputationFixtures.LADDER.stream().map(ReputationLadder.Rank::id)
            .toList();

    @AfterEach
    void clear() {
        ReputationFixtures.reset();
    }

    @Test
    void aBadRoleNeverReachesTheGroup() {
        List<ReputationNative.Group> groups = List.of(
                new ReputationNative.Group("Test_Old_Jack", 0, List.of("Test_Old_Jack")));
        List<ReputationMembers.Problem> problems = ReputationMembers.problems(
                List.of("Guard_Miner", "Not_A_Role", "Test_Old_Jack_Role", "guard_miner"), ID, IS_ROLE, groups, LOADED,
                (role, group) -> role.equals("Test_Old_Jack_Role") && group.equals("Test_Old_Jack"));
        assertEquals(List.of(
                new ReputationMembers.Problem("Not_A_Role", ReputationMembers.Kind.UNKNOWN_ROLE, null),
                new ReputationMembers.Problem("Test_Old_Jack_Role", ReputationMembers.Kind.OTHER_REPUTATION,
                        "Test_Old_Jack")), problems, "an unknown role and one another reputation holds, each once");

        ReputationDraft.Plan plan = ReputationDraft.plan(ID, Map.of(ReputationDraft.NAME, "Miners Guild"),
                List.of("Guard_Miner", "Not_A_Role"), null, ID, IS_ROLE, LOADED, SHARED, key -> null);
        assertEquals(List.of("Guard_Miner"), rolesOf(plan.memberGroup()),
                "the member group includes only roles the engine knows");
        assertEquals(List.of(ID), ReputationDraft.npcGroupsOf(plan.group()), "the group names its member group");

        ReputationDraft.Saved saved = new ReputationDraft.Saved(ID,
                json("{ \"NPCGroups\": [ \"Miners_Guild\", \"Gone_Group\", \"Goblin\" ], \"InitialReputationValue\": 500 }"),
                json("{}"), ID, json("{ \"IncludeRoles\": [ \"Guard_Miner\" ], \"ExcludeRoles\": [ \"Miner_Foreman\" ] }"));
        ReputationDraft.Plan edit = ReputationDraft.plan(ID, Map.of(ReputationDraft.NAME, "Miners Guild"),
                List.of("Guard_Miner", "Miner_Foreman"), saved, null, IS_ROLE, LOADED, SHARED, key -> null);
        assertEquals(List.of(ID, "Goblin"), ReputationDraft.npcGroupsOf(edit.group()),
                "an NPC group the engine has not loaded never reaches a written group");
        assertEquals(500, edit.group().get("InitialReputationValue").getAsInt(), "the group's own leaves stay");
        assertTrue(edit.memberGroup().has("ExcludeRoles"), "the member group's own leaves stay");

        ReputationDraft.Plan nobody = ReputationDraft.plan(ID, Map.of(ReputationDraft.NAME, "Miners Guild"),
                List.of(), null, null, IS_ROLE, LOADED, SHARED, key -> null);
        assertNull(nobody.memberGroup(), "nobody belongs: no member group is made");
        assertTrue(nobody.group().get("NPCGroups").isJsonArray(), "NPCGroups is written even when empty");
        assertEquals(List.of(), ReputationDraft.npcGroupsOf(nobody.group()));
    }

    @Test
    void theWrittenCompanionDecodesAsTheFormSaysAndReadsBack() {
        Map<String, String> values = new HashMap<>();
        values.put(ReputationDraft.NAME, "Miners Guild");
        values.put(ReputationDraft.DESCRIPTION, "The folk who dig the deep roads.");
        values.put(ReputationDraft.ICON, "Tool_Pickaxe_Iron");
        values.put(ReputationDraft.killGroup(1), "Goblin");
        values.put(ReputationDraft.killAmount(1), "5");
        values.put(ReputationDraft.killGroup(2), "Not_Loaded");
        values.put(ReputationDraft.killAmount(2), "3");
        values.put(ReputationDraft.earn(1), "Finish the guild's quests");
        values.put(ReputationDraft.rankName(1), "Trusted Partner");
        values.put(ReputationDraft.rankFrom(1), "30000");
        values.put(ReputationDraft.EVERY, "5000");
        values.put(ReputationDraft.item(1), "Ingredient_Bar_Gold");
        values.put(ReputationDraft.count(1), "3");
        values.put(ReputationDraft.LOOT, "Miners_Cache");

        ReputationDraft.Plan plan = ReputationDraft.plan(ID, values, List.of("Guard_Miner"), null, ID, IS_ROLE,
                LOADED, SHARED, key -> null);
        assertTrue(plan.ranksChanged(), "a rank of its own is new: it applies after a restart");
        assertEquals("Trusted_Partner", plan.minted().get(ReputationDraft.rankId(1)), "a rank's id is minted once");
        assertEquals("Miners Guild", plan.texts().get("reputations.Miners_Guild.name"));
        assertEquals("Trusted Partner", plan.texts().get("reputations.Miners_Guild.rank.Trusted_Partner"));

        ReputationAsset companion = ReputationFixtures.companion(ID, plan.companion().toString());
        assertEquals("server.reputations.Miners_Guild.name", companion.titleKey());
        assertEquals("server.reputations.Miners_Guild.description", companion.flavorKey());
        assertEquals("Tool_Pickaxe_Iron", companion.icon());
        assertEquals(1, companion.kills().size(), "a kill row naming an unloaded NPC group is left out");
        assertEquals(List.of("Goblin"), companion.kills().get(0).npcGroups());
        assertEquals(5, companion.kills().get(0).amount());
        assertEquals(Map.of("Trusted_Partner", 30000), companion.tierFloors());
        assertEquals("server.reputations.Miners_Guild.rank.Trusted_Partner", companion.rankNameKey("Trusted_Partner"));
        assertEquals(List.of("server.reputations.Miners_Guild.earn.1"), companion.earnKeys());
        assertEquals(5000, companion.beyondEvery());
        assertEquals(List.of("Item", "Lootable"), companion.beyondRewards().stream().map(r -> r.kind()).toList());

        Map<String, String> words = new HashMap<>();
        plan.texts().forEach((key, english) -> words.put("server." + key, english));
        ReputationDraft.Form form = ReputationDraft.read(plan.asSaved(), words::get);
        Map<String, String> back = new HashMap<>(form.values());
        back.remove(ReputationDraft.EDIT);
        Map<String, String> expected = new HashMap<>(values);
        expected.remove(ReputationDraft.killGroup(2));
        expected.remove(ReputationDraft.killAmount(2));
        expected.put(ReputationDraft.rankId(1), "Trusted_Partner");
        assertEquals(expected, back, "a saved reputation reads back as the form that saved it");
        assertEquals(List.of("Guard_Miner"), form.members());
        assertEquals(0, ReputationDraft.keptCount(plan.asSaved(), words::get));
    }

    @Test
    void anEditKeepsWhatTheFormDoesNotShow() {
        JsonObject companion = json("""
                { "Text": { "TitleKey": "server.reputations.Miners_Guild.name" },
                  "Cap": 40000,
                  "Prestige": { "Ranks": 1 },
                  "Ranks": { "Friendly": { "Name": "yourpack.rank.friendly" },
                             "Trusted": { "Name": "server.reputations.Miners_Guild.rank.Trusted", "From": 30000 } },
                  "Kills": [ { "NPCGroups": [ "Goblin" ], "Amount": 2 },
                             { "NPCGroups": [ "Goblin" ], "Amount": 4, "Season": "Hallows_Eve" } ],
                  "Earn": { "Lines": [ "yourpack.earn.contracts" ] },
                  "Beyond": { "Every": 1000, "Collect": false,
                              "Rewards": [ { "Kind": "Boost", "Params": { "Boost": "Xp" } } ] } }
                """);
        ReputationDraft.Saved saved = new ReputationDraft.Saved(ID, json("{ \"NPCGroups\": [] }"), companion, null,
                null);
        Function<String, String> words = Map.of("server.reputations.Miners_Guild.name", "Miners Guild",
                "server.reputations.Miners_Guild.rank.Trusted", "Trusted")::get;
        assertEquals(4, ReputationDraft.keptCount(saved, words),
                "the shared rank's own name, the seasonal row, another file's line and the boost payout");

        ReputationDraft.Form form = ReputationDraft.read(saved, words);
        ReputationDraft.Plan plan = ReputationDraft.plan(ID, form.values(), form.members(), saved, null, IS_ROLE,
                LOADED, SHARED, words);
        assertFalse(plan.ranksChanged(), "an unchanged rank of its own needs no restart");
        assertTrue(plan.minted().isEmpty(), "a kept rank keeps its id");
        JsonObject written = plan.companion();
        assertEquals(40000, written.get("Cap").getAsInt());
        assertTrue(written.has("Prestige"));
        assertEquals("yourpack.rank.friendly", written.getAsJsonObject("Ranks").getAsJsonObject("Friendly")
                .get("Name").getAsString());
        assertEquals(30000, written.getAsJsonObject("Ranks").getAsJsonObject("Trusted").get("From").getAsInt());
        assertEquals(2, written.getAsJsonArray("Kills").size(), "the seasonal row is written back");
        assertEquals("Hallows_Eve", written.getAsJsonArray("Kills").get(1).getAsJsonObject().get("Season")
                .getAsString());
        assertEquals("yourpack.earn.contracts", written.getAsJsonObject("Earn").getAsJsonArray("Lines").get(0)
                .getAsString());
        JsonObject beyond = written.getAsJsonObject("Beyond");
        assertFalse(beyond.get("Collect").getAsBoolean(), "a Beyond leaf the form does not show stays");
        assertEquals("Boost", beyond.getAsJsonArray("Rewards").get(0).getAsJsonObject().get("Kind").getAsString());
        assertEquals(List.of(), ReputationDraft.npcGroupsOf(plan.group()));
    }

    private static JsonObject json(String text) {
        JsonElement doc = JsonParser.parseString(text);
        return doc.getAsJsonObject();
    }

    private static List<String> rolesOf(JsonObject memberGroup) {
        List<String> out = new ArrayList<>();
        memberGroup.getAsJsonArray("IncludeRoles").forEach(e -> out.add(e.getAsString()));
        return out;
    }
}
