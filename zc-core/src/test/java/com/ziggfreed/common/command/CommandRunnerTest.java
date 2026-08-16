package com.ziggfreed.common.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The authored-command primitive. Every case here is a mistake an author cannot see from in-game
 * behaviour alone: a placeholder that never filled, a give that delivered one item, a broken line
 * that swallowed the rest of the list, or a line the command system refused being answered as one
 * that ran.
 */
class CommandRunnerTest {

    /** Records what would have been dispatched, and can be told to refuse instead. */
    private static final class RecordingDispatcher implements CommandRunner.Dispatcher {
        final List<String> dispatched = new ArrayList<>();
        boolean refuse;

        @Override
        public boolean dispatch(String command) {
            dispatched.add(command);
            return !refuse;
        }
    }

    @Nested
    class Substitution {

        @Test
        void aKeyIsReplacedEverywhereItAppears() {
            assertEquals("give Bob Wood --to=Bob",
                    CommandRunner.substitute("give {player} Wood --to={player}", Map.of("player", "Bob")));
        }

        @Test
        void theVocabularyIsWhateverTheCallerPasses() {
            assertEquals("log sawmill/cut x3", CommandRunner.substitute("log {station}/{action} x{cycles}",
                    Map.of("station", "sawmill", "action", "cut", "cycles", "3")));
        }

        @Test
        void anUnknownKeyIsLeftStandingRatherThanBlanked() {
            // Blanking would turn a typo into a silently malformed command; leaving it makes the
            // mistake visible in whatever ran.
            assertEquals("give Bob {itme}",
                    CommandRunner.substitute("give {player} {itme}", Map.of("player", "Bob")));
        }

        @Test
        void noPlaceholdersAtAllIsTheLineItself() {
            assertEquals("say hi", CommandRunner.substitute("say hi", Map.of()));
            assertEquals("say hi", CommandRunner.substitute("say hi", null));
        }
    }

    @Nested
    class GiveQuantityNormalization {

        @Test
        void aPositionalCountBecomesTheNamedForm() {
            assertEquals("give Bob Wood_Planks --quantity=32",
                    CommandRunner.normalizeGive("give Bob Wood_Planks 32"));
        }

        @Test
        void aLeadingSlashIsPreserved() {
            assertEquals("/give Bob Wood_Planks --quantity=32",
                    CommandRunner.normalizeGive("/give Bob Wood_Planks 32"));
        }

        @Test
        void anAlreadyNamedQuantityIsLeftAlone() {
            assertEquals("give Bob Wood_Planks --quantity=32",
                    CommandRunner.normalizeGive("give Bob Wood_Planks --quantity=32"));
        }

        @Test
        void aGiveWithNoCountIsLeftAlone() {
            assertEquals("give Bob Wood_Planks", CommandRunner.normalizeGive("give Bob Wood_Planks"));
        }

        @Test
        void anItemWhoseNameEndsInDigitsIsNotMistakenForACount() {
            assertEquals("give Bob Block_Stone2", CommandRunner.normalizeGive("give Bob Block_Stone2"));
        }

        @Test
        void aNonGiveCommandIsNeverTouched() {
            assertEquals("summon Bob Zombie 3", CommandRunner.normalizeGive("summon Bob Zombie 3"));
            assertEquals("xp add Bob 500", CommandRunner.normalizeGive("xp add Bob 500"));
        }

        @Test
        void zeroAndNegativeCountsAreLeftForTheEngineToRefuse() {
            assertEquals("give Bob Wood_Planks 0", CommandRunner.normalizeGive("give Bob Wood_Planks 0"));
            assertEquals("give Bob Wood_Planks -4", CommandRunner.normalizeGive("give Bob Wood_Planks -4"));
        }

        @Test
        void otherFlagsDoNotCountAsPositionals() {
            assertEquals("give Bob Wood_Planks --silent --quantity=8",
                    CommandRunner.normalizeGive("give Bob Wood_Planks --silent 8"));
        }

        @Test
        void blankAndNullAreSafe() {
            assertEquals("", CommandRunner.normalizeGive(null));
            assertEquals("   ", CommandRunner.normalizeGive("   "));
        }
    }

    @Nested
    class ReadingAGiveLineBack {

        @Test
        void aNamedQuantityIsWhatTheLineHandsOver() {
            CommandRunner.Give give = CommandRunner.readGive("give Bob Wood_Planks --quantity=32");

            assertNotNull(give);
            assertEquals("Bob", give.target());
            assertEquals("Wood_Planks", give.itemId());
            assertEquals(32, give.quantity());
        }

        @Test
        void aPositionalCountIsReadLenientlyAsTheAuthorsIntent() {
            // The ENGINE ignores this form, which is what normalizeGive exists to fix. A reader
            // asking what the author meant still answers 32, so a preview and a fit probe are not
            // the two places a misauthored line looks like a single item.
            assertEquals(32, CommandRunner.readGive("give Bob Wood_Planks 32").quantity());
        }

        @Test
        void theNamedFormWinsOverAPositionalOne() {
            assertEquals(8, CommandRunner.readGive("give Bob Wood_Planks 32 --quantity=8").quantity());
        }

