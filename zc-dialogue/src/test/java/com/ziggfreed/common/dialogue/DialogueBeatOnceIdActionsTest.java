package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.ArraySchema;
import com.hypixel.hytale.codec.schema.config.ObjectSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.ziggfreed.common.dialogue.schema.DialogueStart;
import com.ziggfreed.common.dialogue.schema.DialogueTypeTable;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.type.DialogueAction;
import com.ziggfreed.common.dialogue.type.DialogueActionExecutor;
import com.ziggfreed.common.dialogue.type.DialogueActionType;
import com.ziggfreed.common.dialogue.type.DialogueCondition;
import com.ziggfreed.common.dialogue.type.DialogueConditionType;
import com.ziggfreed.common.dialogue.validate.DialogueStructureValidator;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * A {@code Start} beat's {@code OnceId} and {@code Actions}: beats naming one OnceId share one claim (so
 * several wordings of one daily greeting are spent together, and a daily one turns over), a beat's
 * Actions travel with its resolution and run only while a claim is pending, and the audit reports either
 * leaf on a beat with no Once.
 */
class DialogueBeatOnceIdActionsTest {

    private static final AtomicInteger PAID = new AtomicInteger();
    private static final AtomicBoolean WARM = new AtomicBoolean();

    /** A payout stand-in: counts each run. */
    public static final class Pay extends DialogueAction {
        public static final BuilderCodec<Pay> CODEC = BuilderCodec.builder(Pay.class, Pay::new).build();
    }

    /** A rank stand-in: passes while {@link #WARM} is set. */
    public static final class Warm extends DialogueCondition {
        public static final BuilderCodec<Warm> CODEC = BuilderCodec.builder(Warm.class, Warm::new).build();
    }

    /** Two wordings of one daily greeting, the warmer first, both filed under the plain screen's claim. */
    private static final String JACK = """
            { "Start": { "First": [
                { "Node": "greet_warm", "When": [ { "Type": "Test_Warm" } ],
                  "Once": { "Period": "Daily" }, "OnceId": "board_fresh", "Actions": [ { "Type": "Test_Pay" } ] },
                { "Node": "board_fresh",
                  "Once": { "Period": "Daily" }, "OnceId": "board_fresh", "Actions": [ { "Type": "Test_Pay" } ] } ],
                "Fallback": "menu" },
              "Nodes": {
                "greet_warm": { "Options": [ { "LabelKey": "warm.back", "Goto": "menu" } ] },
                "board_fresh": { "Options": [ { "LabelKey": "plain.back", "Goto": "menu" } ] },
                "menu": { "Options": [] } } }
            """;

    private final long[] now = {at("2026-10-31T12:00:00Z")};

    @BeforeEach
    void reset() {
        DialogueTestSupport.reset();
        PAID.set(0);
        WARM.set(false);
    }

    @Nonnull
    private DialogueEngine engine() {
        return DialogueEngine.builder().warn(m -> { }).clock(() -> now[0])
                .action(DialogueActionType.of("Test_Pay", Pay.class, Pay.CODEC,
                        (Pay a, DialogueExecContext ctx, DialogueActionExecutor.Mut out) -> PAID.incrementAndGet()))
                .condition(DialogueConditionType.of("Test_Warm", Warm.class, Warm.CODEC,
                        (Warm c, DialogueContext ctx) -> WARM.get()))
                .build();
    }

    private static long at(@Nonnull String instant) {
        return Instant.parse(instant).toEpochMilli();
    }

    private static long day(int year, int month, int dayOfMonth) {
        return LocalDate.of(year, month, dayOfMonth).toEpochDay();
    }

    @Test
    void bothLeavesDecode() {
        NpcDialogue d = engine().decode("old_jack", JACK);
        assertNotNull(d);
        DialogueStart.Beat warm = d.getStart().first().get(0);
        assertEquals("board_fresh", warm.getOnceId());
        assertEquals(1, warm.getActions().size());
        assertTrue(warm.getActions().get(0) instanceof Pay);
    }

