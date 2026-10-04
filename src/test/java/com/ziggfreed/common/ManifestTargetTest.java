package com.ziggfreed.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.common.semver.Semver;
import com.hypixel.hytale.common.semver.SemverRange;

/**
 * The library's shipped manifest targets the server line this build is compiled and tested against, read
 * the way the server reads it.
 *
 * <p>The server checks a plugin's {@code ServerVersion} with {@code SemverRange.satisfies} against its own
 * version, matching pre-releases npm-strict, and on a miss still loads the plugin but lists it as outdated
 * (a WARNING, a SEVERE roll-up and a red notice to players allowed to see outdated mods). So the range
 * admits the server jar on this classpath, the one the build compiles against, and that line's release,
 * and claims no later line.
 *
 * <p>The manifest read here is the processed one on the test classpath (after resource templating), so
 * the test reads exactly what a server reads. It calls only the engine's semver types, never
 * {@code PluginManifest}, whose class init builds a server logger this JVM does not have.
 */
class ManifestTargetTest {

    private static final String OWN_NAME = "ZiggfreedCommon";

    @Test
    void theServerRangeAdmitsTheServerThisBuildCompilesAgainst() throws IOException, URISyntaxException {
        SemverRange range = serverRange();
        Semver server = serverThisBuildCompilesAgainst();

        assertTrue(range.satisfies(server), "a server running the build this jar compiles against would list it"
                + " as outdated: " + range + " does not admit " + server);
    }

    @Test
    void theServerRangeAdmitsThatLinesReleaseAndClaimsNoLaterLine() throws IOException, URISyntaxException {
        SemverRange range = serverRange();
        Semver server = serverThisBuildCompilesAgainst();
        Semver release = new Semver(server.getMajor(), server.getMinor(), server.getPatch());
        Semver nextLine = new Semver(server.getMajor(), server.getMinor() + 1, 0);

        assertTrue(range.satisfies(release), "the release of the line this jar is built on must load it clean: "
                + range + " does not admit " + release);
        assertFalse(range.satisfies(nextLine), "a jar built on one server line must not claim the next: "
                + range + " admits " + nextLine);
    }

    /** The library's processed {@code ServerVersion}, parsed as the server parses it. */
    private static SemverRange serverRange() throws IOException {
        JsonObject own = manifestNamed(OWN_NAME);
        assertTrue(own.has("ServerVersion"), "the manifest names the server line it targets");
        return SemverRange.fromString(own.get("ServerVersion").getAsString());
    }

    /** The version the server jar on this classpath (the one the build compiles against) names in its own manifest. */
    private static Semver serverThisBuildCompilesAgainst() throws IOException, URISyntaxException {
        Path serverJar = Path.of(Semver.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile jar = new JarFile(serverJar.toFile())) {
            Manifest manifest = jar.getManifest();
            assertNotNull(manifest, "the server jar carries a manifest: " + serverJar);
            String version = manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            assertNotNull(version, "the server jar names its version: " + serverJar);
            return Semver.fromString(version);
        }
    }

    /**
     * The processed {@code manifest.json} whose {@code Name} is {@code name}. Any jar on the test classpath
     * may ship one, so they are told apart by name, never by classpath order.
     */
    private static JsonObject manifestNamed(String name) throws IOException {
        Enumeration<URL> found = ManifestTargetTest.class.getClassLoader().getResources("manifest.json");
        while (found.hasMoreElements()) {
            URL url = found.nextElement();
            try (InputStream in = url.openStream();
                    Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed.isJsonObject()) {
                    JsonObject manifest = parsed.getAsJsonObject();
                    if (manifest.has("Name") && name.equals(manifest.get("Name").getAsString())) {
                        return manifest;
                    }
                }
            }
        }
        return fail("no manifest.json named '" + name + "' on the test classpath");
    }
}
