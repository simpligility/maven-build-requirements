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
import com.simpligility.maven.util.Coords;
import com.simpligility.maven.util.Dom;
import eu.maveniverse.maven.mima.context.Context;
import eu.maveniverse.maven.mima.context.ContextOverrides;
import eu.maveniverse.maven.mima.context.Runtime;
import eu.maveniverse.maven.mima.context.Runtimes;
import eu.maveniverse.maven.mima.extensions.mmr.MavenModelReader;
import eu.maveniverse.maven.mima.extensions.mmr.ModelRequest;
import eu.maveniverse.maven.mima.extensions.mmr.ModelResponse;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
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
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/// Analyzes a Maven project's dependencies, plugins, and parent POMs and writes the resolved
/// artifact coordinates and a human-readable report to disk.
///
/// Run from the root directory of the Maven project to analyze.
///
/// Uses [mima](https://maveniverse.eu/docs/mima/) to bootstrap Maven Resolver and the MMR
/// extension to compute effective POM models (parent inheritance, plugin management).
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

    private ProgressLogger logger;

    private void print(String line) {
        logger.print(line);
    }

    private void printArtifactLine(String coords, Artifact artifact) {
        logger.printArtifactLine(coords, artifact);
    }

    @Override
    public Integer call() throws Exception {
        Path rootPom = projectDir.resolve("pom.xml");
        if (!Files.exists(rootPom)) {
            System.err.println("Error: no pom.xml found in " + projectDir.toAbsolutePath());
            return 1;
        }

        logger = new ProgressLogger(outputFile, showPaths);

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(null);

        // Read the root POM's GAV up front so we can include it in the header.
        // Parent fallback applies for groupId and version (Maven inheritance).
        Document headerDoc = db.parse(rootPom.toFile());
        Element headerProject = headerDoc.getDocumentElement();
        String headerGroupId = Dom.directText(headerProject, "groupId");
        String headerArtifactId = Dom.directText(headerProject, "artifactId");
        String headerVersion = Dom.directText(headerProject, "version");
        Element headerParent = Dom.directElement(headerProject, "parent");
        if (headerParent != null) {
            if (isBlank(headerGroupId)) headerGroupId = Dom.directText(headerParent, "groupId");
            if (isBlank(headerVersion)) headerVersion = Dom.directText(headerParent, "version");
        }

        print("Maven build requirements analysis");
        print("");
        print("Date: " + ZonedDateTime.now().format(DATE_FORMAT));
        print("Project: " + projectDir.toAbsolutePath().normalize());
        print("Group ID: " + headerGroupId);
        print("Artifact ID: " + headerArtifactId);
        print("Version: " + headerVersion);
        print("=======================================================================");
        print("");

        // ── Step 0: Maven environment ────────────────────────────────────────
        MavenEnvironmentDetector.Result env =
                new MavenEnvironmentDetector(logger, projectDir).detect();
        String mavenVersion = env.version();
        DefaultArtifact mavenDistroCandidate = env.distributionCandidate();

        // ── Step 1: Parse project POM files ──────────────────────────────────
        ProjectStructure structure = new ProjectStructureLoader(logger, db).load(rootPom);
        List<Path> pomFiles = structure.pomFiles();
        Set<String> reactorCoords = structure.reactorCoords();
        Map<String, String> properties = structure.properties();
        Map<String, Dependency> uniqueDeps = structure.declaredDependencies();

        // ── Step 2: Resolve everything via mima ───────────────────────────────
        print("Resolving with Maven Resolver...");

        Runtime runtime = Runtimes.INSTANCE.getRuntime();
        Path coordsFile = outputFile.resolveSibling("maven-build-requirements-coords.txt");
        try (Context context = runtime.create(
                ContextOverrides.create().withUserSettings(true).build())) {

            AnalysisContext analysisCtx = AnalysisContext.create(context, logger, projectDir);
            AnalysisResult analysis = new AnalysisResult();
            MavenModelReader modelReader = analysisCtx.modelReader();

            // ── 2a: Build effective POM via MavenModelReader (MMR extension) ──
            EffectiveModelBuilder.Result model =
                    new EffectiveModelBuilder(analysisCtx).build(structure);
            Map<String, DefaultArtifact> pluginCandidates = model.pluginCandidates();
            List<DefaultArtifact> parentPomCandidates = model.parentPomCandidates();
            Set<String> reactorPackagings = model.reactorPackagings();

            // ── 2b: Resolve transitive dependency tree ────────────────────────
            new DependencyResolver(analysisCtx).resolve(structure, model, analysis);

            // ── 2c0: Lifecycle-bound plugins from maven-core/default-bindings.xml
            new LifecyclePluginLoader(analysisCtx)
                    .load(mavenVersion, reactorPackagings, pluginCandidates);

            // ── 2c + 2d: Project parent POMs, plugin JARs, plugin dep trees ──
            new PluginAnalyzer(analysisCtx)
                    .analyze(pluginCandidates, parentPomCandidates, structure, analysis);

            // ── 2e + 2f + 2g: Extensions ──────────────────────────────────────
            new ExtensionAnalyzer(analysisCtx).analyze(structure, analysis);

            // ── 2h: Maven wrapper distribution ───────────────────────────────
            new MavenDistributionResolver(analysisCtx).resolve(mavenDistroCandidate, analysis);

            // ── Step 3: Print resolved artifact lists + write coords file ────
            new ReportWriter(logger, outputFile, coordsFile).write(analysis);
        }

        logger.close();
        return 0;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new BuildRequirementsAnalyzer()).execute(args));
    }
}
