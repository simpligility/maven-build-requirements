package com.simpligility.maven;

import module java.base;
import module java.xml;

import com.simpligility.maven.analysis.AnalysisContext;
import com.simpligility.maven.analysis.AnalysisResult;
import com.simpligility.maven.analysis.DependencyResolver;
import com.simpligility.maven.analysis.EffectiveModelBuilder;
import com.simpligility.maven.analysis.ExtensionAnalyzer;
import com.simpligility.maven.analysis.LifecyclePluginLoader;
import com.simpligility.maven.analysis.MavenDistributionResolver;
import com.simpligility.maven.analysis.MavenEnvironmentDetector;
import com.simpligility.maven.analysis.PluginAnalyzer;
import com.simpligility.maven.analysis.ProgressLogger;
import com.simpligility.maven.analysis.ProjectStructure;
import com.simpligility.maven.analysis.ProjectStructureLoader;
import com.simpligility.maven.analysis.ReportWriter;
import com.simpligility.maven.util.Dom;
import eu.maveniverse.maven.mima.context.Context;
import eu.maveniverse.maven.mima.context.ContextOverrides;
import eu.maveniverse.maven.mima.context.Runtime;
import eu.maveniverse.maven.mima.context.Runtimes;
import org.eclipse.aether.artifact.DefaultArtifact;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/// CLI entry point. Parses the picocli options, opens the [ProgressLogger] and a mima
/// [Context], then runs each analyzer in sequence and hands the aggregate [AnalysisResult]
/// to [ReportWriter]. Each analyzer lives in `com.simpligility.maven.analysis`; this class
/// only orchestrates.
@Command(
        name = "build-requirements-analyzer",
        description = "Analyzes Maven project dependencies, plugins, and parent POMs.",
        mixinStandardHelpOptions = true
)
public class BuildRequirementsAnalyzer implements Callable<Integer> {

    @Option(names = {"-p", "--project"},
            description = "Project directory containing pom.xml (default: current directory)")
    private Path projectDir = Path.of(".");

    @Option(names = {"-o", "--output"},
            description = "Output file (default: maven-build-requirements-results.txt)")
    private Path outputFile = Path.of("maven-build-requirements-results.txt");

    @Option(names = {"--paths"},
            description = "Append each artifact's local repository path after its GAV (default: false)")
    private boolean showPaths = false;

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH);

    @Override
    public Integer call() throws Exception {
        Path rootPom = projectDir.resolve("pom.xml");
        if (!Files.exists(rootPom)) {
            System.err.println("Error: no pom.xml found in " + projectDir.toAbsolutePath());
            return 1;
        }

        try (ProgressLogger logger = new ProgressLogger(outputFile, showPaths)) {
            printHeader(logger, rootPom);

            MavenEnvironmentDetector.Result env =
                    new MavenEnvironmentDetector(logger, projectDir).detect();

            ProjectStructure structure = new ProjectStructureLoader(logger, Dom.newDocumentBuilder())
                    .load(rootPom);

            logger.print("Resolving with Maven Resolver...");
            Runtime runtime = Runtimes.INSTANCE.getRuntime();
            Path coordsFile = outputFile.resolveSibling("maven-build-requirements-coords.txt");
            try (Context context = runtime.create(
                    ContextOverrides.create().withUserSettings(true).build())) {

                AnalysisContext ctx = AnalysisContext.create(context, logger, projectDir);
                AnalysisResult analysis = new AnalysisResult();

                EffectiveModelBuilder.Result model =
                        new EffectiveModelBuilder(ctx).build(structure);
                new DependencyResolver(ctx).resolve(structure, model, analysis);
                new LifecyclePluginLoader(ctx).load(
                        env.version(), model.reactorPackagings(), model.pluginCandidates());
                new PluginAnalyzer(ctx).analyze(
                        model.pluginCandidates(), model.parentPomCandidates(), structure, analysis);
                new ExtensionAnalyzer(ctx).analyze(structure, analysis);
                new MavenDistributionResolver(ctx).resolve(env.distributionCandidate(), analysis);

                new ReportWriter(logger, outputFile, coordsFile).write(analysis);
            }
        }
        return 0;
    }

    /// Reads the root POM's GAV (with parent fallback per Maven inheritance rules) and prints
    /// the report banner. Done before [MavenEnvironmentDetector] runs so users see what's
    /// being analyzed before the slower resolution steps start.
    private void printHeader(ProgressLogger logger, Path rootPom) throws Exception {
        Document headerDoc = Dom.newDocumentBuilder().parse(rootPom.toFile());
        Element project = headerDoc.getDocumentElement();
        String groupId = Dom.directText(project, "groupId");
        String artifactId = Dom.directText(project, "artifactId");
        String version = Dom.directText(project, "version");
        Element parent = Dom.directElement(project, "parent");
        if (parent != null) {
            if (isBlank(groupId)) groupId = Dom.directText(parent, "groupId");
            if (isBlank(version)) version = Dom.directText(parent, "version");
        }
        logger.print("Maven build requirements analysis");
        logger.print("");
        logger.print("Date: " + ZonedDateTime.now().format(DATE_FORMAT));
        logger.print("Project: " + projectDir.toAbsolutePath().normalize());
        logger.print("Group ID: " + groupId);
        logger.print("Artifact ID: " + artifactId);
        logger.print("Version: " + version);
        logger.print("=======================================================================");
        logger.print("");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new BuildRequirementsAnalyzer()).execute(args));
    }
}
