package com.ziggfreed.common.objectives.producer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.calendar.event.CalendarAttendedEvent;
import com.ziggfreed.common.progress.asset.ObjectiveKindAsset;

/** One attendance is one CALENDAR_ATTENDED moment naming the event and its year, and the kind ships as a file. */
class ZigCalendarProducerTest {

    @Test
    void oneAttendanceIsOneMomentNamingTheEventAndItsYear() {
        UUID player = UUID.randomUUID();
        List<String> seen = new ArrayList<>();
        ZigCalendarProducer.fanOut(new CalendarAttendedEvent(player, "hallows_eve", 2026, 123L),
                (playerId, kind, target, qualifier, amount) ->
                        seen.add(playerId + "|" + kind + "|" + target + "|" + qualifier + "|" + amount));
        assertEquals(List.of(player + "|CALENDAR_ATTENDED|hallows_eve|2026|1"), seen,
                "one moment of amount 1 per attendance, which is once per player per run: what a count of runs"
                        + " attended adds up");
    }

    @Test
    void theKindShipsAsAProducibleCountingFileWithItsOwnSentence() throws Exception {
        String path = "/Server/ZiggfreedCommon/ObjectiveKinds/Calendar_Attended.json";
        String json;
        try (InputStream in = ZigCalendarProducerTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "the calendar module ships " + path);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ObjectiveKindAsset kind = ObjectiveKindAsset.CODEC.decodeJsonAsset(RawJsonReader.fromJsonString(json),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(ObjectiveKindAsset.class, "Calendar_Attended", null)));
        assertTrue(ZigCalendarProducer.KIND.equalsIgnoreCase(kind.getId()), "the file name is the kind id");
        assertEquals(Boolean.TRUE, kind.getProducible());
        assertEquals(Boolean.FALSE, kind.getValueBased());
        assertEquals("ziggfreedcommon.calendar.objective.calendar_attended", kind.getPresentation().getTextKey());
    }
}
