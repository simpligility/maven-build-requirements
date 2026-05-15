package com.simpligility.maven.analysis;

import module java.base;
import module java.xml;

import com.simpligility.maven.util.Coords;
import com.simpligility.maven.util.Dom;
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

/// Steps 2e + 2f + 2g of the analysis: parses `.mvn/extensions.xml`, resolves each declared
/// extension's JAR, and walks each extension's parent POM lineage and transitive dependency
/// tree via MMR — analogous to [PluginAnalyzer] for build extensions.
///
/// No-op when no `.mvn/extensions.xml` exists. Extension parent POMs are deduped against
/// project and plugin parent POMs already in [AnalysisResult] so a shared parent only
/// surfaces once.
public final class ExtensionAnalyzer {

    private final AnalysisContext ctx;

    public ExtensionAnalyzer(AnalysisContext ctx) {
        this.ctx = ctx;
    }

    public void analyze(ProjectStructure structure, AnalysisResult result) throws Exception {
        Path extensionsXml = ctx.projectDir().resolve(".mvn").resolve("extensions.xml");
        if (!Files.exists(extensionsXml)) return;

        Map<String, DefaultArtifact> extensionCandidates =
                parseExtensions(extensionsXml, structure.properties());
        resolveExtensionJars(extensionCandidates, result);

        if (extensionCandidates.isEmpty()) return;

        List<DefaultArtifact> extensionParentPomCandidates = new ArrayList<>();
        ctx.logger().print("Loading extension dependency trees...");
        for (Map.Entry<String, DefaultArtifact> entry : extensionCandidates.entrySet()) {
            loadExtensionDepTree(entry.getKey(), entry.getValue(),
                    structure, result, extensionParentPomCandidates);
        }
        resolveExtensionParentPoms(extensionParentPomCandidates, result);
        ctx.logger().print("  Extension trees loaded — " + result.extensionDeps().size()
                + " dep(s), " + result.extensionParentPoms().size() + " extension parent POM(s).");
        ctx.logger().print("");
    }

    private Map<String, DefaultArtifact> parseExtensions(Path extensionsXml,
                                                         Map<String, String> properties) {
        Map<String, DefaultArtifact> extensionCandidates = new LinkedHashMap<>();
        ctx.logger().print("Collecting extensions from .mvn/extensions.xml...");
        try {
            Document extDoc = ctx.documentBuilder().parse(extensionsXml.toFile());
            Element extRoot = extDoc.getDocumentElement();
            NodeList extNodes = extRoot.getElementsByTagName("extension");
            for (int i = 0; i < extNodes.getLength(); i++) {
                Element ext = (Element) extNodes.item(i);
                String eg = Dom.directText(ext, "groupId");
                String ea = Dom.directText(ext, "artifactId");
                String ev = Dom.directText(ext, "version");
                if (isBlank(eg) || isBlank(ea) || isBlank(ev)) continue;
                if (ev.startsWith("${")) {
                    String propName = ev.substring(2, ev.length() - 1);
                    String resolved = properties.get(propName);
                    if (!isBlank(resolved) && !resolved.startsWith("${")) ev = resolved;
                }
                if (ev.startsWith("${")) continue;
                extensionCandidates.putIfAbsent(eg + ":" + ea,
                        new DefaultArtifact(eg, ea, "jar", ev));
            }
            ctx.logger().print("  Found " + extensionCandidates.size() + " extension(s).");
        } catch (Exception e) {
            ctx.logger().print("  Warning: could not parse extensions.xml: " + e.getMessage());
        }
        ctx.logger().print("");
        return extensionCandidates;
    }

    private void resolveExtensionJars(Map<String, DefaultArtifact> extensionCandidates,
                                      AnalysisResult result) {
        for (DefaultArtifact candidate : extensionCandidates.values()) {
            String coords = Coords.artifactCoords(candidate);
            try {
                ArtifactRequest req = new ArtifactRequest(
                        candidate, ctx.mima().remoteRepositories(), null);
                ArtifactResult res = ctx.mima().repositorySystem()
                        .resolveArtifact(ctx.mima().repositorySystemSession(), req);
                result.addExtension(coords, res.getArtifact());
            } catch (Exception e) {
                ctx.logger().print("  Warning: could not resolve extension " + coords + ": "
                        + e.getMessage());
            }
        }
    }

