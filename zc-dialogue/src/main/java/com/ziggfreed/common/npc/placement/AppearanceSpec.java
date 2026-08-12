package com.ziggfreed.common.npc.placement;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.ziggfreed.common.codec.Vec3;

/**
 * What a placed NPC LOOKS LIKE, as a group of independent knobs rather than one asset id.
 *
 * <p>There are two ways to answer "what does this NPC look like", and they are the group's one
 * exclusive choice:
 *
 * <ul>
 *   <li><b>{@code Model}</b> - name a Model asset that already exists and use it as it is. Nothing
 *       is generated; the placement's role simply points at that id.</li>
 *   <li><b>{@code Base}</b> - name a Model asset to CLONE, then re-dress the clone with the
 *       override knobs below ({@code Texture}, {@code GradientSet}/{@code GradientId},
 *       {@code Scale}, {@code Particles}). A model asset is written for this placement alone, so
 *       two placements can share one base and still look different without either shipping a model
 *       file.</li>
 * </ul>
 *
 * <p>Authoring both is contradictory and is reported by {@link NpcPlacementValidator}; the override
 * knobs mean nothing beside {@code Model}, because a Model asset picked as-is is used as its author
 * made it.
 *
 * <p>{@code Equipment} is orthogonal to both: it is what the NPC HOLDS and WEARS, which lives on
 * the NPC role rather than on a model, so it applies whichever of the two forms is authored. A
 * placement naming an explicit {@code Identity.Role} is placed exactly as that role file describes,
 * so its equipment belongs in the role file itself.
 *
 * <pre>{@code
 * "Appearance": {
 *   "Base": "Human_Male_01",
 *   "Texture": "NPC/Intelligent/Human/Models/Model_Textures/Villager_01.png",
 *   "GradientSet": "Hair",
 *   "GradientId": "Black",
 *   "Scale": 1.15,
 *   "Particles": [ { "SystemId": "Spectre_Void_Hands", "TargetNodeName": "Chest" } ],
 *   "Equipment": { "Armor": ["Armor_Iron_Chest"], "Hotbar": ["Weapon_Sword_Iron"] }
 * }
 * }</pre>
 *
 * <p><b>Scale is a model property, not an entity one.</b> An authored {@code Scale} is written to
 * the generated model as {@code MinScale} and {@code MaxScale} together, which is what makes it a
 * constant rather than a range the engine draws from at spawn. The engine then persists the drawn
 * scale on the NPC itself, so a reload or a chunk waking up does not resize the NPC.
 *
 * <p><b>Bone names cannot be checked.</b> A particle's {@code TargetNodeName} is a bone on the
 * model's mesh, and nothing on the server can enumerate a mesh's bones. A name that does not exist
 * is not an error anywhere: that one particle simply never appears, with no log line and no crash.
 * Copy bone names from the model you are cloning (or from a vanilla model that shares its rig) and
 * confirm in game.
 */
public final class AppearanceSpec {

    @Nullable protected String model;
    @Nullable protected String base;
    @Nullable protected String texture;
    @Nullable protected String gradientSet;
    @Nullable protected String gradientId;
    @Nullable protected Double scale;
    @Nullable protected ParticleSpec[] particles;
    @Nullable protected Equipment equipment;

