package com.ziggfreed.common.quest.asset;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.ziggfreed.common.validation.Finding;

/**
 * Turns a {@link QuestGeneratorAsset} into the quest bodies it describes: walk the axes, take every
 * combination, and write the child body once per combination with its tokens filled in.
 *
 * <p>It MERGES NOTHING. Each body comes out as ordinary quest JSON carrying {@code Parent}, and the
 * inheritance that follows is the same one a hand-written child gets, which is what makes a
 * generated quest indistinguishable from an authored one.
 *
 * <p>Every problem it can see is returned as an {@link Finding} beside the bodies rather than thrown,
 * so one bad axis reports itself instead of taking a content load down. The same call backs both
 * the live pool and the validator, so what an owner is told is what actually happened.
 */
public final class QuestGeneratorExpander {

    /** The placeholder spelling: a name in braces, letters, digits, and the usual id punctuation. */
    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z0-9_.\\-]+)}");

    /** What one generator produced, and everything worth reporting about it. */
    public record Expansion(@Nonnull List<GeneratedQuestBody> bodies, @Nonnull List<Finding> issues) {

        /** Nothing produced, nothing to report. */
        public static final Expansion EMPTY = new Expansion(List.of(), List.of());

        public Expansion {
            bodies = List.copyOf(bodies);
            issues = List.copyOf(issues);
        }
    }

    private QuestGeneratorExpander() {
    }

    /**
     * Expand {@code generator}.
     *
     * @param enumerators the registered value sources an axis may name; null means none are
     * @return the bodies to inject into the quest pool, plus any findings
     */
    @Nonnull
    public static Expansion expand(@Nonnull QuestGeneratorAsset generator,
            @Nullable QuestEnumeratorRegistry enumerators) {
        String sourceId = generator.getId() == null ? "" : generator.getId();
        List<Finding> issues = new ArrayList<>();

        if (!generator.isEnabled()) {
            return Expansion.EMPTY;
        }

        String baseId = generator.getBase();
        if (baseId == null) {
            issues.add(Finding.error(QuestPoolValidator.DOMAIN, "MISSING_BASE",
                    "no Base is authored, so there is no quest for the children to inherit from and nothing "
                            + "is written", sourceId));
            return new Expansion(List.of(), issues);
        }
        JsonObject child = generator.getChild();
        if (child == null) {
            issues.add(Finding.error(QuestPoolValidator.DOMAIN, "MISSING_CHILD",
                    "no Child body is authored, so there is nothing to write once per combination", sourceId));
            return new Expansion(List.of(), issues);
        }
        String idPattern = generator.getIdPattern();
        if (idPattern == null || idPattern.isBlank()) {
            issues.add(Finding.error(QuestPoolValidator.DOMAIN, "MISSING_ID_PATTERN",
                    "no IdPattern is authored, so the children have no ids to be told apart by", sourceId));
            return new Expansion(List.of(), issues);
        }

        List<Map<String, JsonPrimitive>> combinations = combinations(generator, enumerators, sourceId, issues);
        if (combinations.isEmpty()) {
            return new Expansion(List.of(), issues);
        }

        Set<String> boundTokens = new LinkedHashSet<>();
        for (Map<String, JsonPrimitive> combination : combinations) {
            boundTokens.addAll(combination.keySet());
        }
        Set<String> usedTokens = new LinkedHashSet<>();
        collectTokens(child, usedTokens);
        collectTokens(idPattern, usedTokens);
        for (String token : boundTokens) {
            if (!usedTokens.contains(token)) {
                issues.add(Finding.warning(QuestPoolValidator.DOMAIN, "UNUSED_TOKEN",
                        "the axes bind '{" + token + "}' but neither Child nor IdPattern ever uses it, so it "
                                + "changes nothing and may only be multiplying the count", sourceId));
            }
        }

        List<GeneratedQuestBody> bodies = new ArrayList<>(combinations.size());
        Set<String> seen = new LinkedHashSet<>();
        String owner = generator.getOwner();
        for (Map<String, JsonPrimitive> bindings : combinations) {
            String id = substituteString(idPattern, bindings).trim().toLowerCase(Locale.ROOT);
            JsonObject body = (JsonObject) substitute(child, bindings);

            Set<String> unresolved = new LinkedHashSet<>();
            collectTokens(id, unresolved);
            collectTokens(body, unresolved);
            if (!unresolved.isEmpty()) {
                issues.add(Finding.error(QuestPoolValidator.DOMAIN, "UNRESOLVED_TOKEN",
                        "'" + id + "' still contains " + braced(unresolved) + " after substitution; no axis "
                                + "binds that name, so the quest is written wrong and is skipped", sourceId));
                continue;
            }
            if (!seen.add(id)) {
                issues.add(Finding.error(QuestPoolValidator.DOMAIN, "DUPLICATE_ID",
                        "two combinations both produce the id '" + id + "', so only one of them can exist; add "
                                + "the missing token to IdPattern", sourceId));
                continue;
            }

            JsonObject withParent = new JsonObject();
            withParent.addProperty("Parent", baseId);
            for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
                if (!"Parent".equals(entry.getKey())) {
                    withParent.add(entry.getKey(), entry.getValue());
                }
            }
            bodies.add(new GeneratedQuestBody(id, withParent, baseId, sourceId, owner));
        }
        return new Expansion(bodies, issues);
    }

    // ==================== the walk ====================

    /** Every combination of every axis, in authored order (the first axis varies slowest). */
    @Nonnull
    private static List<Map<String, JsonPrimitive>> combinations(@Nonnull QuestGeneratorAsset generator,
            @Nullable QuestEnumeratorRegistry enumerators, @Nonnull String sourceId,
            @Nonnull List<Finding> issues) {

        QuestGeneratorAsset.Axis[] axes = generator.axesOrEmpty();
        if (axes.length == 0) {
            issues.add(Finding.warning(QuestPoolValidator.DOMAIN, "NO_AXES",
                    "no ForEach axes are authored, so there is nothing to vary and nothing is written; author "
                            + "the quest as its own file instead", sourceId));
            return List.of();
        }

        List<Map<String, JsonPrimitive>> combinations = new ArrayList<>();
        combinations.add(Map.of());
        for (QuestGeneratorAsset.Axis axis : axes) {
            List<Map<String, JsonPrimitive>> rows = rowsOf(axis, enumerators, sourceId, issues);
            if (rows.isEmpty()) {
                return List.of();
            }
            List<Map<String, JsonPrimitive>> next = new ArrayList<>(combinations.size() * rows.size());
            for (Map<String, JsonPrimitive> carried : combinations) {
                for (Map<String, JsonPrimitive> row : rows) {
                    Map<String, JsonPrimitive> merged = new LinkedHashMap<>(carried);
                    merged.putAll(row);
                    next.add(merged);
                }
            }
            combinations = next;
        }
        return combinations;
    }

    /** The bindings one axis contributes, from its authored values or from a registered source. */
    @Nonnull
    private static List<Map<String, JsonPrimitive>> rowsOf(@Nullable QuestGeneratorAsset.Axis axis,
            @Nullable QuestEnumeratorRegistry enumerators, @Nonnull String sourceId,
            @Nonnull List<Finding> issues) {

        if (axis == null) {
            return List.of();
        }
        String token = axis.getToken();
        List<Map<String, JsonPrimitive>> rows = new ArrayList<>();

        JsonElement values = axis.getValues();
        if (values != null && values.isJsonArray()) {
            for (JsonElement entry : values.getAsJsonArray()) {
                Map<String, JsonPrimitive> binding = bindingOf(entry, token);
                if (!binding.isEmpty()) {
                    rows.add(binding);
                }
            }
        } else if (axis.getSource() != null) {
            String source = axis.getSource();
            QuestValueEnumerator enumerator = enumerators == null ? null : enumerators.enumerator(source);
            if (enumerator == null) {
                issues.add(Finding.warning(QuestPoolValidator.DOMAIN, "UNKNOWN_SOURCE",
                        "axis '" + describe(axis) + "' reads the source '" + source + "', which nothing "
                                + "registered; it produces no values, so this generator writes nothing until "
                                + "whichever mod owns it is installed", sourceId));
                return List.of();
            }
            try {
                for (QuestAxisRow row : enumerator.rows(axis.filterOrEmpty())) {
                    if (row == null || row.isEmpty()) {
                        continue;
                    }
                    Map<String, JsonPrimitive> binding = row.bind(token);
                    if (!binding.isEmpty()) {
                        rows.add(binding);
                    }
                }
            } catch (Exception e) {
                if (enumerators != null) {
                    enumerators.recordFailure(source, e.getMessage());
                }
                issues.add(Finding.error(QuestPoolValidator.DOMAIN, "SOURCE_FAILED",
                        "the source '" + source + "' threw while listing its values, so this generator writes "
                                + "nothing: " + e.getMessage(), sourceId));
                return List.of();
            }
        }

        if (rows.isEmpty()) {
            issues.add(Finding.warning(QuestPoolValidator.DOMAIN, "EMPTY_AXIS",
                    "axis '" + describe(axis) + "' has no values, so no combination exists and this generator "
                            + "writes nothing", sourceId));
        }
        return rows;
    }

    /** One authored {@code Values} entry: a plain value fills the axis token, an object binds names. */
    @Nonnull
    private static Map<String, JsonPrimitive> bindingOf(@Nullable JsonElement entry, @Nullable String token) {
        Map<String, JsonPrimitive> out = new LinkedHashMap<>();
        if (entry == null || entry.isJsonNull()) {
            return out;
        }
        if (entry.isJsonPrimitive()) {
            if (token != null) {
                out.put(token, entry.getAsJsonPrimitive());
            }
            return out;
        }
        if (entry.isJsonObject()) {
            for (Map.Entry<String, JsonElement> field : entry.getAsJsonObject().entrySet()) {
                if (field.getValue() != null && field.getValue().isJsonPrimitive()) {
                    out.put(field.getKey().trim(), field.getValue().getAsJsonPrimitive());
                }
            }
        }
        return out;
    }

    // ==================== substitution ====================

    /**
     * A copy of {@code node} with every token filled in: inside every string, inside every object
     * key, and - where a value is EXACTLY one token - as that token's own type, so a number stays a
     * number.
     */
    @Nonnull
    static JsonElement substitute(@Nonnull JsonElement node, @Nonnull Map<String, JsonPrimitive> bindings) {
        if (node.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
                out.add(substituteString(entry.getKey(), bindings), substitute(entry.getValue(), bindings));
            }
            return out;
        }
        if (node.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement entry : node.getAsJsonArray()) {
                out.add(substitute(entry, bindings));
            }
            return out;
        }
        if (node.isJsonPrimitive() && node.getAsJsonPrimitive().isString()) {
            String text = node.getAsString();
            JsonPrimitive whole = wholeTokenValue(text, bindings);
            return whole != null ? whole : new JsonPrimitive(substituteString(text, bindings));
        }
        return node;
    }

    /** The bound value when {@code text} is exactly one token, else null. */
    @Nullable
    private static JsonPrimitive wholeTokenValue(@Nonnull String text,
            @Nonnull Map<String, JsonPrimitive> bindings) {
        Matcher matcher = TOKEN.matcher(text);
        if (!matcher.matches()) {
            return null;
        }
        return bindings.get(matcher.group(1));
    }

    /** {@code text} with every bound token spliced in as text; unbound tokens are left alone. */
    @Nonnull
    static String substituteString(@Nonnull String text, @Nonnull Map<String, JsonPrimitive> bindings) {
        if (text.indexOf('{') < 0) {
            return text;
        }
        Matcher matcher = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            JsonPrimitive bound = bindings.get(matcher.group(1));
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(bound == null ? matcher.group() : bound.getAsString()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // ==================== token scanning ====================

    /** Add every {@code {token}} name found anywhere in {@code node} to {@code out}. */
    private static void collectTokens(@Nonnull JsonElement node, @Nonnull Set<String> out) {
        if (node.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
                collectTokens(entry.getKey(), out);
                collectTokens(entry.getValue(), out);
            }
            return;
        }
        if (node.isJsonArray()) {
            for (JsonElement entry : node.getAsJsonArray()) {
                collectTokens(entry, out);
            }
            return;
        }
        if (node.isJsonPrimitive() && node.getAsJsonPrimitive().isString()) {
            collectTokens(node.getAsString(), out);
        }
    }

    /** Add every {@code {token}} name found in {@code text} to {@code out}. */
    private static void collectTokens(@Nonnull String text, @Nonnull Set<String> out) {
        if (text.indexOf('{') < 0) {
            return;
        }
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            out.add(matcher.group(1));
        }
    }

    @Nonnull
    private static String braced(@Nonnull Set<String> tokens) {
        List<String> spelled = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            spelled.add("{" + token + "}");
        }
        return String.join(", ", spelled);
    }

    @Nonnull
    private static String describe(@Nonnull QuestGeneratorAsset.Axis axis) {
        String token = axis.getToken();
        if (token != null) {
            return token;
        }
        String source = axis.getSource();
        return source != null ? source : "(unnamed)";
    }
}
