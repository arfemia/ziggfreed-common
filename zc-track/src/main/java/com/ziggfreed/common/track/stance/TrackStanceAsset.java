package com.ziggfreed.common.track.stance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.commerce.asset.CostAsset;
import com.ziggfreed.common.grant.BuiltInGrants;
import com.ziggfreed.common.grant.Grant;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.asset.SlotRow;
import com.ziggfreed.common.track.pick.TrackPickAsset;

/**
 * One stance, at {@code Server/ZiggfreedCommon/TrackStances/<Owner>/<Id>.json}: a set of picks a player owns for good
 * once taken (its faces, each with the steps that name it, {@code TrackPickAsset}'s {@code Stance} group) but that
 * count only while held in one of the stance's slots. The FILE NAME is the id; the folders are the author's own. Every
 * number and rule of it is data here, under the owner file {@code mods/ziggfreedcommon/track-stances.json}:
 *
 * <ul>
 *   <li>{@code Slots}: the places a face is held in, rows of the shared {@link SlotRow} shape, each open while its
 *       {@code Requires} holds and its {@code Pick} is owned; a third slot is one more row.</li>
 *   <li>{@code Kinds}: per kind of face (your own words), the most held at once ({@code Max}) and what holding one adds
 *       ({@code WhileHeld}, grants of any kind, a negative amount allowed, lifted the moment it is released).</li>
 *   <li>{@code Hold} and {@code Break}: what filling a slot and releasing a face cost ({@code Cost}, the library's one
 *       price shape), both free unwritten; a release a face's loss forces (its pick undone or respecced, its slot
 *       closed) is always free.</li>
 *   <li>{@code Where}: the placements whose page holds and releases; none means anywhere.</li>
 *   <li>{@code Budget}: the most one face's live grants may add, per stat id or grant family, which the validator
 *       holds every face to ({@code STANCE_FACE_OVER_BUDGET}, a WARNING) and a consumer's audit may read.</li>
 * </ul>
 *
 * <pre>{@code
 * { "Text": { "TitleKey": "yourmod.stance.name" }, "Icon": "Weapon_Sword_Iron",
 *   "Slots": [ { "Id": "I" }, { "Id": "II", "Pick": "Your_Second_Slot_Key" } ],
 *   "Kinds": { "Weapon": { "Max": 1, "WhileHeld": [ { "Kind": "Stat", "Stat": "Health", "Add": -25 } ] } },
 *   "Break": { "Cost": { "Currencies": { "Your_Coin": 500 } } },
 *   "Where": { "Placements": [ "Your_Trainer" ] },
 *   "Budget": { "DamageBonus_Physical": 25, "Health": 40 } }
 * }</pre>
 */
