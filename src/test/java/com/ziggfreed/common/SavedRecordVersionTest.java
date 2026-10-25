package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import javax.annotation.Nonnull;

import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.almanac.AlmanacComponent;
import com.ziggfreed.common.calendar.attendance.CalendarAttendanceComponent;
import com.ziggfreed.common.commerce.CommerceComponent;
import com.ziggfreed.common.effect.buff.BuffSlotsComponent;
import com.ziggfreed.common.encounter.run.ZigEncounterRest;
import com.ziggfreed.common.entity.flair.ZigFlairComponent;
import com.ziggfreed.common.entity.performer.PerformerIdentityComponent;
import com.ziggfreed.common.entity.title.ZigTitleComponent;
import com.ziggfreed.common.instance.claims.SpoilsChest;
import com.ziggfreed.common.npc.placement.runtime.PlacedNpcComponent;
import com.ziggfreed.common.objectives.store.ZigProgressComponent;
import com.ziggfreed.common.playerstats.PlayerStatsComponent;
import com.ziggfreed.common.reputation.ReputationComponent;
import com.ziggfreed.common.settings.PlayerSettingsComponent;
import com.ziggfreed.common.stats.gearset.GearSetLooksComponent;
import com.ziggfreed.common.track.TrackComponent;

/**
 * Every record the library saves on an entity carries a version: each save writes its {@code Version} leaf, a
 * save from before the leaf existed reads through the same leaves and writes back the same shape, and a save
 * from a newer build is refused rather than misread as today's shape. A new saved component joins this list.
 */
class SavedRecordVersionTest {

    private static final List<BuilderCodec<?>> RECORDS = List.of(
            AlmanacComponent.CODEC, CalendarAttendanceComponent.CODEC, CommerceComponent.CODEC,
            BuffSlotsComponent.CODEC, ZigEncounterRest.CODEC, ZigFlairComponent.CODEC,
            PerformerIdentityComponent.CODEC, ZigTitleComponent.CODEC, SpoilsChest.CODEC,
            PlacedNpcComponent.CODEC, ZigProgressComponent.CODEC, PlayerStatsComponent.CODEC,
            ReputationComponent.CODEC, PlayerSettingsComponent.CODEC, GearSetLooksComponent.CODEC,
            TrackComponent.CODEC);

    @Test
    void everySavedRecordWritesItsVersionReadsAnOlderSaveAndRefusesANewerOne() {
        for (BuilderCodec<?> codec : RECORDS) {
            String name = codec.getInnerClass().getSimpleName();
            BsonDocument saved = encodeFresh(codec);
            assertEquals(new BsonInt32(1), saved.get("Version"), name + " writes its version");

            BsonDocument older = saved.clone();
            older.remove("Version");
            BsonDocument again = reEncode(codec, older);
            assertEquals(saved.keySet(), again.keySet(),
                    name + " reads a save from before the version and writes the same leaves back, versioned");

            BsonDocument newer = saved.clone();
            newer.put("Version", new BsonInt32(2));
            assertThrows(IllegalArgumentException.class, () -> codec.decode(newer, new ExtraInfo()),
                    name + " refuses a save from a newer build");
        }
    }

    @Nonnull
    private static <T> BsonDocument encodeFresh(@Nonnull BuilderCodec<T> codec) {
        return codec.encode(codec.getDefaultValue(), new ExtraInfo());
    }

    @Nonnull
    private static <T> BsonDocument reEncode(@Nonnull BuilderCodec<T> codec, @Nonnull BsonDocument doc) {
        return codec.encode(codec.decode(doc, new ExtraInfo()), new ExtraInfo());
    }
}
