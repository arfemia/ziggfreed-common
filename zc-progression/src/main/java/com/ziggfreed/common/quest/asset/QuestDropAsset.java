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
 * "bring_horns": { "Kind": "TURN_IN", "Target": "yourmod_Ogre_Horn", "Amount": 2,
 *                  "Drop": { "Roles": ["Ogre_Brute"], "Delivery": "Ground" } }
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
    @Nullable protected QuestItemDrop.Delivery delivery;

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
                    .appendInherited(new KeyedCodec<>("Delivery", Codec.STRING, false),
                            (o, v) -> o.delivery = delivery(v), o -> o.delivery == null ? null : o.delivery.word(),
                            (o, p) -> o.delivery = p.delivery)
                    .metadata(EditorSchema.oneOfDocumented(
                            QuestItemDrop.Delivery.SPOILS.word(), "Waits in the step's Claim, paid for the fight: "
                                    + "a won boss fight's spoils chest offers it. The default for Encounters.",
                            QuestItemDrop.Delivery.BAG_THEN_CLAIM.word(), "Into the bag like a pickup; what the bag "
                                    + "cannot take waits in the step's Claim. The default for Roles.",
                            QuestItemDrop.Delivery.BAG.word(), "Into the bag like a pickup; with no room it is not "
                                    + "handed over, and the player is told.",
                            QuestItemDrop.Delivery.GROUND.word(), "Spilled on the ground where the creature or the "
                                    + "boss fell, one stack for each player owed it, which anyone nearby can pick up."))
                    .documentation("How the item reaches a player owed it: Spoils, BagThenClaim, Bag or Ground. "
                            + "Unauthored reads Spoils for a boss fight's win (Encounters) and BagThenClaim for a "
                            + "kill (Roles). Spoils waits in the step's Claim, paid for the fight, so the spoils "
                            + "chest a won fight leaves offers it, and under Claims where no chest stands. "
                            + "BagThenClaim goes into the bag like a pickup and what the bag cannot take waits in "
                            + "the step's Claim. Bag goes into the bag and, with no room, is not handed over (the "
                            + "player is told, and a later drop tries again). Ground spills one stack for each "
                            + "player owed it where the creature or the boss fell, owned by nobody, so anyone "
                            + "nearby can pick it up; with no place to spill it, it waits in the step's Claim. A "
                            + "step has one Claim, which a later drop adds to. Any other word fails the read.").add()
                    .build();

    public QuestDropAsset() {
    }

    /** Java-side factory, its delivery the source's default; sets the same fields the codec fills. */
    @Nonnull
    public static QuestDropAsset of(@Nullable String[] roles, @Nullable String[] encounters,
            @Nullable Double chance, @Nullable Integer count) {
        return of(roles, encounters, chance, count, null);
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static QuestDropAsset of(@Nullable String[] roles, @Nullable String[] encounters,
            @Nullable Double chance, @Nullable Integer count, @Nullable QuestItemDrop.Delivery delivery) {
        QuestDropAsset drop = new QuestDropAsset();
        drop.roles = roles == null ? null : roles.clone();
        drop.encounters = encounters == null ? null : encounters.clone();
        drop.chance = chance;
        drop.count = count;
        drop.delivery = delivery;
        return drop;
    }

    /** The engine's form of this block, defaults applied. */
    @Nonnull
    public QuestItemDrop toDrop() {
        return QuestItemDrop.of(roles == null ? null : Arrays.asList(roles),
                encounters == null ? null : Arrays.asList(encounters), chance, count, delivery);
    }

    /** The word as a {@link QuestItemDrop.Delivery}; null stays null (unauthored), any other word fails the read. */
    @Nullable
    static QuestItemDrop.Delivery delivery(@Nullable String word) {
        if (word == null) {
            return null;
        }
        QuestItemDrop.Delivery delivery = QuestItemDrop.Delivery.byWord(word);
        if (delivery == null) {
            throw new IllegalArgumentException("Delivery must be one of " + QuestItemDrop.Delivery.SPOILS.word()
                    + " / " + QuestItemDrop.Delivery.BAG_THEN_CLAIM.word() + " / " + QuestItemDrop.Delivery.BAG.word()
                    + " / " + QuestItemDrop.Delivery.GROUND.word() + ", not '" + word + "'");
        }
        return delivery;
    }
}
