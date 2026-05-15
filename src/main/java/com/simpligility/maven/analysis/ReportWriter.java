package com.simpligility.maven.analysis;

import module java.base;

import org.eclipse.aether.artifact.Artifact;

/// Step 3 of the analysis: prints the structured artifact lists and the summary line, then
/// writes the sorted coords file. Reads from [AnalysisResult]; prints via the
/// [ProgressLogger] in [AnalysisContext] so the report file gets the same content the user
/// sees on stdout.
public final class ReportWriter {

    private final ProgressLogger logger;
    private final Path outputFile;
    private final Path coordsFile;

    public ReportWriter(ProgressLogger logger, Path outputFile, Path coordsFile) {
        this.logger = logger;
        this.outputFile = outputFile;
        this.coordsFile = coordsFile;
    }

    public void write(AnalysisResult result) throws IOException {
        printSectionOfScopedDeps("Resolved dependencies", result.dependenciesByScope());
        printSection("Parent POMs", result.parentPoms());
        printSection("Plugins", result.plugins());
        printSection("Plugin parent POMs", result.pluginParentPoms());
        printSection("Plugin dependencies", result.pluginDeps());
        printSection("Extensions", result.extensions());
        printSection("Extension parent POMs", result.extensionParentPoms());
        printSection("Extension dependencies", result.extensionDeps());
        printMavenDistribution(result);

        logger.print("=======================================================================");
        logger.print("");
        logger.print(summaryLine(result));
        logger.print("");

        Files.write(coordsFile, result.allCoords());
        logger.print("Coordinates saved to:  " + coordsFile.toAbsolutePath());
        logger.print("");
        logger.print("Analysis results saved to: " + outputFile.toAbsolutePath());
    }

    private void printSectionOfScopedDeps(String title,
                                          Map<String, Map<String, Artifact>> byScope) {
        logger.print(title + ":");
        logger.print("");
        byScope.values().forEach(scope -> scope.forEach(logger::printArtifactLine));
        logger.print("");
    }

    private void printSection(String title, Map<String, Artifact> artifacts) {
        if (artifacts.isEmpty()) return;
        logger.print(title + ":");
        logger.print("");
        artifacts.forEach(logger::printArtifactLine);
        logger.print("");
    }

    private void printMavenDistribution(AnalysisResult result) {
        if (result.mavenDistribution() == null) return;
        logger.print("Maven distribution:");
        logger.print("");
        logger.printArtifactLine(result.mavenDistributionCoords(), result.mavenDistribution());
        logger.print("");
    }

    private static String summaryLine(AnalysisResult result) {
        int mavenDistCount = result.mavenDistribution() != null ? 1 : 0;
        StringBuilder summary = new StringBuilder("Found ")
                .append(result.totalArtifactCount()).append(" artifact(s) (")
                .append(result.dependencyCount()).append(" project deps, ")
                .append(result.parentPoms().size()).append(" project parent POMs, ")
                .append(result.plugins().size()).append(" plugins, ")
                .append(result.pluginParentPoms().size()).append(" plugin parent POMs, ")
                .append(result.pluginDeps().size()).append(" plugin deps, ")
                .append(result.extensions().size()).append(" extensions, ")
                .append(result.extensionParentPoms().size()).append(" extension parent POMs, ")
                .append(result.extensionDeps().size()).append(" extension deps");
        if (mavenDistCount > 0) {
            summary.append(", ").append(mavenDistCount).append(" Maven distribution");
        }
        summary.append(").");
        return summary.toString();
    }
}
