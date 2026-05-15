package com.simpligility.maven.analysis;

import module java.base;

import com.simpligility.maven.util.Coords;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;

/// Step 2b of the analysis: feeds the effective-model direct dependencies into Maven Resolver
/// and walks the resulting transitive tree, grouping artifacts by scope and adding them to
/// the [AnalysisResult]. Falls back to the DOM-collected dependency list from
/// [ProjectStructureLoader] when the effective-model didn't surface any direct deps.
public final class DependencyResolver {

    private final AnalysisContext ctx;

    public DependencyResolver(AnalysisContext ctx) {
        this.ctx = ctx;
    }

    public void resolve(ProjectStructure structure,
                        EffectiveModelBuilder.Result model,
                        AnalysisResult result) throws Exception {

        List<Dependency> depsForResolution = model.directDeps().isEmpty()
                ? new ArrayList<>(structure.declaredDependencies().values())
                : model.directDeps();

        CollectRequest collectRequest = new CollectRequest();
        collectRequest.setDependencies(depsForResolution);
        collectRequest.setRepositories(ctx.mima().remoteRepositories());

        CollectResult collectResult = ctx.mima().repositorySystem()
                .collectDependencies(ctx.mima().repositorySystemSession(), collectRequest);

        ctx.mima().repositorySystem().resolveDependencies(ctx.mima().repositorySystemSession(),
                new DependencyRequest(collectResult.getRoot(), null));

        PreorderNodeListGenerator nlg = new PreorderNodeListGenerator();
        collectResult.getRoot().accept(nlg);

        for (DependencyNode node : nlg.getNodes()) {
            if (node.getDependency() == null) continue;
            Artifact artifact = node.getArtifact();
            String scope = node.getDependency().getScope();
            String reactorKey = artifact.getGroupId() + ":" + artifact.getArtifactId()
                    + ":" + artifact.getVersion();
            if (structure.reactorCoords().contains(reactorKey)) continue;
            result.addDependency(scope, Coords.artifactCoords(artifact), artifact);
        }
    }
}
