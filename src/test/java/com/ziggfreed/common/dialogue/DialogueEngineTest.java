package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ziggfreed.common.dialogue.page.DialogueEventData;
import com.ziggfreed.common.dialogue.template.DialogueTemplateResolver;

/**
 * Build-time guards + pure end-to-end checks for the generic dialogue engine.
 * No server needed: codecs, sugar, and the template resolver are all pure.
 */
class DialogueEngineTest {

    /**
     * Force class-init of every static codec so a lowercase PascalCase field name
     * (rejected by the KeyedCodec ctor at static init) fails the build, not the
     * live server.
     */
    @Test
    void allCodecsInitialize() {
        assertNotNull(DialogueAction.Goto.CODEC);
        assertNotNull(DialogueAction.Close.CODEC);
        assertNotNull(DialogueAction.SetFlag.CODEC);
        assertNotNull(DialogueAction.Talk.CODEC);
        assertNotNull(DialogueAction.OpenPage.CODEC);
        assertNotNull(DialogueCondition.Flag.CODEC);
        assertNotNull(DialogueCondition.NotFlag.CODEC);
        assertNotNull(DialogueTextParam.CODEC);
        assertNotNull(DialogueEventData.CODEC);
    }

    private static DialogueEngine engine() {
        return DialogueEngine.builder().warn(m -> { }).build();
    }

    @Test
    void engineBuildsAndExposesCodec() {
        DialogueEngine engine = engine();
        assertNotNull(engine.dialogueCodec());
        assertNotNull(engine.executor());
        assertNotNull(engine.sugar());
    }

    @Test
    void decodesCanonicalTree() {
        DialogueEngine engine = engine();
        String json = "{\"Start\":[{\"Node\":\"greet\"}],\"Nodes\":{\"greet\":{\"Text\":\"hi\","
                + "\"Options\":[{\"Label\":\"bye\",\"Actions\":[{\"Type\":\"Close\"}]},"
                + "{\"Label\":\"more\",\"Actions\":[{\"Type\":\"Goto\",\"Node\":\"greet\"}]}]}}}";
        NpcDialogue d = engine.decode("test", json);
        assertNotNull(d);
        assertEquals("test", d.getId());
        assertEquals(1, d.getNodes().size());
        DialogueNode node = d.getNode("greet");
        assertNotNull(node);
        assertEquals(2, node.getOptions().size());
        assertTrue(node.getOptions().get(0).getActions().get(0) instanceof DialogueAction.Close);
        DialogueAction second = node.getOptions().get(1).getActions().get(0);
        assertTrue(second instanceof DialogueAction.Goto);
        assertEquals("greet", ((DialogueAction.Goto) second).getNode());
    }

    @Test
    void decodesNodeTextParams() {
        DialogueEngine engine = engine();
        String json = "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Text\":\"still {0}, the {1} comes\","
                + "\"TextParams\":[{\"Key\":\"word.green\",\"Color\":\"#7affa0\"},"
                + "{\"Text\":\"Void\",\"Color\":\"#a06cff\",\"Italic\":true}],\"Options\":[]}}}";
        NpcDialogue d = engine.decode("tp", json);
        assertNotNull(d);
        DialogueNode node = d.getNode("g");
        assertNotNull(node);
        assertEquals(2, node.getTextParams().size());
        DialogueTextParam p0 = node.getTextParams().get(0);
        assertEquals("word.green", p0.getKey());
        assertEquals("#7affa0", p0.getColor());
        DialogueTextParam p1 = node.getTextParams().get(1);
        assertEquals("Void", p1.getText());
        assertEquals("#a06cff", p1.getColor());
        assertEquals(Boolean.TRUE, p1.getItalic());
    }

