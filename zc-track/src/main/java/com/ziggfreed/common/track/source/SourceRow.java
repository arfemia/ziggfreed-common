package com.ziggfreed.common.track.source;

import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDouble;
import org.bson.BsonValue;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.WrappedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.NumberSchema;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * One row of a {@link TrackSourceAsset}'s {@code Rows}: what a matching moment pays, and the level it waits for.
 * Written as a bare number (the {@code Xp}) or as a group:
 *
 * <pre>{@code
 * "Rows": { "Ore_Iron*": { "Xp": 50, "Level": 10 }, "Rock_*": 3, "Trork_*": { "PerAmount": 1.5 } }
 * }</pre>
 *
 * <p>{@code Xp} pays once per moment, {@code PerAmount} once per unit of the moment's amount (a hit's damage, a
 * distance walked, a craft's count); a row writing both pays both. {@code Level} is a soft gate on the track's
 * current level: below it this row stands aside and the next row matching the same moment pays instead, so nothing
 * is ever refused, only paid at the rate already reached.
 */
public final class SourceRow {

    @Nullable protected Double xp;
    @Nullable protected Double perAmount;
    @Nullable protected Integer level;

    public static final BuilderCodec<SourceRow> CODEC = BuilderCodec.builder(SourceRow.class, SourceRow::new)
            .documentation("What a matching moment pays, and the level it waits for. A bare number is its Xp.")
            .appendInherited(new KeyedCodec<>("Xp", Codec.DOUBLE, false),
                    (o, v) -> o.xp = v, o -> o.xp, (o, p) -> o.xp = p.xp)
            .metadata(EditorSchema.defaultValue(0.0))
            .documentation("What each matching moment pays, once per moment. 0 when unauthored.").add()
            .appendInherited(new KeyedCodec<>("PerAmount", Codec.DOUBLE, false),
                    (o, v) -> o.perAmount = v, o -> o.perAmount, (o, p) -> o.perAmount = p.perAmount)
            .metadata(EditorSchema.defaultValue(0.0))
            .documentation("What each unit of the moment's amount pays: a hit's damage (capped at what the target "
                    + "had left), a distance, a craft's count. 0 when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Level", Codec.INTEGER, false),
                    (o, v) -> o.level = v, o -> o.level, (o, p) -> o.level = p.level)
            .metadata(EditorSchema.defaultValue(0L))
            .documentation("The track level this row waits for: below it the next row matching the same moment "
                    + "pays instead (a broader pattern, or nothing), so nothing is refused. 0 or unauthored waits "
                    + "for nothing.").add()
            .build();

    /** A row written as a bare number or as a group: what {@code Rows} maps each pattern to. */
    public static final Codec<SourceRow> NUMBER_OR_ROW = new NumberOrRowCodec();

    public SourceRow() {
    }

    /** Java-side factory; nulls stay unauthored. */
    @Nonnull
    public static SourceRow of(@Nullable Double xp, @Nullable Double perAmount, @Nullable Integer level) {
        SourceRow row = new SourceRow();
        row.xp = xp;
        row.perAmount = perAmount;
        row.level = level;
        return row;
    }

    /** What each moment pays, 0 or more. */
    public double xp() {
        return clean(xp);
    }

    /** What each unit of the moment's amount pays, 0 or more. */
    public double perAmount() {
        return clean(perAmount);
    }

    /** The level this row waits for, 0 for none. */
    public int level() {
        return level == null ? 0 : Math.max(0, level);
    }

    /** Does this row pay anything at all? */
    public boolean pays() {
        return xp() > 0.0 || perAmount() > 0.0;
    }

    private static double clean(@Nullable Double value) {
        return value == null || !Double.isFinite(value) ? 0.0 : Math.max(0.0, value);
    }

    /**
     * The {@code Rows} value codec: a JSON number decodes as a row of that {@code Xp}, anything else through
     * {@link #CODEC}. Its schema advertises both arms, so the Asset Editor offers either. Encodes the group form.
     */
    private static final class NumberOrRowCodec implements Codec<SourceRow>, WrappedCodec<SourceRow> {

        @Override
        public Codec<SourceRow> getChildCodec() {
            return CODEC;
        }

        @Override
        public SourceRow decode(@Nonnull BsonValue bsonValue, @Nonnull ExtraInfo extraInfo) {
            if (bsonValue.isNumber()) {
                return of(bsonValue.asNumber().doubleValue(), null, null);
            }
            return CODEC.decode(bsonValue, extraInfo);
        }

        @Override
        public SourceRow decodeJson(@Nonnull RawJsonReader reader, @Nonnull ExtraInfo extraInfo) throws IOException {
            reader.consumeWhiteSpace();
            if (reader.peekFor('{')) {
                return CODEC.decodeJson(reader, extraInfo);
            }
            return of(reader.readDoubleValue(), null, null);
        }

        @Nonnull
        @Override
        public BsonValue encode(@Nonnull SourceRow row, ExtraInfo extraInfo) {
            if (row.perAmount == null && row.level == null && row.xp != null) {
                return new BsonDouble(row.xp);
            }
            return CODEC.encode(row, extraInfo);
        }

        @Nonnull
        @Override
        public Schema toSchema(@Nonnull SchemaContext context) {
            NumberSchema number = new NumberSchema();
            number.setDescription("The row's Xp, written alone.");
            return Schema.anyOf(number, context.refDefinition(CODEC));
        }
    }
}
