package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.ziggfreed.common.dialogue.page.DialogueEventData;

/**
 * Build-time guards + pure end-to-end checks for the generic dialogue engine.
 * No server needed: codecs, sugar, and native Parent inheritance are all pure.
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
        assertNotNull(DialogueOption.Presentation.CODEC);
        assertNotNull(DialogueOption.Icon.CODEC);
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
    void nativeParentMergesNodesByKey() {
        DialogueEngine engine = engine();
        NpcDialogue parent = engine.decode("base",
                "{\"Start\":[{\"Node\":\"greet\"}],\"Nodes\":{"
                        + "\"greet\":{\"Text\":\"base greet\",\"Options\":[{\"Label\":\"a\"}]},"
                        + "\"bye\":{\"Text\":\"base bye\",\"Options\":[]}}}");
        assertNotNull(parent);
        // Child overrides greet's TEXT only (keeps its options), adds a node, omits bye (inherits it).
        NpcDialogue child = engine.decodeWithParent("kid",
                "{\"Nodes\":{\"greet\":{\"Text\":\"kid greet\"},\"extra\":{\"Text\":\"new\",\"Options\":[]}}}",
                parent);
        assertNotNull(child);
        assertEquals(3, child.getNodes().size());
        assertEquals("kid greet", child.getNode("greet").getText());          // overridden field
        assertEquals(1, child.getNode("greet").getOptions().size());          // sibling field inherited
        assertNotNull(child.getNode("bye"));                                  // parent-only node retained
        assertEquals("base bye", child.getNode("bye").getText());
        assertNotNull(child.getNode("extra"));                               // child-added node
    }

    @Test
    void childOmittingNodesInheritsParent() {
        DialogueEngine engine = engine();
        NpcDialogue parent = engine.decode("base",
                "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Text\":\"p\",\"Options\":[]}}}");
        assertNotNull(parent);
        // Child provides only Start; omitting Nodes entirely inherits the parent's node map.
        NpcDialogue child = engine.decodeWithParent("kid", "{\"Start\":[{\"Node\":\"g\"}]}", parent);
        assertNotNull(child);
        assertNotNull(child.getNode("g"));
        assertEquals("p", child.getNode("g").getText());
    }

    @Test
    void decodesBooleanCombinators() {
        DialogueEngine engine = engine();
        String json = "{\"Start\":[{\"Node\":\"g\",\"Conditions\":[{\"Type\":\"AnyOf\",\"Any\":["
                + "{\"Type\":\"Flag\",\"Flag\":\"a\"},{\"Type\":\"NotFlag\",\"Flag\":\"b\"}]}]}],"
                + "\"Nodes\":{\"g\":{\"Conditions\":[{\"Type\":\"Not\",\"Of\":[{\"Type\":\"Flag\",\"Flag\":\"c\"}]}],"
                + "\"Options\":[]}}}";
        NpcDialogue d = engine.decode("combo", json);
        assertNotNull(d);
        DialogueCondition start = d.getStart().get(0).getConditions().get(0);
        assertTrue(start instanceof DialogueCondition.AnyOf);
        assertEquals(2, ((DialogueCondition.AnyOf) start).getChildren().size());
        DialogueCondition nodeCond = d.getNode("g").getConditions().get(0);
        assertTrue(nodeCond instanceof DialogueCondition.Not);
        assertTrue(d.getNode("g").hasConditions());
    }

    @Test
    void decodesOptionPresentation() {
        DialogueEngine engine = engine();
        String json = "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Options\":[{\"Label\":\"x\","
                + "\"Presentation\":{\"Color\":\"#5ab0ff\",\"Icon\":{\"Item\":\"hytale:iron_sword\"}}}]}}}";
        NpcDialogue d = engine.decode("pres", json);
        assertNotNull(d);
        DialogueOption.Presentation p = d.getNode("g").getOptions().get(0).getPresentation();
        assertNotNull(p);
        assertEquals("#5ab0ff", p.getColor());
        assertNotNull(p.getIcon());
        assertEquals("hytale:iron_sword", p.getIcon().getItem());
    }
}
