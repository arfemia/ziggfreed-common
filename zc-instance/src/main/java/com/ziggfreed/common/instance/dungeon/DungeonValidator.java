package com.ziggfreed.common.instance.dungeon;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.builtin.adventure.worldevents.WorldEventsConfig;
import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.portalworld.PortalType;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.ziggfreed.common.encounter.validate.EncounterScriptScan;
import com.ziggfreed.common.encounter.validate.EncounterScripts;
import com.ziggfreed.common.encounter.wave.WaveRamp;
import com.ziggfreed.common.encounter.wave.WaveRules;
import com.ziggfreed.common.instance.dungeon.layout.Cell;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan;
import com.ziggfreed.common.instance.dungeon.layout.RoomCatalogue;
import com.ziggfreed.common.instance.dungeon.layout.RoomCatalogueAudit;
import com.ziggfreed.common.instance.dungeon.layout.asset.DungeonLayoutConfig;
import com.ziggfreed.common.instance.dungeon.layout.asset.DungeonRoomConfig;
import com.ziggfreed.common.instance.dungeon.layout.asset.PrefabPoolIndex;
import com.ziggfreed.common.instance.dungeon.room.RoomLogicAudit;
import com.ziggfreed.common.loot.LootableAsset;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.season.SeasonGate;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;
import com.ziggfreed.common.validation.TextKeyAudit;

/**
 * The content audit over the folded dungeons, filed under the {@code dungeon} domain, run once every pack
 * has loaded (the boot audit's pass). A switched-off dungeon is skipped.
 *
 * <p>Split the way every validator in this family is: {@link #audit()} is the thin engine walk (the loaded
 * packs' instance templates and prefabs, the server's view distance, the gameplay configs) and
 * {@link #audit(Collection, Predicate, Function, Predicate, Predicate, int, Predicate)} the pure core.
 *
 * <p>The codes:
 * <ul>
 *   <li>ERROR {@link #TEMPLATE_INSTANCE_KEY} (the engine converges every spawn and return that goes by a
 *       template's {@code InstanceKey} on one world, which a copy zc names must never share), {@link #SPACING_TOO_SMALL}
 *       (areas closer than twice the view distance see each other), {@link #PACKED_PORTAL_TIMER} and
 *       {@link #PACKED_WORLD_EVENTS} (a portal's timer and world events belong to the whole world, so a copy
 *       shared by several parties would share them), and for an endless run ({@code Run.Endless})
 *       {@link #ENDLESS_NO_WAVE_POOL} (no band names a slot, so no wave could rise and every empty wave would be
 *       beaten at once), {@link #ENDLESS_BOSS_EVERY_TOO_LOW} (a boss every wave, or a spacing of none, is no wave
 *       run) and {@link #ENDLESS_RAMP_UNCAPPED} (a ramp with a step and no {@code Max} grows until the library's
 *       ceiling);</li>
 *   <li>WARNING {@link #UNKNOWN_DUNGEON_TEMPLATE}, {@link #UNKNOWN_DUNGEON_PREFAB},
 *       {@link #NO_DUNGEON_PREFAB}, {@link #UNKNOWN_DUNGEON_BOSS}, {@link #UNKNOWN_DUNGEON_LOOTABLE} and
 *       {@link #UNKNOWN_DUNGEON_COST_ITEM} (an id may belong to a pack this server does not run; an
 *       {@code EntryCost} item no loaded item answers to is one nobody can pay, so the dungeon refuses everyone who
 *       would open an area, and such a key in {@code EntryCost.Keys} one nobody can hold),
 *       {@link #NO_DUNGEON_CLEAR} (nothing in {@code Complete} can
 *       clear a run, or its survive timer outlasts its time limit, so every run ends failed),
 *       {@link #GATED_DUNGEON_LOOTABLE} (a table it pays from asks something of the player beyond mod presence,
 *       which a member who is offline when it rolls never meets), and the shared {@code UNKNOWN_SEASON} and
 *       {@code UNKNOWN_TEXT_KEY};</li>
 *   <li>ERROR {@link #ENDLESS_WITH_LAYOUT}: a generated dungeon cannot be endless yet, since an endless run's chest
 *       and every area spot it reads stand at the area's paste spot, which a generated copy leaves as void (its
 *       runs stand further along the lane), so the entry refuses it;</li>
 *   <li>for a generated dungeon ({@code Area.Layout}): WARNING {@link #UNKNOWN_DUNGEON_LAYOUT} (no loaded file
 *       defines the plan, so nobody can enter), {@link #LAYOUT_AND_PREFAB} (it names a prefab too, which is never
 *       pasted), {@link #LANES_CLAMPED} (its copies hold fewer areas than it asks, the furthest lane kept within
 *       {@link RunLanes#FAR_LIMIT}) and {@link #LAYOUT_TOO_TALL} (its plan reaches past the world's top); the prefab
 *       checks are skipped; and the engine walk runs the room catalogue's own audit
 *       ({@code layout/RoomCatalogueAudit}) once over every plan an enabled dungeon names, then the room logic's
 *       ({@code room/RoomLogicAudit}: ERROR {@code LOGIC_WORLD_ABSOLUTE}, WARNING {@code LOGIC_WORLD_WIDE}) over every
 *       room such a plan may place, its findings filed under the same domain;</li>
 *   <li>the engine walk then runs the tiers and omens pass ({@link DungeonReplayAudit}, WARNINGs only).</li>
 * </ul>
 */