public final class TrackStanceAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, TrackStanceAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/TrackStances";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Boolean enabled;
    @Nullable protected ContentTextAsset text;
    @Nullable protected String icon;
    @Nullable protected Integer order;
    @Nullable protected SlotRow[] slots;
    @Nullable protected Map<String, Kind> kinds;
    @Nullable protected Fee hold;
    @Nullable protected Fee release;
    @Nullable protected TrackPickAsset.Where where;
    @Nullable protected Map<String, Double> budget;

    static {
        // WhileHeld decodes the library's own grant kinds in any JVM.
        BuiltInGrants.ensure();
    }

    public static final AssetBuilderCodec<String, TrackStanceAsset> CODEC = AssetBuilderCodec.builder(
                    TrackStanceAsset.class,
                    TrackStanceAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether this stance exists on this server; unauthored means true. False holds nothing: "
                    + "every pick in it counts nowhere and what holding a face adds is taken off, while what each "
                    + "player holds is kept, unread, until it is switched back on.").add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the stance is called (TitleKey) and a line on what it is for (FlavorKey), as "
                    + "localization keys in your own lang file. Without a TitleKey it is named by its id.").add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .metadata(EditorSchema.assetRef(Item.class))
            .documentation("The item whose picture stands for the stance.").add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .metadata(EditorSchema.defaultValue(0L))
            .documentation("Where the stance sorts among the others on its page: lower first, then by id.").add()
            .appendInherited(new KeyedCodec<>("Slots", SlotRow.ARRAY_CODEC, false),
                    (a, v) -> a.slots = v, a -> a.slots, (a, p) -> a.slots = p.slots)
            .documentation("The slots a face is held in, in the order they fill (the lowest open one first): each "
                    + "row its Id, and the Requires and Pick that open it (a row naming neither is open from the "
                    + "start). How many faces a player may hold is how many rows are open, so a third slot is one "
                    + "more row. A face whose slot closes is released, free. Under Parent this list replaces the "
                    + "parent's whole.").add()
            .appendInherited(new KeyedCodec<>("Kinds", new InheritMapCodec<>(Kind.CODEC).documented(
                    "Per kind of face, by the word a face's Stance.Kind writes.",
                    "One kind: how many of it may be held at once, and what holding one adds."), false),
                    (a, v) -> a.kinds = v, a -> a.kinds, (a, p) -> a.kinds = p.kinds)
            .documentation("Per kind of face (your own words, such as Weapon or Element): the most held at once "
                    + "(Max) and what holding one adds (WhileHeld). Under Parent, or in the owner file, a kind "
                    + "written merges into the one below it leaf by leaf.").add()
            .appendInherited(new KeyedCodec<>("Hold", Fee.CODEC, false),
                    (a, v) -> a.hold = v, a -> a.hold, (a, p) -> a.hold = p.hold)
            .documentation("What filling a slot with a face costs, charged as it is held. Free when unauthored.")
            .add()
            .appendInherited(new KeyedCodec<>("Break", Fee.CODEC, false),
                    (a, v) -> a.release = v, a -> a.release, (a, p) -> a.release = p.release)
            .documentation("What releasing a held face costs, charged as the player releases it (never in a fight "
                    + "or an encounter). A face released because it was lost (its pick undone or respecced, its "
                    + "slot closed, an admin's release) is always free. Free when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Where", TrackPickAsset.Where.CODEC, false),
                    (a, v) -> a.where = v, a -> a.where, (a, p) -> a.where = p.where)
            .documentation("Where a player holds and releases a face: the NPC placements (trainers) whose page "
                    + "does it. A placement switched off or gone counts as none; with none written or none "
                    + "standing, anywhere the stance's page opens.").add()
            .appendInherited(new KeyedCodec<>("Budget", new InheritMapCodec<>(Codec.DOUBLE).documented(
                    "Per stat id or grant family, the most one face may add.",
                    "The most one face's live grants may add to it."), false),
                    (a, v) -> a.budget = v, a -> a.budget, (a, p) -> a.budget = p.budget)
            .documentation("The most one face's live grants (the face and every step naming it, each at its full "
                    + "rank, an either-or at its largest face) may add, per stat id (such as Health) or grant "
                    + "family (a stat's ZigGrantFamily tag, such as Combat). The validator warns of a face past it "
                    + "(STANCE_FACE_OVER_BUDGET); nothing is capped while playing. What holding a face costs "
                    + "(Kinds WhileHeld) is not counted.").add()
            .build();

    public TrackStanceAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** Is the stance switched on? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    /** The localization key the stance is named by, or null. */
    @Nullable
    public String titleKey() {
        String key = text == null ? null : text.getTitleKey();
        return key == null || key.isBlank() ? null : key.trim();
    }

    /** The localization key of the stance's description, or null. */
    @Nullable
    public String flavorKey() {
        String key = text == null ? null : text.getFlavorKey();
        return key == null || key.isBlank() ? null : key.trim();
    }

    /** The item standing for the stance, or null. */
    @Nullable
    public String icon() {
        return icon == null || icon.isBlank() ? null : icon.trim();
    }

    public int order() {
        return order == null ? 0 : order;
    }

    /** The slot rows, in fill order ({@link SlotRow#rows}: a row with no id, or repeating one, left out). */
    @Nonnull
    public List<SlotRow> slots() {
        return SlotRow.rows(slots);
    }

    /** Every kind, by lower-cased word, in authored order. */
    @Nonnull
    public Map<String, Kind> kinds() {
        if (kinds == null || kinds.isEmpty()) {
            return Map.of();
        }
        Map<String, Kind> out = new LinkedHashMap<>();
        kinds.forEach((word, kind) -> {
            String key = TrackKey.normalize(word);
            if (!key.isEmpty() && kind != null) {
                out.putIfAbsent(key, kind);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /** The kind {@code word} names (any case), or null for none. */
    @Nullable
    public Kind kind(@Nullable String word) {
        return word == null ? null : kinds().get(TrackKey.normalize(word));
    }

    /** What filling a slot costs ({@code Hold.Cost}), or null for free. */
    @Nullable
    public CostAsset holdCost() {
        return hold == null ? null : hold.cost();
    }

    /** What a player's own release costs ({@code Break.Cost}), or null for free. */
    @Nullable
    public CostAsset breakCost() {
        return release == null ? null : release.cost();
    }

    /** The placements whose page holds and releases, lower-cased; empty for anywhere. */
    @Nonnull
    public List<String> placements() {
        return where == null ? List.of() : where.placements();
    }

    /** The budget, per stat id or family as written (blank keys and non-finite amounts left out), in authored order. */
    @Nonnull
    public Map<String, Double> budget() {
        if (budget == null || budget.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> out = new LinkedHashMap<>();
        budget.forEach((key, most) -> {
            if (key != null && !key.isBlank() && most != null && Double.isFinite(most)) {
                out.put(key.trim(), most);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /** One kind of face: what it is called, how many may be held at once, and what holding one adds. */
    public static final class Kind {

        @Nullable protected ContentTextAsset text;
        @Nullable protected Integer max;
        @Nullable protected Grant[] whileHeld;

        public static final BuilderCodec<Kind> CODEC = BuilderCodec.builder(Kind.class, Kind::new)
                .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                        (o, v) -> o.text = v, o -> o.text, (o, p) -> o.text = p.text)
                .documentation("What this kind is called (TitleKey), as a localization key: what a player reads "
                        + "when the stance holds as many of it as it may. Without one it is named by its word.")
                .add()
                .appendInherited(new KeyedCodec<>("Max", Codec.INTEGER, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("The most faces of this kind held at once, whatever slots are open. 0 or less, or "
                        + "unauthored, sets no limit of its own. Lowered on a live server, the faces past it are "
                        + "released at each player's next settle, the latest slot first, free.").add()
                .appendInherited(new KeyedCodec<>("WhileHeld", Grant.ARRAY_CODEC, false),
                        (o, v) -> o.whileHeld = v, o -> o.whileHeld, (o, p) -> o.whileHeld = p.whileHeld)
                .documentation("What holding one face of this kind adds, once per face held, taken off the moment "
                        + "it is released: grants of any kind, a negative amount allowed (a cost to the body, such "
                        + "as { \"Kind\": \"Stat\", \"Stat\": \"Health\", \"Add\": -25 }). Under Parent this list "
                        + "replaces the parent's whole.").add()
                .build();

        public Kind() {
        }

        @Nullable
        public ContentTextAsset getText() {
            return text;
        }

        /** The localization key this kind is named by, or null. */
        @Nullable
        public String titleKey() {
            String key = text == null ? null : text.getTitleKey();
            return key == null || key.isBlank() ? null : key.trim();
        }

        /** The most held at once: {@link Integer#MAX_VALUE} for no limit of its own. */
        public int max() {
            return max == null || max <= 0 ? Integer.MAX_VALUE : max;
        }

        /** What holding one adds (null entries left out). */
        @Nonnull
        public List<Grant> whileHeld() {
            List<Grant> out = new ArrayList<>();
            if (whileHeld != null) {
                for (Grant grant : whileHeld) {
                    if (grant != null) {
                        out.add(grant);
                    }
                }
            }
            return List.copyOf(out);
        }
    }

    /** A fee group ({@code Hold}, {@code Break}): the library's one price shape. */
    public static final class Fee {

        @Nullable protected CostAsset cost;

        public static final BuilderCodec<Fee> CODEC = BuilderCodec.builder(Fee.class, Fee::new)
                .appendInherited(new KeyedCodec<>("Cost", CostAsset.CODEC, false),
                        (o, v) -> o.cost = v, o -> o.cost, (o, p) -> o.cost = p.cost)
                .documentation("The price, in any wallet or item (the one price shape shops use). Free when "
                        + "unauthored.").add()
                .build();

        public Fee() {
        }

        /** The price, or null for free. */
        @Nullable
        public CostAsset cost() {
            return cost == null || cost.isFree() ? null : cost;
        }
    }
}