    @Test
    void beatsNamingOneOnceIdShareOneDailyClaim() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("old_jack", JACK);
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);

        DialogueEngine.EntryResolution plain = engine.resolveEntry(d, ctx);
        assertEquals("board_fresh", plain.nodeId());
        assertEquals("once:e:old_jack:board_fresh:PD" + day(2026, 10, 31), plain.onceKey(),
                "filed under the OnceId; here it is the plain screen's own key, so a line heard today stays heard");
        engine.consumeOnce(plain.onceKey(), plain.onceFamily(), d, "board_fresh", null, ctx);

        WARM.set(true);
        assertEquals("menu", engine.resolveEntry(d, ctx).nodeId(),
                "rising later the same day finds the warmer wording's claim already spent");

        now[0] = at("2026-11-01T08:00:00Z");
        DialogueEngine.EntryResolution next = engine.resolveEntry(d, ctx);
        assertEquals("greet_warm", next.nodeId(), "a new day greets with the warmer wording");
        assertEquals("once:e:old_jack:board_fresh:PD" + day(2026, 11, 1), next.onceKey());
    }

    @Test
    void aBeatsActionsRideItsResolutionAndRunOnlyWithAClaimPending() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("old_jack", JACK);
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);
        DialogueEngine.EntryResolution plain = engine.resolveEntry(d, ctx);
        assertEquals(1, plain.actions().size());

        engine.runBeatActions(plain.onceKey(), plain.actions(), ctx);
        assertEquals(1, PAID.get(), "a completed beat pays once");
        engine.runBeatActions(null, plain.actions(), ctx);
        assertEquals(1, PAID.get(), "with no claim pending, nothing runs");
    }

    @Test
    void aBeatWithNeitherLeafFilesUnderItsScreenAndCarriesNothing() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("old_jack", """
                { "Start": { "First": [ { "Node": "hello", "Once": { "Period": "Daily" } } ], "Fallback": "menu" },
                  "Nodes": {
                    "hello": { "Options": [ { "LabelKey": "hello.back", "Goto": "menu" } ] },
                    "menu": { "Options": [] } } }
                """);
        assertNotNull(d);
        DialogueStart.Beat hello = d.getStart().first().get(0);
        assertEquals(null, hello.getOnceId());
        assertTrue(hello.getActions().isEmpty());

        TestDialogueContext ctx = new TestDialogueContext(d);
        DialogueEngine.EntryResolution entry = engine.resolveEntry(d, ctx);
        assertEquals("hello", entry.nodeId());
        assertEquals("once:e:old_jack:hello:PD" + day(2026, 10, 31), entry.onceKey(), "still its screen's own key");
        assertEquals("once:e:old_jack:hello:P", entry.onceFamily());
        assertTrue(entry.actions().isEmpty());
        engine.runBeatActions(entry.onceKey(), entry.actions(), ctx);
        assertEquals(0, PAID.get(), "nothing to run");
        assertTrue(ctx.state().keys.isEmpty(), "running a beat's actions spends nothing by itself");

        List<String> codes = DialogueTestSupport.codes(DialogueStructureValidator.validate(d, null, engine));
        assertTrue(!codes.contains("BEAT_ACTIONS_WITHOUT_ONCE") && !codes.contains("BEAT_ONCE_ID_WITHOUT_ONCE"),
                codes.toString());
    }

    @Test
    void eitherLeafOnABeatWithNoOnceIsReportedAndCarriesNothing() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("t", """
                { "Start": { "First": [ { "Node": "g", "OnceId": "x", "Actions": [ { "Type": "Test_Pay" } ] } ] },
                  "Nodes": { "g": { "Options": [] } } }
                """);
        assertNotNull(d);
        assertTrue(engine.resolveEntry(d, new TestDialogueContext(d)).actions().isEmpty(),
                "with nothing to spend there is no moment for them to run at");
        List<Finding> findings = DialogueStructureValidator.validate(d, null, engine);
        assertEquals(Severity.ERROR, finding(findings, "BEAT_ACTIONS_WITHOUT_ONCE").severity());
        assertEquals(Severity.WARNING, finding(findings, "BEAT_ONCE_ID_WITHOUT_ONCE").severity());
    }

    @Test
    void aBeatActionNothingCanRunIsReported() {
        NpcDialogue d = engine().decode("t", """
                { "Start": { "First": [ { "Node": "g", "Once": true, "Actions": [ { "Type": "Test_Pay" } ] } ] },
                  "Nodes": { "g": { "Options": [] } } }
                """);
        assertNotNull(d);
        DialogueEngine bare = DialogueEngine.builder().warn(m -> { }).build();
        assertEquals(Severity.WARNING,
                finding(DialogueStructureValidator.validate(d, null, bare), "UNKNOWN_ACTION_TYPE").severity());
    }

    @Test
    void theEditorSchemaDocumentsBothLeavesAndTheActionsSayWhatTheyHold() {
        engine();
        SchemaContext context = new SchemaContext();
        DialogueTypeTable.get().startCodec().toSchema(context);
        ObjectSchema beat = null;
        for (Map.Entry<String, Schema> entry : context.getDefinitions().entrySet()) {
            if (entry.getValue() instanceof ObjectSchema object && object.getProperties() != null
                    && object.getProperties().containsKey("Node") && object.getProperties().containsKey("Pick")) {
                beat = object;
            }
        }
        assertNotNull(beat, "the beat is filed as a definition: " + context.getDefinitions().keySet());
        assertNotNull(beat.getProperties().get("OnceId").getMarkdownDescription());
        ArraySchema actions = (ArraySchema) beat.getProperties().get("Actions");
        assertNotNull(actions.getItems(), "the list says it holds actions");
        assertNotNull(actions.getMarkdownDescription());
    }

    @Nonnull
    private static Finding finding(@Nonnull List<Finding> findings, @Nonnull String code) {
        return findings.stream().filter(f -> f.code().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + code + " in " + DialogueTestSupport.codes(findings)));
    }
}