    public static final BuilderCodec<AppearanceSpec> CODEC =
            BuilderCodec.builder(AppearanceSpec.class, AppearanceSpec::new)
                    .appendInherited(new KeyedCodec<>("Model", Codec.STRING, false),
                            (o, v) -> o.model = v, o -> o.model, (o, p) -> o.model = p.model)
                    .documentation("An existing Model asset id, used exactly as its author made it. Use this when "
                            + "the look you want already ships as a model. Do not author Base as well.").add()
                    .appendInherited(new KeyedCodec<>("Base", Codec.STRING, false),
                            (o, v) -> o.base = v, o -> o.base, (o, p) -> o.base = p.base)
                    .documentation("A Model asset id to CLONE for this placement alone, so the override knobs below "
                            + "can re-dress it. Use this when you want a variant of an existing look.").add()
                    .appendInherited(new KeyedCodec<>("Texture", Codec.STRING, false),
                            (o, v) -> o.texture = v, o -> o.texture, (o, p) -> o.texture = p.texture)
                    .documentation("The skin texture path the clone uses instead of the base's own, e.g. "
                            + "'NPC/Intelligent/Human/Models/Model_Textures/Villager_01.png'. Needs Base.").add()
                    .appendInherited(new KeyedCodec<>("GradientSet", Codec.STRING, false),
                            (o, v) -> o.gradientSet = v, o -> o.gradientSet, (o, p) -> o.gradientSet = p.gradientSet)
                    .documentation("The named palette the recolour comes from, e.g. 'Hair'. Pair it with "
                            + "GradientId; on its own it recolours nothing. Needs Base.").add()
                    .appendInherited(new KeyedCodec<>("GradientId", Codec.STRING, false),
                            (o, v) -> o.gradientId = v, o -> o.gradientId, (o, p) -> o.gradientId = p.gradientId)
                    .documentation("Which entry of GradientSet to recolour with, e.g. 'Black'. Needs Base.").add()
                    .appendInherited(new KeyedCodec<>("Scale", Codec.DOUBLE, false),
                            (o, v) -> o.scale = v, o -> o.scale, (o, p) -> o.scale = p.scale)
                    .documentation("A constant size multiplier for the clone; 1 is the base's own size, 2 is twice "
                            + "as tall. It scales the hit box, eye height and particle offsets with the mesh, so a "
                            + "large value needs headroom to stand and walk in. Needs Base.").add()
                    .appendInherited(new KeyedCodec<>("Particles",
                                    new ArrayCodec<>(ParticleSpec.CODEC, ParticleSpec[]::new), false),
                            (o, v) -> o.particles = v, o -> o.particles, (o, p) -> o.particles = p.particles)
                    .documentation("Persistent particle systems attached to the clone's bones, one entry per "
                            + "attachment point. This is ONE leaf, so a file inheriting from another and authoring "
                            + "Particles replaces the whole list rather than adding to it. Needs Base.").add()
                    .appendInherited(new KeyedCodec<>("Equipment", Equipment.CODEC, false),
                            (o, v) -> o.equipment = v, o -> o.equipment, (o, p) -> o.equipment = p.equipment)
                    .documentation("What the NPC wears and holds. This rides the NPC role rather than the model, so "
                            + "it applies whether you authored Model or Base.").add()
                    .build();

    public AppearanceSpec() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static AppearanceSpec of(@Nullable String model, @Nullable String base, @Nullable String texture,
            @Nullable String gradientSet, @Nullable String gradientId, @Nullable Double scale,
            @Nullable ParticleSpec[] particles, @Nullable Equipment equipment) {
        AppearanceSpec s = new AppearanceSpec();
        s.model = model;
        s.base = base;
        s.texture = texture;
        s.gradientSet = gradientSet;
        s.gradientId = gradientId;
        s.scale = scale;
        s.particles = particles == null ? null : particles.clone();
        s.equipment = equipment;
        return s;
    }

    /** The shorthand for the commonest case: use an existing Model asset as it is. */
    @Nonnull
    public static AppearanceSpec model(@Nullable String modelId) {
        return of(modelId, null, null, null, null, null, null, null);
    }

    @Nullable
    public String getModel() {
        return model;
    }

    @Nullable
    public String getBase() {
        return base;
    }

    @Nullable
    public String getTexture() {
        return texture;
    }

    @Nullable
    public String getGradientSet() {
        return gradientSet;
    }

    @Nullable
    public String getGradientId() {
        return gradientId;
    }

    @Nullable
    public Double getScale() {
        return scale;
    }

    /** The authored particles, defensively copied, or null when none were authored. */
    @Nullable
    public ParticleSpec[] getParticles() {
        return particles == null ? null : particles.clone();
    }

    /** The authored particles without copying, for the emission path. */
    @Nonnull
    ParticleSpec[] particlesOrEmpty() {
        return particles == null ? new ParticleSpec[0] : particles;
    }

