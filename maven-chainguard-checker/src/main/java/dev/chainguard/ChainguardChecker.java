package dev.chainguard;

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
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Analyzes a Maven project's dependencies, plugins, and parent POMs and checks which are
 * covered by Chainguard Libraries.
 *
 * <p>Run from the root directory of the Maven project to analyze.
 *
 * <p>Uses <a href="https://maveniverse.eu/docs/mima/">mima</a> to bootstrap Maven Resolver
 * and the MMR extension to compute effective POM models (parent inheritance, plugin management).
 */
@Command(
        name = "chainguard-checker",
        description = "Analyzes Maven project dependencies and checks Chainguard library coverage.",
        mixinStandardHelpOptions = true
)
public class ChainguardChecker implements Callable<Integer> {

    @Option(names = {"-p", "--project"},
            description = "Project directory containing pom.xml (default: current directory)")
    private Path projectDir = Paths.get(".");

    @Option(names = {"-o", "--output"},
            description = "Output file (default: chainguard-check-java-results.txt)")
    private Path outputFile = Paths.get("chainguard-check-java-results.txt");

    @Option(names = {"-v", "--verify"},
            description = "Run chainctl libraries verify on resolved artifacts (default: false)")
    private boolean runVerify = false;

    @Option(names = {"-y", "--yes"},
            description = "Skip confirmation prompts when --verify is active")
    private boolean autoConfirm = false;

