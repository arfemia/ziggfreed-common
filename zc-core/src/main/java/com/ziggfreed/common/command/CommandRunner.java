package com.ziggfreed.common.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.CommandExecutor;

/**
 * Runs an AUTHORED command line - the zero-code integration surface a pack author gets when a
 * reward, a drop or a station grant needs to do something the mod has no schema for.
 *
 * <p>It is the one place three things happen, because every consumer that runs authored commands
 * needs all three and none of them is worth getting wrong twice:
 *
 * <ol>
 *   <li><b>Placeholder substitution</b> ({@link #substitute}), map-driven so the vocabulary belongs
 *       to the CONSUMER. Common bakes in no key list: a station passes {@code station}/{@code action},
 *       a level-up reward passes {@code level}/{@code skill}, and {@code player} is simply the key
 *       every consumer happens to share.</li>
 *   <li><b>The {@code /give} quantity fix</b> ({@link #normalizeGive}). The engine's give command
 *       reads a quantity ONLY from {@code --quantity=N}; a positional count is parsed as nothing and
 *       silently delivers a single item. An author writing {@code give Bob Wood_Planks 32} and
 *       getting one plank has no way to tell why, so the fix belongs here rather than in each
 *       consumer's docs.</li>
 *   <li><b>A per-call guard</b>. One bad authored line reports itself and the next line still runs;
 *       nothing escapes into the caller's grant loop. A line that did not run is answered as such -
 *       a refused dispatch reads exactly like a thrown one - so a caller that pays out on the
 *       strength of a command can tell the difference between a grant and a silence.</li>
 * </ol>
 *
 * <p>Commands run AS THE SERVER CONSOLE. An authored command is server-owner content, so it must
 * not be limited to what the player who happened to trigger it may do.
 *
 * <p>Failures go to a caller-supplied {@link Consumer} rather than a logger, so a consumer routes
 * them into its own guarded log seam, a validation report, or a test list. World-thread, like the
 * engine command manager it dispatches through.
 */
public final class CommandRunner {

    /**
     * Where a resolved command line actually goes. The default is the server console; a test (or a
     * consumer that has its own dispatch policy) supplies its own.
     */
    @FunctionalInterface
    public interface Dispatcher {

        /**
         * Run one resolved command line, and answer whether it ACTUALLY dispatched.
         *
         * <p>The answer is the whole contract. False is treated exactly like a throw: the line is
         * reported to the caller's failure sink and {@link CommandRunner#runWith} answers false, so a
         * grant site can queue a retry instead of recording a payout that never happened. Returning
         * true for a line the command system refused is how a reward is lost silently.
         *
         * <p>Throwing is fine - {@link CommandRunner} guards it and reports it the same way. A
         * dispatcher with genuinely nothing to check (it records the line, or hands it to something
         * that cannot answer) returns true, and should say in a comment why that is honest.
         */
        boolean dispatch(@Nonnull String command) throws Exception;
    }

    /**
     * The server console, via the shared {@code util.CommandExecutor}, whose own boolean answer is
     * passed straight through - so a line the command system refused counts as not run.
     */
    public static final Dispatcher CONSOLE = command -> CommandExecutor.executeAsConsole(command);

    private CommandRunner() {
    }

    // ==================== substitution ====================

    /**
     * Replace each {@code {key}} in {@code raw} with its value. A key the map does not carry is left
     * standing rather than blanked, so a typo shows up in the command that ran instead of quietly
     * becoming an empty argument; a null value substitutes as empty.
     */
    @Nonnull
    public static String substitute(@Nullable String raw, @Nullable Map<String, String> placeholders) {
        if (raw == null || raw.isEmpty() || placeholders == null || placeholders.isEmpty()) {
            return raw == null ? "" : raw;
        }
        String out = raw;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                continue;
            }
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    // ==================== the give-quantity fix ====================

