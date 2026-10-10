package com.ziggfreed.common.encounter.validate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.ziggfreed.common.encounter.signal.EncounterSignal;

/**
 * What one native encounter script SAYS, read off its authored JSON: which {@code zc:} signals it
 * fires, which target slots it names, whether its Player sensor carries the member collector,
 * whether it puts up a boss bar and over which slot, whether every {@code zc:defeated} beat sits
 * beside a {@code ClearEncounterBossBar}, and whether a blocking action list sits under a sensor
 * marked {@code Once} (which the engine spends after its first tick, stranding every action after
 * the first).
 *
 * <p>Pure: it walks a JSON tree, follows a {@code Reference} (a Variant's base, a macro's Content)
 * through a resolver the caller supplies, substitutes {@code {"Compute": "<Name>"}} reads from the
 * referenced file's own {@code Parameters} under the referencing {@code Modify}, and never touches
 * the engine. A value it cannot resolve is simply not reported, so an unusual authoring shape can
 * hide a finding but never invent one.
 *
 * <p>The engine's {@code SetEncounterBossBar} points the bar at whatever its instruction's own
 * sensor matched, so the bar's slot is that sibling {@code Sensor}'s {@code TargetSlot} or
 * {@code LockedTargetSlot}, read through an {@code And} or {@code Or} sensor's {@code Sensors} (a
 * {@code Not} matches only when its target is absent, so it names none), and through an action
 * macro the action list references.
 *
 * @param id             the script id
 * @param spawnable      whether the script is a Generic or Variant (false for an Abstract base)
 * @param signals        every {@code SignalWorldEvent} id it fires, resolved where it could be
 * @param targetSlots    every {@code TargetSlot} and {@code LockedTargetSlot} name it mentions
 * @param memberCollector whether an {@code EncounterMembers} collector is authored
 * @param clearsBossBar  whether a {@code ClearEncounterBossBar} is authored anywhere
 * @param defeatBeatsWithoutBarClear how many {@code zc:defeated} beats sit in an action list with
 *                       no {@code ClearEncounterBossBar} beside them
 * @param onceHeadedBlockingLists how many blocking action lists of two or more actions sit under a
 *                       sensor marked {@code Once}, which is spent after its first tick and strands
 *                       the rest of the list
 * @param setsBossBar    whether a {@code SetEncounterBossBar} is authored anywhere, the script's own
 *                       word that it is a boss fight
 * @param bossBarSlots   every slot a bar is put up over, where its sensor names one
 */
