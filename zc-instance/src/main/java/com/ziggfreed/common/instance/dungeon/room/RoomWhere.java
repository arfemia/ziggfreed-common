package com.ziggfreed.common.instance.dungeon.room;

import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;
import org.bson.BsonValue;

import com.hypixel.hytale.codec.CollectingExtraInfo;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateRuntime;
import com.ziggfreed.common.progress.gate.GateSpec;

/**
 * Room logic placed only on a server where a world-level {@code Requires} holds: a volume, group or point tagged
 * {@code ZigWhere: "<a Requires block, written as JSON>"} is placed in a generated run only when that block passes
 * asked of nobody ({@link GateQuery#NOBODY}), so it reads what is true of the whole server, chiefly whether a mod runs
 * here ({@code hytale:mod_installed} with {@code Min: 1} for installed, {@code Max: 0} for not). A reading about a
 * player has nobody to read and fails, so a piece gated on one is never placed.
 *
 * <p>So one room can hold one piece per kind of server, each saying its own line: a skill lock's volume asking for a
 * skill level where the mod that keeps levels runs, and its twin asking for the right tool where it does not, each
 * refusing with its own words, and each server placing only its own (the room tools' {@code SkillLock} template
 * stamps such a pair from its {@code Branches}).
 *
 * <p>A blank value, or no tag, is always placed. A value that does not read as a {@code Requires} block keeps its
 * piece out, since a gate that cannot be read stays shut ({@link RunLogic} warns once; the boot audit reports it,
 * {@link RoomLogicAudit#LOGIC_WHERE_UNREADABLE}). The tag is read from a trigger volume's, a volume group's and a
 * point's {@code Tags}, as {@link RoomVariants} reads its own; an encounter or a marker holder carries none. Keep it
 * off a piece that carries {@code ZigVariant}: a variant group counts every piece of the run before this filter, so
 * every server picks alike, and a server that drops the group's highest variant could pick one it has no piece for.
 * Only a generated run's room logic reads it: a prefab the engine pastes whole places every piece, each branch's
 * volume saying its own line when used.
 */
public final class RoomWhere {

    /** The tag key the block is written under. */
    public static final String KEY = "ZigWhere";

    private RoomWhere() {
    }

    /**
     * The block a holder's encoded JSON names in its {@code Tags} ({@link #KEY}, matched without regard to case; a
     * value written as a list reads its first entry, as the engine reads it), trimmed; null for none or a blank one.
     */
    @Nullable
    public static String of(@Nonnull BsonDocument holder) {
        BsonDocument tags = RoomTurns.doc(holder, "Tags");
        if (tags == null) {
            return null;
        }
        for (Map.Entry<String, BsonValue> tag : tags.entrySet()) {
            if (!KEY.equalsIgnoreCase(tag.getKey().trim())) {
                continue;
            }
            BsonValue value = tag.getValue();
            if (value.isArray() && !value.asArray().isEmpty()) {
                value = value.asArray().get(0);
            }
            String text = value.isString() ? value.asString().getValue().trim() : null;
            return text == null || text.isEmpty() ? null : text;
        }
        return null;
    }

    /**
     * {@code where} read as a {@code Requires} block through the shared codec; null when it does not read as one: not
     * a JSON object, or naming a key the block has no leaf for (a misspelt {@code Factors} would otherwise read as an
     * empty block, which passes everywhere).
     */
    @Nullable
    public static GateSpec parse(@Nullable String where) {
        if (where == null || where.isBlank()) {
            return null;
        }
        try {
            CollectingExtraInfo info = new CollectingExtraInfo();
            GateSpec spec = GateSpec.CODEC.decode(BsonDocument.parse(where), info);
            return info.getUnknownKeys().isEmpty() ? spec : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Whether logic tagged {@code where} is placed, {@code holds} answering a block that reads: untagged
     * ({@code null}) always, an unreadable block never.
     */
    public static boolean keeps(@Nullable String where, @Nonnull Predicate<GateSpec> holds) {
        if (where == null) {
            return true;
        }
        GateSpec spec = parse(where);
        return spec != null && holds.test(spec);
    }

    /** Whether {@code spec} holds on this server, asked of nobody through the server's one gate evaluator. */
    public static boolean holdsHere(@Nonnull GateSpec spec) {
        return GateRuntime.evaluator().check(spec, GateQuery.NOBODY).passed();
    }
}