    /**
     * Rewrite a {@code give} line's POSITIONAL quantity into the {@code --quantity=N} form the
     * engine actually reads. Anything that is not a give command, already names
     * {@code --quantity}, or carries no trailing count is returned unchanged.
     *
     * <pre>
     * give Bob Wood_Planks 32            -&gt;  give Bob Wood_Planks --quantity=32
     * /give Bob Wood_Planks 32           -&gt;  /give Bob Wood_Planks --quantity=32
     * give Bob Wood_Planks --quantity=32 -&gt;  unchanged
     * give Bob Wood_Planks               -&gt;  unchanged
     * summon Bob Zombie 3                -&gt;  unchanged (not a give)
     * </pre>
     */
    @Nonnull
    public static String normalizeGive(@Nullable String command) {
        if (command == null || command.isBlank()) {
            return command == null ? "" : command;
        }
        String[] tokens = command.trim().split("\\s+");
        int verb = 0;
        String bare = tokens[0].startsWith("/") ? tokens[0].substring(1) : tokens[0];
        if (!"give".equals(bare.toLowerCase(Locale.ROOT))) {
            return command;
        }

        int lastPositional = -1;
        int positionals = 0;
        for (int i = verb + 1; i < tokens.length; i++) {
            if (tokens[i].startsWith("-")) {
                if (tokens[i].toLowerCase(Locale.ROOT).startsWith("--quantity")) {
                    return command; // Already says it the way the engine reads it.
                }
                continue;
            }
            positionals++;
            lastPositional = i;
        }

        // player + item + count. Fewer positionals means no count was written at all.
        if (positionals < 3 || lastPositional < 0 || !isPositiveInteger(tokens[lastPositional])) {
            return command;
        }
        tokens[lastPositional] = "--quantity=" + tokens[lastPositional];
        return String.join(" ", tokens);
    }

    private static boolean isPositiveInteger(@Nonnull String token) {
        if (token.isEmpty() || token.length() > 10) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (token.charAt(i) < '0' || token.charAt(i) > '9') {
                return false;
            }
        }
        return Long.parseLong(token) > 0;
    }

    // ==================== running ====================

    /**
     * Resolve {@code raw} (substitute, then {@link #normalizeGive}) and run it as the server
     * console.
     *
     * @return true when the dispatcher confirmed the line ran; false for a blank line, a dispatch
     *         the dispatcher refused, or a throw - each reported to {@code failureSink}
     */
    public static boolean run(@Nullable String raw, @Nullable Map<String, String> placeholders,
            @Nullable Consumer<String> failureSink) {
        return runWith(CONSOLE, raw, placeholders, failureSink);
    }

    /** As {@link #run}, through a caller-supplied {@link Dispatcher}. */
    public static boolean runWith(@Nullable Dispatcher dispatcher, @Nullable String raw,
            @Nullable Map<String, String> placeholders, @Nullable Consumer<String> failureSink) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        if (dispatcher == null) {
            report(failureSink, "no command dispatcher, so '" + raw.trim() + "' did not run");
            return false;
        }
        String resolved;
        try {
            resolved = normalizeGive(substitute(raw.trim(), placeholders));
        } catch (Throwable t) {
            report(failureSink, "could not resolve command '" + raw.trim() + "': " + t);
            return false;
        }
        try {
            if (dispatcher.dispatch(resolved)) {
                return true;
            }
            report(failureSink, "command '" + resolved + "' did not dispatch");
            return false;
        } catch (Throwable t) {
            report(failureSink, "command '" + resolved + "' failed: " + t);
            return false;
        }
    }

    /**
     * Run every line in order, and keep going past one that fails - an authored list is a list of
     * independent grants, so one broken line must not cost the rest.
     *
     * @return how many lines dispatched successfully
     */
    public static int runAll(@Nullable Collection<String> raws, @Nullable Map<String, String> placeholders,
            @Nullable Consumer<String> failureSink) {
        return runAllWith(CONSOLE, raws, placeholders, failureSink);
    }

    /** As {@link #runAll}, through a caller-supplied {@link Dispatcher}. */
    public static int runAllWith(@Nullable Dispatcher dispatcher, @Nullable Collection<String> raws,
            @Nullable Map<String, String> placeholders, @Nullable Consumer<String> failureSink) {
        if (raws == null || raws.isEmpty()) {
            return 0;
        }
        int ran = 0;
        for (String raw : raws) {
            if (runWith(dispatcher, raw, placeholders, failureSink)) {
                ran++;
            }
        }
        return ran;
    }

    /** Every line as it WOULD be dispatched, without running any of it (for a validator or a preview). */
    @Nonnull
    public static List<String> resolveAll(@Nullable Collection<String> raws,
            @Nullable Map<String, String> placeholders) {
        List<String> out = new ArrayList<>();
        if (raws == null) {
            return out;
        }
        for (String raw : raws) {
            if (raw != null && !raw.isBlank()) {
                out.add(normalizeGive(substitute(raw.trim(), placeholders)));
            }
        }
        return out;
    }

    private static void report(@Nullable Consumer<String> failureSink, @Nonnull String message) {
        if (failureSink == null) {
            return;
        }
        try {
            failureSink.accept(message);
        } catch (Throwable ignored) {
            // A sink that throws costs its own line, never the grant loop that called us.
        }
    }
}