public final class DungeonValidator {

    public static final String DOMAIN = "dungeon";

    /** The label the dungeon audit's lines carry when it is logged whole (zc's boot audit). */
    public static final String LOG_LABEL = "[dungeon] audit";

    public static final String UNKNOWN_DUNGEON_TEMPLATE = "UNKNOWN_DUNGEON_TEMPLATE";
    public static final String UNKNOWN_DUNGEON_PREFAB = "UNKNOWN_DUNGEON_PREFAB";
    public static final String NO_DUNGEON_PREFAB = "NO_DUNGEON_PREFAB";
    public static final String TEMPLATE_INSTANCE_KEY = "TEMPLATE_INSTANCE_KEY";
    public static final String SPACING_TOO_SMALL = "SPACING_TOO_SMALL";
    public static final String PACKED_PORTAL_TIMER = "PACKED_PORTAL_TIMER";
    public static final String PACKED_WORLD_EVENTS = "PACKED_WORLD_EVENTS";
    public static final String NO_DUNGEON_CLEAR = "NO_DUNGEON_CLEAR";
    public static final String UNKNOWN_DUNGEON_BOSS = "UNKNOWN_DUNGEON_BOSS";
    public static final String UNKNOWN_DUNGEON_LOOTABLE = "UNKNOWN_DUNGEON_LOOTABLE";
    public static final String GATED_DUNGEON_LOOTABLE = "GATED_DUNGEON_LOOTABLE";
    public static final String UNKNOWN_DUNGEON_COST_ITEM = "UNKNOWN_DUNGEON_COST_ITEM";
    public static final String ENDLESS_NO_WAVE_POOL = "ENDLESS_NO_WAVE_POOL";
    public static final String ENDLESS_BOSS_EVERY_TOO_LOW = "ENDLESS_BOSS_EVERY_TOO_LOW";
    public static final String ENDLESS_RAMP_UNCAPPED = "ENDLESS_RAMP_UNCAPPED";
    public static final String UNKNOWN_DUNGEON_LAYOUT = "UNKNOWN_DUNGEON_LAYOUT";
    public static final String LAYOUT_AND_PREFAB = "LAYOUT_AND_PREFAB";
    public static final String LANES_CLAMPED = "LANES_CLAMPED";
    public static final String LAYOUT_TOO_TALL = "LAYOUT_TOO_TALL";
    public static final String ENDLESS_WITH_LAYOUT = "ENDLESS_WITH_LAYOUT";

    /** The template's config file, inside its folder under {@code Server/Instances/}. */
    static final String TEMPLATE_CONFIG = "instance.bson";

    /** The gameplay config a template naming none runs (the engine's own default id). */
    static final String DEFAULT_GAMEPLAY_CONFIG = "Default";

    /** The view radius, in chunks, assumed when the server's cannot be read: the engine's own ceiling. */
    static final int FALLBACK_VIEW_RADIUS = 32;

    /**
     * What the audit reads off a template's {@code instance.bson}: the {@code InstanceKey} it pins (null for
     * none), whether it carries a portal's timer, and whether world events run in it.
     */
    public record TemplateFacts(@Nullable String instanceKey, boolean portalTimed, boolean worldEvents) {
    }

    /** A dungeon's entry verdict: the loaded asset it was read for, and its ERROR codes. */
    private record EntryVerdict(@Nonnull DungeonAsset dungeon, @Nonnull List<String> errors) {
    }

    /** Each dungeon's entry verdict, by the fold's lower-cased id. */
    private static final Map<String, EntryVerdict> ENTRY_VERDICTS = new ConcurrentHashMap<>();

    private DungeonValidator() {
    }

    /**
     * The ERROR codes the audit finds in {@code dungeon} as loaded now, filed under {@code id} (the fold's
     * lower-cased id); empty when it has none. The entry refuses a dungeon with any, since its copies would break
     * what shared areas rely on. The boot audit runs only when the process asks for it, so the entry asks here:
     * the same engine walk, once per loaded asset (a reload folds new ones), each ERROR's sentence logged once.
     */
    @Nonnull
    public static List<String> entryErrors(@Nonnull String id, @Nonnull DungeonAsset dungeon) {
        EntryVerdict known = ENTRY_VERDICTS.get(id);
        if (known != null && known.dungeon() == dungeon) {
            return known.errors();
        }
        List<String> errors = new ArrayList<>();
        try {
            for (Finding finding : audit(List.of(dungeon), DungeonValidator::templateKnown,
                    DungeonValidator::templateFacts, DungeonValidator::prefabKnown, script -> true, table -> true,
                    minimumSpacing(), key -> true)) {
                if (finding.severity() == Severity.ERROR) {
                    errors.add(finding.code());
                    SafeLog.warn(LOG_LABEL + " '" + id + "' [" + finding.code() + "] " + finding.message()
                            + "; it cannot be entered until this is fixed");
                }
            }
        } catch (Throwable t) {
            // What could not be told is no ERROR, and the verdict is not kept: the next entry asks again.
            SafeLog.warn("[dungeon] the entry audit of '" + id + "' failed: " + t.getMessage(), t);
            return List.copyOf(errors);
        }
        List<String> verdict = List.copyOf(errors);
        ENTRY_VERDICTS.put(id, new EntryVerdict(dungeon, verdict));
        return verdict;
    }

