package com.ziggfreed.common.objectives.admin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import com.ziggfreed.common.loot.LootGrants;
import com.ziggfreed.common.loot.LootRef;
import com.ziggfreed.common.loot.Roll;
import com.ziggfreed.common.loot.reward.LootRewardKinds;
import com.ziggfreed.common.loot.trigger.BonusMoment;
import com.ziggfreed.common.loot.trigger.BonusRowAsset;
import com.ziggfreed.common.loot.trigger.BonusRowConfig;
import com.ziggfreed.common.loot.trigger.BonusRowOwnerLayers;
import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.owner.OwnerFileBindings;
import com.ziggfreed.common.ui.form.OwnerFormAsset;
import com.ziggfreed.common.ui.form.OwnerFormField;
import com.ziggfreed.common.ui.form.OwnerFormKind;
import com.ziggfreed.common.ui.form.OwnerFormPlan;
import com.ziggfreed.common.util.OwnerFiles;
import com.ziggfreed.common.util.SafeLog;

/**
 * What the Kill Drops page shows right now, read once per open: every kill bonus row on the server (any mod's, and the
 * owner's own in {@code mods/ziggfreedcommon/bonus-rows.json}), a page of them at a time, each with what it covers and
 * pays and the four leaves of its own the page edits, each leaf's value in the owner file (if the file writes one) and
 * the value under the owner's word (the rows' binding's Default). Pure apart from reading the owner file and the rows.
 *
 * <p>A row's commands are never a leaf here: they are named and counted, and change in the owner file only, so a
 * settings permission is never a way to run a console command. A row whose id has characters an owner form never
 * writes under ({@link OwnerFormAsset#isEntry}) is shown and not edited.
 *
 * <p>The page is a stand-in for an owner form's row-per-id shape: each row is one entry of the keyed file, its leaves
 * written through the file's binding exactly as a form writes ({@code OwnerFormWrite}), so a form over the same file
 * can take its place with no change to the file.
 */
final class KillDropsPlan {

    /** Rows on one page of the list. */
    static final int ROWS_PER_PAGE = 5;

    /** A switch's two control values, as the settings form reads a three-state field. */
    static final String ON = "on";
    static final String OFF = "off";

    /** One leaf of a row the page edits: its path inside the row, its kind, its bounds and its words. */
    enum Setting {
        ENABLED("Enabled", OwnerFormKind.SWITCH, null, null, "enabled"),
        MATCH("When.Match", OwnerFormKind.TEXT, null, null, "match"),
        CHANCE("Chance.Base", OwnerFormKind.NUMBER, 0.0, 100.0, "chance"),
        ADDS_YIELD("AddsYield", OwnerFormKind.SWITCH, null, null, "yield");

        final String path;
        final OwnerFormKind kind;
        @Nullable final Double min;
        @Nullable final Double max;
        /** The setting's word under {@code admin.field.}, its label and its {@code .hint}. */
        final String word;

        Setting(String path, OwnerFormKind kind, @Nullable Double min, @Nullable Double max, String word) {
            this.path = path;
            this.kind = kind;
            this.min = min;
            this.max = max;
            this.word = word;
        }

        /**
         * Whether this leaf's value may be taken out of a row with nothing under the owner's word: a row's own
         * {@code When.Match} may not, since a row naming no mob covers every mob.
         */
        boolean mayGoBlankAlone() {
            return this != MATCH;
        }
    }

    /**
     * One leaf on the page.
     *
     * @param field    the writer's view: its index on the page and its path from the file's root
     * @param below    the value under the owner's word, or null when the layer under says nothing
     * @param ownerRaw the owner file's value as control text, or null when the file does not write it
     */
    record Leaf(@Nonnull Setting setting, @Nonnull OwnerFormField field, @Nullable Object below,
            @Nullable String ownerRaw) {

        boolean set() {
            return ownerRaw != null;
        }

        /** The text the control shows: the owner's value, else the one under it, else blank. */
        @Nonnull
        String shown() {
            return ownerRaw != null ? ownerRaw : OwnerFormPlan.raw(setting.kind, below);
        }
    }

    /**
     * One kill row on the page.
     *
     * @param id       the row's id as the page names it (the owner file's spelling where it writes one)
     * @param asset    the row as every layer folds it
     * @param editable whether the page writes its leaves
     * @param leaves   its leaves, empty when it is not editable
     */
    record Row(@Nonnull String id, @Nonnull BonusRowAsset asset, boolean editable, @Nonnull List<Leaf> leaves) {
    }

    /** What a row hands over, counted for its note. */
    record Pays(int rolls, @Nonnull List<String> tables, @Nonnull List<String> dropLists, int commands) {

        boolean nothing() {
            return rolls == 0 && tables.isEmpty();
        }
    }

    @Nullable private final OwnerFileBinding binding;
    private final boolean malformed;
    @Nonnull private final List<Row> rows;
    private final int page;
    private final int pages;
    private final int total;

