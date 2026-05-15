package com.simpligility.maven.analysis;

import module java.base;

import com.simpligility.maven.util.Coords;
import eu.maveniverse.maven.mima.extensions.mmr.ModelRequest;
import eu.maveniverse.maven.mima.extensions.mmr.ModelResponse;
import org.apache.maven.model.Model;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;

/// Steps 2c + 2d of the analysis: resolves project parent POMs and plugin JARs, then builds
/// each plugin's effective POM via MMR to collect its parent POM lineage and transitive
/// dependency tree. Everything lands in [AnalysisResult].
///
/// Project parent POMs (the lineage walked from each reactor module up) are deduped against
/// plugin parent POMs so a parent POM that appears in both lists only shows once. Plugin
/// transitive deps are deduped against the resolved plugin set so a plugin that's also a
/// plugin dependency of another plugin isn't double-listed.
public final class PluginAnalyzer {

    private final AnalysisContext ctx;

    public PluginAnalyzer(AnalysisContext ctx) {
        this.ctx = ctx;
    }

    public void analyze(Map<String, DefaultArtifact> pluginCandidates,
                        List<DefaultArtifact> parentPomCandidates,
                        ProjectStructure structure,
                        AnalysisResult result) {

        resolveProjectParentPoms(parentPomCandidates, result);
        resolvePluginJars(pluginCandidates, result);

        ctx.logger().print("Loading plugin dependency trees...");
        List<DefaultArtifact> pluginParentPomCandidates = new ArrayList<>();
        for (Map.Entry<String, DefaultArtifact> pluginEntry : pluginCandidates.entrySet()) {
            loadPluginDepTree(pluginEntry.getKey(), pluginEntry.getValue(),
                    structure, result, pluginParentPomCandidates);
        }
        resolvePluginParentPoms(pluginParentPomCandidates, result);
        ctx.logger().print("  Plugin trees loaded — " + result.pluginDeps().size() + " dep(s), "
                + result.pluginParentPoms().size() + " plugin parent POM(s).");
        ctx.logger().print("");
    }

    private void resolveProjectParentPoms(List<DefaultArtifact> candidates, AnalysisResult result) {
        for (DefaultArtifact candidate : candidates) {
            String coords = Coords.artifactCoords(candidate);
            try {
                ArtifactRequest req = new ArtifactRequest(
                        candidate, ctx.mima().remoteRepositories(), null);
                ArtifactResult res = ctx.mima().repositorySystem()
                        .resolveArtifact(ctx.mima().repositorySystemSession(), req);
                result.addParentPom(coords, res.getArtifact());
            } catch (Exception e) {
                ctx.logger().print("  Warning: could not resolve parent POM " + coords + ": "
                        + e.getMessage());
            }
        }
    }

    private void resolvePluginJars(Map<String, DefaultArtifact> pluginCandidates, AnalysisResult result) {
        for (DefaultArtifact candidate : pluginCandidates.values()) {
            String coords = Coords.artifactCoords(candidate);
            try {
                ArtifactRequest req = new ArtifactRequest(
                        candidate, ctx.mima().remoteRepositories(), null);
                ArtifactResult res = ctx.mima().repositorySystem()
                        .resolveArtifact(ctx.mima().repositorySystemSession(), req);
                result.addPlugin(coords, res.getArtifact());
            } catch (Exception e) {
                ctx.logger().print("  Warning: could not resolve plugin " + coords + ": "
                        + e.getMessage());
            }
        }
    }

    private void loadPluginDepTree(String pluginKey,
                                   DefaultArtifact pluginArtifact,
                                   ProjectStructure structure,
                                   AnalysisResult result,
                                   List<DefaultArtifact> pluginParentPomCandidates) {
        DefaultArtifact pluginPomArtifact = new DefaultArtifact(
                pluginArtifact.getGroupId(), pluginArtifact.getArtifactId(),
                "pom", pluginArtifact.getVersion());
        try {
            ModelResponse pluginResponse = ctx.modelReader().readModel(
                    ModelRequest.builder().setArtifact(pluginPomArtifact).build());
            Model pluginEffective = pluginResponse.getEffectiveModel();

            String pluginGav = pluginArtifact.getGroupId() + ":"
                    + pluginArtifact.getArtifactId() + ":" + pluginArtifact.getVersion();
            for (String modelId : pluginResponse.getLineage()) {
                if (isBlank(modelId) || modelId.equals(pluginGav)) continue;
                String[] parts = modelId.split(":");
                if (parts.length != 3) continue;
                String g = parts[0], a = parts[1], v = parts[2];
                if (isBlank(g) || isBlank(a) || isBlank(v)) continue;
                pluginParentPomCandidates.add(new DefaultArtifact(g, a, "pom", v));
            }

            List<Dependency> pluginDirectDeps = new ArrayList<>();
            for (org.apache.maven.model.Dependency d : pluginEffective.getDependencies()) {
                String g = d.getGroupId(), a = d.getArtifactId(), v = d.getVersion();
                String scope = isBlank(d.getScope()) ? "compile" : d.getScope();
                String type = isBlank(d.getType()) ? "jar" : d.getType();
                if (isBlank(g) || isBlank(a) || isBlank(v)) continue;
                if ("import".equals(scope)) continue;
                pluginDirectDeps.add(new Dependency(new DefaultArtifact(g, a, type, v), scope));
            }
            if (pluginDirectDeps.isEmpty()) return;

            CollectRequest pluginCollect = new CollectRequest();
            pluginCollect.setDependencies(pluginDirectDeps);
            pluginCollect.setRepositories(ctx.mima().remoteRepositories());
            CollectResult pluginCollectResult = ctx.mima().repositorySystem()
                    .collectDependencies(ctx.mima().repositorySystemSession(), pluginCollect);
            ctx.mima().repositorySystem().resolveDependencies(
                    ctx.mima().repositorySystemSession(),
                    new DependencyRequest(pluginCollectResult.getRoot(), null));
            PreorderNodeListGenerator pluginNlg = new PreorderNodeListGenerator();
            pluginCollectResult.getRoot().accept(pluginNlg);
            for (DependencyNode depNode : pluginNlg.getNodes()) {
                if (depNode.getDependency() == null) continue;
                Artifact depArtifact = depNode.getArtifact();
                String reactorKey = depArtifact.getGroupId() + ":"
                        + depArtifact.getArtifactId() + ":" + depArtifact.getVersion();
                if (structure.reactorCoords().contains(reactorKey)) continue;
                String depCoords = Coords.artifactCoords(depArtifact);
                if (result.plugins().containsKey(depCoords)) continue;
                result.addPluginDep(depCoords, depArtifact);
            }
        } catch (Exception e) {
            ctx.logger().print("  Warning: could not load plugin model for " + pluginKey + ": "
                    + e.getMessage());
        }
    }

    private void resolvePluginParentPoms(List<DefaultArtifact> candidates, AnalysisResult result) {
        for (DefaultArtifact candidate : candidates) {
            String coords = Coords.artifactCoords(candidate);
            if (result.pluginParentPoms().containsKey(coords)) continue;
            if (result.parentPoms().containsKey(coords)) continue;
            try {
                ArtifactRequest req = new ArtifactRequest(
                        candidate, ctx.mima().remoteRepositories(), null);
                ArtifactResult res = ctx.mima().repositorySystem()
                        .resolveArtifact(ctx.mima().repositorySystemSession(), req);
                result.addPluginParentPom(coords, res.getArtifact());
            } catch (Exception e) {
                ctx.logger().print("  Warning: could not resolve plugin parent POM " + coords + ": "
                        + e.getMessage());
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
