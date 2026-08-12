package com.ziggfreed.common.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The authored-command primitive. Every case here is a mistake an author cannot see from in-game
 * behaviour alone: a placeholder that never filled, a give that delivered one item, or a broken
 * line that swallowed the rest of the list.
 */
class CommandRunnerTest {

    /** Records what would have been dispatched. */
    private static final class RecordingDispatcher implements CommandRunner.Dispatcher {
        final List<String> dispatched = new ArrayList<>();

        @Override
        public void dispatch(String command) {
            dispatched.add(command);
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
                dispatched.add(command);
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
