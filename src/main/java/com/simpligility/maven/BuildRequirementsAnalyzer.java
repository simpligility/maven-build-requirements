package com.simpligility.maven;

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
import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.Callable;

/**
 * Analyzes a Maven project's dependencies, plugins, and parent POMs and writes the resolved
 * artifact coordinates and a human-readable report to disk.
 *
 * <p>Run from the root directory of the Maven project to analyze.
 *
 * <p>Uses <a href="https://maveniverse.eu/docs/mima/">mima</a> to bootstrap Maven Resolver
 * and the MMR extension to compute effective POM models (parent inheritance, plugin management).
 */
@Command(
        name = "build-requirements-analyzer",
        description = "Analyzes Maven project dependencies, plugins, and parent POMs.",
        mixinStandardHelpOptions = true
)
public class BuildRequirementsAnalyzer implements Callable<Integer> {

    @Option(names = {"-p", "--project"},
            description = "Project directory containing pom.xml (default: current directory)")
    private Path projectDir = Paths.get(".");

    @Option(names = {"-o", "--output"},
            description = "Output file (default: maven-build-requirements-results.txt)")
    private Path outputFile = Paths.get("maven-build-requirements-results.txt");

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

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(null);

        // Read the root POM's GAV up front so we can include it in the header.
        // Parent fallback applies for groupId and version (Maven inheritance).
        Document headerDoc = db.parse(rootPom.toFile());
        Element headerProject = headerDoc.getDocumentElement();
        String headerGroupId = directText(headerProject, "groupId");
        String headerArtifactId = directText(headerProject, "artifactId");
        String headerVersion = directText(headerProject, "version");
        Element headerParent = directElement(headerProject, "parent");
        if (headerParent != null) {
            if (isBlank(headerGroupId)) headerGroupId = directText(headerParent, "groupId");
            if (isBlank(headerVersion)) headerVersion = directText(headerParent, "version");
        }

        print("Maven build requirements analysis");
        print("");
        print("Date: " + new Date());
        print("Project: " + projectDir.toAbsolutePath().normalize());
        print("Group ID: " + headerGroupId);
        print("Artifact ID: " + headerArtifactId);
        print("Version: " + headerVersion);
        print("=======================================================================");
        print("");

