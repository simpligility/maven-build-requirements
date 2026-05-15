package com.simpligility.maven.analysis;

import module java.base;
import module java.xml;

import com.simpligility.maven.util.Dom;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;

/// Resolves the wrapper-specified Maven version's `maven-model-builder` jar and pulls plugin
/// version defaults out of its bundled super POM
/// (`org/apache/maven/model/pom-4.0.0.xml`). Adds anything not already in the plugin
/// candidate map — same `putIfAbsent` discipline that [LifecyclePluginLoader] uses for
/// `default-bindings.xml`.
///
/// Why this exists: mima's bundled model-builder applies *its* super POM during effective
/// model construction (whichever 3.9.x version mima ships with), not the version the
/// project's wrapper declares. For projects whose plugins all carry explicit versions this
/// is invisible — but projects that declare plugins like `maven-assembly-plugin` /
/// `maven-dependency-plugin` / `maven-release-plugin` *without* a version pick up
/// mima-version defaults instead of the right ones. This loader is the narrow fix:
/// resolve and merge the correct super POM defaults so the candidate set matches what
/// `./mvnw <goal>` would actually use.
///
/// As of Maven 3.9.x the super POM's `<pluginManagement>` only contains four plugins —
/// the lifecycle-bound ones live in `default-bindings.xml` instead — and the Maven docs
/// flag these four for eventual removal (MNG-4453). Small effect in practice; clean fix
/// for the cases where it bites.
public final class SuperPomLoader {

    private static final String SUPER_POM_PATH = "org/apache/maven/model/pom-4.0.0.xml";

    private final AnalysisContext ctx;

    public SuperPomLoader(AnalysisContext ctx) {
        this.ctx = ctx;
    }

    public void load(String mavenVersion, Map<String, DefaultArtifact> pluginCandidates) {
        if (mavenVersion == null) return;

        ctx.logger().print("Resolving super POM plugin defaults from maven-model-builder "
                + mavenVersion + "...");
        DefaultArtifact mmbArtifact = new DefaultArtifact(
                "org.apache.maven", "maven-model-builder", "jar", mavenVersion);
        try {
            ArtifactRequest req = new ArtifactRequest(
                    mmbArtifact, ctx.mima().remoteRepositories(), null);
            ArtifactResult result = ctx.mima().repositorySystem()
                    .resolveArtifact(ctx.mima().repositorySystemSession(), req);
            int added = parseSuperPomPlugins(result.getArtifact().getFile(), pluginCandidates);
            ctx.logger().print("  Added " + added + " super POM plugin default(s).");
        } catch (Exception e) {
            ctx.logger().print("  Warning: could not load super POM from maven-model-builder "
                    + mavenVersion + ": " + e.getMessage());
        }
        ctx.logger().print("");
    }

    private int parseSuperPomPlugins(File mmbJar,
                                     Map<String, DefaultArtifact> pluginCandidates) throws Exception {
        try (JarFile jar = new JarFile(mmbJar)) {
            JarEntry entry = jar.getJarEntry(SUPER_POM_PATH);
            if (entry == null) {
                ctx.logger().print("  Warning: " + SUPER_POM_PATH
                        + " not found in maven-model-builder JAR.");
                return 0;
            }
            try (InputStream is = jar.getInputStream(entry)) {
                Document doc = ctx.documentBuilder().parse(is);
                Element project = doc.getDocumentElement();
                Element build = Dom.directElement(project, "build");
                if (build == null) return 0;
                Element pluginManagement = Dom.directElement(build, "pluginManagement");
                if (pluginManagement == null) return 0;
                Element plugins = Dom.directElement(pluginManagement, "plugins");
                if (plugins == null) return 0;

                int added = 0;
                NodeList pluginNodes = plugins.getElementsByTagName("plugin");
                for (int i = 0; i < pluginNodes.getLength(); i++) {
                    Element plugin = (Element) pluginNodes.item(i);
                    String pg = Dom.directText(plugin, "groupId");
                    String pa = Dom.directText(plugin, "artifactId");
                    String pv = Dom.directText(plugin, "version");

                    // Super POM omits groupId for the core Maven-plugins namespace.
                    if (isBlank(pg)) pg = "org.apache.maven.plugins";
                    if (isBlank(pa) || isBlank(pv)) continue;

                    String key = pg + ":" + pa;
                    if (!pluginCandidates.containsKey(key)) {
                        pluginCandidates.put(key, new DefaultArtifact(pg, pa, "jar", pv));
                        added++;
                    }
                }
                return added;
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
