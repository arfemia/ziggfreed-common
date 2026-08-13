package com.ziggfreed.common.npc.placement;

import java.util.LinkedHashMap;
import java.util.Locale;
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
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.world.WorldSelector;

/**
 * "Put this NPC here, in these worlds, under these conditions" - one placement, authored at
 * {@code Server/ZiggfreedCommon/NpcPlacements/<id>.json}.
 *
 * <p>Pattern A: this codec IS the schema, the engine decodes straight into typed fields. Every
 * leaf of every nested group is registered with {@code appendInherited}, so a file carrying
 * {@code "Parent": "<id>"} that overrides one leaf still inherits every untouched sibling instead
 * of silently losing it. That is what makes "the same NPC, but standing in the temple" a
 * three-line file.
 *
 * <p>Authored shape (every group optional):
 * <pre>{@code
 * { "Enabled": true,
 *   "Identity": { "BaseRole": "Template_MyMod_Guide", "Appearance": { "Model": "Human_Male_01" },
 *                 "NameKey": "npc.guide.name", "HintKey": "npc.guide.hint" },
 *   "Where":    { "Names": ["default"] },
 *   "Anchor":   { "WorldSpawn": { "Offset": {"X": 2.5}, "Yaw": 180 } },
 *   "Requires": { "Conditions": [ {"Factor": "yourmod:feature", "Param": "shop", "Min": 1} ] },
 *   "Limits":   { "SpawnChance": 1.0, "OncePerWorld": true },
 *   "Lifecycle":{ "KeepAlive": true, "Fortify": true },
 *   "Interact": { "Dialogue": "guide_intro",
 *                 "Bindings": { "yourmod:npc_id": { "Value": "guide" } } } }
 * }</pre>
 *
 * <p><b>The groups, and why each is where it is.</b>
 * <ul>
 *   <li><b>{@link Identity}</b> - who stands there. An {@code Appearance} with no explicit
 *       {@code Role} generates a per-placement NPC role as a tiny variant of the template role
 *       {@code BaseRole} names (see {@link NpcRoleGenerator}), so a pack ships no role JSON of its
 *       own; an {@link AppearanceSpec} naming a {@code Base} to clone generates a per-placement
 *       MODEL beside it, so a pack ships no model JSON either. {@code NpcId} and {@code Aliases}
 *       are what CONTENT calls this character - a quest's giver, a hand-in target, a talk
 *       objective - and both are optional: unauthored, the placement answers to its own id.</li>
 *   <li><b>{@code Where}</b> - a {@link WorldSelector}, the shared world-identity vocabulary.
 *       The selector carries no default of its own; THIS read site treats a null or empty
 *       {@code Where} as {@code Names: ["default"]}, i.e. the ordinary persistent world.</li>
 *   <li><b>{@link Anchor}</b> - where in the world. Five INDEPENDENT nullable groups, never a
 *       placement-mode enum: authoring several produces the UNION of their resolved positions,
 *       each an independent instance. See that class for the multi-anchor rules.</li>
 *   <li><b>{@link Requires}</b> - whether it appears at all, in authored terms (see
 *       {@link FactorCondition}); it feeds the same gate chain an admin override does.</li>
 *   <li><b>{@link Limits}</b> - how many, and how likely.</li>
 *   <li><b>{@link Lifecycle}</b> - what happens to it afterwards. Every knob is OPT-IN and
 *       defaults false, because each one costs something (a pinned chunk, a re-place, a health
 *       pool) that a placement should only pay for when it asks to.</li>
 *   <li><b>{@link Interact}</b> - what press-F does. {@code Dialogue} is understood here (this
 *       library owns the dialogue engine); every other behaviour rides {@code Bindings}, a map
 *       keyed by namespaced channel that is forwarded verbatim and interpreted by nobody here.</li>
 * </ul>
 *
 * <p>To re-tune a placement someone else shipped, override the file by id (a same-named file in a
 * later pack or in the owner layer), or ship your own file with {@code "Parent"} set to theirs and
 * author only the leaves you change. To stop one entirely without editing any file, use the owner
 * override at {@code mods/ziggfreedcommon/npc-placements.json}.
 */