    private static final Pattern PERCENTAGE_PATTERN = Pattern.compile("[0-9]+\\.[0-9]+%");
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\[[0-9;]*m");

    private static final List<String> SCOPE_ORDER =
            List.of("compile", "runtime", "provided", "test", "system", "import");

    private PrintWriter writer;

    private void print(String line) {
        System.out.println(line);
        writer.println(line);
        writer.flush();
    }

    @Override
    public Integer call() throws Exception {
        Path rootPom = projectDir.resolve("pom.xml");
        if (!Files.exists(rootPom)) {
            System.err.println("Error: no pom.xml found in " + projectDir.toAbsolutePath());
            return 1;
        }

        writer = new PrintWriter(Files.newBufferedWriter(outputFile));

        print("Chainguard Coverage Analysis");
        print("Date: " + new Date());
        print("Project: " + projectDir.toAbsolutePath().normalize());
        print("=======================================================================");
        print("");

        // ── Step 1: Parse project POM files ──────────────────────────────────
        print("Collecting project structure...");

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(null);

        List<Path> pomFiles = new ArrayList<>();
        pomFiles.add(rootPom);
        collectSubModulePoms(rootPom, pomFiles, db);

        // Pass 1: collect reactor coordinates, properties, and dependency management
        Set<String> reactorCoords = new LinkedHashSet<>();
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, String> managed = new LinkedHashMap<>();
        String rootGroupId = null;
        String rootVersion = null;

        for (int i = 0; i < pomFiles.size(); i++) {
            Document doc = db.parse(pomFiles.get(i).toFile());
            Element project = doc.getDocumentElement();

            String groupId = directText(project, "groupId");
            String artifactId = directText(project, "artifactId");
            String version = directText(project, "version");

            Element parent = directElement(project, "parent");
            if (parent != null) {
                if (isBlank(groupId)) groupId = directText(parent, "groupId");
                if (isBlank(version)) version = directText(parent, "version");
            }

            if (i == 0) {
                rootGroupId = groupId;
                rootVersion = version;
                properties.put("project.version", rootVersion);
                properties.put("project.groupId", rootGroupId);
                properties.put("revision", rootVersion);
            }
            if (isBlank(groupId)) groupId = rootGroupId;
            if (isBlank(version)) version = rootVersion;

            Element propsEl = directElement(project, "properties");
            if (propsEl != null) {
                NodeList propNodes = propsEl.getChildNodes();
                for (int k = 0; k < propNodes.getLength(); k++) {
                    if (propNodes.item(k) instanceof Element propEl) {
                        properties.putIfAbsent(propEl.getTagName(), propEl.getTextContent().trim());
                    }
                }
            }

            Element dmEl = directElement(project, "dependencyManagement");
            if (dmEl != null) {
                Element depsEl = directElement(dmEl, "dependencies");
                if (depsEl != null) {
                    NodeList depNodes = depsEl.getElementsByTagName("dependency");
                    for (int k = 0; k < depNodes.getLength(); k++) {
                        Element dep = (Element) depNodes.item(k);
                        String dmG = directText(dep, "groupId");
                        String dmA = directText(dep, "artifactId");
                        String dmV = directText(dep, "version");
                        if (!isBlank(dmG) && !isBlank(dmA) && !isBlank(dmV)) {
                            managed.putIfAbsent(dmG + ":" + dmA, dmV);
                        }
                    }
                }
            }

            if (!isBlank(artifactId) && !isBlank(groupId) && !isBlank(version)) {
                String coord = groupId + ":" + artifactId + ":" + version;
                reactorCoords.add(coord);
                print("  Reactor module: " + coord);
            }
        }
        print("");

        // Pass 2: collect declared dependency coordinates
        Map<String, Dependency> uniqueDeps = new LinkedHashMap<>();

        for (Path pomFile : pomFiles) {
            Document doc = db.parse(pomFile.toFile());
            Element project = doc.getDocumentElement();

            Element dependenciesEl = directElement(project, "dependencies");
            if (dependenciesEl == null) continue;

            NodeList depNodes = dependenciesEl.getElementsByTagName("dependency");
            for (int j = 0; j < depNodes.getLength(); j++) {
                Element dep = (Element) depNodes.item(j);
                String depG = directText(dep, "groupId");
                String depA = directText(dep, "artifactId");
                String depV = directText(dep, "version");
                String depScope = directText(dep, "scope");
                String depType = directText(dep, "type");

                if (isBlank(depG) || isBlank(depA)) continue;

                if (isBlank(depV)) depV = managed.get(depG + ":" + depA);
                if (!isBlank(depV) && depV.startsWith("${")) {
                    String propName = depV.substring(2, depV.length() - 1);
                    depV = properties.get(propName);
                    if (isBlank(depV) || depV.startsWith("${")) depV = managed.get(depG + ":" + depA);
                }
                if (isBlank(depV)) continue;

                if (isBlank(depScope)) depScope = "compile";
                if (isBlank(depType)) depType = "jar";

                String depCoord = depG + ":" + depA + ":" + depV;
                if (reactorCoords.contains(depCoord)) continue;

                uniqueDeps.putIfAbsent(depG + ":" + depA,
                        new Dependency(new DefaultArtifact(depG, depA, depType, depV), depScope));
            }
        }

        // ── Step 2: Resolve everything via mima ───────────────────────────────
        print("Resolving with Maven Resolver...");

        Runtime runtime = Runtimes.INSTANCE.getRuntime();
        try (Context context = runtime.create(
                ContextOverrides.create().withUserSettings(true).build())) {

            // ── 2a: Build effective POM via MavenModelReader (MMR extension) ──
            Map<String, DefaultArtifact> pluginCandidates = new LinkedHashMap<>();
            List<DefaultArtifact> parentPomCandidates = new ArrayList<>();
            List<Dependency> directDeps = new ArrayList<>();

            print("Building effective POM model...");
            MavenModelReader modelReader = new MavenModelReader(context);
            try {
                ModelResponse rootResponse = modelReader.readModel(
                        ModelRequest.builder().setPomFile(rootPom).build());

                Model effectiveModel = rootResponse.getEffectiveModel();
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

                for (String modelId : rootResponse.getLineage()) {
                    if (isBlank(modelId)) continue;
                    String[] parts = modelId.split(":");
                    if (parts.length != 3) continue;
                    String g = parts[0], a = parts[1], v = parts[2];
                    if (isBlank(g) || isBlank(a) || isBlank(v)) continue;
                    String coord = g + ":" + a + ":" + v;
                    if (!reactorCoords.contains(coord)) {
                        parentPomCandidates.add(new DefaultArtifact(g, a, "pom", v));
                    }
                }

                // Collect direct deps from effective model — this resolves BOM-managed versions
                // that the DOM-based Pass 2 cannot follow.
                for (org.apache.maven.model.Dependency d : effectiveModel.getDependencies()) {
                    String g = d.getGroupId();
                    String a = d.getArtifactId();
                    String v = d.getVersion();
                    String scope = isBlank(d.getScope()) ? "compile" : d.getScope();
                    String type = isBlank(d.getType()) ? "jar" : d.getType();
                    if (isBlank(g) || isBlank(a) || isBlank(v)) continue;
                    if ("import".equals(scope)) continue;
                    String coord = g + ":" + a + ":" + v;
                    if (reactorCoords.contains(coord)) continue;
                    directDeps.add(new Dependency(new DefaultArtifact(g, a, type, v), scope));
                }

                print("  Effective model built — "
                        + pluginCandidates.size() + " plugin(s) found, "
                        + parentPomCandidates.size() + " parent POM(s) in lineage, "
                        + directDeps.size() + " direct dep(s).");

            } catch (Exception e) {
                print("  Warning: effective model could not be built: " + e.getMessage());
                print("  Falling back to DOM-based dependency and plugin collection.");
            }
            print("");

            // ── 2b: Resolve transitive dependency tree ────────────────────────
            // Use effective model deps when available (handles BOM imports); fall back to DOM-parsed deps.
            List<Dependency> depsForResolution = directDeps.isEmpty()
                    ? new ArrayList<>(uniqueDeps.values())
                    : directDeps;
            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setDependencies(depsForResolution);
            collectRequest.setRepositories(context.remoteRepositories());

            CollectResult collectResult = context.repositorySystem()
                    .collectDependencies(context.repositorySystemSession(), collectRequest);

            context.repositorySystem()
                    .resolveDependencies(context.repositorySystemSession(),
                            new DependencyRequest(collectResult.getRoot(), null));

            Map<String, Map<String, Artifact>> byScope = new LinkedHashMap<>();
            PreorderNodeListGenerator nlg = new PreorderNodeListGenerator();
            collectResult.getRoot().accept(nlg);

            for (DependencyNode node : nlg.getNodes()) {
                if (node.getDependency() == null) continue;
                Artifact artifact = node.getArtifact();
                String scope = node.getDependency().getScope();
                String reactorKey = artifact.getGroupId() + ":" + artifact.getArtifactId()
                        + ":" + artifact.getVersion();
                if (reactorCoords.contains(reactorKey)) continue;
                byScope.computeIfAbsent(scope, k -> new LinkedHashMap<>())
                        .putIfAbsent(artifactCoords(artifact), artifact);
            }

            // Also scan sub-module poms for any explicitly versioned plugins that might not
            // appear in the root's effective model (e.g., declared only in a sub-module).
            for (int i = 1; i < pomFiles.size(); i++) {
                Document doc = db.parse(pomFiles.get(i).toFile());
                Element project = doc.getDocumentElement();
                Element buildEl = directElement(project, "build");
                if (buildEl != null) {
                    collectPluginsFromSection(directElement(buildEl, "plugins"),
                            pluginCandidates, properties);
                    Element pmEl = directElement(buildEl, "pluginManagement");
                    if (pmEl != null) {
                        collectPluginsFromSection(directElement(pmEl, "plugins"),
                                pluginCandidates, properties);
                    }
                }
            }

            // ── 2c: Resolve parent POM files and plugin JARs ─────────────────
            Map<String, Artifact> resolvedParentPoms = new LinkedHashMap<>();
            for (DefaultArtifact candidate : parentPomCandidates) {
                String coords = artifactCoords(candidate);
                try {
                    ArtifactRequest req = new ArtifactRequest(candidate, context.remoteRepositories(), null);
                    ArtifactResult result = context.repositorySystem()
                            .resolveArtifact(context.repositorySystemSession(), req);
                    resolvedParentPoms.put(coords, result.getArtifact());
                } catch (Exception e) {
                    print("  Warning: could not resolve parent POM " + coords + ": " + e.getMessage());
                }
            }

            Map<String, Artifact> resolvedPlugins = new LinkedHashMap<>();
            for (Map.Entry<String, DefaultArtifact> entry : pluginCandidates.entrySet()) {
                DefaultArtifact candidate = entry.getValue();
                String coords = artifactCoords(candidate);
                try {
                    ArtifactRequest req = new ArtifactRequest(candidate, context.remoteRepositories(), null);
                    ArtifactResult result = context.repositorySystem()
                            .resolveArtifact(context.repositorySystemSession(), req);
                    resolvedPlugins.put(coords, result.getArtifact());
                } catch (Exception e) {
                    print("  Warning: could not resolve plugin " + coords + ": " + e.getMessage());
                }
            }

            // ── Step 3: Print resolved artifact lists ─────────────────────────
            print("Resolved dependencies:");
            print("");
            for (Map<String, Artifact> scopeArtifacts : byScope.values()) {
                for (Map.Entry<String, Artifact> e : scopeArtifacts.entrySet()) {
                    print("  " + e.getKey());
                    File file = e.getValue().getFile();
                    print("  " + (file != null ? file.getAbsolutePath() : "NOT FOUND in local cache"));
                    print("");
                }
            }

            if (!resolvedParentPoms.isEmpty()) {
                print("Parent POMs:");
                print("");
                for (Map.Entry<String, Artifact> e : resolvedParentPoms.entrySet()) {
                    print("  " + e.getKey());
                    File file = e.getValue().getFile();
                    print("  " + (file != null ? file.getAbsolutePath() : "NOT FOUND in local cache"));
                    print("");
                }
            }

            if (!resolvedPlugins.isEmpty()) {
                print("Plugins:");
                print("");
                for (Map.Entry<String, Artifact> e : resolvedPlugins.entrySet()) {
                    print("  " + e.getKey());
                    File file = e.getValue().getFile();
                    print("  " + (file != null ? file.getAbsolutePath() : "NOT FOUND in local cache"));
                    print("");
                }
            }

            print("=======================================================================");
            print("");

            int depCount = byScope.values().stream().mapToInt(Map::size).sum();
            int artifactCount = depCount + resolvedParentPoms.size() + resolvedPlugins.size();
            print("Found " + artifactCount + " artifact(s) to verify"
                    + " (" + depCount + " dependencies, "
                    + resolvedParentPoms.size() + " parent POMs, "
                    + resolvedPlugins.size() + " plugins).");
            print("");

            if (!runVerify) {
                print("Run with --verify to check Chainguard coverage for these artifacts.");
                print("Analysis results saved to: " + outputFile.toAbsolutePath());
                writer.close();
                return 0;
            }

            if (!confirmProceed(artifactCount)) {
                print("Verification skipped. Analysis results saved to: " + outputFile.toAbsolutePath());
                writer.close();
                return 0;
            }
            print("");

            // ── Step 4: chainctl verification ─────────────────────────────────
            int total = 0;
            int covered = 0;
            Set<String> printedScopes = new LinkedHashSet<>();

            for (String scope : SCOPE_ORDER) {
                Map<String, Artifact> scopeArtifacts = byScope.get(scope);
                if (scopeArtifacts == null || scopeArtifacts.isEmpty()) continue;
                printedScopes.add(scope);
                print("=== " + scope + " ===");
                for (Map.Entry<String, Artifact> e : scopeArtifacts.entrySet()) {
                    total++;
                    String pct = runChainctl(e.getValue().getFile());
                    if ("100.00%".equals(pct)) covered++;
                    print("  " + e.getKey() + " => " + pct);
                }
                print("");
            }
            for (Map.Entry<String, Map<String, Artifact>> entry : byScope.entrySet()) {
                if (printedScopes.contains(entry.getKey())) continue;
                print("=== " + entry.getKey() + " ===");
                for (Map.Entry<String, Artifact> ae : entry.getValue().entrySet()) {
                    total++;
                    String pct = runChainctl(ae.getValue().getFile());
                    if ("100.00%".equals(pct)) covered++;
                    print("  " + ae.getKey() + " => " + pct);
                }
                print("");
            }

            if (!resolvedParentPoms.isEmpty()) {
                print("=== parent-poms ===");
                for (Map.Entry<String, Artifact> e : resolvedParentPoms.entrySet()) {
                    total++;
                    String pct = runChainctl(e.getValue().getFile());
                    if ("100.00%".equals(pct)) covered++;
                    print("  " + e.getKey() + " => " + pct);
                }
                print("");
            }

            if (!resolvedPlugins.isEmpty()) {
                print("=== plugins ===");
                for (Map.Entry<String, Artifact> e : resolvedPlugins.entrySet()) {
                    total++;
                    String pct = runChainctl(e.getValue().getFile());
                    if ("100.00%".equals(pct)) covered++;
                    print("  " + e.getKey() + " => " + pct);
                }
                print("");
            }

            print("=======================================================================");
            print("Summary");
            print("  Total artifacts checked  : " + total);
            print("  Chainguard covered (100%): " + covered);
            if (total > 0) {
                print("  Overall coverage         : " + (covered * 100 / total) + "%");
            }
            print("=======================================================================");
            print("");
            print("Full results saved to: " + outputFile.toAbsolutePath());
        }

        writer.close();
        return 0;
    }

    /** Collects explicitly-versioned plugins from a DOM {@code <plugins>} element. */
    private void collectPluginsFromSection(Element pluginsEl,
                                           Map<String, DefaultArtifact> pluginCandidates,
                                           Map<String, String> properties) {
        if (pluginsEl == null) return;
        NodeList pluginNodes = pluginsEl.getElementsByTagName("plugin");
        for (int i = 0; i < pluginNodes.getLength(); i++) {
            Element plugin = (Element) pluginNodes.item(i);
            String pg = directText(plugin, "groupId");
            String pa = directText(plugin, "artifactId");
            String pv = directText(plugin, "version");

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

    private boolean confirmProceed(int artifactCount) {
        if (autoConfirm) return true;
        if (!askYesNo("Proceed with chainctl verification?")) return false;
        System.out.println("Warning: verifying " + artifactCount
                + " artifact(s) may take several minutes.");
        return askYesNo("Are you sure you want to continue?");
    }

    private boolean askYesNo(String prompt) {
        System.out.print(prompt + " (y/n): ");
        System.out.flush();
        Console console = System.console();
        if (console == null) {
            System.out.println("y [no interactive terminal, proceeding automatically]");
            return true;
        }
        String response = console.readLine();
        return response != null
                && (response.equalsIgnoreCase("y") || response.equalsIgnoreCase("yes"));
    }

    private String runChainctl(File jarFile) throws IOException, InterruptedException {
        if (jarFile == null || !jarFile.exists()) return "NOT FOUND in local cache";
        print("Running chainctl libraries verify " + jarFile.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder("chainctl", "libraries", "verify", jarFile.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        process.waitFor();

        String clean = ANSI_PATTERN.matcher(output).replaceAll("");
        Matcher m = PERCENTAGE_PATTERN.matcher(clean);
        String percentage = "N/A";
        while (m.find()) percentage = m.group();
        return percentage;
    }

    private void collectSubModulePoms(Path pomFile, List<Path> result, DocumentBuilder db) throws Exception {
        Document doc = db.parse(pomFile.toFile());
        Element project = doc.getDocumentElement();
        Element modules = directElement(project, "modules");
        if (modules == null) return;
        NodeList moduleNodes = modules.getElementsByTagName("module");
        for (int i = 0; i < moduleNodes.getLength(); i++) {
            String moduleName = moduleNodes.item(i).getTextContent().trim();
            Path modulePom = pomFile.getParent().resolve(moduleName).resolve("pom.xml");
            if (Files.exists(modulePom) && !result.contains(modulePom)) {
                result.add(modulePom);
                collectSubModulePoms(modulePom, result, db);
            }
        }
    }

    private String directText(Element parent, String tagName) {
        Element el = directElement(parent, tagName);
        return el != null ? el.getTextContent().trim() : null;
    }

    private Element directElement(Element parent, String tagName) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el) {
                String name = el.getLocalName() != null ? el.getLocalName() : el.getTagName();
                if (tagName.equals(name)) return el;
            }
        }
        return null;
    }

    private String artifactCoords(Artifact artifact) {
        String ext = artifact.getExtension();
        String classifier = artifact.getClassifier();
        StringBuilder sb = new StringBuilder();
        sb.append(artifact.getGroupId()).append(':')
          .append(artifact.getArtifactId()).append(':')
          .append(isBlank(ext) ? "jar" : ext);
        if (!isBlank(classifier)) {
            sb.append(':').append(classifier);
        }
        sb.append(':').append(artifact.getVersion());
        return sb.toString();
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new ChainguardChecker()).execute(args));
    }
}
