package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.page.DialogueEventData;

/**
 * Build-time guards + pure end-to-end checks for the generic dialogue engine.
 * No server needed: codecs, sugar, and native Parent inheritance are all pure.
 */
class DialogueEngineTest {

    /** The decode vocabulary is process-wide; start every test from a clean one. */
    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    /**
     * Force class-init of every static codec so a lowercase PascalCase field name
     * (rejected by the KeyedCodec ctor at static init) fails the build, not the
     * live server.
     */
    @Test
    void allCodecsInitialize() {
        assertNotNull(DialogueAction.Goto.CODEC);
        assertNotNull(DialogueAction.Close.CODEC);
        assertNotNull(DialogueAction.Remember.CODEC);
        assertNotNull(DialogueAction.Forget.CODEC);
        assertNotNull(DialogueAction.MarkTalked.CODEC);
        assertNotNull(DialogueAction.OpenPage.CODEC);
        assertNotNull(DialogueCondition.Remembered.CODEC);
        assertNotNull(DialogueCondition.NotRemembered.CODEC);
        assertNotNull(DialogueCondition.World.CODEC);
        assertNotNull(DialogueFlagScope.CODEC);
        assertNotNull(DialogueOnce.CODEC);
        assertNotNull(DialogueMemory.CODEC);
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
                + "\"Conditions\":[{\"Type\":\"Remembered\",\"Memory\":\"met\"},"
                + "{\"Type\":\"NotRemembered\",\"Memory\":\"done\"}],"
                + "\"Actions\":[{\"Type\":\"Close\"}]}]}}}";
        NpcDialogue d = engine.decode("c", json);
        assertNotNull(d);
        DialogueOption opt = d.getNode("g").getOptions().get(0);
        assertTrue(opt.hasConditions());
        assertEquals(2, opt.getConditions().size());
        assertTrue(opt.getConditions().get(0) instanceof DialogueCondition.Remembered);
        assertTrue(opt.getConditions().get(1) instanceof DialogueCondition.NotRemembered);
    }