public final class NpcPlacementAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, NpcPlacementAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private Boolean enabled;
    @Nullable private Identity identity;
    @Nullable private WorldSelector where;
    @Nullable private Anchor anchor;
    @Nullable private Requires requires;
    @Nullable private Limits limits;
    @Nullable private Lifecycle lifecycle;
    @Nullable private Interact interact;

    public static final AssetBuilderCodec<String, NpcPlacementAsset> CODEC = AssetBuilderCodec.builder(
                    NpcPlacementAsset.class,
                    NpcPlacementAsset::new,
                    Codec.STRING,
                    // Canonicalize the id at decode: the engine's asset key is the verbatim
                    // filename, while every consumer (the ledger, owner overrides, a binding
                    // lookup) addresses it lowercased. One transform, at the one decode
                    // authority, keeps getId() canonical everywhere.
                    (a, id) -> a.id = id == null ? null : id.toLowerCase(Locale.ROOT),
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            // An optional human-readable echo of the asset key (the authoritative key is the
            // filename), consumed by a no-op setter and emitted on encode for round-trip.
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op: the id comes from the filename */ },
                    a -> a.id)
            .add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .documentation("Whether this placement may appear at all; unauthored means true. Setting false is a "
                    + "permanent off switch in the content itself, and despawns a standing NPC on the next sweep.")
            .add()
            .appendInherited(new KeyedCodec<>("Identity", Identity.CODEC, false),
                    (a, v) -> a.identity = v, a -> a.identity, (a, p) -> a.identity = p.identity)
            .documentation("Who stands here: the NPC role, or an appearance plus the template role to generate a "
                    + "variant of.")
            .add()
            .appendInherited(new KeyedCodec<>("Where", WorldSelector.CODEC, false),
                    (a, v) -> a.where = v, a -> a.where, (a, p) -> a.where = p.where)
            .documentation("Which worlds this placement applies to. Leave it out and the placement stands "
                    + "in the 'default' selector's worlds, which is the ordinary persistent world "
                    + "players log into rather than any instance.")
            .add()
            .appendInherited(new KeyedCodec<>("Anchor", Anchor.CODEC, false),
                    (a, v) -> a.anchor = v, a -> a.anchor, (a, p) -> a.anchor = p.anchor)
            .documentation("Where in the world. Author any combination of the five groups; they produce the union "
                    + "of their positions.")
            .add()
            .appendInherited(new KeyedCodec<>("Requires", Requires.CODEC, false),
                    (a, v) -> a.requires = v, a -> a.requires, (a, p) -> a.requires = p.requires)
            .documentation("Authored gate conditions. A condition on a factor nobody registered fails closed, so "
                    + "the placement simply does not appear.")
            .add()
            .appendInherited(new KeyedCodec<>("Limits", Limits.CODEC, false),
                    (a, v) -> a.limits = v, a -> a.limits, (a, p) -> a.limits = p.limits)
            .documentation("How many of this placement may stand in one world, and how likely each one is.")
            .add()
            .appendInherited(new KeyedCodec<>("Lifecycle", Lifecycle.CODEC, false),
                    (a, v) -> a.lifecycle = v, a -> a.lifecycle, (a, p) -> a.lifecycle = p.lifecycle)
            .documentation("Opt-in upkeep knobs: keep the chunk loaded, re-place after loss, fortify health.")
            .add()
            .appendInherited(new KeyedCodec<>("Interact", Interact.CODEC, false),
                    (a, v) -> a.interact = v, a -> a.interact, (a, p) -> a.interact = p.interact)
            .documentation("What pressing F on this NPC does: a dialogue id, plus namespaced bindings other mods read.")
            .add()
            .build();

    public NpcPlacementAsset() {
    }

    /** Java-side construction (tests, a consumer building a placement in code). */
    @Nonnull
    public static NpcPlacementAsset of(@Nonnull String id, @Nullable Boolean enabled, @Nullable Identity identity,
            @Nullable WorldSelector where, @Nullable Anchor anchor, @Nullable Requires requires,
            @Nullable Limits limits, @Nullable Lifecycle lifecycle, @Nullable Interact interact) {
        NpcPlacementAsset a = new NpcPlacementAsset();
        a.id = id.toLowerCase(Locale.ROOT);
        a.enabled = enabled;
        a.identity = identity;
        a.where = where;
        a.anchor = anchor;
        a.requires = requires;
        a.limits = limits;
        a.lifecycle = lifecycle;
        a.interact = interact;
        return a;
    }

    @Override
    public String getId() {
        return id;
    }

    /** {@code Enabled}, reader-defaulted to {@code true} (a placement ships on unless it says otherwise). */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Nullable
    public Identity getIdentity() {
        return identity;
    }

    @Nullable
    public WorldSelector getWhere() {
        return where;
    }

    @Nullable
    public Anchor getAnchor() {
        return anchor;
    }

    @Nullable
    public Requires getRequires() {
        return requires;
    }

    @Nullable
    public Limits getLimits() {
        return limits;
    }

    @Nullable
    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    @Nullable
    public Interact getInteract() {
        return interact;
    }

    // ==================== Identity ====================

    /** Who stands at this placement. */
    public static final class Identity {

        @Nullable protected String role;
        @Nullable protected String baseRole;
        @Nullable protected AppearanceSpec appearance;
        @Nullable protected String nameKey;
        @Nullable protected String hintKey;
        @Nullable protected String npcId;
        @Nullable protected String[] aliases;

        public static final BuilderCodec<Identity> CODEC = BuilderCodec.builder(Identity.class, Identity::new)
                .appendInherited(new KeyedCodec<>("Role", Codec.STRING, false),
                        (o, v) -> o.role = v, o -> o.role, (o, p) -> o.role = p.role)
                .documentation("An existing NPC role id to place as-is. Author this when you ship your own role JSON.").add()
                .appendInherited(new KeyedCodec<>("BaseRole", Codec.STRING, false),
                        (o, v) -> o.baseRole = v, o -> o.baseRole, (o, p) -> o.baseRole = p.baseRole)
                .documentation("The id of a parameterized TEMPLATE role, shipped in any pack, that the generated "
                        + "per-placement role is a variant of. The template supplies all the behaviour and must "
                        + "declare the keys this placement overrides (Appearance, NameTranslationKey, Hint, Weapons, "
                        + "OffHand, DefaultOffHandSlot, Armor) in its own Parameters block. Ignored when Role is "
                        + "authored.").add()
                .appendInherited(new KeyedCodec<>("Appearance", AppearanceSpec.CODEC, false),
                        (o, v) -> o.appearance = v, o -> o.appearance, (o, p) -> o.appearance = p.appearance)
                .documentation("How the NPC looks: a Model asset to use as-is, or one to clone and re-dress, plus "
                        + "what it wears and holds. Authoring this without a Role is what opts the placement into "
                        + "role generation.").add()
                .appendInherited(new KeyedCodec<>("NameKey", Codec.STRING, false),
                        (o, v) -> o.nameKey = v, o -> o.nameKey, (o, p) -> o.nameKey = p.nameKey)
                .documentation("Localization key for the nameplate on a generated role.").add()
                .appendInherited(new KeyedCodec<>("HintKey", Codec.STRING, false),
                        (o, v) -> o.hintKey = v, o -> o.hintKey, (o, p) -> o.hintKey = p.hintKey)
                .documentation("Localization key for the press-F prompt on a generated role.").add()
                .appendInherited(new KeyedCodec<>("NpcId", Codec.STRING, false),
                        (o, v) -> o.npcId = v, o -> o.npcId, (o, p) -> o.npcId = p.npcId)
                .documentation("The character id content binds to: a quest's giver, a hand-in target, a talk "
                        + "objective's target, and the waypoint marked for it. Unauthored, the placement id "
                        + "itself is used, so an ordinary placement needs no line here at all. Two placements "
                        + "of the same character in different worlds may carry different ids, which is how a "
                        + "quest step is scoped to one of them.").add()
                .appendInherited(new KeyedCodec<>("Aliases", Codec.STRING_ARRAY, false),
                        (o, v) -> o.aliases = v, o -> o.aliases, (o, p) -> o.aliases = p.aliases)
                .documentation("Further ids this placement also ANSWERS to, one per entry, for the same "
                        + "character standing somewhere else. The primary is what the placement IS; an alias "
                        + "is only what it responds to, and aliases go one way. Authoring this replaces the "
                        + "parent's whole list rather than adding to it.").add()
                .build();

        public Identity() {
        }

        @Nonnull
        public static Identity of(@Nullable String role, @Nullable String baseRole,
                @Nullable AppearanceSpec appearance, @Nullable String nameKey, @Nullable String hintKey) {
            return of(role, baseRole, appearance, nameKey, hintKey, null, null);
        }

        /** As {@link #of(String, String, AppearanceSpec, String, String)}, with the character id it answers as. */
        @Nonnull
        public static Identity of(@Nullable String role, @Nullable String baseRole,
                @Nullable AppearanceSpec appearance, @Nullable String nameKey, @Nullable String hintKey,
                @Nullable String npcId, @Nullable String[] aliases) {
            Identity i = new Identity();
            i.role = role;
            i.baseRole = baseRole;
            i.appearance = appearance;
            i.nameKey = nameKey;
            i.hintKey = hintKey;
            i.npcId = npcId;
            i.aliases = aliases == null ? null : aliases.clone();
            return i;
        }

        @Nullable
        public String getRole() {
            return role;
        }

        @Nullable
        public String getBaseRole() {
            return baseRole;
        }

        @Nullable
        public AppearanceSpec getAppearance() {
            return appearance;
        }

        @Nullable
        public String getNameKey() {
            return nameKey;
        }

        @Nullable
        public String getHintKey() {
            return hintKey;
        }

        /**
         * The character id this placement IS, or null when it authors none - in which case the
         * placement id itself is the identity (see {@code npc/NpcIdentities}).
         */
        @Nullable
        public String getNpcId() {
            return npcId;
        }

        /** The further ids this placement also answers to, or null when it lists none. */
        @Nullable
        public String[] getAliases() {
            return aliases == null ? null : aliases.clone();
        }

        /**
         * True when this identity wants a generated role: an {@code Appearance} is authored and no
         * explicit {@code Role} takes precedence over it.
         */
        public boolean usesGeneratedRole() {
            return (role == null || role.isBlank()) && appearance != null && !appearance.isBlank();
        }

        /**
         * True when the appearance asks for a MODEL to be generated as well as a role, i.e. it
         * names a {@code Base} to clone rather than a {@code Model} to use as it is.
         */
        public boolean usesGeneratedModel() {
            return usesGeneratedRole() && appearance != null && appearance.hasBase();
        }
    }

    // ==================== Anchor ====================

    /**
     * Where in a matching world the NPC stands. Five INDEPENDENT nullable groups rather than one
     * placement-mode enum, so a placement can say "at the world spawn AND at every matching
     * structure" without the schema growing a new enum value for the combination.
     *
     * <p><b>Multi-anchor semantics.</b> Several authored groups produce the UNION of their
     * resolved positions, each an independent instance keyed by its anchor kind plus an instance
     * id (so two matching structures are two NPCs, not one). {@code Limits.MaxPerWorld} counts
     * ACROSS the union rather than per group, and {@code Limits.OncePerWorld} collapses the union
     * to the first resolved position in declaration order: WorldSpawn, Coords, Structure, Zone,
     * Custom.
     */
    public static final class Anchor {

        @Nullable protected WorldSpawn worldSpawn;
        @Nullable protected Coords coords;
        @Nullable protected Structure structure;
        @Nullable protected Zone zone;
        @Nullable protected Custom custom;

        public static final BuilderCodec<Anchor> CODEC = BuilderCodec.builder(Anchor.class, Anchor::new)
                .appendInherited(new KeyedCodec<>("WorldSpawn", WorldSpawn.CODEC, false),
                        (o, v) -> o.worldSpawn = v, o -> o.worldSpawn, (o, p) -> o.worldSpawn = p.worldSpawn)
                .documentation("Stand at the world's own spawn point, plus an offset.").add()
                .appendInherited(new KeyedCodec<>("Coords", Coords.CODEC, false),
                        (o, v) -> o.coords = v, o -> o.coords, (o, p) -> o.coords = p.coords)
                .documentation("Stand at fixed world coordinates. Only useful in a world whose layout you control.").add()
                .appendInherited(new KeyedCodec<>("Structure", Structure.CODEC, false),
                        (o, v) -> o.structure = v, o -> o.structure, (o, p) -> o.structure = p.structure)
                .documentation("Stand beside a matching worldgen spawn marker, so the NPC lands in a generated "
                        + "village or camp instead of an arbitrary spot.").add()
                .appendInherited(new KeyedCodec<>("Zone", Zone.CODEC, false),
                        (o, v) -> o.zone = v, o -> o.zone, (o, p) -> o.zone = p.zone)
                .documentation("Stand where a named zone was discovered. A consumer supplies the discovery trigger.").add()
                .appendInherited(new KeyedCodec<>("Custom", Custom.CODEC, false),
                        (o, v) -> o.custom = v, o -> o.custom, (o, p) -> o.custom = p.custom)
                .documentation("Stand wherever a registered anchor provider says. The extension point for a mod that "
                        + "owns positions this library knows nothing about.").add()
                .build();

        public Anchor() {
        }

        @Nonnull
        public static Anchor of(@Nullable WorldSpawn worldSpawn, @Nullable Coords coords,
                @Nullable Structure structure, @Nullable Zone zone, @Nullable Custom custom) {
            Anchor a = new Anchor();
            a.worldSpawn = worldSpawn;
            a.coords = coords;
            a.structure = structure;
            a.zone = zone;
            a.custom = custom;
            return a;
        }

        @Nullable
        public WorldSpawn getWorldSpawn() {
            return worldSpawn;
        }

        @Nullable
        public Coords getCoords() {
            return coords;
        }

        @Nullable
        public Structure getStructure() {
            return structure;
        }

        @Nullable
        public Zone getZone() {
            return zone;
        }

        @Nullable
        public Custom getCustom() {
            return custom;
        }

        /** True when no anchor group is authored at all, so this placement has nowhere to stand. */
        public boolean isBlank() {
            return worldSpawn == null && coords == null && structure == null && zone == null && custom == null;
        }

        /** The world spawn point plus an offset. */
        public static final class WorldSpawn {

            @Nullable protected Vec3 offset;
            @Nullable protected Double yaw;

            public static final BuilderCodec<WorldSpawn> CODEC =
                    BuilderCodec.builder(WorldSpawn.class, WorldSpawn::new)
                            .appendInherited(new KeyedCodec<>("Offset", Vec3.CODEC, false),
                                    (o, v) -> o.offset = v, o -> o.offset, (o, p) -> o.offset = p.offset)
                            .documentation("World-space shift from the spawn point, in blocks. Unauthored axes are 0.").add()
                            .appendInherited(new KeyedCodec<>("Yaw", Codec.DOUBLE, false),
                                    (o, v) -> o.yaw = v, o -> o.yaw, (o, p) -> o.yaw = p.yaw)
                            .documentation("Facing in degrees; unauthored means 0.").add()
                            .build();

            public WorldSpawn() {
            }

            @Nonnull
            public static WorldSpawn of(@Nullable Vec3 offset, @Nullable Double yaw) {
                WorldSpawn w = new WorldSpawn();
                w.offset = offset;
                w.yaw = yaw;
                return w;
            }

            @Nullable
            public Vec3 getOffset() {
                return offset;
            }

            @Nullable
            public Double getYaw() {
                return yaw;
            }

            public float effectiveYaw() {
                return yaw != null ? yaw.floatValue() : 0.0f;
            }
        }

        /** Fixed world coordinates. */
        public static final class Coords {

            @Nullable protected Double x;
            @Nullable protected Double y;
            @Nullable protected Double z;
            @Nullable protected Double yaw;

            public static final BuilderCodec<Coords> CODEC = BuilderCodec.builder(Coords.class, Coords::new)
                    .appendInherited(new KeyedCodec<>("X", Codec.DOUBLE, false),
                            (o, v) -> o.x = v, o -> o.x, (o, p) -> o.x = p.x)
                    .documentation("World X in blocks.").add()
                    .appendInherited(new KeyedCodec<>("Y", Codec.DOUBLE, false),
                            (o, v) -> o.y = v, o -> o.y, (o, p) -> o.y = p.y)
                    .documentation("World Y in blocks.").add()
                    .appendInherited(new KeyedCodec<>("Z", Codec.DOUBLE, false),
                            (o, v) -> o.z = v, o -> o.z, (o, p) -> o.z = p.z)
                    .documentation("World Z in blocks.").add()
                    .appendInherited(new KeyedCodec<>("Yaw", Codec.DOUBLE, false),
                            (o, v) -> o.yaw = v, o -> o.yaw, (o, p) -> o.yaw = p.yaw)
                    .documentation("Facing in degrees; unauthored means 0.").add()
                    .build();

            public Coords() {
            }

            @Nonnull
            public static Coords of(@Nullable Double x, @Nullable Double y, @Nullable Double z, @Nullable Double yaw) {
                Coords c = new Coords();
                c.x = x;
                c.y = y;
                c.z = z;
                c.yaw = yaw;
                return c;
            }

            @Nullable
            public Double getX() {
                return x;
            }

            @Nullable
            public Double getY() {
                return y;
            }

            @Nullable
            public Double getZ() {
                return z;
            }

            @Nullable
            public Double getYaw() {
                return yaw;
            }

            public float effectiveYaw() {
                return yaw != null ? yaw.floatValue() : 0.0f;
            }

            /** True when all three axes are authored, which is the minimum for a usable position. */
            public boolean isComplete() {
                return x != null && y != null && z != null;
            }
        }

        /**
         * Beside a worldgen spawn marker. The three match lists are an ANY-of allow-list and are
         * FAIL-CLOSED together: authoring none anchors to nothing, so a typo leaves the NPC
         * absent rather than standing beside every marker in the world.
         */
        public static final class Structure {

            @Nullable protected String[] markerIds;
            @Nullable protected String[] roles;
            @Nullable protected String[] keyContains;
            @Nullable protected Vec3 offset;
            @Nullable protected Double yaw;

            public static final BuilderCodec<Structure> CODEC = BuilderCodec.builder(Structure.class, Structure::new)
                    .appendInherited(new KeyedCodec<>("MarkerIds", Codec.STRING_ARRAY, false),
                            (o, v) -> o.markerIds = v, o -> o.markerIds, (o, p) -> o.markerIds = p.markerIds)
                    .documentation("Spawn-marker asset ids to anchor to, matched case-insensitively.").add()
                    .appendInherited(new KeyedCodec<>("Roles", Codec.STRING_ARRAY, false),
                            (o, v) -> o.roles = v, o -> o.roles, (o, p) -> o.roles = p.roles)
                    .documentation("NPC role names the marker itself can spawn; anchors to any marker offering one.").add()
                    .appendInherited(new KeyedCodec<>("KeyContains", Codec.STRING_ARRAY, false),
                            (o, v) -> o.keyContains = v, o -> o.keyContains, (o, p) -> o.keyContains = p.keyContains)
                    .documentation("Substrings of the marker id, for matching a family of markers without listing each.").add()
                    .appendInherited(new KeyedCodec<>("Offset", Vec3.CODEC, false),
                            (o, v) -> o.offset = v, o -> o.offset, (o, p) -> o.offset = p.offset)
                    .documentation("World-space shift from the marker, in blocks. Unauthored axes are 0.").add()
                    .appendInherited(new KeyedCodec<>("Yaw", Codec.DOUBLE, false),
                            (o, v) -> o.yaw = v, o -> o.yaw, (o, p) -> o.yaw = p.yaw)
                    .documentation("Facing in degrees; unauthored means 0.").add()
                    .build();

            public Structure() {
            }

            @Nonnull
            public static Structure of(@Nullable String[] markerIds, @Nullable String[] roles,
                    @Nullable String[] keyContains, @Nullable Vec3 offset, @Nullable Double yaw) {
                Structure s = new Structure();
                s.markerIds = markerIds;
                s.roles = roles;
                s.keyContains = keyContains;
                s.offset = offset;
                s.yaw = yaw;
                return s;
            }

            @Nullable
            public String[] getMarkerIds() {
                return markerIds;
            }

            @Nullable
            public String[] getRoles() {
                return roles;
            }

            @Nullable
            public String[] getKeyContains() {
                return keyContains;
            }

            @Nullable
            public Vec3 getOffset() {
                return offset;
            }

            @Nullable
            public Double getYaw() {
                return yaw;
            }

            public float effectiveYaw() {
                return yaw != null ? yaw.floatValue() : 0.0f;
            }

            /** True when no allow-list entry is authored, so this anchor can never match. */
            public boolean hasNoMatcher() {
                return isAllBlank(markerIds) && isAllBlank(roles) && isAllBlank(keyContains);
            }

            /**
             * The PURE match test: does a sighted marker satisfy this allow-list? Fail-closed on
             * an empty allow-list. {@code markerId} and {@code roleName} are each optional so a
             * caller can test the two axes independently.
             */
            public boolean matches(@Nullable String markerId, @Nullable String roleName) {
                if (hasNoMatcher()) {
                    return false;
                }
                if (markerId != null && !markerId.isBlank()) {
                    if (containsIgnoreCase(markerIds, markerId)) {
                        return true;
                    }
                    String lower = markerId.toLowerCase(Locale.ROOT);
                    if (keyContains != null) {
                        for (String k : keyContains) {
                            if (k != null && !k.isBlank() && lower.contains(k.trim().toLowerCase(Locale.ROOT))) {
                                return true;
                            }
                        }
                    }
                }
                return roleName != null && !roleName.isBlank() && containsIgnoreCase(roles, roleName);
            }
        }

        /** Where a named zone was discovered. */
        public static final class Zone {

            @Nullable protected String zone;
            @Nullable protected Vec3 offset;
            @Nullable protected Double yaw;

            public static final BuilderCodec<Zone> CODEC = BuilderCodec.builder(Zone.class, Zone::new)
                    .appendInherited(new KeyedCodec<>("Zone", Codec.STRING, false),
                            (o, v) -> o.zone = v, o -> o.zone, (o, p) -> o.zone = p.zone)
                    .documentation("The zone or region name, matched case-insensitively against whichever name the "
                            + "consumer reports on discovery.").add()
                    .appendInherited(new KeyedCodec<>("Offset", Vec3.CODEC, false),
                            (o, v) -> o.offset = v, o -> o.offset, (o, p) -> o.offset = p.offset)
                    .documentation("World-space shift from the discovery position, in blocks.").add()
                    .appendInherited(new KeyedCodec<>("Yaw", Codec.DOUBLE, false),
                            (o, v) -> o.yaw = v, o -> o.yaw, (o, p) -> o.yaw = p.yaw)
                    .documentation("Facing in degrees; unauthored means 0.").add()
                    .build();

            public Zone() {
            }

            @Nonnull
            public static Zone of(@Nullable String zone, @Nullable Vec3 offset, @Nullable Double yaw) {
                Zone z = new Zone();
                z.zone = zone;
                z.offset = offset;
                z.yaw = yaw;
                return z;
            }

            @Nullable
            public String getZone() {
                return zone;
            }

            @Nullable
            public Vec3 getOffset() {
                return offset;
            }

            @Nullable
            public Double getYaw() {
                return yaw;
            }

            public float effectiveYaw() {
                return yaw != null ? yaw.floatValue() : 0.0f;
            }

            /** True when no zone name is authored, so this anchor can never resolve. */
            public boolean isBlank() {
                return zone == null || zone.isBlank();
            }
        }

        /**
         * Wherever a registered anchor provider says. This is what keeps the engine open: a mod
         * that owns positions this library knows nothing about registers a provider id with
         * {@link AnchorResolverRegistry} and content addresses it by name, with no change here.
         *
         * <pre>{@code "Custom": { "Provider": "yourmod:station_block", "Params": { "Station": "sawmill" } } }</pre>
         *
         * <p>A provider nobody registered resolves to no position, so the placement stays absent
         * rather than crashing; {@link NpcPlacementValidator} reports it.
         */
        public static final class Custom {

            @Nullable protected String provider;
            @Nullable protected Map<String, String> params;

            public static final BuilderCodec<Custom> CODEC = BuilderCodec.builder(Custom.class, Custom::new)
                    .appendInherited(new KeyedCodec<>("Provider", Codec.STRING, false),
                            (o, v) -> o.provider = v, o -> o.provider, (o, p) -> o.provider = p.provider)
                    .documentation("The namespaced provider id that resolves positions for this anchor. Unregistered "
                            + "means no position, never a crash.").add()
                    .appendInherited(new KeyedCodec<>("Params", new InheritMapCodec<>(Codec.STRING), false),
                            (o, v) -> o.params = v, o -> o.params, (o, p) -> o.params = p.params)
                    .documentation("Free-form arguments passed to the provider verbatim; whatever that provider "
                            + "documents. Merged per key under Parent inheritance.").add()
                    .build();

            public Custom() {
            }

            @Nonnull
            public static Custom of(@Nullable String provider, @Nullable Map<String, String> params) {
                Custom c = new Custom();
                c.provider = provider;
                c.params = params == null ? null : new LinkedHashMap<>(params);
                return c;
            }

            @Nullable
            public String getProvider() {
                return provider;
            }

            /** The authored params, never null (an unauthored map reads as empty). */
            @Nonnull
            public Map<String, String> getParams() {
                return params == null ? Map.of() : params;
            }

            /** True when no provider id is authored, so this anchor can never resolve. */
            public boolean isBlank() {
                return provider == null || provider.isBlank();
            }
        }
    }

    // ==================== Requires ====================

    /**
     * The authored gate: every condition must pass for the placement to appear. Each entry is the
     * shared {@link FactorCondition} leaf, so a factor id gating a placement means exactly what
     * the same id means gating a dialogue option.
     */
    public static final class Requires {

        @Nullable protected FactorCondition[] conditions;

        public static final BuilderCodec<Requires> CODEC = BuilderCodec.builder(Requires.class, Requires::new)
                .appendInherited(new KeyedCodec<>("Conditions",
                                new ArrayCodec<>(FactorCondition.codec(EditorDataSets.PLACEMENT_FACTORS),
                                        FactorCondition[]::new), false),
                        (o, v) -> o.conditions = v, o -> o.conditions, (o, p) -> o.conditions = p.conditions)
                .documentation("All of these must pass. A condition naming a factor nobody registered cannot "
                        + "resolve and fails closed, so the placement does not appear.").add()
                .build();

        public Requires() {
        }

        @Nonnull
        public static Requires of(@Nullable FactorCondition[] conditions) {
            Requires r = new Requires();
            r.conditions = conditions == null ? null : conditions.clone();
            return r;
        }

        @Nullable
        public FactorCondition[] getConditions() {
            return conditions == null ? null : conditions.clone();
        }

        /** The authored conditions without copying, for the hot evaluation path. */
        @Nonnull
        FactorCondition[] conditionsOrEmpty() {
            return conditions == null ? new FactorCondition[0] : conditions;
        }
    }

    // ==================== Limits ====================

    /**
     * How many of this placement may stand in one world, and how likely each one is.
     *
     * <p>The chance has two independent sources and they are not a mode: {@code SpawnChance} is a
     * plain number, {@code ChanceFormula} works it out from live factor readings. Author whichever
     * suits the placement; author both and the formula is what is used, with the number left as the
     * value a server owner reads to see what was intended.
     */
    public static final class Limits {

        @Nullable protected Double spawnChance;
        @Nullable protected FactorFormula chanceFormula;
        @Nullable protected Integer maxPerWorld;
        @Nullable protected Boolean oncePerWorld;

        public static final BuilderCodec<Limits> CODEC = BuilderCodec.builder(Limits.class, Limits::new)
                .appendInherited(new KeyedCodec<>("SpawnChance", Codec.DOUBLE, false),
                        (o, v) -> o.spawnChance = v, o -> o.spawnChance, (o, p) -> o.spawnChance = p.spawnChance)
                .documentation("Chance in 0..1 that a resolved position is actually used; unauthored means 1 (always). "
                        + "The roll is deterministic per position, so it never re-rolls on a reload.").add()
                .appendInherited(new KeyedCodec<>("ChanceFormula",
                                FactorFormula.codec(EditorDataSets.PLACEMENT_FACTORS), false),
                        (o, v) -> o.chanceFormula = v, o -> o.chanceFormula,
                        (o, p) -> o.chanceFormula = p.chanceFormula)
                .documentation("Work the chance out from factor readings instead of writing a fixed number, e.g. a "
                        + "base of 0.2 plus a world-difficulty factor, clamped to 0..1. It is read once per position "
                        + "and then rolled the same deterministic way SpawnChance is. Authored beside SpawnChance, "
                        + "this is what wins. Factors that need an entity to ask about cannot answer here (nothing "
                        + "stands at the spot yet), so they contribute 0 - author Base for the value the formula "
                        + "must have on its own.").add()
                .appendInherited(new KeyedCodec<>("MaxPerWorld", Codec.INTEGER, false),
                        (o, v) -> o.maxPerWorld = v, o -> o.maxPerWorld, (o, p) -> o.maxPerWorld = p.maxPerWorld)
                .documentation("Ceiling on how many of this placement may stand in one world, counted across every "
                        + "anchor group together. Unauthored or 0 means unlimited.").add()
                .appendInherited(new KeyedCodec<>("OncePerWorld", Codec.BOOLEAN, false),
                        (o, v) -> o.oncePerWorld = v, o -> o.oncePerWorld, (o, p) -> o.oncePerWorld = p.oncePerWorld)
                .documentation("Collapse every resolved position to the first one, in the order WorldSpawn, Coords, "
                        + "Structure, Zone, Custom. Unauthored means false.").add()
                .build();

        public Limits() {
        }

        @Nonnull
        public static Limits of(@Nullable Double spawnChance, @Nullable Integer maxPerWorld,
                @Nullable Boolean oncePerWorld) {
            return of(spawnChance, null, maxPerWorld, oncePerWorld);
        }

        /** Java-side construction including the formula form of the chance. */
        @Nonnull
        public static Limits of(@Nullable Double spawnChance, @Nullable FactorFormula chanceFormula,
                @Nullable Integer maxPerWorld, @Nullable Boolean oncePerWorld) {
            Limits l = new Limits();
            l.spawnChance = spawnChance;
            l.chanceFormula = chanceFormula;
            l.maxPerWorld = maxPerWorld;
            l.oncePerWorld = oncePerWorld;
            return l;
        }

        @Nullable
        public Double getSpawnChance() {
            return spawnChance;
        }

        @Nullable
        public FactorFormula getChanceFormula() {
            return chanceFormula;
        }

        /**
         * True when a formula is authored AND says something, so it is the chance source. An empty
         * formula group is an authoring accident rather than a constant 0, so it is ignored here
         * and reported by {@link NpcPlacementValidator}.
         */
        public boolean hasChanceFormula() {
            return chanceFormula != null && !chanceFormula.isEmpty();
        }

        @Nullable
        public Integer getMaxPerWorld() {
            return maxPerWorld;
        }

        @Nullable
        public Boolean getOncePerWorld() {
            return oncePerWorld;
        }

        /** {@code SpawnChance}, reader-defaulted to {@code 1.0}. */
        public double effectiveSpawnChance() {
            return spawnChance != null ? spawnChance : 1.0;
        }

        /** {@code MaxPerWorld}, reader-defaulted to {@code 0} (unlimited). */
        public int effectiveMaxPerWorld() {
            return maxPerWorld != null ? maxPerWorld : 0;
        }

        /** {@code OncePerWorld}, reader-defaulted to {@code false}. */
        public boolean effectiveOncePerWorld() {
            return oncePerWorld != null && oncePerWorld;
        }
    }

    // ==================== Lifecycle ====================

    /**
     * Opt-in upkeep. Every knob defaults FALSE because each costs something real, and a placement
     * should only pay for what it asks for: {@code KeepAlive} pins a chunk in memory for the life
     * of the world, {@code Respawn} lets a lost NPC come back, {@code Fortify} makes it
     * effectively unkillable.
     */
    public static final class Lifecycle {

        @Nullable protected Boolean keepAlive;
        @Nullable protected Boolean respawn;
        @Nullable protected Boolean fortify;
        @Nullable protected Double fortifyHealth;

        /** The health bonus a fortified placement gets when it does not name its own. */
        public static final double DEFAULT_FORTIFY_HEALTH = 1_000_000.0;

        public static final BuilderCodec<Lifecycle> CODEC = BuilderCodec.builder(Lifecycle.class, Lifecycle::new)
                .appendInherited(new KeyedCodec<>("KeepAlive", Codec.BOOLEAN, false),
                        (o, v) -> o.keepAlive = v, o -> o.keepAlive, (o, p) -> o.keepAlive = p.keepAlive)
                .documentation("Keep the NPC's own chunk loaded so it is always there, at the cost of that chunk never "
                        + "unloading. Reserve it for an NPC players must always be able to find.").add()
                .appendInherited(new KeyedCodec<>("Respawn", Codec.BOOLEAN, false),
                        (o, v) -> o.respawn = v, o -> o.respawn, (o, p) -> o.respawn = p.respawn)
                .documentation("Place the NPC again if it is genuinely gone (killed or removed) while its chunk is "
                        + "loaded. Without this a lost NPC stays lost until an admin re-places it.").add()
                .appendInherited(new KeyedCodec<>("Fortify", Codec.BOOLEAN, false),
                        (o, v) -> o.fortify = v, o -> o.fortify, (o, p) -> o.fortify = p.fortify)
                .documentation("Raise the NPC's max health enormously at spawn so a direct health write cannot kill it. "
                        + "A role's Invulnerable flag does NOT cover that case.").add()
                .appendInherited(new KeyedCodec<>("FortifyHealth", Codec.DOUBLE, false),
                        (o, v) -> o.fortifyHealth = v, o -> o.fortifyHealth, (o, p) -> o.fortifyHealth = p.fortifyHealth)
                .documentation("The additive max-health bonus Fortify applies; unauthored means 1000000.").add()
                .build();

        public Lifecycle() {
        }

        @Nonnull
        public static Lifecycle of(@Nullable Boolean keepAlive, @Nullable Boolean respawn,
                @Nullable Boolean fortify, @Nullable Double fortifyHealth) {
            Lifecycle l = new Lifecycle();
            l.keepAlive = keepAlive;
            l.respawn = respawn;
            l.fortify = fortify;
            l.fortifyHealth = fortifyHealth;
            return l;
        }

        /** {@code KeepAlive}, reader-defaulted to {@code false}. */
        public boolean effectiveKeepAlive() {
            return keepAlive != null && keepAlive;
        }

        /** {@code Respawn}, reader-defaulted to {@code false}. */
        public boolean effectiveRespawn() {
            return respawn != null && respawn;
        }

        /** {@code Fortify}, reader-defaulted to {@code false}. */
        public boolean effectiveFortify() {
            return fortify != null && fortify;
        }

        /** {@code FortifyHealth}, reader-defaulted to {@link #DEFAULT_FORTIFY_HEALTH}. */
        public double effectiveFortifyHealth() {
            return fortifyHealth != null && fortifyHealth > 0 ? fortifyHealth : DEFAULT_FORTIFY_HEALTH;
        }
    }

    // ==================== Interact ====================

    /**
     * What pressing F on the NPC does.
     *
     * <p>{@code Dialogue} is the one behaviour this library understands on its own, because it
     * owns the dialogue engine. Everything else rides {@code Bindings}, a map keyed by namespaced
     * channel that is forwarded verbatim to whichever mod registered that namespace.
     *
     * <p><b>{@code Bindings} is a MAP, not an array, and that is load-bearing.</b> An array is one
     * leaf as far as inheritance is concerned, so a child placement authoring {@code Bindings} at
     * all would drop every entry its {@code Parent} authored. Keyed by channel and decoded through
     * a per-key merging codec, a child can override ONE channel and keep the rest.
     */
    public static final class Interact {

        @Nullable protected String dialogue;
        @Nullable protected Map<String, PlacementBinding> bindings;

        public static final BuilderCodec<Interact> CODEC = BuilderCodec.builder(Interact.class, Interact::new)
                .appendInherited(new KeyedCodec<>("Dialogue", Codec.STRING, false),
                        (o, v) -> o.dialogue = v, o -> o.dialogue, (o, p) -> o.dialogue = p.dialogue)
                .documentation("A dialogue id to open on press-F.").add()
                .appendInherited(new KeyedCodec<>("Bindings",
                                new InheritMapCodec<>(PlacementBinding.CODEC, LinkedHashMap::new), false),
                        (o, v) -> o.bindings = v, o -> o.bindings, (o, p) -> o.bindings = p.bindings)
                .documentation("Per-channel payloads other mods read, keyed by namespaced channel id. Forwarded "
                        + "verbatim and interpreted by nobody here. Merged per key under Parent inheritance, so a "
                        + "child can override one channel and inherit the others.").add()
                .build();

        public Interact() {
        }

        @Nonnull
        public static Interact of(@Nullable String dialogue, @Nullable Map<String, PlacementBinding> bindings) {
            Interact i = new Interact();
            i.dialogue = dialogue;
            i.bindings = bindings == null ? null : new LinkedHashMap<>(bindings);
            return i;
        }

        @Nullable
        public String getDialogue() {
            return dialogue;
        }

        /** The authored bindings, never null (an unauthored map reads as empty). */
        @Nonnull
        public Map<String, PlacementBinding> getBindings() {
            return bindings == null ? Map.of() : bindings;
        }
    }

    // ==================== shared helpers ====================

    static boolean isAllBlank(@Nullable String[] values) {
        if (values == null || values.length == 0) {
            return true;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return false;
            }
        }
        return true;
    }

    static boolean containsIgnoreCase(@Nullable String[] values, @Nonnull String wanted) {
        if (values == null) {
            return false;
        }
        String want = wanted.trim().toLowerCase(Locale.ROOT);
        for (String v : values) {
            if (v != null && v.trim().toLowerCase(Locale.ROOT).equals(want)) {
                return true;
            }
        }
        return false;
    }
}
