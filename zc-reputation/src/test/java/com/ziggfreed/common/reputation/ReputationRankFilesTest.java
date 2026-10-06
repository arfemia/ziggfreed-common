package com.ziggfreed.common.reputation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The one ladder zc ships as seven native ReputationRank files: exactly the seven ids, each a half-open
 * span starting where the one below ends (so the engine warns about no gap and no overlap at start), a
 * fresh standing of 0 landing in Neutral, an open top, each rank's attitude as the spec fixes it, and a
 * tip in every file.
 */
class ReputationRankFilesTest {

    private static final Path RANKS = Path.of("src", "main", "resources", "Server", "NPC", "Reputation", "Ranks");

    private static final List<String> LADDER = List.of(
            "Hated", "Unfriendly", "Neutral", "Friendly", "Honored", "Revered", "Exalted");

    /** The attitude a group's NPCs take toward a player in each rank, in the engine's own spelling. */
    private static final Map<String, String> ATTITUDES = Map.of(
            "Hated", "Hostile", "Unfriendly", "Neutral", "Neutral", "Neutral", "Friendly", "Friendly",
            "Honored", "Friendly", "Revered", "Revered", "Exalted", "Revered");

    private record RankFile(String id, int min, int max, String attitude, JsonObject json) {
    }

    private static List<RankFile> load() throws IOException {
        List<RankFile> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(RANKS)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                JsonObject json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                String name = file.getFileName().toString();
                out.add(new RankFile(name.substring(0, name.length() - ".json".length()),
                        json.get("MinValue").getAsInt(), json.get("MaxValue").getAsInt(),
                        json.get("Attitude").getAsString(), json));
            }
        }
        out.sort(Comparator.comparingInt(RankFile::min));
        return out;
    }

    @Test
    void theLadderIsTheSevenRanksInOrder() throws IOException {
        assertEquals(LADDER, load().stream().map(RankFile::id).toList());
    }

    @Test
    void eachRankStartsWhereTheOneBelowEnds() throws IOException {
        List<RankFile> ranks = load();
        for (int i = 0; i < ranks.size(); i++) {
            RankFile rank = ranks.get(i);
            assertTrue(rank.min() < rank.max(),
                    rank.id() + ": MinValue must sit below MaxValue, or the engine refuses the file");
            if (i > 0) {
                RankFile below = ranks.get(i - 1);
                assertEquals(below.max(), rank.min(), rank.id() + " starts where " + below.id()
                        + " ends: MinValue is included and MaxValue is not, so equal bounds leave no gap and no overlap");
            }
        }
    }

    @Test
    void aFreshStandingOfZeroReadsNeutralAndTheTopIsOpen() throws IOException {
        List<RankFile> ranks = load();
        RankFile neutral = ranks.stream().filter(r -> r.id().equals("Neutral")).findFirst().orElseThrow();
        assertEquals(0, neutral.min(), "a reputation starting at 0 must land in Neutral");
        assertEquals(2_000_000_000, ranks.get(ranks.size() - 1).max(),
                "Exalted is open-ended: the engine keeps earned standing one below the top's MaxValue");
    }

    @Test
    void eachRankCarriesItsAttitudeAndATip() throws IOException {
        for (RankFile rank : load()) {
            assertEquals(ATTITUDES.get(rank.id()), rank.attitude(), rank.id() + "'s Attitude");
            assertTrue(rank.json().has("$Comment") && !rank.json().get("$Comment").getAsString().isBlank(),
                    rank.id() + " carries a $Comment tip for a first-time reader");
        }
    }
}
