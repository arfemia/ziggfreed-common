package com.ziggfreed.common.track.asset;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonDouble;
import org.bson.BsonValue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.track.TrackText;
import com.ziggfreed.common.track.curve.CurveTable;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The level curves' owner form ({@code Server/ZiggfreedCommon/OwnerForms/Track_Curves.json}, an Entries form over
 * {@code mods/ziggfreedcommon/track-curves.json}, a row per curve whose last piece is a Hybrid one): the file's
 * binding, and the milestone preview the form shows ({@link #PREVIEW}).
 *
 * <p>A curve's formula sits inside its {@code Pieces} list, which a form's dotted paths cannot reach, and an owner
 * entry's {@code Pieces} replaces the packs' whole. So the binding lifts the last piece's Hybrid group to the entry's
 * own {@code Hybrid.Base} and {@code Hybrid.Scale} ({@link #BASE}, {@link #SCALE}): in the owner's file as the form
 * reads it ({@link #view}), in each curve as it reads now ({@code Entries.resolved}, so {@code Having: Hybrid} lists the
 * formula curves alone) and in the value without the owner's word ({@link #below}). Its writer turns them back: the
 * curve's pieces as they read now with the last one's numbers changed, written whole ({@link #pieces}); a Reset puts
 * the number without the owner's word back, and once the pieces read exactly as they would without the owner's entry,
 * and it writes nothing else, the entry comes out, so the curve reads as before.
 *
 * <p>The value without the owner's word is the curve below {@code track-curves.json} itself
 * ({@link TrackCurveConfig#resolveBelowFile}): a consumer's own word from its older settings, else the packs'. A
 * write keeps the pieces' {@code To} levels as they read now.
 */
public final class TrackCurveForm {

    /** The binding's id: what the form's {@code File} names. */
    public static final String BINDING = "ziggfreedcommon/track-curves.json";

    /** The id the milestone preview is registered under. */
    public static final String PREVIEW = "ziggfreedcommon:curve_milestones";

    /** The last piece's Hybrid numbers, as the form names them. */
    public static final String BASE = "Hybrid.Base";
    public static final String SCALE = "Hybrid.Scale";

    private static final String PIECES = "Pieces";
    private static final String HYBRID = "Hybrid";

    /** The levels the preview reads, for a curve that reaches them. */
    private static final int[] MILESTONES = {10, 25, 50, 100};

    private TrackCurveForm() {
    }

    /** The binding of {@code track-curves.json}, edited one curve at a time. */
    @Nonnull
    public static OwnerFileBinding binding() {
        TrackCurveConfig config = TrackCurveConfig.getInstance();
        OwnerFileBinding.Entries store = OwnerFileBinding.entriesOf(config, TrackCurveAsset.CODEC);
        return OwnerFileBinding.builder(BINDING, TrackCurveForm::file)
                .below(TrackCurveForm::below)
                .entries(new OwnerFileBinding.Entries() {
                    @Override
                    @Nonnull
                    public List<String> ids() {
                        return store.ids();
                    }

                    @Override
                    public boolean hasBelow(@Nonnull String id) {
                        return config.resolveBelowFile(id) != null;
                    }

                    @Override
                    @Nullable
                    public BsonDocument resolved(@Nonnull String id) {
                        BsonDocument doc = store.resolved(id);
                        TrackCurveAsset curve = config.resolve(id.trim());
                        if (doc != null && curve != null) {
                            liftHybrid(doc, lastHybrid(curve));
                        }
                        return doc;
                    }

                    @Override
                    public boolean accepts(@Nullable String id) {
                        return store.accepts(id);
                    }

                    @Override
                    public boolean put(@Nonnull Path file, @Nonnull String id, @Nonnull Map<String, Object> leaves) {
                        return write(store, file, id, leaves);
                    }

                    @Override
                    public boolean remove(@Nonnull Path file, @Nonnull String id) {
                        return store.remove(file, id);
                    }
                })
                .view(TrackCurveForm::view)
                .reload(TrackCurveOwnerLayers::reload)
                .build();
    }

    /** Where the owner's file lives right now. */
    @Nonnull
    static Path file() {
        return TrackCurveOwnerLayers.directory().resolve(TrackCurveOwnerLayers.FILE);
    }

    /**
     * A leaf of the curve {@code entry} without the owner's word: the last piece's Hybrid numbers for {@link #BASE} and
     * {@link #SCALE}, any other path read off the curve through its codec; null for none.
     */
    @Nullable
    static Object below(@Nullable String entry, @Nonnull String path) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        TrackCurveAsset curve = TrackCurveConfig.getInstance().resolveBelowFile(entry.trim());
        if (curve == null) {
            return null;
        }
        TrackCurveAsset.Piece last = lastHybrid(curve);
        if (BASE.equals(path)) {
            return last == null ? null : last.hybridBase();
        }
        if (SCALE.equals(path)) {
            return last == null ? null : last.hybridScale();
        }
        BsonValue encoded = TrackCurveAsset.CODEC.encode(curve, new ExtraInfo());
        return encoded != null && encoded.isDocument() ? plain(encoded.asDocument(), path) : null;
    }

    /**
     * The owner's file as the form reads it: each curve whose entry writes {@code Pieces} ending in a Hybrid piece gets
     * that piece's {@code Hybrid} group at the entry's own top, where the form's paths find it.
     */
    @Nonnull
    static JsonObject view(@Nonnull JsonObject root) {
        for (Map.Entry<String, JsonElement> curve : root.entrySet()) {
            if (OwnerFiles.isReservedKey(curve.getKey()) || !curve.getValue().isJsonObject()) {
                continue;
            }
            JsonObject entry = curve.getValue().getAsJsonObject();
            JsonElement pieces = entry.get(PIECES);
            if (pieces == null || !pieces.isJsonArray() || pieces.getAsJsonArray().isEmpty()) {
                continue;
            }
            JsonArray list = pieces.getAsJsonArray();
            JsonElement last = list.get(list.size() - 1);
            if (last != null && last.isJsonObject() && last.getAsJsonObject().get(HYBRID) != null
                    && last.getAsJsonObject().get(HYBRID).isJsonObject()) {
                entry.add(HYBRID, last.getAsJsonObject().get(HYBRID).deepCopy());
            }
        }
        return root;
    }

    /**
     * Write {@code leaves} into the owner's entry for {@code id}: the formula's numbers as the whole {@code Pieces}
     * list (the curve's pieces as they read now, the last one's numbers changed), every other leaf as given through
     * the store's entry writer; or the whole entry out when the pieces would read exactly as without it and the entry
     * holds nothing else. False, nothing written, for a curve not in force or one whose last piece is no Hybrid one
     * with its {@code To}.
     */
    static boolean write(@Nonnull OwnerFileBinding.Entries store, @Nonnull Path file, @Nonnull String id,
            @Nonnull Map<String, Object> leaves) {
        boolean baseGiven = leaves.containsKey(BASE);
        boolean scaleGiven = leaves.containsKey(SCALE);
        if (!baseGiven && !scaleGiven) {
            return store.put(file, id, leaves);
        }
        TrackCurveConfig config = TrackCurveConfig.getInstance();
        TrackCurveAsset inForce = config.resolve(id.trim());
        TrackCurveAsset below = config.resolveBelowFile(id.trim());
        List<TrackCurveAsset.Piece> next = inForce == null ? null : pieces(inForce, below,
                baseGiven, number(leaves.get(BASE)), scaleGiven, number(leaves.get(SCALE)));
        if (next == null) {
            return false;
        }
        Map<String, Object> out = new LinkedHashMap<>(leaves);
        out.remove(BASE);
        out.remove(SCALE);
        if (out.isEmpty() && below != null && samePieces(next, below.pieces()) && entryHoldsOnlyPieces(file, id)) {
            return store.remove(file, id);
        }
        out.put(PIECES, written(next));
        return store.put(file, id, out);
    }

    /**
     * The pieces a change to the formula writes: {@code inForce}'s pieces, the last one (a Hybrid piece with its
     * {@code To}) carrying the given numbers, a null one put back to {@code below}'s last Hybrid piece's (else left as
     * it reads now). Null when the last piece is no Hybrid one with its {@code To}. Pure.
     */
    @Nullable
    static List<TrackCurveAsset.Piece> pieces(@Nonnull TrackCurveAsset inForce, @Nullable TrackCurveAsset below,
            boolean baseGiven, @Nullable Double base, boolean scaleGiven, @Nullable Double scale) {
        List<TrackCurveAsset.Piece> pieces = inForce.pieces();
        TrackCurveAsset.Piece last = pieces.isEmpty() ? null : pieces.get(pieces.size() - 1);
        if (last == null || !last.hasHybrid() || last.hasFloors() || last.to() == null) {
            return null;
        }
        TrackCurveAsset.Piece under = below == null ? null : lastHybrid(below);
        double b = pick(baseGiven, base, under == null ? null : under.hybridBase(), last.hybridBase());
        double s = pick(scaleGiven, scale, under == null ? null : under.hybridScale(), last.hybridScale());
        List<TrackCurveAsset.Piece> out = new ArrayList<>(pieces);
        out.set(out.size() - 1, TrackCurveAsset.Piece.hybrid(b, s, last.to()));
        return out;
    }

    /** A number the change gives, else (a Reset) the one without the owner's word, else the one in force, else 0. */
    private static double pick(boolean given, @Nullable Double value, @Nullable Double under, @Nullable Double now) {
        if (given && value != null) {
            return value;
        }
        if (given && under != null) {
            return under;
        }
        return now == null ? 0.0 : now;
    }

    /**
     * {@code pieces} as the owner's file writes them: a Hybrid piece's {@code Base} and {@code Scale} as decimals and
     * its {@code To} as a whole number, a Floors piece's list as whole numbers, as the curve codec reads each.
     */
    @Nonnull
    static List<Object> written(@Nonnull List<TrackCurveAsset.Piece> pieces) {
        List<Object> out = new ArrayList<>();
        for (TrackCurveAsset.Piece piece : pieces) {
            Map<String, Object> one = new LinkedHashMap<>();
            if (piece.hasFloors()) {
                List<Object> floors = new ArrayList<>();
                for (long floor : piece.floorsOrEmpty()) {
                    floors.add(floor);
                }
                one.put("Floors", floors);
            } else if (piece.hasHybrid()) {
                Map<String, Object> hybrid = new LinkedHashMap<>();
                if (piece.hybridBase() != null) {
                    hybrid.put("Base", piece.hybridBase());
                }
                if (piece.hybridScale() != null) {
                    hybrid.put("Scale", piece.hybridScale());
                }
                one.put(HYBRID, hybrid);
            }
            if (!piece.hasFloors() && piece.to() != null) {
                one.put("To", piece.to());
            }
            out.add(one);
        }
        return out;
    }

    /** Do two piece lists read the same: each piece's floors, or its Hybrid numbers and {@code To}, in order? */
    static boolean samePieces(@Nonnull List<TrackCurveAsset.Piece> a, @Nonnull List<TrackCurveAsset.Piece> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            TrackCurveAsset.Piece x = a.get(i);
            TrackCurveAsset.Piece y = b.get(i);
            if (x.hasFloors() != y.hasFloors() || x.hasHybrid() != y.hasHybrid()
                    || !Arrays.equals(x.floorsOrEmpty(), y.floorsOrEmpty()) || !Objects.equals(x.to(), y.to())
                    || !sameNumber(x.hybridBase(), y.hybridBase()) || !sameNumber(x.hybridScale(), y.hybridScale())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameNumber(@Nullable Double a, @Nullable Double b) {
        double x = a == null ? 0.0 : a;
        double y = b == null ? 0.0 : b;
        return Double.compare(x, y) == 0;
    }

    /**
     * Does the owner's file hold, for {@code id}, no entry or one writing nothing but {@code Pieces} (a comment
     * aside)? False when the file cannot be read, so an entry is never taken out on a guess.
     */
    static boolean entryHoldsOnlyPieces(@Nonnull Path file, @Nonnull String id) {
        try {
            if (!Files.isRegularFile(file)) {
                return true;
            }
            String body = Files.readString(file, StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return true;
            }
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return false;
            }
            for (Map.Entry<String, JsonElement> e : parsed.getAsJsonObject().entrySet()) {
                if (OwnerFiles.isReservedKey(e.getKey()) || !e.getKey().equalsIgnoreCase(id.trim())) {
                    continue;
                }
                if (!e.getValue().isJsonObject()) {
                    return false;
                }
                for (String key : e.getValue().getAsJsonObject().keySet()) {
                    if (!PIECES.equals(key) && !OwnerFiles.isReservedKey(key)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (Exception unreadable) {
            return false;
        }
    }

    /**
     * The milestone preview: what reaching level 10, 25, 50 and 100 takes on curve {@code entryId} with the numbers
     * on the screen (a blank one reads as the number without the owner's word), the rest of the curve as it reads now;
     * a curve whose top is under 100 reads a quarter, a half, three quarters and all of its levels. Null when the
     * curve is not in force, its last piece is no Hybrid one, or a number on the screen is no number of 0 or more.
     */
    @Nullable
    public static Message preview(@Nullable String entryId, @Nonnull Map<String, String> values) {
        if (entryId == null || entryId.isBlank()) {
            return null;
        }
        TrackCurveConfig config = TrackCurveConfig.getInstance();
        TrackCurveAsset inForce = config.resolve(entryId.trim());
        if (inForce == null) {
            return null;
        }
        Double base = typed(values.get(BASE));
        Double scale = typed(values.get(SCALE));
        if (Double.isNaN(nullToZero(base)) || Double.isNaN(nullToZero(scale))) {
            return null;
        }
        List<TrackCurveAsset.Piece> next = pieces(inForce, config.resolveBelowFile(entryId.trim()), true, base, true,
                scale);
        if (next == null) {
            return null;
        }
        long every = inForce.beyondEvery();
        CurveTable table = TrackCurveAsset.of(entryId.trim(), next.toArray(new TrackCurveAsset.Piece[0]),
                every > 0 ? every : null, null).table();
        int[] levels = milestones(table.top());
        Object[] args = new Object[levels.length * 2];
        for (int i = 0; i < levels.length; i++) {
            args[i * 2] = levels[i];
            args[i * 2 + 1] = table.floorOf(levels[i]);
        }
        return TrackText.line("form.curves.preview", args);
    }

    /** The four levels the preview reads on a curve whose top level is {@code top}. */
    @Nonnull
    static int[] milestones(int top) {
        if (top >= MILESTONES[MILESTONES.length - 1]) {
            return MILESTONES.clone();
        }
        int at = Math.max(1, top);
        return new int[] {Math.max(1, at / 4), Math.max(1, at / 2), Math.max(1, at * 3 / 4), at};
    }

    /** A control's text as a number: null for blank, NaN for anything not a number of 0 or more. */
    @Nullable
    private static Double typed(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isFinite(value) && value >= 0 ? value : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double nullToZero(@Nullable Double value) {
        return value == null ? 0.0 : value;
    }

    @Nullable
    private static Double number(@Nullable Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    /** The last piece of {@code curve} when it is a Hybrid one, else null. */
    @Nullable
    static TrackCurveAsset.Piece lastHybrid(@Nonnull TrackCurveAsset curve) {
        List<TrackCurveAsset.Piece> pieces = curve.pieces();
        TrackCurveAsset.Piece last = pieces.isEmpty() ? null : pieces.get(pieces.size() - 1);
        return last != null && last.hasHybrid() && !last.hasFloors() ? last : null;
    }

    /** {@code doc} with {@code last}'s Hybrid numbers at its own top, where the form's paths find them. */
    private static void liftHybrid(@Nonnull BsonDocument doc, @Nullable TrackCurveAsset.Piece last) {
        if (last == null) {
            return;
        }
        BsonDocument hybrid = new BsonDocument();
        if (last.hybridBase() != null) {
            hybrid.put("Base", new BsonDouble(last.hybridBase()));
        }
        if (last.hybridScale() != null) {
            hybrid.put("Scale", new BsonDouble(last.hybridScale()));
        }
        doc.put(HYBRID, hybrid);
    }

    /** The leaf at a dotted path inside {@code doc} as a plain value (a Boolean, a Number, a String), else null. */
    @Nullable
    private static Object plain(@Nonnull BsonDocument doc, @Nonnull String path) {
        BsonValue cursor = doc;
        for (String part : path.split("\\.")) {
            if (cursor == null || !cursor.isDocument()) {
                return null;
            }
            cursor = cursor.asDocument().get(part);
        }
        if (cursor == null) {
            return null;
        }
        if (cursor.isBoolean()) {
            return cursor.asBoolean().getValue();
        }
        if (cursor.isInt32()) {
            return cursor.asInt32().getValue();
        }
        if (cursor.isInt64()) {
            return cursor.asInt64().getValue();
        }
        if (cursor.isDouble()) {
            return cursor.asDouble().getValue();
        }
        return cursor.isString() ? cursor.asString().getValue() : null;
    }
}