    @Nullable
    public Equipment getEquipment() {
        return equipment;
    }

    /** True when a Model asset is named to use as-is. */
    public boolean hasModel() {
        return model != null && !model.isBlank();
    }

    /** True when a Model asset is named to clone, which is what makes a model get written. */
    public boolean hasBase() {
        return base != null && !base.isBlank();
    }

    /** True when both forms are authored, which is the one contradiction in this group. */
    public boolean hasBothForms() {
        return hasModel() && hasBase();
    }

    /**
     * True when at least one knob that only means something on a CLONE is authored. Used to report
     * an override that will be ignored because no {@code Base} was named.
     */
    public boolean hasCloneOverrides() {
        return (texture != null && !texture.isBlank())
                || (gradientSet != null && !gradientSet.isBlank())
                || (gradientId != null && !gradientId.isBlank())
                || scale != null
                || particlesOrEmpty().length > 0;
    }

    /** True when this group says nothing at all, so it cannot describe an NPC. */
    public boolean isBlank() {
        return !hasModel() && !hasBase() && !hasCloneOverrides()
                && (equipment == null || equipment.isBlank());
    }

    // ==================== ParticleSpec ====================

    /**
     * One persistent particle system attached to the generated model. The field names and meanings
     * are the engine's own model-particle shape, so a spec here reads exactly like the same block
     * written by hand in a Model asset.
     *
     * <p>Only {@code SystemId} is really required; everything else refines where and how the
     * system renders. A {@code TargetNodeName} naming a bone the mesh does not have makes that one
     * particle silently do nothing (see the class javadoc).
     */
    public static final class ParticleSpec {

        @Nullable protected String systemId;
        @Nullable protected String targetNodeName;
        @Nullable protected String color;
        @Nullable protected Double scale;
        @Nullable protected Vec3 positionOffset;
        @Nullable protected Rotation rotationOffset;
        @Nullable protected Boolean detachedFromModel;

        public static final BuilderCodec<ParticleSpec> CODEC =
                BuilderCodec.builder(ParticleSpec.class, ParticleSpec::new)
                        .appendInherited(new KeyedCodec<>("SystemId", Codec.STRING, false),
                                (o, v) -> o.systemId = v, o -> o.systemId, (o, p) -> o.systemId = p.systemId)
                        .documentation("The particle-system asset id to spawn, e.g. 'Spectre_Void_Hands'. Without "
                                + "it the entry does nothing.").add()
                        .appendInherited(new KeyedCodec<>("TargetNodeName", Codec.STRING, false),
                                (o, v) -> o.targetNodeName = v, o -> o.targetNodeName,
                                (o, p) -> o.targetNodeName = p.targetNodeName)
                        .documentation("The bone on the model the system rides, e.g. 'Chest' or 'L-Eye-Attachment'. "
                                + "Omit to spawn at the model's own origin. A bone name the mesh does not have is "
                                + "not an error: that particle simply never appears.").add()
                        .appendInherited(new KeyedCodec<>("Color", Codec.STRING, false),
                                (o, v) -> o.color = v, o -> o.color, (o, p) -> o.color = p.color)
                        .documentation("Tint for the particles, as '#rrggbb' or 'rgb(r,g,b)'. Omit to keep the "
                                + "system's own colours.").add()
                        .appendInherited(new KeyedCodec<>("Scale", Codec.DOUBLE, false),
                                (o, v) -> o.scale = v, o -> o.scale, (o, p) -> o.scale = p.scale)
                        .documentation("Size multiplier for the particles; 1 is the system's own size. This "
                                + "compounds with the model's own scale, so a large NPC already has large "
                                + "particles before this is touched.").add()
                        .appendInherited(new KeyedCodec<>("PositionOffset", Vec3.CODEC, false),
                                (o, v) -> o.positionOffset = v, o -> o.positionOffset,
                                (o, p) -> o.positionOffset = p.positionOffset)
                        .documentation("A shift from the bone, in the model's own frame. Each axis is independently "
                                + "optional.").add()
                        .appendInherited(new KeyedCodec<>("RotationOffset", Rotation.CODEC, false),
                                (o, v) -> o.rotationOffset = v, o -> o.rotationOffset,
                                (o, p) -> o.rotationOffset = p.rotationOffset)
                        .documentation("A turn from the bone's own facing, in degrees. Each axis is independently "
                                + "optional.").add()
                        .appendInherited(new KeyedCodec<>("DetachedFromModel", Codec.BOOLEAN, false),
                                (o, v) -> o.detachedFromModel = v, o -> o.detachedFromModel,
                                (o, p) -> o.detachedFromModel = p.detachedFromModel)
                        .documentation("Author true to leave the particles behind in the world as the NPC moves "
                                + "instead of carrying them along. Unauthored means false (they follow).").add()
                        .build();

