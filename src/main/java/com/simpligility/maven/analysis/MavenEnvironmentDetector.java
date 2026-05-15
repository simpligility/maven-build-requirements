package com.simpligility.maven.analysis;

import module java.base;

import com.simpligility.maven.util.Coords;
import org.eclipse.aether.artifact.DefaultArtifact;

/// Step 0 of the analysis: figure out which Maven version is in play so lifecycle bindings
/// can be loaded from the matching `maven-core` jar, and capture the wrapper-declared binary
/// distribution (if any) as a build requirement.
///
/// Order of preference:
/// 1. `.mvn/wrapper/maven-wrapper.properties` `distributionUrl` — most accurate; matches what
///    the project actually builds with.
/// 2. `mvn --version` on `PATH` — fallback when no wrapper is configured.
/// 3. Neither — version stays `null`; lifecycle plugins are skipped downstream.
public final class MavenEnvironmentDetector {

    public record Result(String version, String source, DefaultArtifact distributionCandidate) {
        public boolean detected() { return version != null; }
    }

    private final ProgressLogger logger;
    private final Path projectDir;

    public MavenEnvironmentDetector(ProgressLogger logger, Path projectDir) {
        this.logger = logger;
        this.projectDir = projectDir;
    }

    public Result detect() {
        logger.print("Detecting Maven environment...");

        String version = null;
        String source = null;
        DefaultArtifact distroCandidate = null;

        Path wrapperProperties = projectDir.resolve(".mvn").resolve("wrapper")
                .resolve("maven-wrapper.properties");
        if (Files.exists(wrapperProperties)) {
            try (var in = Files.newInputStream(wrapperProperties)) {
                Properties wp = new Properties();
                wp.load(in);
                String distUrl = wp.getProperty("distributionUrl");
                if (distUrl != null && !distUrl.isBlank()) {
                    DefaultArtifact d = Coords.artifactFromMavenUrl(distUrl);
                    if (d != null) {
                        version = d.getVersion();
                        source = "Maven wrapper (.mvn/wrapper/maven-wrapper.properties)";
                        distroCandidate = d;
                    } else {
                        logger.print("  Warning: could not parse distributionUrl: " + distUrl);
                    }
                }
            } catch (Exception e) {
                logger.print("  Warning: could not parse maven-wrapper.properties: "
                        + e.getMessage());
            }
        }

        if (version == null) {
            try {
                ProcessBuilder pb = new ProcessBuilder("mvn", "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String out = new String(p.getInputStream().readAllBytes());
                p.waitFor();
                String marker = "Apache Maven ";
                int idx = out.indexOf(marker);
                if (idx >= 0) {
                    int s = idx + marker.length();
                    int e = s;
                    while (e < out.length() && !Character.isWhitespace(out.charAt(e))) e++;
                    String v = out.substring(s, e).trim();
                    if (!v.isEmpty()) {
                        version = v;
                        source = "'mvn --version' on PATH";
                    }
                }
            } catch (Exception _) {
                // fall through to the no-version-detected case
            }
        }

        if (version == null) {
            logger.print("  Maven version: unknown — no wrapper configured and no 'mvn' on PATH.");
            logger.print("  Lifecycle-bound plugins will not be included in the analysis.");
        } else {
            logger.print("  Maven version: " + version + " (source: " + source + ")");
        }
        logger.print("");

        return new Result(version, source, distroCandidate);
    }
}
