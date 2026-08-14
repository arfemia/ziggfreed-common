package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The load-ORDER edges between framework stores, which nothing else can assert.
 *
 * <p>A store is registered with a {@code loadsAfter} list or without one, and the registry iterates
 * an unordered map, so two stores with no edge between them fold in whatever order that map hands
 * out. Where one store's content is written in another's vocabulary, that is a coin flip a server
 * pays for at boot: a pass reads the dependent content while the vocabulary is half there, and the
 * result is a warning about a name that arrives milliseconds later.
 *
 * <p>Each edge below exists because the arrow really points that way, and the assertion is a source
 * scan because the alternative is standing up the engine's asset registry for one array argument.
 */
class FrameworkStoreOrderTest {

    private static final Path REGISTRAR = Path.of("src", "main", "java", "com", "ziggfreed",
            "common", "asset", "FrameworkAssetRegistrar.java");

    /** dependent store -> the store it must load after, and why. */
    private static final Map<String, String[]> REQUIRED_EDGES = new LinkedHashMap<>();

    static {
        REQUIRED_EDGES.put("QuestGeneratorAsset", new String[]{"QuestAsset",
                "a generated child is resolved against its Base out of the quest store"});
        REQUIRED_EDGES.put("ZcDialogueAsset", new String[]{"DialogueFragmentAsset",
                "a conversation splices the shared option groups it names as it is read, so a group "
                        + "that has not loaded yet drops its lines out of the screen silently"});
    }

    @Test
    void everyDeclaredStoreOrderingEdgeIsRegistered() throws IOException {
        assertTrue(Files.isRegularFile(REGISTRAR), "missing " + REGISTRAR.toAbsolutePath());
        String source = strip(Files.readString(REGISTRAR, StandardCharsets.UTF_8));

        for (Map.Entry<String, String[]> edge : REQUIRED_EDGES.entrySet()) {
            String dependent = edge.getKey();
            String prerequisite = edge.getValue()[0];
            String call = registerStoreCall(source, dependent);
            assertTrue(call.contains(prerequisite + ".class"),
                    () -> dependent + " must be registered loadsAfter " + prerequisite + ": "
                            + edge.getValue()[1] + ". Its registerStore call reads: " + call);
        }
    }

    /**
     * The argument list of the {@code registerStore} call for {@code assetClass}, whitespace
     * collapsed. Cut at the first {@code );}, so the comment block introducing the NEXT store is
     * never mistaken for part of this one.
     */
    private static String registerStoreCall(String source, String assetClass) {
        String opener = "AssetStoreRegistrar.registerStore(";
        int at = source.indexOf(opener + assetClass + ".class,");
        assertTrue(at >= 0, () -> "no registerStore call found for " + assetClass);
        int from = at + opener.length();
        int end = source.indexOf(");", from);
        return end < 0 ? source.substring(from) : source.substring(from, end);
    }

    /** Drop comments and collapse whitespace, so a wrapped call reads as one line. */
    private static String strip(String source) {
        String noBlocks = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        String noLines = noBlocks.replaceAll("(?m)//.*$", " ");
        return noLines.replaceAll("\\s+", " ");
    }
}
