package com.ziggfreed.common.loot.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.diminish.DiminishLedger;

/**
 * The Claims file speaks a versioned shape and keeps a Claim whole across a restart: its id, source,
 * title and arguments, difficulty, every reward and when it was written.
 *
 * <p>Tagged {@code engine-items}: the write goes through the engine's own atomic writer
 * ({@code FileUtil.writeStringAtomic}), which on Update 7 logs at class init and so loads only under the
 * engine's log manager ({@code engineItemTest}).
 */
class ClaimStoreTest {

    private static final UUID OWED = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    @Tag("engine-items")
    @Test
    void aClaimSurvivesARestartWhole(@TempDir Path dir) throws IOException {
        Claim claim = new Claim("test:boss:run-1:daily:" + OWED, "test:boss", "test.claims.title",
                List.of("Brood Queen"), "Normal",
                List.of(InstanceReward.item("Ingredient_Bar_Iron", 3, null),
                        InstanceReward.command("give {player} Ingredient_Bar_Copper", 2, "test.chip", "Icon_Item")),
                1_760_000_000_000L);
        ClaimStore store = new ClaimStore("claims", new DiminishLedger("claims-diminish"));
        store.init(dir);
        assertTrue(store.award(OWED, claim, null, "test:boss", 1_760_000_000_000L));

        JsonObject root = JsonParser.parseString(Files.readString(dir.resolve("claims.json"))).getAsJsonObject();
        assertEquals(1, root.get("version").getAsInt(), "every write carries the version");

        ClaimStore restarted = new ClaimStore("claims", new DiminishLedger("claims-diminish"));
        restarted.init(dir);
        assertEquals(List.of(claim), restarted.list(OWED), "the Claim reads back exactly as it was written");
    }
}
