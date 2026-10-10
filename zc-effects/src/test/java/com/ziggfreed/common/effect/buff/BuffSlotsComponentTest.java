package com.ziggfreed.common.effect.buff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;

/**
 * The saved slot record ({@link BuffSlotsComponent}): its codec round trip, exercised through the
 * {@code BuilderCodec} directly (never a live store, engine state a unit JVM cannot stand up). A player
 * saved with the record must read it back as written, or a relog moves every buff to another tile and
 * the login sweep forgets an effect a source named for itself.
 */
class BuffSlotsComponentTest {

    @Test
    void theRecordRoundTripsThroughTheCodecPerScopeInOrder() {
        Map<String, String> personal = new LinkedHashMap<>();
        personal.put("mymod:double_xp", "Zig_Buff_Personal_2");
        personal.put("mymod:mining_rush", "Zig_Buff_Personal");
        Map<String, String> server = new LinkedHashMap<>();
        server.put("mymod:weekend", "MyMod_Weekend_Buff");
        BuffSlotsComponent original = new BuffSlotsComponent();
        original.record(Map.of(BuffScope.PERSONAL, personal, BuffScope.SERVER, server));

        ExtraInfo info = new ExtraInfo();
        var bson = BuffSlotsComponent.CODEC.encode(original, info);
        BuffSlotsComponent decoded = BuffSlotsComponent.CODEC.decode(bson, info);

        assertEquals(List.copyOf(personal.entrySet()), List.copyOf(decoded.slots(BuffScope.PERSONAL).entrySet()));
        assertEquals(server, decoded.slots(BuffScope.SERVER));
    }

    @Test
    void theSavedKeysArePersonalAndServerAndAPlayerSavedWithoutOneReadsEmpty() throws IOException {
        BuffSlotsComponent decoded = BuffSlotsComponent.CODEC.decodeJson(RawJsonReader.fromJsonString(
                "{\"Personal\":{\"mymod:double_xp\":\"Zig_Buff_Personal\",\"blank\":\"\"},"
                        + "\"Server\":{\"mymod:weekend\":\"Zig_Buff_Server\"}}"),
                new ExtraInfo());

        assertEquals(Map.of("mymod:double_xp", "Zig_Buff_Personal"), decoded.slots(BuffScope.PERSONAL),
                "a blank effect id reads as no slot");
        assertEquals(Map.of("mymod:weekend", "Zig_Buff_Server"), decoded.slots(BuffScope.SERVER));

        BuffSlotsComponent empty = BuffSlotsComponent.CODEC.decodeJson(RawJsonReader.fromJsonString("{}"),
                new ExtraInfo());
        assertTrue(empty.slots(BuffScope.PERSONAL).isEmpty());
        assertTrue(empty.slots(BuffScope.SERVER).isEmpty());
    }
}
