package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.command.CommandRunner;
import com.ziggfreed.common.subject.Subject;

/**
 * What an authored kind actually runs, and what it refuses to run.
 *
 * <p>Every case here is a way a payout could go quietly wrong: a parameter that reached no argument,
 * a required one nobody noticed was missing, a command that ran with a hole in it. The dispatcher is
 * a recording seam, so all of it is testable with no live server.
 */
class CommandRewardKindTest {

    /** A dispatcher that records instead of running, and can be told to fail. */
    static final class Recorder implements CommandRunner.Dispatcher {
        final List<String> ran = new ArrayList<>();
        boolean fail;

        @Override
        public void dispatch(String command) throws Exception {
            ran.add(command);
            if (fail) {
                throw new IllegalStateException("dispatch refused");
            }
        }

        String only() {
            assertEquals(1, ran.size(), "exactly one command line should have been dispatched");
            return ran.get(0);
        }
    }

    static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    static Subject player() {
        return Subject.of(PLAYER_ID, "Bob");
    }

    static RewardKindAsset kind(String command, Map<String, RewardKindAsset.Param> params) {
        return RewardKindAsset.of("Mmo_Xp", params, command);
    }

    static Map<String, RewardKindAsset.Param> params(Object... pairs) {
        Map<String, RewardKindAsset.Param> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            out.put((String) pairs[i], (RewardKindAsset.Param) pairs[i + 1]);
        }
        return out;
    }

    // ==================== substitution ====================

    @Nested
    class Substitution {

        @Test
        void theReservedPlaceholdersAndEveryDeclaredParameterAreFilledIn() throws Exception {
            Recorder recorder = new Recorder();
            RewardKindAsset asset = kind("mmoawardxp {player} {Skill} {Amount} ({uuid})",
                    params("Skill", RewardKindAsset.Param.of(true, null),
                            "Amount", RewardKindAsset.Param.of(true, null)));

            new CommandRewardKind(asset, recorder).grant(
                    RewardSpec.of("Mmo_Xp", Map.of("Skill", "MINING", "Amount", "500")), player());

            assertEquals("mmoawardxp Bob MINING 500 (" + PLAYER_ID + ")", recorder.only());
        }

        @Test
        void anOmittedOptionalParameterFallsBackToItsDeclaredDefault() throws Exception {
            Recorder recorder = new Recorder();
            RewardKindAsset asset = kind("pay {player} --silent={Silent}",
                    params("Silent", RewardKindAsset.Param.of(false, "false")));

            new CommandRewardKind(asset, recorder).grant(RewardSpec.of("Pay"), player());

            assertEquals("pay Bob --silent=false", recorder.only());
        }

        @Test
        void anOmittedOptionalParameterWithNoDefaultSubstitutesEmpty() throws Exception {
            Recorder recorder = new Recorder();
            RewardKindAsset asset = kind("pay {player} {Flavour}",
                    params("Flavour", RewardKindAsset.Param.of(null, null)));

            new CommandRewardKind(asset, recorder).grant(RewardSpec.of("Pay"), player());

            assertEquals("pay Bob ", recorder.only());
        }

        @Test
        void aRewardsOwnValueBeatsTheDeclaredDefault() throws Exception {
            Recorder recorder = new Recorder();
            RewardKindAsset asset = kind("pay {player} --silent={Silent}",
                    params("Silent", RewardKindAsset.Param.of(false, "false")));

            new CommandRewardKind(asset, recorder).grant(
                    RewardSpec.of("Pay", "Silent", "true"), player());

            assertEquals("pay Bob --silent=true", recorder.only());
        }

        @Test
        void theSchemaIsTheAuthority_soAnUndeclaredParameterReachesNothing() throws Exception {
            Recorder recorder = new Recorder();
            RewardKindAsset asset = kind("pay {player} {Amount}",
                    params("Amount", RewardKindAsset.Param.of(null, null)));

            new CommandRewardKind(asset, recorder).grant(
                    RewardSpec.of("Pay", Map.of("Amount", "5", "Sneaky", "rm -rf")), player());

            assertEquals("pay Bob 5", recorder.only());
        }

        @Test
        void aPlaceholderNobodyFillsIsLeftStandingRatherThanBlanked() throws Exception {
            Recorder recorder = new Recorder();
            RewardKindAsset asset = kind("pay {player} {Typo}", params());

            new CommandRewardKind(asset, recorder).grant(RewardSpec.of("Pay"), player());

            assertEquals("pay Bob {Typo}", recorder.only(),
                    "a typo has to show up in the command that ran, not become an empty argument");
        }

        @Test
        void aGiveLineGetsTheQuantityFormTheEngineActuallyReads() throws Exception {
            Recorder recorder = new Recorder();
            RewardKindAsset asset = kind("give {player} Wood_Planks {Count}",
                    params("Count", RewardKindAsset.Param.of(true, null)));

            new CommandRewardKind(asset, recorder).grant(
                    RewardSpec.of("Give", "Count", "32"), player());

            assertEquals("give Bob Wood_Planks --quantity=32", recorder.only());
        }
    }

    // ==================== refusing ====================

    @Nested
    class Refusing {

        @Test
        void aMissingRequiredParameterRefusesLoudlyAndNamesIt() {
            Recorder recorder = new Recorder();
            RewardKindAsset asset = kind("mmoawardxp {player} {Skill} {Amount}",
                    params("Skill", RewardKindAsset.Param.of(true, null),
                            "Amount", RewardKindAsset.Param.of(true, null)));

            Exception thrown = assertThrows(Exception.class, () -> new CommandRewardKind(asset, recorder)
                    .grant(RewardSpec.of("Mmo_Xp", "Skill", "MINING"), player()));

            assertTrue(thrown.getMessage().contains("Amount"), "the refusal names the parameter at fault");
            assertTrue(recorder.ran.isEmpty(), "nothing may run once a required parameter is missing");
        }

        @Test
        void aRewardThatCannotSayWhatItPaysIsNotReplayableEither() {
            RewardKindAsset asset = kind("mmoawardxp {player} {Amount}",
                    params("Amount", RewardKindAsset.Param.of(true, null)));

            assertNull(new CommandRewardKind(asset, new Recorder())
                    .retryCommand(RewardSpec.of("Mmo_Xp"), player(), "quest:test"),
                    "queueing a retry that could never deliver would report the reward as saved");
        }

        @Test
        void aRequiredParameterWithADefaultIsNeverMissing() throws Exception {
            Recorder recorder = new Recorder();
            RewardKindAsset asset = kind("pay {player} {Amount}",
                    params("Amount", RewardKindAsset.Param.of(true, "1")));

            new CommandRewardKind(asset, recorder).grant(RewardSpec.of("Pay"), player());

            assertEquals("pay Bob 1", recorder.only());
        }

        @Test
        void aKindWithNoCommandRefusesRatherThanReportingAPayout() {
            RewardKindAsset asset = kind(null, params());

            assertThrows(Exception.class,
                    () -> new CommandRewardKind(asset, new Recorder()).grant(RewardSpec.of("Empty"), player()));
        }

        @Test
        void aDispatchThatFailsThrowsSoTheFailureCanBeQueued() {
            Recorder recorder = new Recorder();
            recorder.fail = true;
            RewardKindAsset asset = kind("pay {player}", params());

            assertThrows(Exception.class,
                    () -> new CommandRewardKind(asset, recorder).grant(RewardSpec.of("Pay"), player()));
        }
    }

    // ==================== the retry ====================

    @Test
    void aPayableRewardsRetryIsTheSameLineTheGrantWouldHaveRun() throws Exception {
        Recorder recorder = new Recorder();
        RewardKindAsset asset = kind("mmoawardxp {player} {Skill} {Amount}",
                params("Skill", RewardKindAsset.Param.of(true, null),
                        "Amount", RewardKindAsset.Param.of(true, null)));
        RewardSpec spec = RewardSpec.of("Mmo_Xp", Map.of("Skill", "MINING", "Amount", "500"));
        CommandRewardKind handler = new CommandRewardKind(asset, recorder);

        handler.grant(spec, player());

        assertEquals(recorder.only(), handler.retryCommand(spec, player(), "quest:test"));
    }

    // ==================== the shared readers ====================

    @Test
    void theReadersReportWhatTheHandlerActsOn() {
        RewardKindAsset asset = kind("pay {player} {Amount}",
                params("Amount", RewardKindAsset.Param.of(true, null),
                        "Silent", RewardKindAsset.Param.of(false, "false")));

        assertEquals(List.of("Amount"),
                CommandRewardKind.missingRequired(asset, RewardSpec.of("Pay")));
        assertEquals(List.of(),
                CommandRewardKind.missingRequired(asset, RewardSpec.of("Pay", "Amount", "5")));
        assertEquals(List.of("sneaky"),
                CommandRewardKind.undeclaredParams(asset, RewardSpec.of("Pay", Map.of("Sneaky", "x"))));
    }
}