    @Test
    void decodesTypeListConditions() {
        DialogueEngine engine = engine();
        String json = "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Options\":[{\"Label\":\"x\","
                + "\"Conditions\":[{\"Type\":\"Flag\",\"Flag\":\"met\"},{\"Type\":\"NotFlag\",\"Flag\":\"done\"}],"
                + "\"Actions\":[{\"Type\":\"Close\"}]}]}}}";
        NpcDialogue d = engine.decode("c", json);
        assertNotNull(d);
        DialogueOption opt = d.getNode("g").getOptions().get(0);
        assertTrue(opt.hasConditions());
        assertEquals(2, opt.getConditions().size());
        assertTrue(opt.getConditions().get(0) instanceof DialogueCondition.Flag);
        assertTrue(opt.getConditions().get(1) instanceof DialogueCondition.NotFlag);
    }

    @Test
    void sugarExpandsInBareOrder() {
        DialogueEngine engine = engine();
        JsonObject body = JsonParser.parseString(
                "{\"Nodes\":{\"g\":{\"Options\":[{\"Talk\":\"\",\"Goto\":\"next\",\"Close\":true}]}}}")
                .getAsJsonObject();
        engine.sugar().desugar(body);
        JsonArray actions = body.getAsJsonObject("Nodes").getAsJsonObject("g")
                .getAsJsonArray("Options").get(0).getAsJsonObject().getAsJsonArray("Actions");
        // Bare order: Talk(10) < Goto(60) < Close(70).
        assertEquals(3, actions.size());
        assertEquals("Talk", actions.get(0).getAsJsonObject().get("Type").getAsString());
        assertEquals("Goto", actions.get(1).getAsJsonObject().get("Type").getAsString());
        assertEquals("Close", actions.get(2).getAsJsonObject().get("Type").getAsString());
        // Sugar keys stripped.
        assertFalse(body.getAsJsonObject("Nodes").getAsJsonObject("g")
                .getAsJsonArray("Options").get(0).getAsJsonObject().has("Goto"));
    }

    @Test
    void templateSubstitutesParams() {
        DialogueEngine engine = engine();
        Map<String, JsonObject> templates = Map.of("base", JsonParser.parseString(
                "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Text\":\"{{greeting}}\",\"Options\":[]}}}")
                .getAsJsonObject());
        JsonObject body = JsonParser.parseString("{\"extends\":\"base\",\"params\":{\"greeting\":\"hello\"}}")
                .getAsJsonObject();
        JsonObject resolved = DialogueTemplateResolver.resolve("t", body, templates, engine.sugar(), m -> { });
        assertEquals("hello", resolved.getAsJsonObject("Nodes").getAsJsonObject("g").get("Text").getAsString());
    }

    @Test
    void templatePrunesEmptyParamBranch() {
        DialogueEngine engine = engine();
        Map<String, JsonObject> templates = Map.of("base", JsonParser.parseString(
                "{\"Start\":[{\"Node\":\"a\",\"PruneIfEmpty\":\"questId\"},{\"Node\":\"b\"}],"
                        + "\"Nodes\":{\"a\":{\"Options\":[]},\"b\":{\"Options\":[]}}}")
                .getAsJsonObject());
        JsonObject body = JsonParser.parseString("{\"extends\":\"base\",\"params\":{}}").getAsJsonObject();
        JsonObject resolved = DialogueTemplateResolver.resolve("t", body, templates, engine.sugar(), m -> { });
        // questId omitted -> candidate "a" pruned, node "a" now unreachable -> dropped.
        assertEquals(1, resolved.getAsJsonArray("Start").size());
        assertEquals("b", resolved.getAsJsonArray("Start").get(0).getAsJsonObject().get("Node").getAsString());
        assertFalse(resolved.getAsJsonObject("Nodes").has("a"));
        assertTrue(resolved.getAsJsonObject("Nodes").has("b"));
        // The marker is stripped from survivors.
        assertFalse(resolved.getAsJsonArray("Start").get(0).getAsJsonObject().has("PruneIfEmpty"));
    }
}