        public ParticleSpec() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static ParticleSpec of(@Nullable String systemId, @Nullable String targetNodeName,
                @Nullable String color, @Nullable Double scale, @Nullable Vec3 positionOffset,
                @Nullable Rotation rotationOffset, @Nullable Boolean detachedFromModel) {
            ParticleSpec p = new ParticleSpec();
            p.systemId = systemId;
            p.targetNodeName = targetNodeName;
            p.color = color;
            p.scale = scale;
            p.positionOffset = positionOffset;
            p.rotationOffset = rotationOffset;
            p.detachedFromModel = detachedFromModel;
            return p;
        }

        /** The shorthand for the commonest case: one system on one bone. */
        @Nonnull
        public static ParticleSpec on(@Nullable String systemId, @Nullable String targetNodeName) {
            return of(systemId, targetNodeName, null, null, null, null, null);
        }

        @Nullable
        public String getSystemId() {
            return systemId;
        }

        @Nullable
        public String getTargetNodeName() {
            return targetNodeName;
        }

        @Nullable
        public String getColor() {
            return color;
        }

        @Nullable
        public Double getScale() {
            return scale;
        }

        @Nullable
        public Vec3 getPositionOffset() {
            return positionOffset;
        }

        @Nullable
        public Rotation getRotationOffset() {
            return rotationOffset;
        }

        @Nullable
        public Boolean getDetachedFromModel() {
            return detachedFromModel;
        }

        /** True when no system id is authored, so this entry can never render. */
        public boolean isBlank() {
            return systemId == null || systemId.isBlank();
        }
    }

    // ==================== Rotation ====================

    /**
     * A turn in degrees, matching the engine's own {@code Yaw}/{@code Pitch}/{@code Roll} leaf
     * names. Every axis is independently optional, so authoring one keeps the others at 0 and a
     * file inheriting from another can override a single axis.
     */
    public static final class Rotation {

        @Nullable protected Double yaw;
        @Nullable protected Double pitch;
        @Nullable protected Double roll;

        public static final BuilderCodec<Rotation> CODEC = BuilderCodec.builder(Rotation.class, Rotation::new)
                .appendInherited(new KeyedCodec<>("Yaw", Codec.DOUBLE, false),
                        (o, v) -> o.yaw = v, o -> o.yaw, (o, p) -> o.yaw = p.yaw)
                .documentation("Turn around the vertical axis, in degrees; unauthored means 0.").add()
                .appendInherited(new KeyedCodec<>("Pitch", Codec.DOUBLE, false),
                        (o, v) -> o.pitch = v, o -> o.pitch, (o, p) -> o.pitch = p.pitch)
                .documentation("Tilt up or down, in degrees; unauthored means 0.").add()
                .appendInherited(new KeyedCodec<>("Roll", Codec.DOUBLE, false),
                        (o, v) -> o.roll = v, o -> o.roll, (o, p) -> o.roll = p.roll)
                .documentation("Twist around the facing axis, in degrees; unauthored means 0.").add()
                .build();

        public Rotation() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Rotation of(@Nullable Double yaw, @Nullable Double pitch, @Nullable Double roll) {
            Rotation r = new Rotation();
            r.yaw = yaw;
            r.pitch = pitch;
            r.roll = roll;
            return r;
        }

