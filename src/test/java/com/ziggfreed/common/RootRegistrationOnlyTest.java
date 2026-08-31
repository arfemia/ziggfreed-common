package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The wiring root is REGISTRATION ONLY - and so is every module {@code *Bootstrap} it calls. This
 * test is the enforcement half of that rule.
 *
 * <p><b>Why the root is special.</b> It is the one place in the library where an edge between any
 * two modules is legal, so it silently absorbs every awkward dependency that does not fit the
 * module graph. That makes it the natural drain for logic too - and logic that lands here is logic
 * no module can be tested, reasoned about, or reused without standing up the whole plugin. Keeping
 * the root to store registration, event wiring and registry population is what keeps the module
 * graph honest: if the root has to DECIDE something, that decision belongs in a module behind a
 * seam the root merely fills.
 *
 * <p><b>What is scanned</b>: EVERY {@code .java} file under the root module's {@code
 * src/main/java}, PLUS every {@code *Bootstrap.java} under any module's {@code src/main/java} -
 * both discovered by walking the tree rather than by naming files. A named list is the one way
 * this guard can be silently disarmed - the root grows a fourth file, nobody adds it, and the
 * "build-enforced" claim quietly stops being true of the new file; modules are found the same way,
 * by listing the project root for directories carrying a source tree. The bootstraps are in scope
 * because the root's phase bodies live in them now: each module hosts its own registration phases
 * in a {@code *Bootstrap} class the root's {@code setup()} calls, so the rule has to follow
 * registration code wherever it lives, and lifting a body out of the root must never be a way out
 * from under the guard. That makes the {@code *Bootstrap} name load-bearing: the suffix is what
 * puts a lifted phase in scope, so a registration class that wires at setup is NAMED
 * {@code *Bootstrap}, never something the walk cannot see. Comments, javadoc and string literals
 * are stripped first, so prose and log lines are free to say whatever they need to.
 *
 * <p><b>What fails</b>: a loop ({@code for} / {@code while} / {@code do}), a {@code switch}, or an
 * {@code else} branch - each of them a decision rather than a registration. A {@code try}/
 * {@code catch} is fine (the root guards every engine call). An {@code if} is fine when it reads as
 * a GUARD: its condition mentions {@code null}, or the branch it opens immediately leaves, by
 * {@code return} or by {@code throw}. Both are the same shape - refuse this input and get out - and
 * a validating registrar that throws on unusable content is doing exactly what a guard does. A
 * ternary is fine when it defaults an absent value, which is to say when its condition mentions
 * {@code null}; a ternary written across several lines is joined back into one statement first, so
 * wrapping it is not a way around the rule.
 *
 * <p><b>Escape hatch</b>: {@code // ROOT-LOGIC-OK: <reason>} with a NON-EMPTY reason. A bare marker
 * fails just like the unmarked line would. It goes on the offending line, anywhere in the statement
 * for a wrapped ternary, or in the {@code //} comment block sitting immediately above - so a real
 * explanation can run to several lines instead of being crushed into a trailing comment. Reach for
 * it only when the root genuinely cannot delegate; the first move is always to move the decision
 * into the module that owns it and leave the root filling a seam.
 */
class RootRegistrationOnlyTest {

    /** The root module's main source tree. Every {@code .java} file under it is held to the rule. */
    private static final Path ROOT_SOURCE_DIR = Path.of("src", "main", "java");

    /**
     * The suffix that puts a MODULE file in scope: a lifted registration phase lives in a class
     * named {@code *Bootstrap}, so the walk can find it without naming modules or files.
     */
    private static final String BOOTSTRAP_SUFFIX = "Bootstrap.java";

    /** Control flow that is a decision however it is written. */
    private static final Pattern DECISION = Pattern.compile("\\b(for|while|do|switch|else)\\b");

    /** A conditional, guard or not; whether it passes is decided by {@link #isGuard}. */
    private static final Pattern CONDITIONAL = Pattern.compile("\\bif\\b");

    /** A ternary. Allowed only as a null default - see the class javadoc. */
    private static final Pattern TERNARY = Pattern.compile("\\?[^?:]*:");

    /** A bare {@code ?} that may be opening a ternary continued on a later line. */
    private static final Pattern TERNARY_OPEN = Pattern.compile("\\?");

    /** The escape hatch. The reason after the colon must be non-empty. */
    private static final Pattern ALLOW_MARKER = Pattern.compile("//\\s*ROOT-LOGIC-OK:\\s*(\\S.*)?$");

    /** How far a wrapped ternary is followed before the join gives up. */
    private static final int MAX_STATEMENT_LINES = 8;

    @Test
    void theWiringRootRegistersAndDecidesNothing() throws IOException {
        List<Path> sources = new ArrayList<>(rootSources());
        assertFalse(sources.isEmpty(), "no root sources found under " + ROOT_SOURCE_DIR.toAbsolutePath());

        List<Path> bootstraps = moduleBootstrapSources();
        assertFalse(bootstraps.isEmpty(), "no module *Bootstrap sources found - the modules host the"
                + " root's lifted phase bodies, so an empty find means this walk is broken (wrong"
                + " working directory?) rather than that no registration code exists");
        sources.addAll(bootstraps);

        List<String> hits = new ArrayList<>();
        for (Path source : sources) {
            scan(source, hits);
        }

        assertTrue(hits.isEmpty(), () -> "The wiring root grew logic (" + hits.size() + " hit(s)). Move the"
                + " decision into the module that owns it and leave the root filling a seam; if it truly"
                + " cannot move, mark the line // ROOT-LOGIC-OK: <reason>.\n" + String.join("\n", hits));
    }

    /** Every {@code .java} file in the root module, in a stable order so failures read the same twice. */
    private static List<Path> rootSources() throws IOException {
        assertTrue(Files.isDirectory(ROOT_SOURCE_DIR),
                "missing root source dir: " + ROOT_SOURCE_DIR.toAbsolutePath());
        try (Stream<Path> walk = Files.walk(ROOT_SOURCE_DIR)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Every {@code *Bootstrap.java} in every module, in a stable order. Modules are DISCOVERED by
     * listing the project root for directories that carry a {@code src/main/java} tree - never by
     * naming them, for the same reason the root walk never names files: a new module's bootstrap
     * must fall in scope the moment it exists. The root's own tree is not re-walked here; it is
     * already covered whole by {@link #rootSources()}.
     */
    private static List<Path> moduleBootstrapSources() throws IOException {
        List<Path> out = new ArrayList<>();
        List<Path> moduleTrees;
        try (Stream<Path> top = Files.list(Path.of("."))) {
            moduleTrees = top.filter(Files::isDirectory)
                    .map(dir -> dir.resolve(ROOT_SOURCE_DIR))
                    .filter(Files::isDirectory)
                    .sorted()
                    .toList();
        }
        for (Path tree : moduleTrees) {
            try (Stream<Path> walk = Files.walk(tree)) {
                out.addAll(walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(BOOTSTRAP_SUFFIX))
                        .sorted()
                        .toList());
            }
        }
        return out;
    }

    private static void scan(Path source, List<String> hits) throws IOException {
        List<String> raw = Files.readAllLines(source, StandardCharsets.UTF_8);
        List<String> code = strip(raw);
        for (int i = 0; i < code.size(); i++) {
            String line = code.get(i);
            if (line.isBlank()) {
                continue;
            }
            Matcher decision = DECISION.matcher(line);
            if (decision.find()) {
                if (!isAllowed(raw, i - 1, i)) {
                    hits.add(report(source, raw, i, decision.group()));
                }
                continue;
            }
            if (CONDITIONAL.matcher(line).find()) {
                if (!isGuard(line, code, i) && !isAllowed(raw, i - 1, i)) {
                    hits.add(report(source, raw, i, "if that is not a guard"));
                }
                continue;
            }
            scanTernary(source, raw, code, i, hits);
        }
    }

    /**
     * A ternary is checked against the whole STATEMENT, not the one line the {@code ?} sits on: a
     * wrapped ternary puts its {@code ?} and its {@code :} on different lines, which a per-line
     * regex never sees at all. The lines from the {@code ?} to the first one ending the statement
     * are joined and tested as one.
     */
    private static void scanTernary(Path source, List<String> raw, List<String> code, int index, List<String> hits) {
        String line = code.get(index);
        if (!TERNARY_OPEN.matcher(line).find()) {
            return;
        }
        int end = statementEnd(code, index);
        String statement = String.join(" ", code.subList(index, end + 1));
        if (!TERNARY.matcher(statement).find() || statement.contains("null")) {
            return;
        }
        if (!isAllowed(raw, index - 1, end)) {
            hits.add(report(source, raw, index, "ternary that is not a null default"));
        }
    }

    /** The last line of the statement starting at {@code index}: the first one ending in {@code ;}. */
    private static int statementEnd(List<String> code, int index) {
        int limit = Math.min(code.size() - 1, index + MAX_STATEMENT_LINES - 1);
        for (int i = index; i <= limit; i++) {
            if (code.get(i).trim().endsWith(";")) {
                return i;
            }
        }
        return limit;
    }

    /**
     * Blank out comments and string literals so prose and log text never trip the scan. Line-based
     * and deliberately simple: it tracks block comments across lines, which is all the root's own
     * formatting needs, and it does not try to be a Java lexer.
     */
    private static List<String> strip(List<String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        boolean inBlock = false;
        for (String line : raw) {
            StringBuilder sb = new StringBuilder();
            boolean inString = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
                if (inBlock) {
                    if (c == '*' && next == '/') {
                        inBlock = false;
                        i++;
                    }
                    continue;
                }
                if (inString) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        inString = false;
                    }
                    continue;
                }
                if (c == '/' && next == '/') {
                    break;
                }
                if (c == '/' && next == '*') {
                    inBlock = true;
                    i++;
                    continue;
                }
                if (c == '"') {
                    inString = true;
                    continue;
                }
                sb.append(c);
            }
            out.add(sb.toString());
        }
        return out;
    }

    /**
     * A conditional passes as a guard when it tests for absence, or when the branch it opens leaves
     * immediately. LEAVING is either a {@code return} or a {@code throw}: both mean "this input is
     * unusable, stop here", which is the shape a guard has, and a registrar that refuses malformed
     * content by throwing is not deciding anything - it is declining.
     */
    private static boolean isGuard(String line, List<String> code, int index) {
        if (line.contains("null")) {
            return true;
        }
        for (int i = index; i < Math.min(code.size(), index + 3); i++) {
            String following = code.get(i).trim();
            if (i > index && (following.startsWith("return") || following.startsWith("throw"))) {
                return true;
            }
            if (following.endsWith("return;") || following.contains("{ return") || following.contains("{ throw")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The marker counts anywhere in {@code [from, to]} (clamped to the file), and its reason must
     * say something. The window is also extended upward across the contiguous {@code //} comment
     * block immediately above {@code from}, because a reason worth writing is usually longer than
     * one line and belongs above the code it explains, not trailing off the end of it.
     */
    private static boolean isAllowed(List<String> raw, int from, int to) {
        int start = Math.max(0, Math.min(from, raw.size() - 1));
        while (start > 0 && raw.get(start - 1).trim().startsWith("//")) {
            start--;
        }
        for (int i = start; i <= Math.min(raw.size() - 1, to); i++) {
            Matcher marker = ALLOW_MARKER.matcher(raw.get(i));
            if (marker.find() && marker.group(1) != null && !marker.group(1).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String report(Path source, List<String> raw, int index, String what) {
        // The whole path, not just the file name: with every module's bootstraps in scope, two
        // modules may legitimately hold classes sharing a simple name.
        return source + ":" + (index + 1) + ": [" + what + "] " + raw.get(index).trim();
    }
}