    /**
     * The engine walk over every folded dungeon, then the room catalogue's own audit over every layout plan an enabled
     * dungeon names.
     */
    @Nonnull
    public static List<Finding> audit() {
        try {
            Collection<DungeonAsset> dungeons = DungeonConfig.getInstance().all().values();
            List<Finding> out = new ArrayList<>(audit(dungeons,
                    DungeonValidator::templateKnown,
                    DungeonValidator::templateFacts,
                    DungeonValidator::prefabKnown,
                    loadedScripts(),
                    DungeonValidator::lootableKnown,
                    DungeonValidator::lootableGated,
                    DungeonValidator::itemKnown,
                    minimumSpacing(),
                    TextKeyAudit.liveCatalogue(),
                    plan -> DungeonLayoutConfig.getInstance().plan(plan)));
            out.addAll(catalogueAudit(dungeons));
            out.addAll(DungeonReplayAudit.audit());
            return out;
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] the dungeon audit failed: " + t.getMessage(), t);
            return List.of();
        }
    }

    /**
     * The room catalogue's audit ({@link RoomCatalogueAudit}) over every layout plan an enabled dungeon names and some
     * loaded file defines, each plan once, the catalogue counted against the packs loaded now; none when no dungeon
     * is generated.
     */
    @Nonnull
    static List<Finding> catalogueAudit(@Nonnull Collection<DungeonAsset> dungeons) {
        Map<String, LayoutPlan> plans = new LinkedHashMap<>();
        for (DungeonAsset dungeon : dungeons) {
            LayoutPlan plan = dungeon == null || !dungeon.isEnabled() ? null : dungeon.layoutPlan();
            if (plan != null) {
                plans.putIfAbsent(plan.id(), plan);
            }
        }
        if (plans.isEmpty()) {
            return List.of();
        }
        PrefabPoolIndex pools = PrefabPoolIndex.live();
        RoomCatalogue catalogue = DungeonRoomConfig.getInstance().catalogue(pools);
        List<Finding> out = new ArrayList<>(RoomCatalogueAudit.audit(catalogue, plans.values()));
        out.addAll(RoomLogicAudit.audit(catalogue, pools, plans.values()));
        return out;
    }

    /**
     * The pure core.
     *
     * @param templateKnown  whether a template id names a folder some loaded pack ships under {@code Server/Instances/}
     * @param templateFacts  what a known template's config says ({@link TemplateFacts}), null when it cannot be read
     * @param prefabKnown    whether a path under {@code Server/Prefabs/} names a prefab some loaded pack ships
     * @param scriptKnown    whether an id names an encounter script some loaded pack ships
     * @param lootableKnown  whether an id names a loaded loot table ({@code Server/ZiggfreedCommon/Lootables/})
     * @param minimumSpacing the least {@code Area.Spacing}, in blocks, that keeps areas out of each other's view
     * @param textShipped    whether a lang key is shipped ({@link TextKeyAudit#liveCatalogue})
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<DungeonAsset> dungeons,
            @Nonnull Predicate<String> templateKnown, @Nonnull Function<String, TemplateFacts> templateFacts,
            @Nonnull Predicate<String> prefabKnown, @Nonnull Predicate<String> scriptKnown,
            @Nonnull Predicate<String> lootableKnown, int minimumSpacing, @Nonnull Predicate<String> textShipped) {
        return audit(dungeons, templateKnown, templateFacts, prefabKnown, scriptKnown, lootableKnown, table -> false,
                item -> true, minimumSpacing, textShipped);
    }

    /**
     * The pure core, with {@code lootableGated} answering whether a loaded table asks something of the player beyond
     * mod presence (zc-loot's {@code LootableAsset.gatesOnPlayer}) and {@code itemKnown} whether an id names a loaded
     * item (an {@code EntryCost} item); the form without them reads no table as gated and every item as known.
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<DungeonAsset> dungeons,
            @Nonnull Predicate<String> templateKnown, @Nonnull Function<String, TemplateFacts> templateFacts,
            @Nonnull Predicate<String> prefabKnown, @Nonnull Predicate<String> scriptKnown,
            @Nonnull Predicate<String> lootableKnown, @Nonnull Predicate<String> lootableGated,
            @Nonnull Predicate<String> itemKnown, int minimumSpacing, @Nonnull Predicate<String> textShipped) {
        return audit(dungeons, templateKnown, templateFacts, prefabKnown, scriptKnown, lootableKnown, lootableGated,
                itemKnown, minimumSpacing, textShipped, DungeonValidator::anyPlan);
    }

    /**
     * The pure core, with {@code layoutPlans} answering the loaded layout plan an id names
     * ({@code Server/ZiggfreedCommon/DungeonLayouts/}), or null for none; the forms without it read every plan as
     * loaded, at the default bounds.
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<DungeonAsset> dungeons,
            @Nonnull Predicate<String> templateKnown, @Nonnull Function<String, TemplateFacts> templateFacts,
            @Nonnull Predicate<String> prefabKnown, @Nonnull Predicate<String> scriptKnown,
            @Nonnull Predicate<String> lootableKnown, @Nonnull Predicate<String> lootableGated,
            @Nonnull Predicate<String> itemKnown, int minimumSpacing, @Nonnull Predicate<String> textShipped,
            @Nonnull Function<String, LayoutPlan> layoutPlans) {
        List<Finding> out = new ArrayList<>();
        for (DungeonAsset dungeon : dungeons) {
            if (dungeon == null || dungeon.getId() == null || !dungeon.isEnabled()) {
                continue;
            }
            auditOne(dungeon, templateKnown, templateFacts, prefabKnown, scriptKnown, lootableKnown, lootableGated,
                    itemKnown, minimumSpacing, textShipped, layoutPlans, out);
        }
        return out;
    }

    /** A plan read as loaded at the default bounds, for the audit forms that are not asked about plans. */
    @Nonnull
    private static LayoutPlan anyPlan(@Nonnull String id) {
        return new LayoutPlan(id, List.of(), List.of(), List.of(), null, List.of(), null, null, 0);
    }

    private static void auditOne(@Nonnull DungeonAsset dungeon, @Nonnull Predicate<String> templateKnown,
            @Nonnull Function<String, TemplateFacts> templateFacts, @Nonnull Predicate<String> prefabKnown,
            @Nonnull Predicate<String> scriptKnown, @Nonnull Predicate<String> lootableKnown,
            @Nonnull Predicate<String> lootableGated, @Nonnull Predicate<String> itemKnown, int minimumSpacing,
            @Nonnull Predicate<String> textShipped, @Nonnull Function<String, LayoutPlan> layoutPlans,
            @Nonnull List<Finding> out) {
        String id = dungeon.getId();
        String where = "dungeon '" + id + "'";
        boolean shared = dungeon.areasPerCopy() > 1;

        String template = dungeon.templateId();
        if (!asks(templateKnown, template)) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_DUNGEON_TEMPLATE, where + " names the instance template '"
                    + template + "', which no loaded pack ships under Server/Instances/ (its pack may not be "
                    + "installed here), so no copy of it can open", id));
        } else {
            TemplateFacts facts = facts(templateFacts, template);
            if (facts != null && facts.instanceKey() != null) {
                out.add(Finding.error(DOMAIN, TEMPLATE_INSTANCE_KEY, where + " copies the template '" + template
                        + "', which pins its world with the InstanceKey '" + facts.instanceKey() + "': every other "
                        + "spawn or return that goes by that key converges on one world, which the dungeon's copies "
                        + "must not share. Name a template without an InstanceKey", id));
            }
            if (facts != null && shared && facts.portalTimed()) {
                out.add(Finding.error(DOMAIN, PACKED_PORTAL_TIMER, where + " shares each copy between "
                        + dungeon.areasPerCopy() + " parties, but its template '" + template + "' runs a portal "
                        + "timer, which belongs to the whole world and would close every party's run at once. Set "
                        + "Share.AreasPerCopy to 1, or name a template without one", id));
            }
            if (facts != null && shared && facts.worldEvents()) {
                out.add(Finding.error(DOMAIN, PACKED_WORLD_EVENTS, where + " shares each copy between "
                        + dungeon.areasPerCopy() + " parties, but world events run in its template '" + template
                        + "', and an event reaches every party in the world. Set Share.AreasPerCopy to 1, or "
                        + "name a template whose gameplay config runs no world events", id));
            }
        }

        String prefab = dungeon.prefabPath();
        String layout = dungeon.layoutId();
        if (layout != null) {
            LayoutPlan plan = plan(layoutPlans, layout);
            if (plan == null) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_DUNGEON_LAYOUT, where + " generates its runs from the layout "
                        + "plan '" + layout + "' (Area.Layout), which no loaded file defines under "
                        + "Server/ZiggfreedCommon/DungeonLayouts/ (its pack may not be installed here), so nobody "
                        + "can enter it", id));
            } else {
                auditLanes(dungeon, plan, id, where, out);
            }
            if (prefab != null) {
                out.add(Finding.warning(DOMAIN, LAYOUT_AND_PREFAB, where + " names both Area.Layout '" + layout
                        + "' and Area.Prefab '" + prefab + "': a dungeon with a Layout is generated and never pastes "
                        + "its Prefab. Remove one of them", id));
            }
        } else if (prefab == null) {
            out.add(Finding.warning(DOMAIN, NO_DUNGEON_PREFAB, where + " names no Area.Prefab, so every area "
                    + "it opens is empty void", id));
        } else if (!asks(prefabKnown, prefab)) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_DUNGEON_PREFAB, where + " names the prefab '" + prefab
                    + "', which no loaded pack ships under Server/Prefabs/ (write the path with its extension; "
                    + "its pack may not be installed here), so its areas stay empty", id));
        }

        // What clears a run: every Complete leaf written (Bosses, Objective, SurviveTimer) must hold. An endless run
        // is never cleared: its own checks stand in for these.
        List<String> bosses = dungeon.completeBosses();
        Integer survive = dungeon.surviveTimerSeconds();
        Integer limit = dungeon.timeLimitSeconds();
        DungeonEndless endless = dungeon.endless();
        if (endless != null && dungeon.generated()) {
            out.add(Finding.error(DOMAIN, ENDLESS_WITH_LAYOUT, where + " is an endless run (Run.Endless) generated from "
                    + "the layout plan '" + dungeon.layoutId() + "' (Area.Layout), but generated dungeons cannot be "
                    + "endless yet: an endless run's chest stands at the area's paste spot, which a generated copy "
                    + "leaves as empty void. Remove Run.Endless or Area.Layout", id));
        }
        if (endless != null) {
            auditEndless(endless, id, where, lootableKnown, lootableGated, out);
        } else if (!dungeon.hasClear()) {
            out.add(Finding.warning(DOMAIN, NO_DUNGEON_CLEAR, where + " writes nothing in Complete that can clear "
                    + "a run (no Bosses, no Objective with a Kind, no SurviveTimer), so a run there can never be "
                    + "cleared: every run ends failed, once its party leaves or its time limit passes. Name the fight, "
                    + "the objective or the time that clears it", id));
        } else if (survive != null && limit != null && survive > limit) {
            out.add(Finding.warning(DOMAIN, NO_DUNGEON_CLEAR, where + " asks a run to last "
                    + survive + " s (Complete.SurviveTimer), longer than its " + limit + " s time limit "
                    + "(Run.TimeLimitSeconds), so the limit fails every run first. Shorten the timer or raise the "
                    + "limit", id));
        }
        for (String boss : endless != null ? List.<String>of() : bosses) {
            if (!asks(scriptKnown, boss)) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_DUNGEON_BOSS, where + " is cleared by beating the "
                        + "encounter script '" + boss + "', which no loaded pack ships (its pack may not be "
                        + "installed here; check the spelling otherwise), so that fight never clears a run", id));
            }
        }

        String lootable = dungeon.rewardsLootable();
        if (lootable != null && !asks(lootableKnown, lootable)) {
            out.add(Finding.warning(DOMAIN, UNKNOWN_DUNGEON_LOOTABLE, where + " pays its clears from the loot table '"
                    + lootable + "' (Rewards.Lootable), which no loaded file defines under "
                    + "Server/ZiggfreedCommon/Lootables/ (its pack may not be installed here), so a clear pays "
                    + "nothing", id));
        } else if (lootable != null && gated(lootableGated, lootable)) {
            out.add(gatedTable(where + " pays its clears from the loot table '" + lootable + "' (Rewards.Lootable)",
                    "each member as the clear rolls for them", id));
        }

        for (DungeonEntryCost.Item item : dungeon.entryCostItems()) {
            if (!asks(itemKnown, item.itemId())) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_DUNGEON_COST_ITEM, where + " takes the item '" + item.itemId()
                        + "' on the way in (EntryCost.Items), which no loaded item answers to (write the id exactly as "
                        + "the item's file is named; its pack may not be installed here), so nobody can pay it and "
                        + "every player who would open an area is refused", id));
            }
        }
        for (String key : dungeon.entryCostKeys()) {
            if (!asks(itemKnown, key)) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_DUNGEON_COST_ITEM, where + " opens for nothing to a player "
                        + "holding the item '" + key + "' (EntryCost.Keys), which no loaded item answers to (write the id "
                        + "exactly as the item's file is named; its pack may not be installed here), so nobody can "
                        + "hold that key and the way in takes EntryCost.Items from everyone", id));
            }
        }

        if (shared && dungeon.spacing() < minimumSpacing) {
            out.add(Finding.error(DOMAIN, SPACING_TOO_SMALL, where + " sets Area.Spacing " + dungeon.spacing()
                    + ", under the " + minimumSpacing + " blocks this server's view distance needs (twice it), so "
                    + "parties in neighbouring areas see each other. Raise it, or leave it out for 2048", id));
        }

        SeasonGate.checkKnown(out, DOMAIN, dungeon.getSeason(), id);
        TextKeyAudit.check(out, DOMAIN, id, where + " Text.NameKey", dungeon.nameKey(), textShipped,
                "its name shows as the raw key");
        TextKeyAudit.check(out, DOMAIN, id, where + " Text.DescriptionKey", dungeon.descriptionKey(), textShipped,
                "its description shows as the raw key");
        TextKeyAudit.check(out, DOMAIN, id, where + " Text.GoalKey", dungeon.goalKey(), textShipped,
                "the goal a player reads on arriving shows as the raw key");
        TextKeyAudit.check(out, DOMAIN, id, where + " Tier.NameKey", dungeon.tierNameKey(), textShipped,
                "the tier's name on the gate and on its Claims shows as the raw key");
        TextKeyAudit.check(out, DOMAIN, id, where + " EntryCost.HintKey", dungeon.entryCostHintKey(), textShipped,
                "the line saying where the items are found shows as the raw key");
        TextKeyAudit.check(out, DOMAIN, id, where + " EntryCost.ConfirmKey", dungeon.entryCostConfirmKey(),
                textShipped, "the confirm screen's button shows the raw key");
        for (DungeonAsset.GateChest chest : dungeon.gateChests()) {
            TextKeyAudit.check(out, DOMAIN, id, where + " Gate.Chests WaitingKey", chest.waitingKey(), textShipped,
                    "the gate's line under the chest '" + chest.chest() + "' shows as the raw key");
        }
    }

    /**
     * What a template's config says, read off its JSON: the {@code InstanceKey} under {@code Plugin.Instance}
     * (or the top-level {@code Instance} of a version 2 or older file), a portal timer (read as the engine's
     * {@code PortalKey.getTimeLimitSeconds} reads it: {@code Plugin.PortalWorldConfig.Config.TimeLimitSeconds}
     * when written, else the named {@code PortalType}'s own limit, {@code portalTypeLimit}; above zero is a
     * timer), and world events: the {@code GameplayConfig} it names (the engine's {@code Default} when it
     * names none) runs some ({@code gameplayHasWorldEvents}), unless the template switches them off
     * ({@code Plugin.WorldEvents.EnableWorldEvents} false). Pure over its two engine questions.
     */
    @Nonnull
    public static TemplateFacts read(@Nonnull JsonObject root, @Nonnull Predicate<String> gameplayHasWorldEvents,
            @Nonnull Function<String, Integer> portalTypeLimit) {
        JsonObject plugin = object(root, "Plugin");
        String key = string(object(plugin, "Instance"), "InstanceKey");
        if (key == null) {
            key = string(object(root, "Instance"), "InstanceKey");
        }
        JsonObject portal = object(object(plugin, "PortalWorldConfig"), "Config");
        boolean portalTimed;
        JsonElement written = member(portal, "TimeLimitSeconds");
        if (written != null && !written.isJsonNull()) {
            portalTimed = number(portal, "TimeLimitSeconds") > 0;
        } else {
            String portalType = string(portal, "PortalType");
            Integer typeLimit = portalType == null ? null : limit(portalTypeLimit, portalType);
            portalTimed = typeLimit != null && typeLimit > 0;
        }
        JsonElement enabled = member(object(plugin, "WorldEvents"), "EnableWorldEvents");
        boolean eventsOff = enabled != null && enabled.isJsonPrimitive() && !enabled.getAsBoolean();
        String gameplay = string(root, "GameplayConfig");
        boolean worldEvents = !eventsOff
                && asks(gameplayHasWorldEvents, gameplay == null ? DEFAULT_GAMEPLAY_CONFIG : gameplay);
        return new TemplateFacts(key, portalTimed, worldEvents);
    }

    // ==================== the engine walk ====================

    /** Does a loaded pack ship this template? A name the engine refuses outright (a path escape) does not. */
    private static boolean templateKnown(@Nonnull String template) {
        try {
            return InstancesPlugin.doesInstanceAssetExist(template);
        } catch (IllegalArgumentException refused) {
            return false;
        } catch (Throwable t) {
            return true; // cannot tell is not "missing"
        }
    }

    /** The template's config, read through the engine's own lookup; null when it cannot be read. */
    @Nullable
    private static TemplateFacts templateFacts(@Nonnull String template) {
        try {
            Path config = InstancesPlugin.getInstanceAssetPath(template).resolve(TEMPLATE_CONFIG);
            JsonElement root = JsonParser.parseString(Files.readString(config, StandardCharsets.UTF_8));
            return root != null && root.isJsonObject()
                    ? read(root.getAsJsonObject(), DungeonValidator::gameplayHasWorldEvents,
                            DungeonValidator::portalTypeLimit) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Does the loaded gameplay config {@code id} list any world events, run at load or on a trigger? */
    private static boolean gameplayHasWorldEvents(@Nonnull String id) {
        try {
            GameplayConfig config = GameplayConfig.getAssetMap().getAsset(id);
            WorldEventsConfig events = config == null ? null : config.getPluginConfig().get(WorldEventsConfig.class);
            return events != null && (events.events().length > 0 || events.globalEvents().length > 0);
        } catch (Throwable t) {
            return false;
        }
    }

    /** The loaded portal type's own time limit in seconds (-1 for none), or null when no such type is loaded. */
    @Nullable
    private static Integer portalTypeLimit(@Nonnull String id) {
        try {
            PortalType type = PortalType.getAssetMap().getAsset(id);
            return type == null ? null : type.getTimeLimitSeconds();
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code read}'s answer for {@code id}, or null when it throws. */
    @Nullable
    private static Integer limit(@Nonnull Function<String, Integer> read, @Nonnull String id) {
        try {
            return read.apply(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Does a loaded pack ship this prefab path (with its extension) under {@code Server/Prefabs/}? */
    private static boolean prefabKnown(@Nonnull String path) {
        try {
            return PrefabStore.get().findAssetPrefabPath(path) != null;
        } catch (IllegalArgumentException refused) {
            return false;
        } catch (Throwable t) {
            return true; // cannot tell is not "missing"
        }
    }

    /**
     * A generated dungeon's lanes against its plan: {@link #LANES_CLAMPED} when its copies hold fewer areas than
     * {@code Share.AreasPerCopy} asks, the furthest lane at its spacing reaching past {@link RunLanes#FAR_LIMIT}; and
     * {@link #LAYOUT_TOO_TALL} when the plan's {@code Bounds.Y}, standing from {@link AreaLayout#AREA_Y}, reaches past
     * {@link RunLanes#WORLD_TOP}.
     */
    private static void auditLanes(@Nonnull DungeonAsset dungeon, @Nonnull LayoutPlan plan, @Nonnull String id,
            @Nonnull String where, @Nonnull List<Finding> out) {
        Cell bounds = plan.bounds();
        int asked = dungeon.areasPerCopy();
        int held = dungeon.areasPerCopy(bounds);
        if (held < asked) {
            int spacing = RunLanes.spacing(dungeon.spacing(), bounds.x());
            out.add(Finding.warning(DOMAIN, LANES_CLAMPED, where + " asks for " + asked + " areas a copy "
                    + "(Share.AreasPerCopy), but at its lanes' spacing of " + spacing + " blocks (its plan '" + plan.id()
                    + "' is " + bounds.x() + " wide) only " + held + " fit within " + RunLanes.FAR_LIMIT + " blocks of "
                    + "the world's centre, so each copy holds " + held + ". Lower Share.AreasPerCopy to " + held
                    + " or less, or Area.Spacing", id));
        }
        if (!RunLanes.fitsHeight(bounds.y())) {
            int top = AreaLayout.AREA_Y + bounds.y() - 1;
            out.add(Finding.warning(DOMAIN, LAYOUT_TOO_TALL, where + " generates from the plan '" + plan.id()
                    + "', whose Bounds.Y of " + bounds.y() + " stands from row " + AreaLayout.AREA_Y + " up to row "
                    + top + ", past the world's top row " + RunLanes.WORLD_TOP + ", so a room standing that high is cut "
                    + "off. Keep Bounds.Y at " + (RunLanes.WORLD_TOP - AreaLayout.AREA_Y + 1) + " or less", id));
        }
    }

    /** {@code read}'s plan for {@code id}, or null when it has none or throws. */
    @Nullable
    private static LayoutPlan plan(@Nonnull Function<String, LayoutPlan> read, @Nonnull String id) {
        try {
            return read.apply(id);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * An endless run's own checks: a band to raise, a boss spacing of 2 or more, a ramp with a cap, and loot tables
     * that load.
     */
    private static void auditEndless(@Nonnull DungeonEndless endless, @Nonnull String id, @Nonnull String where,
            @Nonnull Predicate<String> lootableKnown, @Nonnull Predicate<String> lootableGated,
            @Nonnull List<Finding> out) {
        if (endless.bandSlots().isEmpty()) {
            out.add(Finding.error(DOMAIN, ENDLESS_NO_WAVE_POOL, where + " is an endless run (Run.Endless) whose wave "
                    + "pool names no band slot from 1 to " + WaveRules.MAX_SLOTS + " (Run.Endless.Bands, "
                    + endless.bandsAuthored() + "), so no wave could rise in it. Name at least one band with the "
                    + "Slot of the encounter script's band that raises it", id));
        }
        Integer bossEvery = endless.bossEveryAuthored();
        if (bossEvery != null && bossEvery < WaveRules.MIN_BOSS_EVERY) {
            out.add(Finding.error(DOMAIN, ENDLESS_BOSS_EVERY_TOO_LOW, where + " sets Run.Endless.BossEvery "
                    + bossEvery + ", under " + WaveRules.MIN_BOSS_EVERY + ": a boss wave needs waves between them. "
                    + "Write 2 or more, or leave it out for no boss waves", id));
        }
        Double cap = endless.rampMax();
        if (endless.rampSteps() && (cap == null || !Double.isFinite(cap))) {
            out.add(Finding.error(DOMAIN, ENDLESS_RAMP_UNCAPPED, where + " ramps its waves (Run.Endless.Ramp) with "
                    + "no Max, so each wave's creatures would grow until the library's own ceiling of x"
                    + WaveRamp.CEILING + ". Name the Max the ramp stops at, such as 4", id));
        }
        for (String table : new String[] {endless.perWaveLootable(), endless.perBossWaveAuthored()}) {
            if (table != null && !asks(lootableKnown, table)) {
                out.add(Finding.warning(DOMAIN, UNKNOWN_DUNGEON_LOOTABLE, where + " banks its endless waves from the "
                        + "loot table '" + table + "' (Run.Endless.Chest), which no loaded file defines under "
                        + "Server/ZiggfreedCommon/Lootables/ (its pack may not be installed here), so those waves "
                        + "bank nothing", id));
            } else if (table != null && gated(lootableGated, table)) {
                out.add(gatedTable(where + " banks its endless waves from the loot table '" + table
                        + "' (Run.Endless.Chest)", "each member as their waves roll", id));
            }
        }
    }

    /**
     * The {@link #GATED_DUNGEON_LOOTABLE} warning: {@code what} names the table and where the dungeon pays from it,
     * {@code whom} who its {@code Requires} is asked of.
     */
    @Nonnull
    private static Finding gatedTable(@Nonnull String what, @Nonnull String whom, @Nonnull String id) {
        return Finding.warning(DOMAIN, GATED_DUNGEON_LOOTABLE, what + ", whose Requires asks more of a player than "
                + "mod presence. It is asked of " + whom + ", so a member who is offline then is paid nothing it "
                + "covers. Gate the rows on what the run itself reads (instance_win, instance_score), or keep the "
                + "table's Requires to mod presence", id);
    }

    /** {@code test}'s answer; one that throws reads as no, since cannot tell is not a finding. */
    private static boolean gated(@Nonnull Predicate<String> test, @Nonnull String table) {
        try {
            return test.test(table);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Does the loaded table of this id ask something of the player beyond mod presence? No when none is loaded. */
    private static boolean lootableGated(@Nonnull String id) {
        LootableAsset table = LootableConfig.getInstance().resolve(id);
        return table != null && table.gatesOnPlayer();
    }

    /**
     * Whether an id names a loaded encounter script, read once for the whole pass through zc-encounter's
     * {@code EncounterScripts}; with none loaded (the engine not up) every id reads as known, since cannot tell
     * is not "missing".
     */
    @Nonnull
    private static Predicate<String> loadedScripts() {
        Map<String, EncounterScriptScan> loaded;
        try {
            loaded = EncounterScripts.scanLoaded();
        } catch (Throwable t) {
            return script -> true;
        }
        if (loaded.isEmpty()) {
            return script -> true;
        }
        Set<String> ids = new HashSet<>();
        for (String id : loaded.keySet()) {
            ids.add(id.toLowerCase(Locale.ROOT));
        }
        return script -> ids.contains(script.trim().toLowerCase(Locale.ROOT));
    }

    /** Does a loaded loot table answer to this id? One that cannot be asked reads as known. */
    private static boolean lootableKnown(@Nonnull String id) {
        try {
            return LootableConfig.getInstance().resolve(id) != null;
        } catch (Throwable t) {
            return true; // cannot tell is not "missing"
        }
    }

    /**
     * Does a loaded item answer to this exact id (the spelling the spend matches)? One that cannot be asked reads as
     * known.
     */
    private static boolean itemKnown(@Nonnull String id) {
        try {
            return Item.getAssetMap().getAsset(id) != null;
        } catch (Throwable t) {
            return true; // cannot tell is not "missing"
        }
    }

    /** Twice the server's view radius, in blocks. */
    private static int minimumSpacing() {
        int radius;
        try {
            radius = HytaleServer.get().getConfig().getMaxViewRadius();
        } catch (Throwable t) {
            radius = FALLBACK_VIEW_RADIUS;
        }
        return 2 * radius * ChunkUtil.SIZE;
    }

    // ==================== helpers ====================

    /** {@code test}'s answer; one that throws reads as yes, since cannot tell is not "missing". */
    private static boolean asks(@Nonnull Predicate<String> test, @Nonnull String value) {
        try {
            return test.test(value);
        } catch (Throwable t) {
            return true;
        }
    }

    @Nullable
    private static TemplateFacts facts(@Nonnull Function<String, TemplateFacts> read, @Nonnull String template) {
        try {
            return read.apply(template);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static JsonElement member(@Nullable JsonObject object, @Nonnull String key) {
        return object == null ? null : object.get(key);
    }

    @Nullable
    private static JsonObject object(@Nullable JsonObject object, @Nonnull String key) {
        JsonElement value = member(object, key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    @Nullable
    private static String string(@Nullable JsonObject object, @Nonnull String key) {
        JsonElement value = member(object, key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        String text = value.getAsString().trim();
        return text.isEmpty() ? null : text;
    }

    private static double number(@Nullable JsonObject object, @Nonnull String key) {
        JsonElement value = member(object, key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
                ? value.getAsDouble() : 0.0;
    }
}