        @Nullable
        public Double getYaw() {
            return yaw;
        }

        @Nullable
        public Double getPitch() {
            return pitch;
        }

        @Nullable
        public Double getRoll() {
            return roll;
        }

        /** True when no axis is authored, so this group turns nothing. */
        public boolean isBlank() {
            return yaw == null && pitch == null && roll == null;
        }
    }

    // ==================== Equipment ====================

    /**
     * What the NPC wears and holds. These are item ids, and they land on the NPC's own inventory
     * the same way a hand-written role's do, so an NPC can carry a weapon or wear a set of armour
     * without a role file per outfit.
     *
     * <p>Each list is ONE leaf: a file inheriting from another and authoring {@code Armor} replaces
     * the whole armour set rather than adding a piece to it.
     */
    public static final class Equipment {

        @Nullable protected String[] armor;
        @Nullable protected String[] hotbar;
        @Nullable protected String[] offHand;
        @Nullable protected Integer defaultOffHandSlot;

        public static final BuilderCodec<Equipment> CODEC = BuilderCodec.builder(Equipment.class, Equipment::new)
                .appendInherited(new KeyedCodec<>("Armor", Codec.STRING_ARRAY, false),
                        (o, v) -> o.armor = v, o -> o.armor, (o, p) -> o.armor = p.armor)
                .documentation("Item ids worn in the armour slots, e.g. ['Armor_Iron_Head', 'Armor_Iron_Chest']. "
                        + "Authoring this replaces the whole set rather than adding one piece.").add()
                .appendInherited(new KeyedCodec<>("Hotbar", Codec.STRING_ARRAY, false),
                        (o, v) -> o.hotbar = v, o -> o.hotbar, (o, p) -> o.hotbar = p.hotbar)
                .documentation("Item ids in the NPC's hotbar, the first of which is what it holds, e.g. "
                        + "['Weapon_Sword_Iron'].").add()
                .appendInherited(new KeyedCodec<>("OffHand", Codec.STRING_ARRAY, false),
                        (o, v) -> o.offHand = v, o -> o.offHand, (o, p) -> o.offHand = p.offHand)
                .documentation("Item ids in the off hand, e.g. ['Weapon_Shield_Iron'].").add()
                .appendInherited(new KeyedCodec<>("DefaultOffHandSlot", Codec.INTEGER, false),
                        (o, v) -> o.defaultOffHandSlot = v, o -> o.defaultOffHandSlot,
                        (o, p) -> o.defaultOffHandSlot = p.defaultOffHandSlot)
                .documentation("Which OffHand entry starts active, counting from 0. Omit unless you author more "
                        + "than one off-hand item.").add()
                .build();

        public Equipment() {
        }

        /** Java-side factory; sets the same fields the codec fills. */
        @Nonnull
        public static Equipment of(@Nullable String[] armor, @Nullable String[] hotbar, @Nullable String[] offHand,
                @Nullable Integer defaultOffHandSlot) {
            Equipment e = new Equipment();
            e.armor = armor == null ? null : armor.clone();
            e.hotbar = hotbar == null ? null : hotbar.clone();
            e.offHand = offHand == null ? null : offHand.clone();
            e.defaultOffHandSlot = defaultOffHandSlot;
            return e;
        }

        @Nullable
        public String[] getArmor() {
            return armor == null ? null : armor.clone();
        }

        @Nullable
        public String[] getHotbar() {
            return hotbar == null ? null : hotbar.clone();
        }

        @Nullable
        public String[] getOffHand() {
            return offHand == null ? null : offHand.clone();
        }

        @Nullable
        public Integer getDefaultOffHandSlot() {
            return defaultOffHandSlot;
        }

        /** True when nothing is authored, so no inventory slot is touched. */
        public boolean isBlank() {
            return isEmpty(armor) && isEmpty(hotbar) && isEmpty(offHand) && defaultOffHandSlot == null;
        }

        private static boolean isEmpty(@Nullable String[] values) {
            return values == null || values.length == 0;
        }
    }
}