    private void loadExtensionDepTree(String extKey,
                                      DefaultArtifact extArtifact,
                                      ProjectStructure structure,
                                      AnalysisResult result,
                                      List<DefaultArtifact> extensionParentPomCandidates) {
        DefaultArtifact extPomArtifact = new DefaultArtifact(
                extArtifact.getGroupId(), extArtifact.getArtifactId(),
                "pom", extArtifact.getVersion());
        try {
            ModelResponse extResponse = ctx.modelReader().readModel(
                    ModelRequest.builder().setArtifact(extPomArtifact).build());
            Model extEffective = extResponse.getEffectiveModel();

            String extGav = extArtifact.getGroupId() + ":"
                    + extArtifact.getArtifactId() + ":" + extArtifact.getVersion();
            for (String modelId : extResponse.getLineage()) {
                if (isBlank(modelId) || modelId.equals(extGav)) continue;
                String[] parts = modelId.split(":");
                if (parts.length != 3) continue;
                String g = parts[0], a = parts[1], v = parts[2];
                if (isBlank(g) || isBlank(a) || isBlank(v)) continue;
                extensionParentPomCandidates.add(new DefaultArtifact(g, a, "pom", v));
            }

            List<Dependency> extDirectDeps = new ArrayList<>();
            for (org.apache.maven.model.Dependency d : extEffective.getDependencies()) {
                String g = d.getGroupId(), a = d.getArtifactId(), v = d.getVersion();
                String scope = isBlank(d.getScope()) ? "compile" : d.getScope();
                String type = isBlank(d.getType()) ? "jar" : d.getType();
                if (isBlank(g) || isBlank(a) || isBlank(v)) continue;
                if ("import".equals(scope)) continue;
                extDirectDeps.add(new Dependency(new DefaultArtifact(g, a, type, v), scope));
            }
            if (extDirectDeps.isEmpty()) return;

            CollectRequest extCollect = new CollectRequest();
            extCollect.setDependencies(extDirectDeps);
            extCollect.setRepositories(ctx.mima().remoteRepositories());
            CollectResult extCollectResult = ctx.mima().repositorySystem()
                    .collectDependencies(ctx.mima().repositorySystemSession(), extCollect);
            ctx.mima().repositorySystem().resolveDependencies(
                    ctx.mima().repositorySystemSession(),
                    new DependencyRequest(extCollectResult.getRoot(), null));
            PreorderNodeListGenerator extNlg = new PreorderNodeListGenerator();
            extCollectResult.getRoot().accept(extNlg);
            for (DependencyNode depNode : extNlg.getNodes()) {
                if (depNode.getDependency() == null) continue;
                Artifact depArtifact = depNode.getArtifact();
                String reactorKey = depArtifact.getGroupId() + ":"
                        + depArtifact.getArtifactId() + ":" + depArtifact.getVersion();
                if (structure.reactorCoords().contains(reactorKey)) continue;
                String depCoords = Coords.artifactCoords(depArtifact);
                if (result.extensions().containsKey(depCoords)) continue;
                result.addExtensionDep(depCoords, depArtifact);
            }
        } catch (Exception e) {
            ctx.logger().print("  Warning: could not load extension model for " + extKey + ": "
                    + e.getMessage());
        }
    }

    private void resolveExtensionParentPoms(List<DefaultArtifact> candidates, AnalysisResult result) {
        for (DefaultArtifact candidate : candidates) {
            String coords = Coords.artifactCoords(candidate);
            if (result.extensionParentPoms().containsKey(coords)) continue;
            if (result.parentPoms().containsKey(coords)) continue;
            if (result.pluginParentPoms().containsKey(coords)) continue;
            try {
                ArtifactRequest req = new ArtifactRequest(
                        candidate, ctx.mima().remoteRepositories(), null);
                ArtifactResult res = ctx.mima().repositorySystem()
                        .resolveArtifact(ctx.mima().repositorySystemSession(), req);
                result.addExtensionParentPom(coords, res.getArtifact());
            } catch (Exception e) {
                ctx.logger().print("  Warning: could not resolve extension parent POM "
                        + coords + ": " + e.getMessage());
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
