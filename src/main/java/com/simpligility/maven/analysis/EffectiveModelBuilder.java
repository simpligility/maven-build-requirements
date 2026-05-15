package com.simpligility.maven.analysis;

import module java.base;
import module java.xml;

import com.simpligility.maven.util.Dom;
import eu.maveniverse.maven.mima.extensions.mmr.ModelRequest;
import eu.maveniverse.maven.mima.extensions.mmr.ModelResponse;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;

/// Step 2a of the analysis: runs the MMR effective-model build on every reactor pom and
/// also DOM-scans sub-module poms for explicitly-versioned plugins that the root's effective
/// model would miss.
///
/// Returns the four pieces downstream resolvers need: the plugin candidate map (used by
/// [LifecyclePluginLoader], [PluginAnalyzer] later), the parent POM candidate list, the
/// effective-model direct dependencies (which [DependencyResolver] uses as the primary
/// input for transitive resolution — BOM-managed versions only surface here, not via the
/// DOM-collected fallback), and the reactor packagings (used by [LifecyclePluginLoader]).
public final class EffectiveModelBuilder {

    public record Result(
            Map<String, DefaultArtifact> pluginCandidates,
            List<DefaultArtifact> parentPomCandidates,
            List<Dependency> directDeps,
            Set<String> reactorPackagings
    ) {}

    private final AnalysisContext ctx;

    public EffectiveModelBuilder(AnalysisContext ctx) {
        this.ctx = ctx;
    }

    public Result build(ProjectStructure structure) throws Exception {
        Map<String, DefaultArtifact> pluginCandidates = new LinkedHashMap<>();
        List<DefaultArtifact> parentPomCandidates = new ArrayList<>();
        List<Dependency> directDeps = new ArrayList<>();
        Set<String> reactorPackagings = new LinkedHashSet<>();

        ctx.logger().print("Building effective POM model(s)...");
        int modelsBuilt = 0;
        for (Path pomFile : structure.pomFiles()) {
            try {
                ModelResponse response = ctx.modelReader().readModel(
                        ModelRequest.builder().setPomFile(pomFile).build());

                Model effectiveModel = response.getEffectiveModel();
                String pkg = effectiveModel.getPackaging();
                reactorPackagings.add(isBlank(pkg) ? "jar" : pkg);
                Build build = effectiveModel.getBuild();
                if (build != null) {
                    PluginManagement pm = build.getPluginManagement();
                    if (pm != null) {
                        for (Plugin p : pm.getPlugins()) {
                            if (!isBlank(p.getVersion())) {
                                pluginCandidates.putIfAbsent(
                                        p.getGroupId() + ":" + p.getArtifactId(),
                                        new DefaultArtifact(p.getGroupId(), p.getArtifactId(), "jar", p.getVersion()));
                            }
                        }
                    }
                    for (Plugin p : build.getPlugins()) {
                        if (!isBlank(p.getVersion())) {
                            pluginCandidates.putIfAbsent(
                                    p.getGroupId() + ":" + p.getArtifactId(),
                                    new DefaultArtifact(p.getGroupId(), p.getArtifactId(), "jar", p.getVersion()));
                        }
                    }
                }

                for (String modelId : response.getLineage()) {
                    if (isBlank(modelId)) continue;
                    String[] parts = modelId.split(":");
                    if (parts.length != 3) continue;
                    String g = parts[0], a = parts[1], v = parts[2];
                    if (isBlank(g) || isBlank(a) || isBlank(v)) continue;
                    String coord = g + ":" + a + ":" + v;
                    if (!structure.reactorCoords().contains(coord)) {
                        parentPomCandidates.add(new DefaultArtifact(g, a, "pom", v));
                    }
                }

                // Direct deps from the effective model resolve BOM-managed versions that the
                // DOM-based pass in ProjectStructureLoader cannot follow.
                for (org.apache.maven.model.Dependency d : effectiveModel.getDependencies()) {
                    String g = d.getGroupId();
                    String a = d.getArtifactId();
                    String v = d.getVersion();
                    String scope = isBlank(d.getScope()) ? "compile" : d.getScope();
                    String type = isBlank(d.getType()) ? "jar" : d.getType();
                    if (isBlank(g) || isBlank(a) || isBlank(v)) continue;
                    if ("import".equals(scope)) continue;
                    String coord = g + ":" + a + ":" + v;
                    if (structure.reactorCoords().contains(coord)) continue;
                    directDeps.add(new Dependency(new DefaultArtifact(g, a, type, v), scope));
                }

                modelsBuilt++;
            } catch (Exception e) {
                ctx.logger().print("  Warning: could not build effective model for "
                        + ctx.projectDir().toAbsolutePath().relativize(pomFile.toAbsolutePath())
                        + ": " + e.getMessage());
            }
        }
        ctx.logger().print("  Effective model built for " + modelsBuilt + " of "
                + structure.pomFiles().size() + " module(s) — " + pluginCandidates.size()
                + " plugin(s), " + parentPomCandidates.size() + " parent POM(s) in lineage, "
                + directDeps.size() + " direct dep(s).");
        ctx.logger().print("");

        scanSubModulePomsForPlugins(structure, pluginCandidates);

        return new Result(pluginCandidates, parentPomCandidates, directDeps, reactorPackagings);
    }

    /// Picks up explicitly-versioned plugins declared only in a sub-module that the root's
    /// effective model would miss.
    private void scanSubModulePomsForPlugins(ProjectStructure structure,
                                             Map<String, DefaultArtifact> pluginCandidates) throws Exception {
        List<Path> pomFiles = structure.pomFiles();
        for (int i = 1; i < pomFiles.size(); i++) {
            Document doc = ctx.documentBuilder().parse(pomFiles.get(i).toFile());
            Element project = doc.getDocumentElement();
            Element buildEl = Dom.directElement(project, "build");
            if (buildEl == null) continue;
            collectPluginsFromSection(Dom.directElement(buildEl, "plugins"),
                    pluginCandidates, structure.properties());
            Element pmEl = Dom.directElement(buildEl, "pluginManagement");
            if (pmEl != null) {
                collectPluginsFromSection(Dom.directElement(pmEl, "plugins"),
                        pluginCandidates, structure.properties());
            }
        }
    }

    /// Collects explicitly-versioned plugins from a DOM `<plugins>` element.
    private void collectPluginsFromSection(Element pluginsEl,
                                           Map<String, DefaultArtifact> pluginCandidates,
                                           Map<String, String> properties) {
        if (pluginsEl == null) return;
        NodeList pluginNodes = pluginsEl.getElementsByTagName("plugin");
        for (int i = 0; i < pluginNodes.getLength(); i++) {
            Element plugin = (Element) pluginNodes.item(i);
            String pg = Dom.directText(plugin, "groupId");
            String pa = Dom.directText(plugin, "artifactId");
            String pv = Dom.directText(plugin, "version");

            if (isBlank(pg)) pg = "org.apache.maven.plugins";
            if (isBlank(pa)) continue;

            if (!isBlank(pv) && pv.startsWith("${")) {
                String propName = pv.substring(2, pv.length() - 1);
                String resolved = properties.get(propName);
                if (!isBlank(resolved) && !resolved.startsWith("${")) pv = resolved;
            }
            if (isBlank(pv) || pv.startsWith("${")) continue;

            pluginCandidates.putIfAbsent(pg + ":" + pa,
                    new DefaultArtifact(pg, pa, "jar", pv));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
