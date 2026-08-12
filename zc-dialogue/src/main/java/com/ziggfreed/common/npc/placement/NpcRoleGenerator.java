package com.ziggfreed.common.npc.placement;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.assetstore.AssetPack;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.common.semver.SemverRange;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.util.SafeLog;

/**
 * Builds one NPC role per placement at runtime, so a pack that only wants a different FACE does
 * not have to ship a near-identical role file per NPC.
 *
 * <p>A placement authoring {@code Identity.Appearance} with no explicit {@code Role} gets a role
 * written for it as a native <b>variant</b>: a three-line file naming the template role it is a
 * variant of plus the handful of keys it overrides. Everything else - the whole idle/watch/interact
 * behaviour, including the press-F action - comes from the template, so a fix to the template flows
 * into every placement that names it instead of into N copies of a role body.
 *
 * <pre>{@code
 * { "Type": "Variant",
 *   "Reference": "Template_MyMod_Guide",
 *   "Modify": { "Appearance": "Zc_Gen_Mdl_hub_guide",
 *               "NameTranslationKey": "npc.guide.name",
 *               "Hint": "npc.guide.hint" } }
 * }</pre>
 *
 * <p><b>The template has to offer what the placement overrides.</b> A native role only accepts an
 * override for a key it declared in its own {@code Parameters} block, so the template a placement
 * names must declare the standard set this generator emits ({@link #MODIFY_APPEARANCE},
 * {@link #MODIFY_NAME_TRANSLATION_KEY}, {@link #MODIFY_HINT}, {@link #MODIFY_WEAPONS},
 * {@link #MODIFY_OFF_HAND}, {@link #MODIFY_DEFAULT_OFF_HAND_SLOT}, {@link #MODIFY_ARMOR}) - or at
 * least whichever of them its placements actually author. A key the template does not offer is
 * DROPPED from the emitted variant with a loud log rather than written and refused, because a
 * refused variant is an NPC that never appears at all; see {@link RoleTemplates} and
 * {@link NpcPlacementValidator}'s {@code MODIFY_KEY_NOT_PARAMETERIZED}.
 *
 * <p><b>A MODEL is generated too when the appearance asks for one.</b> An
 * {@link AppearanceSpec} naming a {@code Base} to clone gets its own model asset written beside the
 * role, carrying the base as its {@code Parent} plus whichever of texture, gradient, scale and
 * particles were authored; {@code Modify.Appearance} then points at that model. An appearance
 * naming a {@code Model} instead points straight at it and writes nothing. Either way a pack
 * author ships neither a role file nor a model file.
 *
 * <p><b>Timing.</b> Call {@link #generateAndRegister} once per boot, AFTER the asset load event
 * has folded {@link NpcPlacementConfig} (so the placements are known and the engine will actually
 * dispatch the pack-register event) and BEFORE any world streams chunks (so no placement can try
 * to spawn a role that does not exist yet). The roles materialize into a directory pack under
 * {@code mods/ziggfreedcommon/generated-roles} registered as {@link AssetPack.PackSource#RUNTIME},
 * the lowest precedence, so a real pack can still override any of them by id. A runtime pack is
 * processed strictly after every boot-time pack, so a variant here can safely name a template from
 * any pack on the server, including one shipped inside a mod jar.
 */
public final class NpcRoleGenerator {

    /** Where the generated roles land (a directory pack, not a zip). */
    private static final Path PACK_DIR = Paths.get("mods", "ziggfreedcommon", "generated-roles");
    private static final Path ROLES_DIR =
            PACK_DIR.resolve("Server").resolve("NPC").resolve("Roles").resolve("Passive");

    /**
     * Where the generated models land, inside the SAME pack. The engine walks a pack once per
     * asset type from its own {@code Server/<type path>} root, so models and roles ride one
     * registration.
     */
    private static final Path MODELS_DIR = PACK_DIR.resolve("Server").resolve("Models");

    // ==================== the Modify keys a template must declare ====================

    /** The model the NPC wears. Named for the field a role carries it in. */
    public static final String MODIFY_APPEARANCE = "Appearance";

    /** The localization key for the nameplate. */
    public static final String MODIFY_NAME_TRANSLATION_KEY = "NameTranslationKey";

