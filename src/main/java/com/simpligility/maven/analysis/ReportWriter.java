package com.simpligility.maven.analysis;

import module java.base;

import org.eclipse.aether.artifact.Artifact;

/// Step 3 of the analysis: prints the structured artifact lists and the summary block, then
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
        summaryLines(result).forEach(logger::print);
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

    private static List<String> summaryLines(AnalysisResult result) {
        int mavenDistCount = result.mavenDistribution() != null ? 1 : 0;
        List<String> summary = new ArrayList<>();
        summary.add("Found " + result.totalArtifactCount() + " artifact(s):");
        summary.add("- " + result.dependencyCount() + " project deps");
        summary.add("- " + result.parentPoms().size() + " project parent POMs");
        summary.add("- " + result.plugins().size() + " plugins");
        summary.add("- " + result.pluginParentPoms().size() + " plugin parent POMs");
        summary.add("- " + result.pluginDeps().size() + " plugin deps");
        summary.add("- " + result.extensions().size() + " extensions");
        summary.add("- " + result.extensionParentPoms().size() + " extension parent POMs");
        summary.add("- " + result.extensionDeps().size() + " extension deps");
        if (mavenDistCount > 0) {
            summary.add("- " + mavenDistCount + " Maven distribution");
        }
        return summary;
    }
}