        @Test
        void aLeadingSlashAndAnyCasingOfTheVerbAreAccepted() {
            assertEquals("Wood_Planks", CommandRunner.readGive("/GIVE Bob Wood_Planks").itemId());
            assertEquals("Wood_Planks", CommandRunner.readGive("  give Bob Wood_Planks  ").itemId());
        }

        @Test
        void noCountAtAllIsOne() {
            assertEquals(1, CommandRunner.readGive("give Bob Wood_Planks").quantity());
        }

        @Test
        void anUnreadableCountCostsTheCountAndNotTheLine() {
            CommandRunner.Give give = CommandRunner.readGive("give Bob Wood_Planks --quantity=lots");

            assertNotNull(give);
            assertEquals("Wood_Planks", give.itemId());
            assertEquals(1, give.quantity());
        }

        @Test
        void anythingThatIsNotAGiveReadsAsNothing() {
            assertNull(CommandRunner.readGive("summon Bob Zombie 3"));
            assertNull(CommandRunner.readGive("xp add Bob 500"));
            assertNull(CommandRunner.readGive("give Bob"), "no item named, so nothing is handed over");
            assertNull(CommandRunner.readGive(null));
            assertNull(CommandRunner.readGive("   "));
        }

        @Test
        void aNormalizedLineAndTheRawOneItCameFromReadTheSame() {
            String raw = "give Bob Wood_Planks 32";

            assertEquals(CommandRunner.readGive(raw),
                    CommandRunner.readGive(CommandRunner.normalizeGive(raw)));
        }
    }

    @Nested
    class Running {

        @Test
        void aLineIsSubstitutedAndNormalizedBeforeItIsDispatched() {
            RecordingDispatcher dispatcher = new RecordingDispatcher();

            assertTrue(CommandRunner.runWith(dispatcher, "give {player} Wood_Planks 32",
                    Map.of("player", "Bob"), null));

            assertEquals(List.of("give Bob Wood_Planks --quantity=32"), dispatcher.dispatched);
        }

        @Test
        void aThrowingDispatcherIsReportedRatherThanEscaping() {
            List<String> failures = new ArrayList<>();

            boolean ran = CommandRunner.runWith(
                    command -> { throw new IllegalStateException("no command manager"); },
                    "say hi", Map.of(), failures::add);

            assertFalse(ran);
            assertEquals(1, failures.size());
            assertTrue(failures.get(0).contains("say hi"), failures.get(0));
            assertTrue(failures.get(0).contains("no command manager"), failures.get(0));
        }

        @Test
        void aDispatcherThatAnswersFalseIsReportedExactlyLikeAThrow() {
            // The failure this pins: a console dispatch the command system refuses answers false
            // rather than throwing, and a caller told "it ran" pays out a reward that never landed.
            RecordingDispatcher dispatcher = new RecordingDispatcher();
            dispatcher.refuse = true;
            List<String> failures = new ArrayList<>();

            boolean ran = CommandRunner.runWith(dispatcher, "say hi", Map.of(), failures::add);

            assertFalse(ran, "a line the dispatcher refused did not run");
            assertEquals(List.of("say hi"), dispatcher.dispatched, "it was still attempted");
            assertEquals(1, failures.size());
            assertTrue(failures.get(0).contains("say hi"), failures.get(0));
        }

        @Test
        void aRefusedLineIsNotCountedByRunAll() {
            List<String> failures = new ArrayList<>();

            int ran = CommandRunner.runAllWith(command -> !command.contains("refused"),
                    List.of("say one", "say refused", "say two"), Map.of(), failures::add);

            assertEquals(2, ran, "only the lines that actually dispatched count");
            assertEquals(1, failures.size());
        }

        @Test
        void aThrowingFailureSinkStillCannotEscape() {
            // The sink is the caller's; a broken one costs its own line, never the grant loop.
            assertFalse(CommandRunner.runWith(
                    command -> { throw new IllegalStateException("boom"); },
                    "say hi", Map.of(),
                    message -> { throw new IllegalStateException("the sink is broken too"); }));
        }

        @Test
        void aBlankLineIsANoOp() {
            RecordingDispatcher dispatcher = new RecordingDispatcher();

            assertFalse(CommandRunner.runWith(dispatcher, "  ", Map.of(), null));
            assertFalse(CommandRunner.runWith(dispatcher, null, Map.of(), null));

            assertTrue(dispatcher.dispatched.isEmpty());
        }

        @Test
        void oneBrokenLineDoesNotCostTheRestOfTheList() {
            List<String> dispatched = new ArrayList<>();
            List<String> failures = new ArrayList<>();

            int ran = CommandRunner.runAllWith(command -> {
                if (command.contains("boom")) {
                    throw new IllegalStateException("bad line");
                }
                return dispatched.add(command);
            }, List.of("say one", "say boom", "say two"), Map.of(), failures::add);

            assertEquals(2, ran);
            assertEquals(List.of("say one", "say two"), dispatched);
            assertEquals(1, failures.size());
        }

        @Test
        void resolveAllPreviewsWithoutRunningAnything() {
            assertEquals(List.of("give Bob Wood_Planks --quantity=32", "say hi Bob"),
                    CommandRunner.resolveAll(
                            List.of("give {player} Wood_Planks 32", "  ", "say hi {player}"),
                            Map.of("player", "Bob")));
        }
    }
}