    private KillDropsPlan(@Nullable OwnerFileBinding binding, boolean malformed, @Nonnull List<Row> rows, int page,
            int pages, int total) {
        this.binding = binding;
        this.malformed = malformed;
        this.rows = List.copyOf(rows);
        this.page = page;
        this.pages = pages;
        this.total = total;
    }

    /** Read page {@code requested} (zero-based, held inside the pages there are) of the server's kill rows. */
    @Nonnull
    static KillDropsPlan read(int requested) {
        OwnerFileBinding binding = OwnerFileBindings.find(BonusRowOwnerLayers.BINDING);
        JsonObject root = new JsonObject();
        boolean malformed = false;
        if (binding != null) {
            try {
                root = readObject(binding.file());
            } catch (Exception e) {
                malformed = true;
                SafeLog.warn("[loot] " + binding.file() + " could not be read for the kill drops screen: "
                        + e.getMessage());
            }
        }
        if (!OwnerFiles.schemaReadable(root, "loot", BonusRowOwnerLayers.FILE)) {
            malformed = true;
        }
        List<Map.Entry<String, BonusRowAsset>> all = killRows();
        int pages = Math.max(1, (all.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        int page = Math.max(0, Math.min(requested, pages - 1));
        int from = page * ROWS_PER_PAGE;
        int to = Math.min(all.size(), from + ROWS_PER_PAGE);
        List<Row> rows = new ArrayList<>();
        int index = 0;
        for (int i = from; i < to; i++) {
            Map.Entry<String, BonusRowAsset> entry = all.get(i);
            String key = keyIn(root, entry.getKey());
            String id = key != null ? key : nameOf(entry.getKey(), entry.getValue());
            boolean editable = OwnerFormAsset.isEntry(id);
            List<Leaf> leaves = new ArrayList<>();
            if (editable) {
                JsonObject scope = key == null ? null : objectAt(root, key);
                boolean shipped = BonusRowConfig.getInstance().resolveBelowOwner(entry.getKey()) != null;
                for (Setting setting : Setting.values()) {
                    OwnerFormField field = new OwnerFormField(index++, setting.kind, id + "." + setting.path,
                            setting.min, setting.max, List.of());
                    leaves.add(new Leaf(setting, field, below(binding, id, setting, shipped),
                            scope == null ? null : ownerRaw(setting.kind, leaf(scope, setting.path))));
                }
            }
            rows.add(new Row(id, entry.getValue(), editable, leaves));
        }
        return new KillDropsPlan(binding, malformed, rows, page, pages, all.size());
    }

    /** Every kill row the fold holds (switched off ones included, so they can be switched on again), by id. */
    @Nonnull
    static List<Map.Entry<String, BonusRowAsset>> killRows() {
        List<Map.Entry<String, BonusRowAsset>> out = new ArrayList<>();
        for (Map.Entry<String, BonusRowAsset> entry : BonusRowConfig.getInstance().all().entrySet()) {
            BonusRowAsset asset = entry.getValue();
            if (asset != null && asset.moment() == BonusMoment.KILL_MOB) {
                out.add(Map.entry(entry.getKey(), asset));
            }
        }
        out.sort(Comparator.comparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** What {@code asset} hands over: its rolls, the tables it names, the drop lists and the commands its grants name. */
    @Nonnull
    static Pays paysOf(@Nonnull BonusRowAsset asset) {
        LootRef loot = asset.getLoot();
        if (loot == null) {
            return new Pays(0, List.of(), List.of(), 0);
        }
        Roll[] rolls = loot.getRolls();
        List<String> tables = new ArrayList<>();
        if (loot.getLootables() != null) {
            for (String table : loot.getLootables()) {
                if (table != null && !table.isBlank()) {
                    tables.add(table.trim());
                }
            }
        }
        Set<String> dropLists = new LinkedHashSet<>();
        int commands = 0;
        int rollCount = 0;
        if (rolls != null) {
            for (Roll roll : rolls) {
                if (roll == null) {
                    continue;
                }
                rollCount++;
                commands += count(roll.getGrants(), dropLists);
                Roll.Ladder ladder = roll.getLadder();
                if (ladder != null && ladder.getFloors() != null) {
                    for (Roll.Ladder.Floor floor : ladder.getFloors()) {
                        if (floor != null) {
                            commands += count(floor.getGrants(), dropLists);
                        }
                    }
                }
            }
        }
        return new Pays(rollCount, tables, new ArrayList<>(dropLists), commands);
    }

    /** {@code grants}' commands (a {@code Command} reward among them), its drop lists added to {@code dropLists}. */
    private static int count(@Nullable LootGrants grants, @Nonnull Set<String> dropLists) {
        if (grants == null) {
            return 0;
        }
        if (grants.getDropLists() != null) {
            for (String list : grants.getDropLists()) {
                if (list != null && !list.isBlank()) {
                    dropLists.add(list.trim());
                }
            }
        }
        int commands = grants.getCommands() == null ? 0 : grants.getCommands().length;
        if (grants.getRewards() != null) {
            for (LootGrants.Reward reward : grants.getRewards()) {
                if (reward != null && LootRewardKinds.KIND_COMMAND.equalsIgnoreCase(reward.getKind())) {
                    commands++;
                }
            }
        }
        return commands;
    }

    /** The bonus rows' binding, or null when no installed module bound the file. */
    @Nullable
    OwnerFileBinding binding() {
        return binding;
    }

    /** May the page write: a bound file that reads. */
    boolean writable() {
        return binding != null && !malformed;
    }

    boolean malformed() {
        return malformed;
    }

    @Nonnull
    List<Row> rows() {
        return rows;
    }

    /** Every leaf on the page, in index order. */
    @Nonnull
    List<Leaf> leaves() {
        List<Leaf> out = new ArrayList<>();
        for (Row row : rows) {
            out.addAll(row.leaves());
        }
        return out;
    }

    /** The leaf at {@code index}, or null. */
    @Nullable
    Leaf leaf(int index) {
        List<Leaf> all = leaves();
        return index >= 0 && index < all.size() ? all.get(index) : null;
    }

    /** Every leaf's writer view, in index order. */
    @Nonnull
    List<OwnerFormField> fields() {
        List<OwnerFormField> out = new ArrayList<>();
        for (Leaf leaf : leaves()) {
            out.add(leaf.field());
        }
        return out;
    }

    int page() {
        return page;
    }

    int pages() {
        return pages;
    }

    int total() {
        return total;
    }

    /**
     * The value under the owner's word for {@code setting} of row {@code id}: the binding's Default, and for a switch
     * a row the packs ship but leave unwritten, the codec's own default (on).
     */
    @Nullable
    private static Object below(@Nullable OwnerFileBinding binding, @Nonnull String id, @Nonnull Setting setting,
            boolean shipped) {
        Object below = binding == null ? null : binding.below(id, setting.path);
        if (below == null && shipped && setting.kind == OwnerFormKind.SWITCH) {
            return Boolean.TRUE;
        }
        return below;
    }

    /** The name a row goes by when the owner file does not write it: its asset's own id, else its folded key. */
    @Nonnull
    private static String nameOf(@Nonnull String key, @Nonnull BonusRowAsset asset) {
        String id = asset.getId();
        return id == null || id.isBlank() ? key : id;
    }

    /** The key of {@code root} that names {@code id} without regard to case, or null when the file writes none. */
    @Nullable
    private static String keyIn(@Nonnull JsonObject root, @Nonnull String id) {
        for (String key : root.keySet()) {
            if (!OwnerFiles.isReservedKey(key) && key.trim().equalsIgnoreCase(id.trim())) {
                return key;
            }
        }
        return null;
    }

    @Nullable
    private static JsonObject objectAt(@Nonnull JsonObject root, @Nonnull String key) {
        JsonElement value = root.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    @Nullable
    private static JsonElement leaf(@Nonnull JsonObject scope, @Nonnull String path) {
        JsonElement cursor = scope;
        for (String part : path.split("\\.")) {
            if (cursor == null || !cursor.isJsonObject()) {
                return null;
            }
            cursor = cursor.getAsJsonObject().get(part);
        }
        return cursor;
    }

    /** The owner file's leaf as control text, or null when the file does not write it. */
    @Nullable
    private static String ownerRaw(@Nonnull OwnerFormKind kind, @Nullable JsonElement leaf) {
        if (leaf == null || leaf.isJsonNull() || leaf.isJsonObject()) {
            return null;
        }
        if (leaf.isJsonArray()) {
            List<Object> items = new ArrayList<>();
            for (JsonElement item : (JsonArray) leaf) {
                if (item != null && item.isJsonPrimitive()) {
                    items.add(item.getAsString());
                }
            }
            return OwnerFormPlan.raw(kind, items);
        }
        JsonPrimitive primitive = leaf.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return OwnerFormPlan.raw(kind, primitive.getAsBoolean());
        }
        return primitive.getAsString();
    }

    /** The file's root object: empty when absent or blank; throws when it is not a JSON object. */
    @Nonnull
    private static JsonObject readObject(@Nonnull Path file) throws Exception {
        if (!Files.exists(file)) {
            return new JsonObject();
        }
        String body = Files.readString(file, StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return new JsonObject();
        }
        JsonElement parsed = JsonParser.parseString(body);
        if (!parsed.isJsonObject()) {
            throw new IllegalStateException("its root is not a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    /** A setting's lang word, lower-cased for its key. */
    @Nonnull
    static String word(@Nonnull Setting setting) {
        return setting.word.toLowerCase(Locale.ROOT);
    }

    /**
     * Whether writing {@code leaves} (dotted paths from the file's root, a null value taking a leaf out) would leave a
     * row of the owner's own with no mobs named, which would widen it to every mob: the first such leaf, or null.
     */
    @Nullable
    Leaf blankedAlone(@Nonnull Map<String, Object> leaves) {
        for (Leaf leaf : leaves()) {
            if (!leaf.setting().mayGoBlankAlone() && leaf.below() == null
                    && leaves.containsKey(leaf.field().writePath()) && leaves.get(leaf.field().writePath()) == null) {
                return leaf;
            }
        }
        return null;
    }
}
