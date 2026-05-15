package com.simpligility.maven;

import module java.base;
import module java.xml;

import com.simpligility.maven.analysis.AnalysisContext;
import com.simpligility.maven.analysis.AnalysisResult;
import com.simpligility.maven.analysis.DependencyResolver;
import com.simpligility.maven.analysis.EffectiveModelBuilder;
import com.simpligility.maven.analysis.LifecyclePluginLoader;
import com.simpligility.maven.analysis.MavenEnvironmentDetector;
import com.simpligility.maven.analysis.PluginAnalyzer;
import com.simpligility.maven.analysis.ProgressLogger;
import com.simpligility.maven.analysis.ProjectStructure;
import com.simpligility.maven.analysis.ProjectStructureLoader;
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
        TreeSet<String> allCoords = new TreeSet<>();

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

            // ── 2e: Collect extensions from .mvn/extensions.xml ──────────────
            Map<String, DefaultArtifact> extensionCandidates = new LinkedHashMap<>();
            Path extensionsXml = projectDir.resolve(".mvn").resolve("extensions.xml");
            if (Files.exists(extensionsXml)) {
                print("Collecting extensions from .mvn/extensions.xml...");
                try {
                    Document extDoc = db.parse(extensionsXml.toFile());
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
                    print("  Found " + extensionCandidates.size() + " extension(s).");
                } catch (Exception e) {
                    print("  Warning: could not parse extensions.xml: " + e.getMessage());
                }
                print("");
            }

            // ── 2f: Resolve extension JARs ───────────────────────────────────
            Map<String, Artifact> resolvedExtensions = new LinkedHashMap<>();
            for (DefaultArtifact candidate : extensionCandidates.values()) {
                String coords = Coords.artifactCoords(candidate);
                try {
                    ArtifactRequest req = new ArtifactRequest(candidate, context.remoteRepositories(), null);
                    ArtifactResult result = context.repositorySystem()
                            .resolveArtifact(context.repositorySystemSession(), req);
                    resolvedExtensions.put(coords, result.getArtifact());
                    allCoords.add(coords);
                } catch (Exception e) {
                    print("  Warning: could not resolve extension " + coords + ": " + e.getMessage());
                }
            }

            // ── 2g: Load extension dependency trees via MMR ──────────────────
            Map<String, Artifact> resolvedExtensionDeps = new LinkedHashMap<>();
            List<DefaultArtifact> extensionParentPomCandidates = new ArrayList<>();
            if (!extensionCandidates.isEmpty()) {
                print("Loading extension dependency trees...");
                for (Map.Entry<String, DefaultArtifact> extEntry : extensionCandidates.entrySet()) {
                    DefaultArtifact extArtifact = extEntry.getValue();
                    DefaultArtifact extPomArtifact = new DefaultArtifact(
                            extArtifact.getGroupId(), extArtifact.getArtifactId(),
                            "pom", extArtifact.getVersion());
                    try {
                        ModelResponse extResponse = modelReader.readModel(
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
                        if (!extDirectDeps.isEmpty()) {
                            CollectRequest extCollect = new CollectRequest();
                            extCollect.setDependencies(extDirectDeps);
                            extCollect.setRepositories(context.remoteRepositories());
                            CollectResult extCollectResult = context.repositorySystem()
                                    .collectDependencies(context.repositorySystemSession(), extCollect);
                            context.repositorySystem().resolveDependencies(context.repositorySystemSession(),
                                    new DependencyRequest(extCollectResult.getRoot(), null));
                            PreorderNodeListGenerator extNlg = new PreorderNodeListGenerator();
                            extCollectResult.getRoot().accept(extNlg);
                            for (DependencyNode depNode : extNlg.getNodes()) {
                                if (depNode.getDependency() == null) continue;
                                Artifact depArtifact = depNode.getArtifact();
                                String reactorKey = depArtifact.getGroupId() + ":"
                                        + depArtifact.getArtifactId() + ":" + depArtifact.getVersion();
                                if (reactorCoords.contains(reactorKey)) continue;
                                String depCoords = Coords.artifactCoords(depArtifact);
                                if (!resolvedExtensions.containsKey(depCoords)) {
                                    resolvedExtensionDeps.putIfAbsent(depCoords, depArtifact);
                                    allCoords.add(depCoords);
                                }
                            }
                        }
                    } catch (Exception e) {
                        print("  Warning: could not load extension model for "
                                + extEntry.getKey() + ": " + e.getMessage());
                    }
                }
            }

            Map<String, Artifact> resolvedExtensionParentPoms = new LinkedHashMap<>();
            for (DefaultArtifact candidate : extensionParentPomCandidates) {
                String coords = Coords.artifactCoords(candidate);
                if (resolvedExtensionParentPoms.containsKey(coords)) continue;
                if (analysis.parentPoms().containsKey(coords)) continue;
                if (analysis.pluginParentPoms().containsKey(coords)) continue;
                try {
                    ArtifactRequest req = new ArtifactRequest(candidate, context.remoteRepositories(), null);
                    ArtifactResult result = context.repositorySystem()
                            .resolveArtifact(context.repositorySystemSession(), req);
                    resolvedExtensionParentPoms.put(coords, result.getArtifact());
                    allCoords.add(coords);
                } catch (Exception e) {
                    print("  Warning: could not resolve extension parent POM " + coords + ": " + e.getMessage());
                }
            }
            if (!extensionCandidates.isEmpty()) {
                print("  Extension trees loaded — " + resolvedExtensionDeps.size() + " dep(s), "
                        + resolvedExtensionParentPoms.size() + " extension parent POM(s).");
                print("");
            }

            // ── 2h: Maven wrapper distribution ───────────────────────────────
            // If a wrapper distributionUrl was identified in Step 0, resolve the
            // configured binary distribution from the repository so it shows up
            // as a build requirement.
            Artifact resolvedMavenDistribution = null;
            String mavenDistributionCoords = null;
            if (mavenDistroCandidate != null) {
                String coords = Coords.artifactCoords(mavenDistroCandidate);
                try {
                    ArtifactRequest req = new ArtifactRequest(
                            mavenDistroCandidate, context.remoteRepositories(), null);
                    ArtifactResult result = context.repositorySystem()
                            .resolveArtifact(context.repositorySystemSession(), req);
                    resolvedMavenDistribution = result.getArtifact();
                    mavenDistributionCoords = coords;
                    allCoords.add(coords);
                } catch (Exception e) {
                    print("  Warning: could not resolve Maven distribution "
                            + coords + ": " + e.getMessage());
                    print("");
                }
            }

            // ── Step 3: Print resolved artifact lists ─────────────────────────
            print("Resolved dependencies:");
            print("");
            analysis.dependenciesByScope().values().forEach(
                    scope -> scope.forEach(logger::printArtifactLine));
            print("");

            if (!analysis.parentPoms().isEmpty()) {
                print("Parent POMs:");
                print("");
                analysis.parentPoms().forEach(this::printArtifactLine);
                print("");
            }

            if (!analysis.plugins().isEmpty()) {
                print("Plugins:");
                print("");
                analysis.plugins().forEach(this::printArtifactLine);
                print("");
            }

            if (!analysis.pluginParentPoms().isEmpty()) {
                print("Plugin parent POMs:");
                print("");
                analysis.pluginParentPoms().forEach(this::printArtifactLine);
                print("");
            }

            if (!analysis.pluginDeps().isEmpty()) {
                print("Plugin dependencies:");
                print("");
                analysis.pluginDeps().forEach(this::printArtifactLine);
                print("");
            }

            if (!resolvedExtensions.isEmpty()) {
                print("Extensions:");
                print("");
                resolvedExtensions.forEach(this::printArtifactLine);
                print("");
            }

            if (!resolvedExtensionParentPoms.isEmpty()) {
                print("Extension parent POMs:");
                print("");
                resolvedExtensionParentPoms.forEach(this::printArtifactLine);
                print("");
            }

            if (!resolvedExtensionDeps.isEmpty()) {
                print("Extension dependencies:");
                print("");
                resolvedExtensionDeps.forEach(this::printArtifactLine);
                print("");
            }

            if (resolvedMavenDistribution != null) {
                print("Maven distribution:");
                print("");
                printArtifactLine(mavenDistributionCoords, resolvedMavenDistribution);
                print("");
            }

            print("=======================================================================");
            print("");

            int depCount = analysis.dependencyCount();
            int mavenDistCount = resolvedMavenDistribution != null ? 1 : 0;
            int artifactCount = depCount + analysis.parentPoms().size() + analysis.plugins().size()
                    + analysis.pluginParentPoms().size() + analysis.pluginDeps().size()
                    + resolvedExtensions.size() + resolvedExtensionParentPoms.size()
                    + resolvedExtensionDeps.size() + mavenDistCount;
            StringBuilder summary = new StringBuilder("Found ").append(artifactCount)
                    .append(" artifact(s) (")
                    .append(depCount).append(" project deps, ")
                    .append(analysis.parentPoms().size()).append(" project parent POMs, ")
                    .append(analysis.plugins().size()).append(" plugins, ")
                    .append(analysis.pluginParentPoms().size()).append(" plugin parent POMs, ")
                    .append(analysis.pluginDeps().size()).append(" plugin deps, ")
                    .append(resolvedExtensions.size()).append(" extensions, ")
                    .append(resolvedExtensionParentPoms.size()).append(" extension parent POMs, ")
                    .append(resolvedExtensionDeps.size()).append(" extension deps");
            if (mavenDistCount > 0) {
                summary.append(", ").append(mavenDistCount).append(" Maven distribution");
            }
            summary.append(").");
            print(summary.toString());
            print("");

            allCoords.addAll(analysis.allCoords());
            Files.write(coordsFile, allCoords);
            print("Coordinates saved to:  " + coordsFile.toAbsolutePath());
            print("");

            print("Analysis results saved to: " + outputFile.toAbsolutePath());
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
