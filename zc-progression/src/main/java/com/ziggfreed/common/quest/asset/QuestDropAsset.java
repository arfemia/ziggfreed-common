package com.ziggfreed.common.quest.asset;

import java.util.Arrays;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.quest.QuestItemDrop;

/**
 * A quest step's {@code Drop} block: where the item the step asks for (its {@code Target}) drops
 * from, for a player whose step still wants it. It drops only on a {@code TURN_IN} or a
 * {@code PICKUP_ITEM} step. The engine's form is {@link QuestItemDrop}.
 *
 * <pre>{@code
 * "bring_pelts": { "Kind": "TURN_IN", "Target": "Wolf_Pelt", "MatchMode": "EXACT", "Amount": 3,
 *                  "Drop": { "Roles": ["Wolf_Grey", "Wolf_Black"], "Chance": 0.5, "Count": 1 } }
 * "bring_crest": { "Kind": "TURN_IN", "Target": "yourmod_Ogre_Crest", "Amount": 1,
 *                  "Drop": { "Encounters": ["yourmod_Ogre_King"] } }
 * }</pre>
 *
 * <p>Every leaf is {@code appendInherited}, so a child quest retunes one number and keeps the rest; a
 * list is ONE leaf and replaces the parent's whole.
 */
public final class QuestDropAsset {

    @Nullable protected String[] roles;
    @Nullable protected String[] encounters;
    @Nullable protected Double chance;
    @Nullable protected Integer count;

    public static final BuilderCodec<QuestDropAsset> CODEC =
            BuilderCodec.builder(QuestDropAsset.class, QuestDropAsset::new)
                    .appendInherited(new KeyedCodec<>("Roles", Codec.STRING_ARRAY, false),
                            (o, v) -> o.roles = v, o -> o.roles, (o, p) -> o.roles = p.roles)
                    .documentation("NPC role ids whose kill can drop the step's item, for the player credited "
                            + "with the kill. Case is ignored. Name a boss fought as an encounter under "
                            + "Encounters instead, so every fighter it credits can get it.").add()
                    .appendInherited(new KeyedCodec<>("Encounters", Codec.STRING_ARRAY, false),
                            (o, v) -> o.encounters = v, o -> o.encounters, (o, p) -> o.encounters = p.encounters)
                    .documentation("Boss fights whose win can drop the step's item for every participant the "
                            + "fight credits, named as an ENCOUNTER_DEFEATED step names them: the encounter "
                            + "script id (or the role id an encounter rule credits). Case is ignored. Do not name "
                            + "the same boss under Roles too, or its killer rolls twice.").add()
                    .appendInherited(new KeyedCodec<>("Chance", Codec.DOUBLE, false),
                            (o, v) -> o.chance = v, o -> o.chance, (o, p) -> o.chance = p.chance)
                    .metadata(EditorSchema.defaultValue(QuestItemDrop.DEFAULT_CHANCE))
                    .documentation("The chance from 0 to 1 that the item drops, rolled once per eligible step: "
                            + "for each player, at each kill or win, whose step still wants it. Unauthored means "
                            + "1, every time.").add()
                    .appendInherited(new KeyedCodec<>("Count", Codec.INTEGER, false),
                            (o, v) -> o.count = v, o -> o.count, (o, p) -> o.count = p.count)
                    .metadata(EditorSchema.defaultValue(QuestItemDrop.DEFAULT_COUNT))
                    .documentation("How many one drop hands over. Unauthored means 1. Never more than the step "
                            + "still wants, less what already waits in its Claim (and, for a TURN_IN step, what "
                            + "the bag holds), so a player is never handed more than the quest asks for.").add()
                    .build();

    public QuestDropAsset() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static QuestDropAsset of(@Nullable String[] roles, @Nullable String[] encounters,
            @Nullable Double chance, @Nullable Integer count) {
        QuestDropAsset drop = new QuestDropAsset();
        drop.roles = roles == null ? null : roles.clone();
        drop.encounters = encounters == null ? null : encounters.clone();
        drop.chance = chance;
        drop.count = count;
        return drop;
    }

    /** The engine's form of this block, defaults applied. */
    @Nonnull
    public QuestItemDrop toDrop() {
        return QuestItemDrop.of(roles == null ? null : Arrays.asList(roles),
                encounters == null ? null : Arrays.asList(encounters), chance, count);
    }
}