    @Test
    void sugarFoldsInBareOrder() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("sugar",
                "{\"Memories\":{\"met\":{}},"
                        + "\"Nodes\":{\"g\":{\"Options\":[{\"Close\":true,\"Goto\":\"next\",\"Remember\":\"met\"}]}}}");
        assertNotNull(d);
        List<DialogueAction> actions = d.getNode("g").getOptions().get(0).getActions();
        // Authored deliberately backwards: bare keys fold by their fixed order, never by the order
        // they were written in. Remember(32) < Goto(60) < Close(70).
        assertEquals(3, actions.size());
        assertTrue(actions.get(0) instanceof DialogueAction.Remember);
        assertTrue(actions.get(1) instanceof DialogueAction.Goto);
        assertTrue(actions.get(2) instanceof DialogueAction.Close);
        assertEquals("next", ((DialogueAction.Goto) actions.get(1)).getNode());
    }

    @Test
    void doAtomsFoldInArrayOrderAndShadowTheBareKeys() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("sugar",
                "{\"Nodes\":{\"g\":{\"Options\":[{\"Goto\":\"ignored\",\"Do\":["
                        + "{\"Close\":true},{\"Goto\":\"next\"}]}]}}}");
        assertNotNull(d);
        List<DialogueAction> actions = d.getNode("g").getOptions().get(0).getActions();
        // Array order wins over the registration order, and the bare Goto never runs.
        assertEquals(2, actions.size());
        assertTrue(actions.get(0) instanceof DialogueAction.Close);
        assertEquals("next", ((DialogueAction.Goto) actions.get(1)).getNode());
    }

    @Test
    void authoredActionsRunBeforeTheShorthand() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("sugar",
                "{\"Nodes\":{\"g\":{\"Options\":[{\"Actions\":[{\"Type\":\"MarkTalked\","
                        + "\"Target\":\"elder\"}],\"Goto\":\"next\"}]}}}");
        assertNotNull(d);
        List<DialogueAction> actions = d.getNode("g").getOptions().get(0).getActions();
        assertEquals(2, actions.size());
        assertEquals("elder", ((DialogueAction.MarkTalked) actions.get(0)).getTarget());
        assertTrue(actions.get(1) instanceof DialogueAction.Goto);
    }

    @Test
    void anUnknownTypeFailsLoudlyRatherThanBeingDropped() {
        DialogueEngine engine = engine();
        assertNull(engine.decode("bad",
                "{\"Nodes\":{\"g\":{\"Options\":[{\"Actions\":[{\"Type\":\"NoSuchAction\"}]}]}}}"),
                "a Type nothing registered must fail the whole read, never silently vanish");
        assertNull(engine.decode("bad2",
                "{\"Start\":[{\"Node\":\"g\",\"Conditions\":[{\"Type\":\"NoSuchCondition\"}]}],"
                        + "\"Nodes\":{\"g\":{\"Options\":[]}}}"));
    }

    @Test
    void onceAcceptsBothTheFlagAndTheGroupForm() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("once",
                "{\"Start\":[{\"Node\":\"g\",\"Once\":true},{\"Node\":\"g\",\"Once\":"
                        + "{\"WorldSelector\":\"temple\"}},{\"Node\":\"g\",\"Once\":false}],"
                        + "\"Nodes\":{\"g\":{\"Options\":[]}}}");
        assertNotNull(d);
        assertNotNull(d.getStart().get(0).getOnce());
        assertNull(d.getStart().get(0).getOnce().getWorldSelector());
        assertEquals("temple", d.getStart().get(1).getOnce().getWorldSelector());
        assertNull(d.getStart().get(2).getOnce(), "false means there is no Once at all");
    }

    @Test
    void fragmentsAreSplicedAfterTheNodesOwnOptions() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("frag",
                "{\"Fragments\":{\"footer\":[{\"LabelKey\":\"bye\",\"Close\":true}]},"
                        + "\"Start\":[{\"Node\":\"g\"}],"
                        + "\"Nodes\":{\"g\":{\"Options\":[{\"LabelKey\":\"a\"}],"
                        + "\"IncludeOptions\":[\"footer\"]}}}");
        assertNotNull(d);
        List<DialogueOption> options = d.getNode("g").getOptions();
        assertEquals(2, options.size());
        assertEquals("a", options.get(0).getLabelKey());
        assertEquals("bye", options.get(1).getLabelKey());
        assertTrue(options.get(1).closesDialogue());
    }

    @Test
    void nativeParentMergesNodesByKey() throws Exception {
        DialogueEngine engine = engine();
        NpcDialogue parent = engine.decode("base",
                "{\"Start\":[{\"Node\":\"greet\"}],\"Nodes\":{"
                        + "\"greet\":{\"Text\":\"base greet\",\"Options\":[{\"Label\":\"a\"}]},"
                        + "\"bye\":{\"Text\":\"base bye\",\"Options\":[]}}}");
        assertNotNull(parent);
        // Child overrides greet's TEXT only (keeps its options), adds a node, omits bye (inherits it).
        NpcDialogue child = DialogueTestSupport.decodeWithParent(engine, "kid",
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
    void childOmittingNodesInheritsParent() throws Exception {
        DialogueEngine engine = engine();
        NpcDialogue parent = engine.decode("base",
                "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Text\":\"p\",\"Options\":[]}}}");
        assertNotNull(parent);
        // Child provides only Start; omitting Nodes entirely inherits the parent's node map.
        NpcDialogue child = DialogueTestSupport.decodeWithParent(engine, "kid", "{\"Start\":[{\"Node\":\"g\"}]}", parent);
        assertNotNull(child);
        assertNotNull(child.getNode("g"));
        assertEquals("p", child.getNode("g").getText());
    }

    @Test
    void decodesBooleanCombinators() {
        DialogueEngine engine = engine();
        String json = "{\"Start\":[{\"Node\":\"g\",\"Conditions\":[{\"Type\":\"AnyOf\",\"Any\":["
                + "{\"Type\":\"Remembered\",\"Memory\":\"a\"},{\"Type\":\"NotRemembered\",\"Memory\":\"b\"}]}]}],"
                + "\"Nodes\":{\"g\":{\"Conditions\":[{\"Type\":\"Not\",\"Of\":[{\"Type\":\"Remembered\","
                + "\"Memory\":\"c\"}]}],\"Options\":[]}}}";
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
