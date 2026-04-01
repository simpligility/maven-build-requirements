package dev.chainguard;

import eu.maveniverse.maven.mima.context.Context;
import eu.maveniverse.maven.mima.context.ContextOverrides;
import eu.maveniverse.maven.mima.context.Runtime;
import eu.maveniverse.maven.mima.context.Runtimes;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
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
 * Analyzes a Maven project's dependencies and checks which are covered by Chainguard Libraries.
 *
 * <p>Run from the root directory of the Maven project to analyze. The project must have been
 * built with {@code mvn install} so that dependencies are resolved to the local Maven cache.
 *
 * <p>Uses <a href="https://maveniverse.eu/docs/mima/">mima</a> to bootstrap Maven Resolver
 * and <a href="https://maveniverse.eu/docs/toolbox/">toolbox</a> for dependency utilities.
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

    private static final Pattern PERCENTAGE_PATTERN = Pattern.compile("[0-9]+\\.[0-9]+%");
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[0-9;]*m");

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
        db.setErrorHandler(null); // suppress XML parser warnings

        // Discover all pom.xml files in the reactor
        List<Path> pomFiles = new ArrayList<>();
        pomFiles.add(rootPom);
        collectSubModulePoms(rootPom, pomFiles, db);

        // Pass 1: collect reactor coordinates, properties, and dependency management
        Set<String> reactorCoords = new LinkedHashSet<>();
        Map<String, String> properties = new LinkedHashMap<>();   // name -> value
        Map<String, String> managed = new LinkedHashMap<>();       // "groupId:artifactId" -> version
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
                // Built-in project properties
                properties.put("project.version", rootVersion);
                properties.put("project.groupId", rootGroupId);
                properties.put("revision", rootVersion); // common CI-friendly property
            }
            if (isBlank(groupId)) groupId = rootGroupId;
            if (isBlank(version)) version = rootVersion;

            // Collect <properties> from each pom
            Element propsEl = directElement(project, "properties");
            if (propsEl != null) {
                NodeList propNodes = propsEl.getChildNodes();
                for (int k = 0; k < propNodes.getLength(); k++) {
                    if (propNodes.item(k) instanceof Element propEl) {
                        String propName = propEl.getTagName();
                        String propValue = propEl.getTextContent().trim();
                        properties.putIfAbsent(propName, propValue);
                    }
                }
            }

            // Collect <dependencyManagement> versions
            Element dmEl = directElement(project, "dependencyManagement");
            if (dmEl != null) {
                Element depsEl = directElement(dmEl, "dependencies");
                if (depsEl != null) {
                    NodeList depNodes = depsEl.getElementsByTagName("dependency");
                    for (int k = 0; k < depNodes.getLength(); k++) {
                        Element dep = (Element) depNodes.item(k);
                        String dmGroupId = directText(dep, "groupId");
                        String dmArtifactId = directText(dep, "artifactId");
                        String dmVersion = directText(dep, "version");
                        if (!isBlank(dmGroupId) && !isBlank(dmArtifactId) && !isBlank(dmVersion)) {
                            managed.putIfAbsent(dmGroupId + ":" + dmArtifactId, dmVersion);
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

        // Pass 2: collect all dependency declarations, resolving versions from management/properties
        // Map: "groupId:artifactId" -> Dependency (first-seen wins for dedup)
        Map<String, Dependency> uniqueDeps = new LinkedHashMap<>();

        for (int i = 0; i < pomFiles.size(); i++) {
            Document doc = db.parse(pomFiles.get(i).toFile());
            Element project = doc.getDocumentElement();

            Element dependenciesEl = directElement(project, "dependencies");
            if (dependenciesEl == null) continue;

            NodeList depNodes = dependenciesEl.getElementsByTagName("dependency");
            for (int j = 0; j < depNodes.getLength(); j++) {
                Element dep = (Element) depNodes.item(j);
                String depGroupId = directText(dep, "groupId");
                String depArtifactId = directText(dep, "artifactId");
                String depVersion = directText(dep, "version");
                String depScope = directText(dep, "scope");
                String depType = directText(dep, "type");

                if (isBlank(depGroupId) || isBlank(depArtifactId)) continue;

                // Resolve version: property expression → managed → skip
                if (isBlank(depVersion)) {
                    depVersion = managed.get(depGroupId + ":" + depArtifactId);
                }
                if (!isBlank(depVersion) && depVersion.startsWith("${")) {
                    String propName = depVersion.substring(2, depVersion.length() - 1);
                    depVersion = properties.get(propName);
                    // Fall back to managed if property resolved to another expression or null
                    if (isBlank(depVersion) || depVersion.startsWith("${")) {
                        depVersion = managed.get(depGroupId + ":" + depArtifactId);
                    }
                }
                if (isBlank(depVersion)) continue; // still unresolvable — skip

                if (isBlank(depScope)) depScope = "compile";
                if (isBlank(depType)) depType = "jar";

                String depCoord = depGroupId + ":" + depArtifactId + ":" + depVersion;
                // Skip reactor modules
                if (reactorCoords.contains(depCoord)) continue;

                String key = depGroupId + ":" + depArtifactId;
                uniqueDeps.putIfAbsent(key,
                        new Dependency(new DefaultArtifact(depGroupId, depArtifactId, depType, depVersion), depScope));
            }
        }

        // ── Step 2: Resolve transitive dependencies using mima + maven-resolver ──
        print("Resolving dependencies with Maven Resolver...");

        Runtime runtime = Runtimes.INSTANCE.getRuntime();
        try (Context context = runtime.create(
                ContextOverrides.create().withUserSettings(true).build())) {

            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setDependencies(new ArrayList<>(uniqueDeps.values()));
            collectRequest.setRepositories(context.remoteRepositories());

            CollectResult collectResult = context.repositorySystem()
                    .collectDependencies(context.repositorySystemSession(), collectRequest);

            // Resolve artifacts to populate local file paths
            DependencyRequest dependencyRequest =
                    new DependencyRequest(collectResult.getRoot(), null);
            context.repositorySystem()
                    .resolveDependencies(context.repositorySystemSession(), dependencyRequest);

            // Traverse the resolved tree; group by effective scope
            // Map: scope -> (coords -> Artifact)
            Map<String, Map<String, Artifact>> byScope = new LinkedHashMap<>();

            PreorderNodeListGenerator nlg = new PreorderNodeListGenerator();
            collectResult.getRoot().accept(nlg);

            for (DependencyNode node : nlg.getNodes()) {
                if (node.getDependency() == null) continue; // root sentinel node
                Artifact artifact = node.getArtifact();
                String scope = node.getDependency().getScope();
                String coords = artifact.getGroupId() + ":" + artifact.getArtifactId()
                        + ":" + artifact.getVersion();

                if (reactorCoords.contains(coords)) continue;

                byScope.computeIfAbsent(scope, k -> new LinkedHashMap<>())
                        .putIfAbsent(coords, artifact);
            }

            // ── Step 3: Log resolved dependency list ─────────────────────────
            print("");
            print("Resolved dependencies:");
            print("");
            for (Map<String, Artifact> scopeArtifacts : byScope.values()) {
                for (Map.Entry<String, Artifact> entry : scopeArtifacts.entrySet()) {
                    print("  " + entry.getKey());
                    File file = entry.getValue().getFile();
                    print("  " + (file != null ? file.getAbsolutePath() : "NOT FOUND in local cache"));
                    print("");
                }
            }
            print("=======================================================================");
            print("");

            // ── Step 4: Run chainctl and collect results by scope ─────────────
            int total = 0;
            int covered = 0;
            Set<String> printedScopes = new LinkedHashSet<>();

            for (String scope : SCOPE_ORDER) {
                Map<String, Artifact> scopeArtifacts = byScope.get(scope);
                if (scopeArtifacts == null || scopeArtifacts.isEmpty()) continue;
                printedScopes.add(scope);
                print("=== " + scope + " ===");
                for (Map.Entry<String, Artifact> entry : scopeArtifacts.entrySet()) {
                    total++;
                    String percentage = runChainctl(entry.getValue().getFile());
                    if ("100.00%".equals(percentage)) covered++;
                    print("  " + entry.getKey() + " => " + percentage);
                }
                print("");
            }
            // Any scopes not in the predefined order
            for (Map.Entry<String, Map<String, Artifact>> entry : byScope.entrySet()) {
                if (printedScopes.contains(entry.getKey())) continue;
                print("=== " + entry.getKey() + " ===");
                for (Map.Entry<String, Artifact> artifactEntry : entry.getValue().entrySet()) {
                    total++;
                    String percentage = runChainctl(artifactEntry.getValue().getFile());
                    if ("100.00%".equals(percentage)) covered++;
                    print("  " + artifactEntry.getKey() + " => " + percentage);
                }
                print("");
            }

            // ── Summary ───────────────────────────────────────────────────────
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

    private String runChainctl(File jarFile) throws IOException, InterruptedException {
        if (jarFile == null || !jarFile.exists()) {
            return "NOT FOUND in local cache";
        }
        print("Running chainctl libraries verify " + jarFile.getAbsolutePath());
        ProcessBuilder pb = new ProcessBuilder("chainctl", "libraries", "verify", jarFile.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        process.waitFor();

        String clean = ANSI_PATTERN.matcher(output).replaceAll("");
        Matcher m = PERCENTAGE_PATTERN.matcher(clean);
        String percentage = "N/A";
        while (m.find()) {
            percentage = m.group();
        }
        return percentage;
    }

    /** Recursively discovers sub-module pom.xml files from a parent pom. */
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

    /** Returns the text content of a direct child element with the given name, or null. */
    private String directText(Element parent, String tagName) {
        Element el = directElement(parent, tagName);
        return el != null ? el.getTextContent().trim() : null;
    }

    /** Returns the first direct child element with the given name, or null. */
    private Element directElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el) {
                String name = el.getLocalName() != null ? el.getLocalName() : el.getTagName();
                if (tagName.equals(name)) return el;
            }
        }
        return null;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new ChainguardChecker()).execute(args));
    }
}
