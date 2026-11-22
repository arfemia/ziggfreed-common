package com.ziggfreed.common.instance.dungeon.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * The {@code ZigWhere} tag's rule over a piece's encoded JSON: a skill lock's two volumes, one per kind of server, as
 * the room tools' {@code SkillLock} template writes them, and each server keeps exactly its own; an untagged or blank
 * piece is always kept, and one whose block does not read is kept nowhere. The server's answer is stood in for by a
 * reading of {@code hytale:mod_installed}, so nothing here needs a plugin table. Untagged: nothing builds an engine item.
 */
class RoomWhereTest {

    private static final String MOD = "Example:Skills";
    private static final String WITH_MOD = "{\"Factors\":[{\"Factor\":\"hytale:mod_installed\",\"Min\":1,\"Param\":\""
            + MOD + "\"}]}";
    private static final String WITHOUT_MOD = "{\"Factors\":[{\"Factor\":\"hytale:mod_installed\",\"Max\":0,\"Param\":\""
            + MOD + "\"}]}";

    @Test
    void aTwoBranchLockKeepsOnlyItsOwnServersVolume() {
        List<BsonDocument> lock = List.of(volume("Lock_1", WITH_MOD, "lock.level"),
                volume("Lock_2", WITHOUT_MOD, "lock.tool"));

        assertEquals(List.of("lock.level"), lines(lock, serverWith(true)), "with the mod, only the level's line");
        assertEquals(List.of("lock.tool"), lines(lock, serverWith(false)), "without it, only the tool's line");
    }

    @Test
    void theTagReadsAsTheSharedRequiresBlock() {
        GateSpec spec = RoomWhere.parse(RoomWhere.of(volume("Lock_1", WITH_MOD, "lock.level")));
        assertNotNull(spec);
        FactorCondition[] factors = spec.factorsOrEmpty();
        assertEquals(1, factors.length);
        assertEquals("hytale:mod_installed", factors[0].getFactor());
        assertEquals(MOD, factors[0].getParam());

        BsonDocument listed = new BsonDocument("Tags", new BsonDocument("zigwhere",
                new BsonArray(List.of(new BsonString(WITHOUT_MOD)))));
        assertEquals(WITHOUT_MOD, RoomWhere.of(listed), "the key matched without case, a list read by its first entry");
    }

    @Test
    void anUntaggedPieceIsKeptAndAnUnreadableOneIsKeptNowhere() {
        Predicate<GateSpec> anything = spec -> true;
        assertNull(RoomWhere.of(new BsonDocument("Name", new BsonString("Plain"))));
        assertTrue(RoomWhere.keeps(null, spec -> false), "untagged logic is always placed");
        assertNull(RoomWhere.of(new BsonDocument("Tags", new BsonDocument(RoomWhere.KEY, new BsonString("  ")))),
                "a blank tag is no tag");

        for (String unreadable : List.of(MOD, "[1, 2]",
                "{\"Factorz\":[{\"Factor\":\"hytale:mod_installed\",\"Min\":1,\"Param\":\"" + MOD + "\"}]}",
                "{\"Factors\":[{\"Factor\":\"hytale:mod_installed\",\"Minn\":1,\"Param\":\"" + MOD + "\"}]}")) {
            assertNull(RoomWhere.parse(unreadable), unreadable);
            assertFalse(RoomWhere.keeps(unreadable, anything), "a gate that cannot be read stays shut: " + unreadable);
        }
    }

    // ==================== helpers ====================

    /** A lock volume as the template writes it: its State, its world-level block and its own refusal line. */
    @Nonnull
    private static BsonDocument volume(@Nonnull String name, @Nonnull String where, @Nonnull String line) {
        BsonDocument tags = new BsonDocument("State", new BsonString("Locked"))
                .append(RoomWhere.KEY, new BsonString(where));
        BsonDocument refusal = new BsonDocument("Type", new BsonString("SendMessage"))
                .append("Event", new BsonString("BLOCK_USED")).append("Message", new BsonString(line));
        return new BsonDocument("Name", new BsonString(name)).append("Tags", tags)
                .append("RejectionEffects", new BsonArray(List.of(refusal)));
    }

    /** A server's answer to a block of {@code hytale:mod_installed} conditions: the mod reads 1 or a definite 0. */
    @Nonnull
    private static Predicate<GateSpec> serverWith(boolean installed) {
        return spec -> {
            for (FactorCondition condition : spec.factorsOrEmpty()) {
                if (!condition.accepts(installed ? 1.0 : 0.0)) {
                    return false;
                }
            }
            return true;
        };
    }

    /** The refusal line of every volume a server keeps, in order. */
    @Nonnull
    private static List<String> lines(@Nonnull List<BsonDocument> volumes, @Nonnull Predicate<GateSpec> server) {
        List<String> out = new ArrayList<>();
        for (BsonDocument volume : volumes) {
            if (RoomWhere.keeps(RoomWhere.of(volume), server)) {
                out.add(volume.getArray("RejectionEffects").get(0).asDocument().getString("Message").getValue());
            }
        }
        return out;
    }
}