    /** The localization key for the press-F prompt. */
    public static final String MODIFY_HINT = "Hint";

    /** Main-hand / hotbar items. Named for the parameter the vanilla humanoid templates declare. */
    public static final String MODIFY_WEAPONS = "Weapons";

    /** Off-hand items. Named for the parameter the vanilla humanoid templates declare. */
    public static final String MODIFY_OFF_HAND = "OffHand";

    /** Which off-hand slot is held by default; -1 for nothing. */
    public static final String MODIFY_DEFAULT_OFF_HAND_SLOT = "DefaultOffHandSlot";

    /** Worn armour pieces. */
    public static final String MODIFY_ARMOR = "Armor";

    /** The guidance written into every generated model, for whoever opens one. */
    @Nonnull
    private static String modelComment(@Nonnull String placementId) {
        return "Generated from the Identity.Appearance of the NPC placement '" + placementId + "', and rebuilt "
                + "from scratch every boot. Edit that placement rather than this file: changes here are lost. "
                + "To take full control of the look instead, author your own Model asset and name it as that "
                + "placement's Identity.Appearance.Model, which stops anything being generated for it.";
    }

    /** The guidance written into every generated role, for whoever opens one. */
    @Nonnull
    private static String roleComment(@Nonnull String placementId) {
        return "Generated from the NPC placement '" + placementId + "', and rebuilt from scratch every boot. "
                + "Edit that placement rather than this file: changes here are lost. The behaviour comes from "
                + "the template named in Reference, so change that template to change how every placement using "
                + "it behaves. To take full control instead, author your own role and name it as that "
                + "placement's Identity.Role, which stops anything being generated for it.";
    }