public record EncounterScriptScan(@Nonnull String id, boolean spawnable, @Nonnull List<String> signals,
                                  @Nonnull Set<String> targetSlots, boolean memberCollector, boolean clearsBossBar,
                                  int defeatBeatsWithoutBarClear, int onceHeadedBlockingLists, boolean setsBossBar,
                                  @Nonnull Set<String> bossBarSlots) {

    /** How deep a chain of references is followed before it is assumed to be a cycle. */
    static final int MAX_REFERENCE_DEPTH = 8;

    private static final String TYPE = "Type";
    private static final String SIGNAL_TYPE = "SignalWorldEvent";
    private static final String SIGNAL_ID = "SignalId";
    private static final String CLEAR_BAR_TYPE = "ClearEncounterBossBar";
    private static final String SET_BAR_TYPE = "SetEncounterBossBar";
    private static final String MEMBERS_TYPE = "EncounterMembers";
    private static final String TARGET_SLOT = "TargetSlot";
    private static final String LOCKED_TARGET_SLOT = "LockedTargetSlot";
    private static final String SENSORS = "Sensors";
    private static final String NOT_TYPE = "Not";
    private static final String REFERENCE = "Reference";
    private static final String MODIFY = "Modify";
    private static final String COMPUTE = "Compute";
    private static final String PARAMETERS = "Parameters";
    private static final String CONTENT = "Content";
    private static final String VALUE = "Value";
    private static final String ACTIONS = "Actions";
    private static final String ACTIONS_BLOCKING = "ActionsBlocking";
    private static final String SENSOR = "Sensor";
    private static final String ONCE = "Once";

    /**
     * The scan as read before it knew boss bars: no bar, no bar slot. Kept so a caller built against
     * the eight-field record still links.
     */
    public EncounterScriptScan(@Nonnull String id, boolean spawnable, @Nonnull List<String> signals,
            @Nonnull Set<String> targetSlots, boolean memberCollector, boolean clearsBossBar,
            int defeatBeatsWithoutBarClear, int onceHeadedBlockingLists) {
        this(id, spawnable, signals, targetSlots, memberCollector, clearsBossBar, defeatBeatsWithoutBarClear,
                onceHeadedBlockingLists, false, Set.of());
    }

    /** True when the script fires any {@code zc:} signal at all. */
    public boolean firesFrameworkSignals() {
        for (String signal : signals) {
            if (EncounterSignal.isFrameworkSignal(signal)) {
                return true;
            }
        }
        return false;
    }

    /** True when the script authors the given reserved moment. */
    public boolean authors(@Nonnull EncounterSignal.Moment moment) {
        for (String signal : signals) {
            EncounterSignal parsed = EncounterSignal.parse(signal);
            if (parsed != null && parsed.moment() == moment) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scan {@code root}, the script's whole file.
     *
     * @param referenced resolves a referenced builder name to its file's root object, or null
     */
    @Nonnull
    public static EncounterScriptScan scan(@Nonnull String id, boolean spawnable, @Nonnull JsonObject root,
            @Nonnull Function<String, JsonObject> referenced) {
        Walker walker = new Walker(referenced);
        walker.walkRoot(root, Map.of(), 0);
        return new EncounterScriptScan(id, spawnable, Collections.unmodifiableList(walker.signals),
                Collections.unmodifiableSet(walker.targetSlots), walker.memberCollector, walker.clearsBossBar,
                walker.defeatWithoutClear, walker.onceHeadedBlocking, walker.setsBossBar,
                Collections.unmodifiableSet(walker.bossBarSlots));
    }

    /** The recursive walk with its findings. */
    private static final class Walker {

        private final Function<String, JsonObject> referenced;
        final List<String> signals = new ArrayList<>();
        final Set<String> targetSlots = new LinkedHashSet<>();
        final Set<String> bossBarSlots = new LinkedHashSet<>();
        boolean memberCollector;
        boolean clearsBossBar;
        boolean setsBossBar;
        int defeatWithoutClear;
        int onceHeadedBlocking;

        Walker(@Nonnull Function<String, JsonObject> referenced) {
            this.referenced = referenced;
        }

        /** A file root: its own Parameters seed the scope, then its body is walked. */
        void walkRoot(@Nonnull JsonObject root, @Nonnull Map<String, JsonElement> outer, int depth) {
            Map<String, JsonElement> scope = new HashMap<>(defaults(root));
            scope.putAll(outer);
            JsonElement content = root.get(CONTENT);
            if (content != null && content.isJsonObject()) {
                walk(content.getAsJsonObject(), scope, depth);
                return;
            }
            walk(root, scope, depth);
        }

        void walk(@Nonnull JsonElement element, @Nonnull Map<String, JsonElement> scope, int depth) {
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) {
                    walk(child, scope, depth);
                }
                return;
            }
            if (!element.isJsonObject()) {
                return;
            }
            JsonObject object = element.getAsJsonObject();
            String type = string(object.get(TYPE), scope);
            if (SIGNAL_TYPE.equals(type)) {
                String signalId = string(object.get(SIGNAL_ID), scope);
                if (signalId != null) {
                    signals.add(signalId);
                }
            } else if (CLEAR_BAR_TYPE.equals(type)) {
                clearsBossBar = true;
            } else if (SET_BAR_TYPE.equals(type)) {
                setsBossBar = true;
            } else if (MEMBERS_TYPE.equals(type)) {
                memberCollector = true;
            }
            addSlot(targetSlots, object.get(TARGET_SLOT), scope);
            addSlot(targetSlots, object.get(LOCKED_TARGET_SLOT), scope);
            JsonElement actions = object.get(ACTIONS);
            if (actions != null && actions.isJsonArray()) {
                checkDefeatBeat(actions.getAsJsonArray(), scope);
                if (setsBar(actions.getAsJsonArray(), scope)) {
                    sensorSlots(object.get(SENSOR), scope);
                }
                if (actions.getAsJsonArray().size() > 1 && isTrue(object.get(ACTIONS_BLOCKING), scope)
                        && sensorIsOnce(object.get(SENSOR), scope)) {
                    onceHeadedBlocking++;
                }
            }
            JsonElement reference = object.get(REFERENCE);
            if (reference != null) {
                follow(reference, object.get(MODIFY), scope, depth);
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (REFERENCE.equals(entry.getKey()) || MODIFY.equals(entry.getKey()) || PARAMETERS.equals(entry.getKey())) {
                    continue;
                }
                walk(entry.getValue(), scope, depth);
            }
        }

        /** A {@code zc:defeated} beat is counted against its own action list when no bar clear sits in it. */
        private void checkDefeatBeat(@Nonnull JsonArray actions, @Nonnull Map<String, JsonElement> scope) {
            boolean defeated = false;
            boolean cleared = false;
            for (JsonElement action : actions) {
                if (!action.isJsonObject()) {
                    continue;
                }
                JsonObject object = action.getAsJsonObject();
                String type = string(object.get(TYPE), scope);
                if (SIGNAL_TYPE.equals(type)) {
                    EncounterSignal signal = EncounterSignal.parse(string(object.get(SIGNAL_ID), scope));
                    defeated |= signal != null && signal.moment() == EncounterSignal.Moment.DEFEATED;
                } else if (CLEAR_BAR_TYPE.equals(type)) {
                    cleared = true;
                } else if (object.has(REFERENCE)) {
                    // A macro carrying the whole beat: judged on its own action lists when followed.
                    JsonObject target = resolve(object.get(REFERENCE), scope);
                    cleared |= target != null && mentions(target, CLEAR_BAR_TYPE);
                }
            }
            if (defeated && !cleared) {
                defeatWithoutClear++;
            }
        }

        /** Whether an action list puts up a boss bar: the action itself, or an action macro carrying it. */
        private boolean setsBar(@Nonnull JsonArray actions, @Nonnull Map<String, JsonElement> scope) {
            for (JsonElement action : actions) {
                if (!action.isJsonObject()) {
                    continue;
                }
                JsonObject object = action.getAsJsonObject();
                if (SET_BAR_TYPE.equals(string(object.get(TYPE), scope))) {
                    return true;
                }
                if (object.has(REFERENCE)) {
                    JsonObject target = resolve(object.get(REFERENCE), scope);
                    if (target != null && mentions(target, SET_BAR_TYPE)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * The slots a bar's sibling sensor matches its target in: its own {@code TargetSlot} or
         * {@code LockedTargetSlot}, and each of an {@code And} or {@code Or} sensor's {@code Sensors}.
         * A {@code Not} matches only while its target is absent, so the bar has nothing of it to track.
         */
        private void sensorSlots(@Nullable JsonElement sensor, @Nonnull Map<String, JsonElement> scope) {
            if (sensor == null || !sensor.isJsonObject()) {
                return;
            }
            JsonObject object = sensor.getAsJsonObject();
            if (NOT_TYPE.equals(string(object.get(TYPE), scope))) {
                return;
            }
            addSlot(bossBarSlots, object.get(TARGET_SLOT), scope);
            addSlot(bossBarSlots, object.get(LOCKED_TARGET_SLOT), scope);
            JsonElement children = object.get(SENSORS);
            if (children != null && children.isJsonArray()) {
                for (JsonElement child : children.getAsJsonArray()) {
                    sensorSlots(child, scope);
                }
            }
        }

        /** Add the slot name {@code value} reads as, through a Compute read, when it reads as one. */
        private static void addSlot(@Nonnull Set<String> into, @Nullable JsonElement value,
                @Nonnull Map<String, JsonElement> scope) {
            String slot = string(value, scope);
            if (slot != null && !slot.isBlank()) {
                into.add(slot);
            }
        }

        private void follow(@Nonnull JsonElement reference, @Nullable JsonElement modify,
                @Nonnull Map<String, JsonElement> scope, int depth) {
            if (depth >= MAX_REFERENCE_DEPTH) {
                return;
            }
            JsonObject target = resolve(reference, scope);
            if (target == null) {
                return;
            }
            Map<String, JsonElement> inner = new HashMap<>(defaults(target));
            if (modify != null && modify.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : modify.getAsJsonObject().entrySet()) {
                    if (entry.getKey().startsWith("_")) {
                        continue;
                    }
                    inner.put(entry.getKey(), resolveValue(entry.getValue(), scope));
                }
            }
            walkRoot(target, inner, depth + 1);
        }

        @Nullable
        private JsonObject resolve(@Nonnull JsonElement reference, @Nonnull Map<String, JsonElement> scope) {
            String name = string(reference, scope);
            if (name == null || name.isBlank() || "$Null".equals(name)) {
                return null;
            }
            try {
                return referenced.apply(name);
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** {@code Parameters: {Name: {Value, Description}}} as {@code Name -> Value}. */
        @Nonnull
        private static Map<String, JsonElement> defaults(@Nonnull JsonObject root) {
            Map<String, JsonElement> out = new HashMap<>();
            JsonElement parameters = root.get(PARAMETERS);
            if (parameters == null || !parameters.isJsonObject()) {
                return out;
            }
            for (Map.Entry<String, JsonElement> entry : parameters.getAsJsonObject().entrySet()) {
                if (entry.getKey().startsWith("_")) {
                    continue;
                }
                JsonElement value = entry.getValue();
                if (value != null && value.isJsonObject() && value.getAsJsonObject().has(VALUE)) {
                    out.put(entry.getKey(), value.getAsJsonObject().get(VALUE));
                } else if (value != null) {
                    out.put(entry.getKey(), value);
                }
            }
            return out;
        }

        /** A value with any {@code {"Compute": name}} read resolved through {@code scope}. */
        @Nullable
        private static JsonElement resolveValue(@Nullable JsonElement value, @Nonnull Map<String, JsonElement> scope) {
            if (value != null && value.isJsonObject() && value.getAsJsonObject().has(COMPUTE)) {
                JsonElement name = value.getAsJsonObject().get(COMPUTE);
                return name != null && name.isJsonPrimitive() ? scope.get(name.getAsString()) : null;
            }
            return value;
        }

        /** The string {@code value} reads as, through a Compute read where there is one, else null. */
        @Nullable
        private static String string(@Nullable JsonElement value, @Nonnull Map<String, JsonElement> scope) {
            JsonElement resolved = resolveValue(value, scope);
            if (resolved == null || !resolved.isJsonPrimitive()) {
                return null;
            }
            return resolved.getAsJsonPrimitive().isString() ? resolved.getAsString() : null;
        }

        /** True when {@code value} reads as the boolean {@code true}, through a Compute read where there is one. */
        private static boolean isTrue(@Nullable JsonElement value, @Nonnull Map<String, JsonElement> scope) {
            JsonElement resolved = resolveValue(value, scope);
            return resolved != null && resolved.isJsonPrimitive() && resolved.getAsJsonPrimitive().isBoolean()
                    && resolved.getAsBoolean();
        }

        /** True when {@code sensor} is a sensor object marked {@code Once}. */
        private static boolean sensorIsOnce(@Nullable JsonElement sensor, @Nonnull Map<String, JsonElement> scope) {
            return sensor != null && sensor.isJsonObject() && isTrue(sensor.getAsJsonObject().get(ONCE), scope);
        }

        private static boolean mentions(@Nonnull JsonElement element, @Nonnull String type) {
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) {
                    if (mentions(child, type)) {
                        return true;
                    }
                }
                return false;
            }
            if (!element.isJsonObject()) {
                return false;
            }
            JsonObject object = element.getAsJsonObject();
            JsonElement t = object.get(TYPE);
            if (t != null && t.isJsonPrimitive() && type.equals(t.getAsString())) {
                return true;
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (mentions(entry.getValue(), type)) {
                    return true;
                }
            }
            return false;
        }
    }
}
