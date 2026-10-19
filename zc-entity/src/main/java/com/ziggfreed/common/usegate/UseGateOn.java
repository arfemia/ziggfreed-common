package com.ziggfreed.common.usegate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.match.NamePattern;

/**
 * What a use gate is about: name patterns on four axes, OR'd. A gate applies to a moment when ANY axis it
 * authors matches what that moment is about: the item in the active hand ({@code Held}, a block broken or a hit
 * dealt with it), an item going into an armor slot ({@code Worn}), the block being broken ({@code Block}) or the
 * creature being hit, by its NPC role ({@code Mob}). Each entry is the library's one name-pattern grammar
 * ({@link NamePattern}: exact, {@code Foo*}, {@code *Foo}, {@code *Foo*}), matched without regard to case; an item
 * or block id is matched along its {@code Parent} lineage, its own id first, so a pack's copy of a pickaxe answers
 * as that pickaxe.
 */
public final class UseGateOn {

    @Nullable protected String[] held;
    @Nullable protected String[] worn;
    @Nullable protected String[] block;
    @Nullable protected String[] mob;

    public static final BuilderCodec<UseGateOn> CODEC = BuilderCodec.builder(UseGateOn.class, UseGateOn::new)
            .append(new KeyedCodec<>("Held", Codec.STRING_ARRAY, false), (o, v) -> o.held = v, o -> o.held)
            .documentation("Item name patterns for the item in the active hand: breaking a block or hitting "
                    + "something while holding one of these asks this gate. Tool_Pickaxe_Iron* or an exact id.").add()
            .append(new KeyedCodec<>("Worn", Codec.STRING_ARRAY, false), (o, v) -> o.worn = v, o -> o.worn)
            .documentation("Item name patterns for armor: putting one of these into an armor slot asks this gate, "
                    + "and a refused piece stays where it was. A piece already worn is never taken off.").add()
            .append(new KeyedCodec<>("Block", Codec.STRING_ARRAY, false), (o, v) -> o.block = v, o -> o.block)
            .documentation("Block name patterns: breaking one of these blocks asks this gate. *Ore_Iron* or an "
                    + "exact block id.").add()
            .append(new KeyedCodec<>("Mob", Codec.STRING_ARRAY, false), (o, v) -> o.mob = v, o -> o.mob)
            .documentation("Creature name patterns, by NPC role id: hitting one of these asks this gate. A refused "
                    + "hit deals nothing.").add()
            .build();

    public UseGateOn() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static UseGateOn of(@Nullable String[] held, @Nullable String[] worn, @Nullable String[] block,
            @Nullable String[] mob) {
        UseGateOn on = new UseGateOn();
        on.held = held == null ? null : held.clone();
        on.worn = worn == null ? null : worn.clone();
        on.block = block == null ? null : block.clone();
        on.mob = mob == null ? null : mob.clone();
        return on;
    }

    /** The patterns authored on {@code axis}, parsed, blanks dropped; empty when none. */
    @Nonnull
    public List<NamePattern> patterns(@Nonnull UseGateAxis axis) {
        String[] raw = switch (axis) {
            case HELD -> held;
            case WORN -> worn;
            case BLOCK -> block;
            case MOB -> mob;
        };
        if (raw == null || raw.length == 0) {
            return List.of();
        }
        List<NamePattern> out = new ArrayList<>(raw.length);
        for (String pattern : raw) {
            if (pattern != null && !pattern.isBlank()) {
                out.add(NamePattern.parse(pattern.trim()));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** True when no axis authors a pattern, so the gate applies to nothing. */
    public boolean isEmpty() {
        for (UseGateAxis axis : UseGateAxis.values()) {
            if (!patterns(axis).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