        // ── Step 1: Parse project POM files ──────────────────────────────────
        print("Collecting project structure...");

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
        Path coordsFile = outputFile.resolveSibling("maven-build-requirements-coords.txt");
        TreeSet<String> allCoords = new TreeSet<>();

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
                String ac = artifactCoords(artifact);
                byScope.computeIfAbsent(scope, k -> new LinkedHashMap<>()).putIfAbsent(ac, artifact);
                allCoords.add(ac);
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
                    allCoords.add(coords);
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
                    allCoords.add(coords);
                } catch (Exception e) {
                    print("  Warning: could not resolve plugin " + coords + ": " + e.getMessage());
                }
            }

            // ── 2d: Load plugin dependency trees via MMR ─────────────────────
            print("Loading plugin dependency trees...");
            Map<String, Artifact> resolvedPluginDeps = new LinkedHashMap<>();
            List<DefaultArtifact> pluginParentPomCandidates = new ArrayList<>();

            for (Map.Entry<String, DefaultArtifact> pluginEntry : pluginCandidates.entrySet()) {
                DefaultArtifact pluginArtifact = pluginEntry.getValue();
                DefaultArtifact pluginPomArtifact = new DefaultArtifact(
                        pluginArtifact.getGroupId(), pluginArtifact.getArtifactId(),
                        "pom", pluginArtifact.getVersion());
                try {
                    ModelResponse pluginResponse = modelReader.readModel(
                            ModelRequest.builder().setArtifact(pluginPomArtifact).build());
                    Model pluginEffective = pluginResponse.getEffectiveModel();

                    String pluginGav = pluginArtifact.getGroupId() + ":"
                            + pluginArtifact.getArtifactId() + ":" + pluginArtifact.getVersion();
                    for (String modelId : pluginResponse.getLineage()) {
                        if (isBlank(modelId) || modelId.equals(pluginGav)) continue;
                        String[] parts = modelId.split(":");
                        if (parts.length != 3) continue;
                        String g = parts[0], a = parts[1], v = parts[2];
                        if (isBlank(g) || isBlank(a) || isBlank(v)) continue;
                        pluginParentPomCandidates.add(new DefaultArtifact(g, a, "pom", v));
                    }

                    List<Dependency> pluginDirectDeps = new ArrayList<>();
                    for (org.apache.maven.model.Dependency d : pluginEffective.getDependencies()) {
                        String g = d.getGroupId(), a = d.getArtifactId(), v = d.getVersion();
                        String scope = isBlank(d.getScope()) ? "compile" : d.getScope();
                        String type = isBlank(d.getType()) ? "jar" : d.getType();
                        if (isBlank(g) || isBlank(a) || isBlank(v)) continue;
                        if ("import".equals(scope)) continue;
                        pluginDirectDeps.add(new Dependency(new DefaultArtifact(g, a, type, v), scope));
                    }
                    if (!pluginDirectDeps.isEmpty()) {
                        CollectRequest pluginCollect = new CollectRequest();
                        pluginCollect.setDependencies(pluginDirectDeps);
                        pluginCollect.setRepositories(context.remoteRepositories());
                        CollectResult pluginCollectResult = context.repositorySystem()
                                .collectDependencies(context.repositorySystemSession(), pluginCollect);
                        context.repositorySystem().resolveDependencies(context.repositorySystemSession(),
                                new DependencyRequest(pluginCollectResult.getRoot(), null));
                        PreorderNodeListGenerator pluginNlg = new PreorderNodeListGenerator();
                        pluginCollectResult.getRoot().accept(pluginNlg);
                        for (DependencyNode depNode : pluginNlg.getNodes()) {
                            if (depNode.getDependency() == null) continue;
                            Artifact depArtifact = depNode.getArtifact();
                            String reactorKey = depArtifact.getGroupId() + ":"
                                    + depArtifact.getArtifactId() + ":" + depArtifact.getVersion();
                            if (reactorCoords.contains(reactorKey)) continue;
                            String depCoords = artifactCoords(depArtifact);
                            if (!resolvedPlugins.containsKey(depCoords)) {
                                resolvedPluginDeps.putIfAbsent(depCoords, depArtifact);
                                allCoords.add(depCoords);
                            }
                        }
                    }
                } catch (Exception e) {
                    print("  Warning: could not load plugin model for "
                            + pluginEntry.getKey() + ": " + e.getMessage());
                }
            }

            Map<String, Artifact> resolvedPluginParentPoms = new LinkedHashMap<>();
            for (DefaultArtifact candidate : pluginParentPomCandidates) {
                String coords = artifactCoords(candidate);
                if (resolvedPluginParentPoms.containsKey(coords)) continue;
                if (resolvedParentPoms.containsKey(coords)) continue;
                try {
                    ArtifactRequest req = new ArtifactRequest(candidate, context.remoteRepositories(), null);
                    ArtifactResult result = context.repositorySystem()
                            .resolveArtifact(context.repositorySystemSession(), req);
                    resolvedPluginParentPoms.put(coords, result.getArtifact());
                    allCoords.add(coords);
                } catch (Exception e) {
                    print("  Warning: could not resolve plugin parent POM " + coords + ": " + e.getMessage());
                }
            }
            print("  Plugin trees loaded — " + resolvedPluginDeps.size() + " dep(s), "
                    + resolvedPluginParentPoms.size() + " plugin parent POM(s).");
            print("");

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

            if (!resolvedPluginParentPoms.isEmpty()) {
                print("Plugin parent POMs:");
                print("");
                for (Map.Entry<String, Artifact> e : resolvedPluginParentPoms.entrySet()) {
                    print("  " + e.getKey());
                    File file = e.getValue().getFile();
                    print("  " + (file != null ? file.getAbsolutePath() : "NOT FOUND in local cache"));
                    print("");
                }
            }

            if (!resolvedPluginDeps.isEmpty()) {
                print("Plugin dependencies:");
                print("");
                for (Map.Entry<String, Artifact> e : resolvedPluginDeps.entrySet()) {
                    print("  " + e.getKey());
                    File file = e.getValue().getFile();
                    print("  " + (file != null ? file.getAbsolutePath() : "NOT FOUND in local cache"));
                    print("");
                }
            }

            print("=======================================================================");
            print("");

            int depCount = byScope.values().stream().mapToInt(Map::size).sum();
            int artifactCount = depCount + resolvedParentPoms.size() + resolvedPlugins.size()
                    + resolvedPluginParentPoms.size() + resolvedPluginDeps.size();
            print("Found " + artifactCount + " artifact(s)"
                    + " (" + depCount + " project deps, "
                    + resolvedParentPoms.size() + " project parent POMs, "
                    + resolvedPlugins.size() + " plugins, "
                    + resolvedPluginParentPoms.size() + " plugin parent POMs, "
                    + resolvedPluginDeps.size() + " plugin deps).");
            print("");

            Files.write(coordsFile, allCoords);
            print("Coordinates saved to:  " + coordsFile.toAbsolutePath());
            print("");

            print("Analysis results saved to: " + outputFile.toAbsolutePath());
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
        System.exit(new CommandLine(new BuildRequirementsAnalyzer()).execute(args));
    }
}
