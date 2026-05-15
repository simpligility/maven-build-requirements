package com.simpligility.maven.analysis;

import module java.base;
import module java.xml;

import com.simpligility.maven.util.Dom;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;

/// Step 2c0 of the analysis: pulls lifecycle-bound plugins out of `maven-core`'s
/// `META-INF/plexus/default-bindings.xml` (e.g. compiler/jar/surefire for "jar" packaging)
/// and adds them to the plugin candidate map.
///
/// These bindings are part of Maven core, not the project's effective POM, so they have to
/// be loaded by resolving and unzipping the matching `maven-core` artifact for whatever
/// Maven version is in play (from [MavenEnvironmentDetector]).
public final class LifecyclePluginLoader {

    private final AnalysisContext ctx;

    public LifecyclePluginLoader(AnalysisContext ctx) {
        this.ctx = ctx;
    }

    public void load(String mavenVersion,
                     Set<String> reactorPackagings,
                     Map<String, DefaultArtifact> pluginCandidates) {
        if (mavenVersion == null || reactorPackagings.isEmpty()) return;

        ctx.logger().print("Resolving lifecycle-bound plugins from maven-core " + mavenVersion + "...");
        DefaultArtifact mavenCoreArtifact = new DefaultArtifact(
                "org.apache.maven", "maven-core", "jar", mavenVersion);
        try {
            ArtifactRequest req = new ArtifactRequest(
                    mavenCoreArtifact, ctx.mima().remoteRepositories(), null);
            ArtifactResult result = ctx.mima().repositorySystem()
                    .resolveArtifact(ctx.mima().repositorySystemSession(), req);
            File coreJar = result.getArtifact().getFile();
            Map<String, List<DefaultArtifact>> bindingsByPackaging =
                    parseDefaultBindings(coreJar);

            int added = 0;
            for (String packaging : reactorPackagings) {
                List<DefaultArtifact> plugins = bindingsByPackaging.get(packaging);
                if (plugins == null) continue;
                for (DefaultArtifact p : plugins) {
                    String key = p.getGroupId() + ":" + p.getArtifactId();
                    if (!pluginCandidates.containsKey(key)) {
                        pluginCandidates.put(key, p);
                        added++;
                    }
                }
            }
            ctx.logger().print("  Added " + added + " lifecycle plugin(s) for packagings: "
                    + reactorPackagings);
        } catch (Exception e) {
            ctx.logger().print("  Warning: could not load maven-core " + mavenVersion + ": "
                    + e.getMessage());
        }
        ctx.logger().print("");
    }

    private Map<String, List<DefaultArtifact>> parseDefaultBindings(File coreJar) throws Exception {
        Map<String, List<DefaultArtifact>> bindingsByPackaging = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(coreJar)) {
            JarEntry entry = jar.getJarEntry("META-INF/plexus/default-bindings.xml");
            if (entry == null) {
                ctx.logger().print("  Warning: default-bindings.xml not found in maven-core JAR.");
                return bindingsByPackaging;
            }
            try (InputStream is = jar.getInputStream(entry)) {
                Document bindingsDoc = ctx.documentBuilder().parse(is);
                NodeList components = bindingsDoc.getElementsByTagName("component");
                for (int i = 0; i < components.getLength(); i++) {
                    Element comp = (Element) components.item(i);
                    String role = Dom.directText(comp, "role");
                    if (!"org.apache.maven.lifecycle.mapping.LifecycleMapping".equals(role)) continue;
                    String roleHint = Dom.directText(comp, "role-hint");
                    if (roleHint == null || roleHint.isBlank()) continue;
                    Element config = Dom.directElement(comp, "configuration");
                    if (config == null) continue;
                    List<DefaultArtifact> plugins = new ArrayList<>();
                    // Newer schema (3.9+): configuration > lifecycles > lifecycle > phases.
                    Element lifecycles = Dom.directElement(config, "lifecycles");
                    if (lifecycles != null) {
                        NodeList lifecycleList = lifecycles.getElementsByTagName("lifecycle");
                        for (int j = 0; j < lifecycleList.getLength(); j++) {
                            Element lifecycle = (Element) lifecycleList.item(j);
                            Element phases = Dom.directElement(lifecycle, "phases");
                            if (phases != null) extractPluginsFromPhases(phases, plugins);
                        }
                    } else {
                        // Older schema: configuration > phases.
                        Element phases = Dom.directElement(config, "phases");
                        if (phases != null) extractPluginsFromPhases(phases, plugins);
                    }
                    bindingsByPackaging.put(roleHint, plugins);
                }
            }
            ctx.logger().print("  Loaded lifecycle bindings for " + bindingsByPackaging.size()
                    + " packaging type(s).");
        }
        return bindingsByPackaging;
    }

    /// Extracts plugin coordinates from a `<phases>` element in `default-bindings.xml`.
    /// Each phase child element's text is one or more comma-separated mojo coordinates
    /// (`g:a:v:goal`); we keep the GAV and dedupe per phases block by `g:a`.
    private void extractPluginsFromPhases(Element phases, List<DefaultArtifact> out) {
        NodeList children = phases.getChildNodes();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element phaseEl)) continue;
            String txt = phaseEl.getTextContent().trim();
            for (String coord : txt.split(",")) {
                coord = coord.trim();
                if (coord.isEmpty()) continue;
                String[] parts = coord.split(":");
                if (parts.length < 3) continue;
                String g = parts[0], a = parts[1], v = parts[2];
                if (isBlank(g) || isBlank(a) || isBlank(v)) continue;
                String key = g + ":" + a;
                if (seen.add(key)) {
                    out.add(new DefaultArtifact(g, a, "jar", v));
                }
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