    /** Stable identity for the generated pack. */
    private static final String PACK_GROUP = "Ziggfreed";
    private static final String PACK_NAME = "ZiggfreedCommonGeneratedRoles";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** Placement id to the role name generated for it, populated by {@link #generateAndRegister}. */
    private static final Map<String, String> GENERATED = new ConcurrentHashMap<>();

    private NpcRoleGenerator() {
    }

    // ==================== generation ====================

    /**
     * The role name generated for {@code placementId}. Deterministic, so a placement that has
     * already been generated resolves to the same role across restarts.
     */
    @Nonnull
    public static String generatedRoleName(@Nonnull String placementId) {
        return "Zc_Gen_" + placementId.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The model name generated for {@code placementId} when its appearance names a {@code Base} to
     * clone. Deterministic for the same reason the role name is: the role file written this boot
     * points at it by name.
     */
    @Nonnull
    public static String generatedModelName(@Nonnull String placementId) {
        return "Zc_Gen_Mdl_" + placementId.trim().toLowerCase(Locale.ROOT);
    }

    /** Was a role actually generated for {@code placementId} this boot? */
    public static boolean wasGenerated(@Nonnull String placementId) {
        return GENERATED.containsKey(placementId.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Materialize a role for every placement that wants one and register the pack. Idempotent per
     * call in the sense that the roles directory is rebuilt from scratch, so a removed or renamed
     * placement leaves nothing stale behind. Never throws: a failure leaves the affected
     * placements unspawnable and logs.
     *
     * @param version the version stamped on the generated pack (a consumer's own mod version)
     */
    public static void generateAndRegister(@Nonnull Semver version) {
        GENERATED.clear();
        try {
            cleanGeneratedDir(ROLES_DIR);
            cleanGeneratedDir(MODELS_DIR);

            int written = 0;
            for (NpcPlacementAsset placement : NpcPlacementConfig.getInstance().all().values()) {
                NpcPlacementAsset.Identity identity = placement == null ? null : placement.getIdentity();
                if (identity == null || !identity.usesGeneratedRole()) {
                    continue;
                }
                String placementId = placement.getId();
                if (placementId == null || placementId.isBlank()) {
                    continue;
                }
                String template = identity.getBaseRole();
                if (template == null || template.isBlank()) {
                    SafeLog.warn("[placement] '" + placementId + "' authors an Appearance but no BaseRole naming the "
                            + "template role to build a variant of - it will not spawn");
                    continue;
                }
                try {
                    JsonObject model = buildModel(identity.getAppearance(), placementId);
                    if (model != null) {
                        writeJson(MODELS_DIR, generatedModelName(placementId), model);
                    }
                    String roleName = generatedRoleName(placementId);
                    writeJson(ROLES_DIR, roleName, checkedVariant(identity, placementId));
                    GENERATED.put(placementId, roleName);
                    written++;
                } catch (Exception e) {
                    SafeLog.warn("[placement] failed to generate a role for '" + placementId + "': " + e.getMessage());
                }
            }

            if (written == 0) {
                return;
            }
            registerPack(version, written);
        } catch (Throwable t) {
            SafeLog.severe("[placement] runtime role generation failed: " + t.getMessage(), t);
        }
    }

    /**
     * {@link #buildVariant} plus the two questions only a running server can answer: does the
     * template exist, and does it offer every key this variant overrides. An unknown template is a
     * warning and the variant is written anyway (it may load later); a key the template does not
     * offer is DROPPED, because the engine refuses the whole variant over one bad key and an NPC
     * that appears with the template's own nameplate is worth more than no NPC at all.
     */
    @Nonnull
    private static JsonObject checkedVariant(@Nonnull NpcPlacementAsset.Identity identity,
            @Nonnull String placementId) {
        JsonObject variant = buildVariant(identity, placementId);
        String template = identity.getBaseRole();
        JsonObject modify = variant.getAsJsonObject("Modify");

        if (Boolean.FALSE.equals(RoleTemplates.templateExists(template))) {
            SafeLog.warn("[placement] '" + placementId + "' names the template role '" + template
                    + "', which no loaded pack provides - it will not spawn until one does");
            return variant;
        }
        for (String key : RoleTemplates.unparameterizedKeys(template, List.copyOf(modify.keySet()))) {
            modify.remove(key);
            SafeLog.severe("[placement] the template role '" + template + "' does not declare '" + key
                    + "' in its Parameters block, so '" + placementId + "' cannot override it. Dropping that key "
                    + "and generating the rest; add \"" + key + "\" to the template's Parameters to fix it");
        }
        return variant;
    }

    /**
     * The PURE emission: the native variant role for this identity, ready to write. A variant names
     * the template it varies and carries nothing but the overrides, so what is NOT authored is
     * simply absent and the template's own value stands.
     */
    @Nonnull
    public static JsonObject buildVariant(@Nonnull NpcPlacementAsset.Identity identity,
            @Nonnull String placementId) {
        JsonObject variant = new JsonObject();
        variant.addProperty("$Comment", roleComment(placementId));
        variant.addProperty("Type", "Variant");
        variant.addProperty("Reference", identity.getBaseRole());
        variant.add("Modify", buildModify(identity, placementId));
        return variant;
    }

    /**
     * The PURE override set: exactly the keys this identity authored, and no others. Every key here
     * has to be one the referenced template declared in its own {@code Parameters} block - the
     * constants on this class are the set, and a template meant to back placements declares them.
     *
     * <p>The appearance key is either the {@code Model} the placement named as it is, or the model
     * generated for this placement when it named a {@code Base} to clone; an appearance carrying
     * only equipment leaves the template's own look alone by omitting the key entirely.
     */
    @Nonnull
    public static JsonObject buildModify(@Nonnull NpcPlacementAsset.Identity identity,
            @Nonnull String placementId) {
        JsonObject modify = new JsonObject();
        AppearanceSpec appearance = identity.getAppearance();
        addIfPresent(modify, MODIFY_APPEARANCE, appearanceIdFor(appearance, placementId));
        addIfPresent(modify, MODIFY_NAME_TRANSLATION_KEY, identity.getNameKey());
        addIfPresent(modify, MODIFY_HINT, identity.getHintKey());
        if (appearance != null) {
            AppearanceSpec.Equipment equipment = appearance.getEquipment();
            if (equipment != null && !equipment.isBlank()) {
                addIfPresent(modify, MODIFY_ARMOR, equipment.getArmor());
                addIfPresent(modify, MODIFY_WEAPONS, equipment.getHotbar());
                addIfPresent(modify, MODIFY_OFF_HAND, equipment.getOffHand());
                if (equipment.getDefaultOffHandSlot() != null) {
                    modify.addProperty(MODIFY_DEFAULT_OFF_HAND_SLOT, equipment.getDefaultOffHandSlot());
                }
            }
        }
        return modify;
    }

    /**
     * The Model asset id a generated role should point at: the appearance's own {@code Model} when
     * it names one, the model generated for this placement when it names a {@code Base} to clone,
     * and {@code null} when it names neither (the template's own appearance stands).
     */
    @Nullable
    public static String appearanceIdFor(@Nullable AppearanceSpec appearance, @Nonnull String placementId) {
        if (appearance == null) {
            return null;
        }
        if (appearance.hasBase()) {
            return generatedModelName(placementId);
        }
        return appearance.hasModel() ? appearance.getModel() : null;
    }

    /**
     * The PURE model clone: the base as {@code Parent} plus whichever overrides were authored, or
     * {@code null} when the appearance names no {@code Base} and so wants no model of its own.
     *
     * <p>An authored {@code Scale} becomes {@code MinScale} and {@code MaxScale} together, which is
     * what makes it a constant instead of a range the engine draws a random size from at spawn.
     *
     * <p>Only keys the Model schema declares are written (its {@code additionalProperties} is
     * false), and the asset's id comes from the file name rather than from any key inside it.
     */
    @Nullable
    public static JsonObject buildModel(@Nullable AppearanceSpec appearance, @Nonnull String placementId) {
        if (appearance == null || !appearance.hasBase()) {
            return null;
        }
        JsonObject model = new JsonObject();
        model.addProperty("$Comment", modelComment(placementId));
        model.addProperty("Parent", appearance.getBase());
        addIfPresent(model, "Texture", appearance.getTexture());
        addIfPresent(model, "GradientSet", appearance.getGradientSet());
        addIfPresent(model, "GradientId", appearance.getGradientId());
        Double scale = appearance.getScale();
        if (scale != null && Double.isFinite(scale)) {
            model.addProperty("MinScale", scale);
            model.addProperty("MaxScale", scale);
        }
        JsonArray particles = buildParticles(appearance);
        if (particles.size() > 0) {
            model.add("Particles", particles);
        }
        return model;
    }

    @Nonnull
    private static JsonArray buildParticles(@Nonnull AppearanceSpec appearance) {
        JsonArray out = new JsonArray();
        for (AppearanceSpec.ParticleSpec spec : appearance.particlesOrEmpty()) {
            if (spec == null || spec.isBlank()) {
                continue;
            }
            JsonObject particle = new JsonObject();
            particle.addProperty("SystemId", spec.getSystemId());
            addIfPresent(particle, "TargetNodeName", spec.getTargetNodeName());
            addIfPresent(particle, "Color", spec.getColor());
            if (spec.getScale() != null && Double.isFinite(spec.getScale())) {
                particle.addProperty("Scale", spec.getScale());
            }
            JsonObject position = vectorOf(spec.getPositionOffset());
            if (position != null) {
                particle.add("PositionOffset", position);
            }
            JsonObject rotation = rotationOf(spec.getRotationOffset());
            if (rotation != null) {
                particle.add("RotationOffset", rotation);
            }
            if (spec.getDetachedFromModel() != null) {
                particle.addProperty("DetachedFromModel", spec.getDetachedFromModel());
            }
            out.add(particle);
        }
        return out;
    }

    private static void addIfPresent(@Nonnull JsonObject target, @Nonnull String key, @Nullable String value) {
        if (value != null && !value.isBlank()) {
            target.addProperty(key, value);
        }
    }

    private static void addIfPresent(@Nonnull JsonObject target, @Nonnull String key, @Nullable String[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        JsonArray array = new JsonArray();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                array.add(value);
            }
        }
        if (array.size() > 0) {
            target.add(key, array);
        }
    }

    @Nullable
    private static JsonObject vectorOf(@Nullable Vec3 offset) {
        if (offset == null) {
            return null;
        }
        JsonObject out = new JsonObject();
        addIfFinite(out, "X", offset.getX());
        addIfFinite(out, "Y", offset.getY());
        addIfFinite(out, "Z", offset.getZ());
        return out.size() > 0 ? out : null;
    }

    @Nullable
    private static JsonObject rotationOf(@Nullable AppearanceSpec.Rotation rotation) {
        if (rotation == null) {
            return null;
        }
        JsonObject out = new JsonObject();
        addIfFinite(out, "Yaw", rotation.getYaw());
        addIfFinite(out, "Pitch", rotation.getPitch());
        addIfFinite(out, "Roll", rotation.getRoll());
        return out.size() > 0 ? out : null;
    }

    private static void addIfFinite(@Nonnull JsonObject target, @Nonnull String key, @Nullable Double value) {
        if (value != null && Double.isFinite(value)) {
            target.addProperty(key, value);
        }
    }

    private static void writeJson(@Nonnull Path dir, @Nonnull String name, @Nonnull JsonObject body) throws Exception {
        Files.createDirectories(dir);
        try (Writer writer = Files.newBufferedWriter(dir.resolve(name + ".json"), StandardCharsets.UTF_8)) {
            GSON.toJson(body, writer);
        }
    }

    /** Delete stale generated files so a removed or renamed placement leaves none behind. */
    private static void cleanGeneratedDir(@Nonnull Path dir) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // Best effort: an undeletable stale file is simply overwritten if regenerated.
                }
            });
        } catch (Exception e) {
            SafeLog.fine("[placement] could not clean " + dir + ": " + e.getMessage());
        }
    }

    // ==================== registration ====================

    /**
     * Write the pack's root manifest so the directory pack looks like a real on-disk pack. The
     * engine revalidates every pack when a server op opens the in-game asset editor and drops a
     * manifest-less directory pack, which would unload the generated roles mid-session.
     */
    private static void writeManifestFile(@Nonnull Semver version) {
        try {
            Files.createDirectories(PACK_DIR);
            JsonObject manifest = new JsonObject();
            manifest.addProperty("Group", PACK_GROUP);
            manifest.addProperty("Name", PACK_NAME);
            manifest.addProperty("Version", version.toString());
            manifest.addProperty("ServerVersion", "*");
            manifest.addProperty("IncludesAssetPack", true);
            try (Writer w = Files.newBufferedWriter(PACK_DIR.resolve("manifest.json"), StandardCharsets.UTF_8)) {
                GSON.toJson(manifest, w);
            }
        } catch (Exception e) {
            SafeLog.warn("[placement] failed to write the generated-roles manifest: " + e.getMessage());
        }
    }

    private static void registerPack(@Nonnull Semver version, int roleCount) {
        AssetModule assetModule = AssetModule.get();
        if (assetModule == null) {
            SafeLog.warn("[placement] AssetModule unavailable - generated roles not registered");
            return;
        }
        writeManifestFile(version);
        PluginManifest manifest = new PluginManifest(
                PACK_GROUP,
                PACK_NAME,
                version,
                "Ziggfreed Common - runtime-generated NPC placement roles and models.",
                List.of(),
                null,
                null,
                SemverRange.WILDCARD,
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                new LinkedHashMap<>(),
                List.of(),
                false);
        String packId = new PluginIdentifier(manifest).toString();
        try {
            boolean ok = assetModule.registerPack(packId, PACK_DIR.toAbsolutePath().normalize(), manifest,
                    AssetPack.PackSource.RUNTIME);
            if (ok) {
                SafeLog.info("[placement] registered runtime role pack '" + packId + "' ("
                        + roleCount + " generated role(s))");
            } else {
                SafeLog.warn("[placement] runtime role pack '" + packId
                        + "' was rejected - generated roles may not load");
            }
        } catch (Throwable t) {
            SafeLog.severe("[placement] registerPack failed for '" + packId + "': " + t.getMessage(), t);
        }
    }

    /** Drop every generation record (tests). */
    static void clearForTests() {
        GENERATED.clear();
    }

    /** Every Modify key this generator can emit, in emission order (diagnostics and tests). */
    @Nonnull
    public static List<String> modifyKeys() {
        return List.of(MODIFY_APPEARANCE, MODIFY_NAME_TRANSLATION_KEY, MODIFY_HINT, MODIFY_ARMOR,
                MODIFY_WEAPONS, MODIFY_OFF_HAND, MODIFY_DEFAULT_OFF_HAND_SLOT);
    }

    /** The Modify keys {@code identity} actually authors, in emission order. */
    @Nonnull
    public static List<String> authoredModifyKeys(@Nonnull NpcPlacementAsset.Identity identity,
            @Nonnull String placementId) {
        return new ArrayList<>(buildModify(identity, placementId).keySet());
    }
}
