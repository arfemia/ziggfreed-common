package com.ziggfreed.common.progress.asset;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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
 * Turns a {@link GeneratorSpec} into the bodies it describes: walk the axes, take every
 * combination, and write the child body once per combination with its tokens filled in.
 *
 * <p><b>ONE substitution contract for every content type that writes a family from one file.</b>
 * Two copies of this walk would mean an author learning that a quest generator and an offer
 * generator disagree about, say, whether an object KEY is substituted - which is exactly the kind of
 * difference nobody discovers until a file silently writes the wrong thing.
 *
 * <p>Substitution rules, in full: every string value, every object KEY, {@code IdPattern} and {@code Base} (so
 * each combination may sit under a parent of its own, its body's {@link GeneratedBody#baseId}); a
 * value that is EXACTLY one token keeps that token's own type (so {@code "Amount": "{amount}"} lands
 * as a number); a token nothing binds is an ERROR finding and that one entry is skipped rather than
 * shipped half-written.
 *
 * <p><b>The second pass.</b> A bound value that itself holds tokens is filled from the same combination
 * before it lands, to any depth, under the same rules: a step row binding {@code "Amount": "{First}"}
 * walked against a member row binding {@code "First": 5} writes {@code 5}, a number, wherever the body
 * says {@code "{Amount}"}. A value naming a token nothing binds lands with that token unfilled (the
 * unbound-token error above), and tokens filling each other in a loop are an ERROR finding of their own
 * ({@code TOKEN_CYCLE}) that skips the entry. A bound value holding no token lands as it always did.
 *
 * <p><b>The rows source.</b> An axis naming {@link GeneratorRows#SOURCE} with {@code "Filter": { "Set": ... }}
 * walks that set's {@link GeneratorRowAsset}s, answered here before any source a store hands in, so every
 * store has it with nothing registered. Their tokens are shared by every generator walking the set, so one
 * a generator leaves unused is never reported as unused.
 *
 * <p>It MERGES NOTHING. Each body comes out as ordinary content JSON carrying {@code Parent}, and
 * the inheritance that follows is the same one a hand-written child gets, which is what makes a
 * generated entry indistinguishable from an authored one.
 *
 * <p>Every problem it can see is returned as a {@link Finding} beside the bodies rather than thrown,
 * so one bad axis reports itself instead of taking a content load down. The same call backs both the
 * live catalogue and the validator, so what an owner is told is what actually happened.
 */
public final class GeneratorCore {

    /** The placeholder spelling: a name in braces, letters, digits, and the usual id punctuation. */
    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z0-9_.\\-]+)}");

    /** What one generator produced, and everything worth reporting about it. */
    public record Expansion(@Nonnull List<GeneratedBody> bodies, @Nonnull List<Finding> issues) {

        /** Nothing produced, nothing to report. */
        public static final Expansion EMPTY = new Expansion(List.of(), List.of());

        public Expansion {
            bodies = List.copyOf(bodies);
            issues = List.copyOf(issues);
        }
    }

    /**
     * Where an axis's values come from when it names a {@code Source} instead of listing them.
     *
     * <p>It is a SEAM rather than a registry of its own so the open value-source vocabulary a
     * consumer registers - "every ore this mod ships", "every region it defines" - is registered ONCE
     * and serves every store that walks axes. A store adapts whatever registry it was handed into
     * this shape; nothing here owns a table.
     */
    public interface AxisValueSource {

        /** Is {@code sourceId} answered by anything? A no is a warning, never a load failure. */
        boolean isRegistered(@Nullable String sourceId);

        /**
         * The bindings {@code sourceId} answers with, one map per row, already bound to token names
         * ({@code token} is the axis's own token, for a source whose rows are bare values).
         *
         * <p>Answering an empty list is legitimate - that axis produces nothing, so the generator
         * writes nothing - and is reported rather than treated as an error. A throw is caught,
         * attributed through {@link #recordFailure}, and reported.
         */
        @Nonnull
        List<Map<String, JsonPrimitive>> rows(@Nonnull String sourceId, @Nullable String token,
                @Nonnull Map<String, String> filter) throws Exception;

        /** Count a source's failure against its owner, so a persistently broken one is visible. */
        default void recordFailure(@Nullable String sourceId, @Nullable String message) {
        }
    }

    private GeneratorCore() {
    }

    /**
     * Expand {@code generator}.
     *
     * @param domain which content family the findings belong to ({@code "quest"}, {@code "shop"})
     * @param noun   what one produced entry is CALLED in a message written for the author
     *               ({@code "quest"}, {@code "offer"}), so a finding reads in the author's own terms
     * @param values where an axis naming a {@code Source} gets its rows; null means none are
     *               registered
     */
    @Nonnull
    public static Expansion expand(@Nonnull GeneratorSpec generator, @Nonnull String domain,
            @Nonnull String noun, @Nullable AxisValueSource values) {

        String sourceId = generator.generatorId();
        List<Finding> issues = new ArrayList<>();

        if (!generator.isEnabled()) {
            return Expansion.EMPTY;
        }

        String baseId = generator.getBase();
        if (baseId == null) {
            issues.add(Finding.error(domain, "MISSING_BASE",
                    "no Base is authored, so there is no " + noun + " for the children to inherit from and "
                            + "nothing is written", sourceId));
            return new Expansion(List.of(), issues);
        }
        JsonObject child = generator.getChild();
        if (child == null) {
            issues.add(Finding.error(domain, "MISSING_CHILD",
                    "no Child body is authored, so there is nothing to write once per combination", sourceId));
            return new Expansion(List.of(), issues);
        }
        String idPattern = generator.getIdPattern();
        if (idPattern == null || idPattern.isBlank()) {
            issues.add(Finding.error(domain, "MISSING_ID_PATTERN",
                    "no IdPattern is authored, so the children have no ids to be told apart by", sourceId));
            return new Expansion(List.of(), issues);
        }

        Set<String> sharedTokens = new LinkedHashSet<>();
        List<Map<String, JsonPrimitive>> combinations =
                combinations(generator, values, domain, noun, sourceId, issues, sharedTokens);
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
        collectTokens(baseId, usedTokens);
        addNestedUses(combinations, usedTokens);
        for (String token : boundTokens) {
            if (!usedTokens.contains(token) && !sharedTokens.contains(token)) {
                issues.add(Finding.warning(domain, "UNUSED_TOKEN",
                        "the axes bind '{" + token + "}' but neither Child nor IdPattern ever uses it, so it "
                                + "changes nothing and may only be multiplying the count", sourceId));
            }
        }

        List<GeneratedBody> bodies = new ArrayList<>(combinations.size());
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, JsonPrimitive> bindings : combinations) {
            Fill fill = new Fill(bindings);
            String id = fill.string(idPattern).trim().toLowerCase(Locale.ROOT);
            String base = fill.string(baseId).trim().toLowerCase(Locale.ROOT);
            JsonObject body = (JsonObject) fill.node(child);

            if (!fill.cycle().isEmpty()) {
                issues.add(Finding.error(domain, "TOKEN_CYCLE",
                        "'" + id + "' fills " + braced(fill.cycle()) + " from each other in a loop, so none of "
                                + "them ever has a value; the " + noun + " is written wrong and is skipped", sourceId));
                continue;
            }
            Set<String> unresolved = new LinkedHashSet<>();
            collectTokens(id, unresolved);
            collectTokens(base, unresolved);
            collectTokens(body, unresolved);
            if (!unresolved.isEmpty()) {
                issues.add(Finding.error(domain, "UNRESOLVED_TOKEN",
                        "'" + id + "' still contains " + braced(unresolved) + " after substitution; no axis "
                                + "binds that name, so the " + noun + " is written wrong and is skipped", sourceId));
                continue;
            }
            if (!seen.add(id)) {
                issues.add(Finding.error(domain, "DUPLICATE_ID",
                        "two combinations both produce the id '" + id + "', so only one of them can exist; add "
                                + "the missing token to IdPattern", sourceId));
                continue;
            }

            JsonObject withParent = new JsonObject();
            withParent.addProperty("Parent", base);
            for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
                if (!"Parent".equals(entry.getKey())) {
                    withParent.add(entry.getKey(), entry.getValue());
                }
            }
            bodies.add(new GeneratedBody(id, withParent, base, sourceId));
        }
        return new Expansion(bodies, issues);
    }

    // ==================== the walk ====================

    /**
     * Every combination of every axis, in authored order (the first axis varies slowest); each token a rows-source
     * axis binds is added to {@code sharedTokens}.
     */
    @Nonnull
    private static List<Map<String, JsonPrimitive>> combinations(@Nonnull GeneratorSpec generator,
            @Nullable AxisValueSource values, @Nonnull String domain, @Nonnull String noun,
            @Nonnull String sourceId, @Nonnull List<Finding> issues, @Nonnull Set<String> sharedTokens) {

        GeneratorAxisAsset[] axes = generator.axesOrEmpty();
        if (axes.length == 0) {
            issues.add(Finding.warning(domain, "NO_AXES",
                    "no ForEach axes are authored, so there is nothing to vary and nothing is written; author "
                            + "the " + noun + " as its own file instead", sourceId));
            return List.of();
        }

        List<Map<String, JsonPrimitive>> combinations = new ArrayList<>();
        combinations.add(Map.of());
        for (GeneratorAxisAsset axis : axes) {
            List<Map<String, JsonPrimitive>> rows =
                    rowsOf(axis, generator, values, domain, sourceId, issues, sharedTokens);
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

    /**
     * The bindings one axis contributes: from its authored values, from the library's rows source (each token added
     * to {@code sharedTokens}), or from a registered source.
     */
    @Nonnull
    private static List<Map<String, JsonPrimitive>> rowsOf(@Nullable GeneratorAxisAsset axis,
            @Nonnull GeneratorSpec generator, @Nullable AxisValueSource values, @Nonnull String domain,
            @Nonnull String sourceId, @Nonnull List<Finding> issues, @Nonnull Set<String> sharedTokens) {

        if (axis == null) {
            return List.of();
        }
        String token = axis.getToken();
        List<Map<String, JsonPrimitive>> rows = new ArrayList<>();

        JsonElement authored = axis.getValues();
        if (authored != null && authored.isJsonArray()) {
            for (JsonElement entry : authored.getAsJsonArray()) {
                Map<String, JsonPrimitive> binding = bindingOf(entry, token);
                if (!binding.isEmpty()) {
                    rows.add(binding);
                }
            }
        } else if (GeneratorRows.isSource(axis.getSource())) {
            String set = GeneratorRows.setOf(axis.filterOrEmpty());
            if (set == null) {
                issues.add(Finding.warning(domain, "ROWS_WITHOUT_SET",
                        "axis '" + axis.describe() + "' reads the rows source but its Filter names no Set, so it "
                                + "walks no rows and this generator writes nothing; write \"Filter\": { \"Set\": "
                                + "\"<set>\" }", sourceId));
                return List.of();
            }
            GeneratorRows.walked(generator.getClass(), set);
            for (Map<String, JsonPrimitive> row : GeneratorRows.rows(set)) {
                rows.add(row);
                sharedTokens.addAll(row.keySet());
            }
            if (rows.isEmpty()) {
                issues.add(Finding.warning(domain, "EMPTY_AXIS",
                        "axis '" + axis.describe() + "' walks the set '" + set + "', which has no switched-on rows, "
                                + "so no combination exists and this generator writes nothing", sourceId));
            }
            return rows;
        } else if (axis.getSource() != null) {
            String source = axis.getSource();
            if (values == null || !values.isRegistered(source)) {
                issues.add(Finding.warning(domain, "UNKNOWN_SOURCE",
                        "axis '" + axis.describe() + "' reads the source '" + source + "', which nothing "
                                + "registered; it produces no values, so this generator writes nothing until "
                                + "whichever mod owns it is installed", sourceId));
                return List.of();
            }
            try {
                for (Map<String, JsonPrimitive> row : values.rows(source, token, axis.filterOrEmpty())) {
                    if (row != null && !row.isEmpty()) {
                        rows.add(row);
                    }
                }
            } catch (Exception e) {
                values.recordFailure(source, e.getMessage());
                issues.add(Finding.error(domain, "SOURCE_FAILED",
                        "the source '" + source + "' threw while listing its values, so this generator writes "
                                + "nothing: " + e.getMessage(), sourceId));
                return List.of();
            }
        }

        if (rows.isEmpty()) {
            issues.add(Finding.warning(domain, "EMPTY_AXIS",
                    "axis '" + axis.describe() + "' has no values, so no combination exists and this generator "
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
     * number. A bound value holding tokens of its own is filled from {@code bindings} first (the second
     * pass); one caught in a loop, like an unbound token, is left written as it was, for the caller's
     * {@link #tokensIn} to find.
     */
    @Nonnull
    public static JsonElement substitute(@Nonnull JsonElement node,
            @Nonnull Map<String, JsonPrimitive> bindings) {
        return new Fill(bindings).node(node);
    }

    /**
     * {@code text} with every bound token spliced in as text, a bound value holding tokens filled from
     * {@code bindings} first; unbound tokens, and tokens caught in a loop, are left alone.
     */
    @Nonnull
    public static String substituteString(@Nonnull String text,
            @Nonnull Map<String, JsonPrimitive> bindings) {
        return new Fill(bindings).string(text);
    }

    /**
     * One combination's bindings as the substitution reads them: a bound value holding no token is returned as
     * bound; one holding tokens is filled from the same bindings on its first use (to any depth, a value that is
     * exactly one token taking that token's own type) and kept. Tokens that fill each other in a loop are noted in
     * {@link #cycle()} and left written as they were.
     */
    private static final class Fill {

        @Nonnull private final Map<String, JsonPrimitive> bound;
        @Nonnull private final Map<String, JsonPrimitive> filled = new HashMap<>();
        @Nonnull private final Set<String> filling = new LinkedHashSet<>();
        @Nonnull private final Set<String> cycle = new LinkedHashSet<>();

        Fill(@Nonnull Map<String, JsonPrimitive> bound) {
            this.bound = bound;
        }

        /** The tokens found filling each other in a loop, in the order the loop was walked; empty when none. */
        @Nonnull
        Set<String> cycle() {
            return cycle;
        }

        /** {@code name}'s value, its own tokens filled; null when nothing binds it or it is caught in a loop. */
        @Nullable
        JsonPrimitive value(@Nonnull String name) {
            JsonPrimitive raw = bound.get(name);
            if (raw == null || !raw.isString() || raw.getAsString().indexOf('{') < 0) {
                return raw;
            }
            JsonPrimitive done = filled.get(name);
            if (done != null) {
                return done;
            }
            if (!filling.add(name)) {
                boolean inLoop = false;
                for (String token : filling) {
                    inLoop |= token.equals(name);
                    if (inLoop) {
                        cycle.add(token);
                    }
                }
                return null;
            }
            String text = raw.getAsString();
            Matcher whole = TOKEN.matcher(text);
            JsonPrimitive out;
            if (whole.matches()) {
                JsonPrimitive inner = value(whole.group(1));
                out = inner != null ? inner : raw;
            } else {
                out = new JsonPrimitive(string(text));
            }
            filling.remove(name);
            filled.put(name, out);
            return out;
        }

        /** A copy of {@code node} with every token filled in, as {@link GeneratorCore#substitute} describes. */
        @Nonnull
        JsonElement node(@Nonnull JsonElement node) {
            if (node.isJsonObject()) {
                JsonObject out = new JsonObject();
                for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
                    out.add(string(entry.getKey()), node(entry.getValue()));
                }
                return out;
            }
            if (node.isJsonArray()) {
                JsonArray out = new JsonArray();
                for (JsonElement entry : node.getAsJsonArray()) {
                    out.add(node(entry));
                }
                return out;
            }
            if (node.isJsonPrimitive() && node.getAsJsonPrimitive().isString()) {
                String text = node.getAsString();
                Matcher matcher = TOKEN.matcher(text);
                JsonPrimitive whole = matcher.matches() ? value(matcher.group(1)) : null;
                return whole != null ? whole : new JsonPrimitive(string(text));
            }
            return node;
        }

        /** {@code text} with every token that has a value spliced in as text; the rest left alone. */
        @Nonnull
        String string(@Nonnull String text) {
            if (text.indexOf('{') < 0) {
                return text;
            }
            Matcher matcher = TOKEN.matcher(text);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                JsonPrimitive value = value(matcher.group(1));
                matcher.appendReplacement(out,
                        Matcher.quoteReplacement(value == null ? matcher.group() : value.getAsString()));
            }
            matcher.appendTail(out);
            return out.toString();
        }
    }

    /**
     * Add to {@code used} every token a used token's bound value names, in any combination, until nothing new is
     * found: a token only another bound value reads (a member's {@code {First}} read by a step's
     * {@code "Amount": "{First}"}) is used.
     */
    private static void addNestedUses(@Nonnull List<Map<String, JsonPrimitive>> combinations,
            @Nonnull Set<String> used) {
        Deque<String> pending = new ArrayDeque<>(used);
        while (!pending.isEmpty()) {
            String token = pending.poll();
            for (Map<String, JsonPrimitive> combination : combinations) {
                JsonPrimitive value = combination.get(token);
                if (value == null || !value.isString()) {
                    continue;
                }
                Set<String> named = new LinkedHashSet<>();
                collectTokens(value.getAsString(), named);
                for (String name : named) {
                    if (used.add(name)) {
                        pending.add(name);
                    }
                }
            }
        }
    }

    // ==================== token scanning ====================

    /**
     * Every {@code {token}} name still written anywhere in {@code node} (its strings and its object keys): what a
     * caller filling a template through {@link #substitute} asks afterwards, so an unbound token is reported the
     * same way {@link #expand} reports it, never shipped half-written.
     */
    @Nonnull
    public static Set<String> tokensIn(@Nonnull JsonElement node) {
        Set<String> out = new LinkedHashSet<>();
        collectTokens(node, out);
        return out;
    }

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
}
