package com.ziggfreed.common.instance.dungeon.layout.asset;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.instance.dungeon.layout.LayoutGenerator;
import com.ziggfreed.common.instance.dungeon.layout.LayoutGeneratorTest;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Beat;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Count;
import com.ziggfreed.common.instance.dungeon.layout.LayoutResult;
import com.ziggfreed.common.instance.dungeon.layout.RoomCatalogue;
import com.ziggfreed.common.instance.dungeon.layout.RoomCatalogueAudit;
import com.ziggfreed.common.instance.dungeon.layout.RoomSpec;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * Every room file this module ships ({@code Server/ZiggfreedCommon/DungeonRooms/}, the imported vanilla kits) against
 * its codec and the catalogue audit (R23: shipped JSON against its codec). Each file must decode, and keep every key
 * it writes (a key the codec does not know, or a shape it cannot read, such as {@code Tags} written as a list rather
 * than the engine's map, would otherwise drop silently). The rooms together must draw no audit ERROR, and one tiny
 * plan per kit must make a whole layout from them, which proves the kits' sockets really snap. The test JVM holds no
 * vanilla prefab, so every pool answers one variant. Untagged: nothing here builds an engine item.
 */
class ShippedDungeonRoomsTest {

    private static final Path ROOMS = Path.of("src", "main", "resources", "Server", "ZiggfreedCommon", "DungeonRooms");

    /** Seeds each kit's plan must make a whole layout from. */
    private static final int SEEDS = 20;

    /** The Stone kit: a room, a fight, a corridor, a dead end. */
    private static final LayoutPlan STONE = plan("Stone_Kit_Check", List.of("Kit=Stone"));

    /** The Cursed Crypt kit's Cursed section (its Tomb is reached only through an adapter, PD7). */
    private static final LayoutPlan CURSED = plan("Cursed_Crypt_Kit_Check", List.of("Kit=Cursed_Crypt", "Section=Cursed"));

    @Test
    void everyShippedRoomDecodesKeepsItsKeysAndTheKitsSnap() throws IOException {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(ROOMS)) {
            files = walk.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        assertTrue(!files.isEmpty(), "the module ships room files under " + ROOMS);

        List<String> problems = new ArrayList<>();
        List<RoomSpec> specs = new ArrayList<>();
        for (Path file : files) {
            String id = file.getFileName().toString().replace(".json", "");
            String json = Files.readString(file, StandardCharsets.UTF_8);
            try {
                DungeonRoomAsset room = decode(id, json);
                List<String> lost = new ArrayList<>();
                lostKeys("", BsonDocument.parse(json), DungeonRoomAsset.CODEC.encode(room, new ExtraInfo()), lost);
                if (!lost.isEmpty()) {
                    problems.add(id + " loses " + lost);
                }
                specs.add(room.toSpec());
            } catch (IOException | RuntimeException e) {
                problems.add(id + " does not decode: " + e);
            }
        }
        assertTrue(problems.isEmpty(), () -> problems.size() + " of " + files.size() + " files: " + problems);

        RoomCatalogue catalogue = RoomCatalogue.of(specs, prefab -> 1);
        List<Finding> findings = RoomCatalogueAudit.audit(catalogue, List.of(STONE, CURSED));
        List<Finding> errors = new ArrayList<>();
        List<Finding> warnings = new ArrayList<>();
        for (Finding finding : findings) {
            (finding.severity() == Severity.ERROR ? errors : warnings).add(finding);
        }

        List<String> layouts = new ArrayList<>();
        for (LayoutPlan plan : List.of(STONE, CURSED)) {
            for (int seed = 1; seed <= SEEDS; seed++) {
                LayoutResult result = LayoutGenerator.generate(plan, catalogue, seed);
                if (!result.ok()) {
                    problems.add(plan.id() + " seed " + seed + " makes no layout: " + result.failure());
                    continue;
                }
                for (String problem : LayoutGeneratorTest.problemsOf(result.layout(), plan, catalogue)) {
                    problems.add(plan.id() + " seed " + seed + ": " + problem);
                }
                if (seed == 1) {
                    layouts.add(plan.id() + " seed 1: " + result.layout().rooms().size() + " rooms ("
                            + result.layout().pathLength() + " on the path), relax " + result.relax());
                }
            }
        }
        System.out.println("[shipped-rooms] " + files.size() + " files decoded, " + errors.size() + " ERRORs, "
                + warnings.size() + " WARNINGs " + warnings + "; " + layouts);
        assertTrue(errors.isEmpty(), () -> errors.size() + " audit ERRORs: " + errors + "; WARNINGs: " + warnings);
        assertTrue(problems.isEmpty(), () -> problems.size() + " kit layout problems: "
                + problems.subList(0, Math.min(10, problems.size())) + "; WARNINGs: " + warnings);
    }

    /** A tiny plan over the rooms carrying {@code tags}: a room, a fight, a corridor, a dead end. */
    @Nonnull
    private static LayoutPlan plan(@Nonnull String id, @Nonnull List<String> tags) {
        return new LayoutPlan(id, tags, List.of(),
                List.of(new Beat("Room", Count.ONE, -1), new Beat("Combat", Count.ONE, -1),
                        new Beat("Corridor", Count.ONE, -1), new Beat("DeadEnd", Count.ONE, -1)),
                null, List.of(), null, null, 0);
    }

    /** Each key {@code written} holds that {@code encoded} does not, by path; {@code $} keys are comments. */
    private static void lostKeys(@Nonnull String path, @Nonnull BsonValue written, @Nullable BsonValue encoded,
            @Nonnull List<String> out) {
        if (written.isDocument()) {
            BsonDocument kept = encoded != null && encoded.isDocument() ? encoded.asDocument() : null;
            for (String key : written.asDocument().keySet()) {
                if (key.startsWith("$")) {
                    continue;
                }
                BsonValue value = kept == null ? null : kept.get(key);
                if (value == null) {
                    out.add(path + key);
                } else {
                    lostKeys(path + key + ".", written.asDocument().get(key), value, out);
                }
            }
        } else if (written.isArray()) {
            BsonArray list = written.asArray();
            BsonArray kept = encoded != null && encoded.isArray() ? encoded.asArray() : null;
            if (kept == null || kept.size() != list.size()) {
                out.add(path + "[" + list.size() + " items]");
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                lostKeys(path + i + ".", list.get(i), kept.get(i), out);
            }
        }
    }

    @Nonnull
    private static DungeonRoomAsset decode(@Nonnull String id, @Nonnull String json) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(DungeonRoomAsset.class, id, null);
        return DungeonRoomAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                new AssetExtraInfo<>(data));
    }
}
