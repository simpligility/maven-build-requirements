package com.simpligility.maven.analysis;

import com.simpligility.maven.util.Coords;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;

/// Step 2h of the analysis: resolves the Maven binary distribution that the project's
/// wrapper declares (`distributionUrl` in `.mvn/wrapper/maven-wrapper.properties`), so it
/// shows up as a build requirement.
///
/// No-op when the project has no wrapper or its `distributionUrl` couldn't be parsed by
/// [MavenEnvironmentDetector].
public final class MavenDistributionResolver {

    private final AnalysisContext ctx;

    public MavenDistributionResolver(AnalysisContext ctx) {
        this.ctx = ctx;
    }

    public void resolve(DefaultArtifact distroCandidate, AnalysisResult result) {
        if (distroCandidate == null) return;
        String coords = Coords.artifactCoords(distroCandidate);
        try {
            ArtifactRequest req = new ArtifactRequest(
                    distroCandidate, ctx.mima().remoteRepositories(), null);
            ArtifactResult res = ctx.mima().repositorySystem()
                    .resolveArtifact(ctx.mima().repositorySystemSession(), req);
            result.setMavenDistribution(coords, res.getArtifact());
        } catch (Exception e) {
            ctx.logger().print("  Warning: could not resolve Maven distribution "
                    + coords + ": " + e.getMessage());
            ctx.logger().print("");
        }
    }
}
