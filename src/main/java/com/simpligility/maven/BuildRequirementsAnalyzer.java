package com.simpligility.maven;

import module java.base;
import module java.xml;

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

    private PrintWriter writer;

    private void print(String line) {
        System.out.println(line);
        writer.println(line);
        writer.flush();
    }

    private void printArtifactLine(String coords, Artifact artifact) {
        if (showPaths) {
            File file = artifact.getFile();
            String pathStr = file != null ? file.getAbsolutePath() : "NOT FOUND in local cache";
            print("  " + coords + "  " + pathStr);
        } else {
            print("  " + coords);
        }
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
        print("Date: " + ZonedDateTime.now().format(DATE_FORMAT));
        print("Project: " + projectDir.toAbsolutePath().normalize());
        print("Group ID: " + headerGroupId);
        print("Artifact ID: " + headerArtifactId);
        print("Version: " + headerVersion);
        print("=======================================================================");
        print("");

        // ── Step 0: Maven environment ────────────────────────────────────────
        // Determine the Maven version we will use for lifecycle bindings and
        // (when the wrapper is configured) for the binary distribution listing.
        // Prefer the wrapper-defined version; otherwise fall back to `mvn` on PATH.
        print("Detecting Maven environment...");
        String mavenVersion = null;
        String mavenVersionSource = null;
        DefaultArtifact mavenDistroCandidate = null;

        Path wrapperProperties = projectDir.resolve(".mvn").resolve("wrapper")
                .resolve("maven-wrapper.properties");
        if (Files.exists(wrapperProperties)) {
            try (var in = Files.newInputStream(wrapperProperties)) {
                Properties wp = new Properties();
                wp.load(in);
                String distUrl = wp.getProperty("distributionUrl");
                if (!isBlank(distUrl)) {
                    DefaultArtifact d = artifactFromMavenUrl(distUrl);
                    if (d != null) {
                        mavenVersion = d.getVersion();
                        mavenVersionSource = "Maven wrapper (.mvn/wrapper/maven-wrapper.properties)";
                        mavenDistroCandidate = d;
                    } else {
                        print("  Warning: could not parse distributionUrl: " + distUrl);
                    }
                }
            } catch (Exception e) {
                print("  Warning: could not parse maven-wrapper.properties: " + e.getMessage());
            }
        }

        if (mavenVersion == null) {
            try {
                ProcessBuilder pb = new ProcessBuilder("mvn", "--version");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String out = new String(p.getInputStream().readAllBytes());
                p.waitFor();
                String marker = "Apache Maven ";
                int idx = out.indexOf(marker);
                if (idx >= 0) {
                    int s = idx + marker.length();
                    int e = s;
                    while (e < out.length() && !Character.isWhitespace(out.charAt(e))) e++;
                    String v = out.substring(s, e).trim();
                    if (!v.isEmpty()) {
                        mavenVersion = v;
                        mavenVersionSource = "'mvn --version' on PATH";
                    }
                }
            } catch (Exception _) {
                // fall through to the no-version-detected case
            }
        }

        if (mavenVersion == null) {
            print("  Maven version: unknown — no wrapper configured and no 'mvn' on PATH.");
            print("  Lifecycle-bound plugins will not be included in the analysis.");
        } else {
            print("  Maven version: " + mavenVersion + " (source: " + mavenVersionSource + ")");
        }
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

            print("Building effective POM model(s)...");
            MavenModelReader modelReader = new MavenModelReader(context);
            int modelsBuilt = 0;
            Set<String> reactorPackagings = new LinkedHashSet<>();
            for (Path pomFile : pomFiles) {
                try {
                    ModelResponse response = modelReader.readModel(
                            ModelRequest.builder().setPomFile(pomFile).build());

                    Model effectiveModel = response.getEffectiveModel();
                    String pkg = effectiveModel.getPackaging();
                    reactorPackagings.add(isBlank(pkg) ? "jar" : pkg);
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

                    for (String modelId : response.getLineage()) {
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

                    modelsBuilt++;
                } catch (Exception e) {
                    print("  Warning: could not build effective model for "
                            + projectDir.toAbsolutePath().relativize(pomFile.toAbsolutePath())
                            + ": " + e.getMessage());
                }
            }
            print("  Effective model built for " + modelsBuilt + " of " + pomFiles.size()
                    + " module(s) — " + pluginCandidates.size() + " plugin(s), "
                    + parentPomCandidates.size() + " parent POM(s) in lineage, "
                    + directDeps.size() + " direct dep(s).");
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

            // ── 2c0: Lifecycle-bound plugins from maven-core/default-bindings.xml
            // Maven binds a set of plugins to each packaging's default lifecycle
            // (e.g. compiler/jar/surefire for "jar" packaging). These bindings live
            // in maven-core's META-INF/plexus/default-bindings.xml and are NOT part
            // of the effective POM model, so we need to load them explicitly.
            if (mavenVersion != null && !reactorPackagings.isEmpty()) {
                print("Resolving lifecycle-bound plugins from maven-core " + mavenVersion + "...");
                DefaultArtifact mavenCoreArtifact = new DefaultArtifact(
                        "org.apache.maven", "maven-core", "jar", mavenVersion);
                try {
                    ArtifactRequest req = new ArtifactRequest(
                            mavenCoreArtifact, context.remoteRepositories(), null);
                    ArtifactResult result = context.repositorySystem()
                            .resolveArtifact(context.repositorySystemSession(), req);
                    File coreJar = result.getArtifact().getFile();
                    Map<String, List<DefaultArtifact>> bindingsByPackaging = new LinkedHashMap<>();
                    try (JarFile jar = new JarFile(coreJar)) {
                        JarEntry entry = jar.getJarEntry("META-INF/plexus/default-bindings.xml");
                        if (entry == null) {
                            print("  Warning: default-bindings.xml not found in maven-core JAR.");
                        } else {
                            try (InputStream is = jar.getInputStream(entry)) {
                                Document bindingsDoc = db.parse(is);
                                NodeList components = bindingsDoc.getElementsByTagName("component");
                                for (int i = 0; i < components.getLength(); i++) {
                                    Element comp = (Element) components.item(i);
                                    String role = directText(comp, "role");
                                    if (!"org.apache.maven.lifecycle.mapping.LifecycleMapping".equals(role)) continue;
                                    String roleHint = directText(comp, "role-hint");
                                    if (isBlank(roleHint)) continue;
                                    Element config = directElement(comp, "configuration");
                                    if (config == null) continue;
                                    List<DefaultArtifact> plugins = new ArrayList<>();
                                    // Newer schema (3.9+): configuration > lifecycles > lifecycle > phases.
                                    Element lifecycles = directElement(config, "lifecycles");
                                    if (lifecycles != null) {
                                        NodeList lifecycleList = lifecycles.getElementsByTagName("lifecycle");
                                        for (int j = 0; j < lifecycleList.getLength(); j++) {
                                            Element lifecycle = (Element) lifecycleList.item(j);
                                            Element phases = directElement(lifecycle, "phases");
                                            if (phases != null) extractPluginsFromPhases(phases, plugins);
                                        }
                                    } else {
                                        // Older schema: configuration > phases.
                                        Element phases = directElement(config, "phases");
                                        if (phases != null) extractPluginsFromPhases(phases, plugins);
                                    }
                                    bindingsByPackaging.put(roleHint, plugins);
                                }
                            }
                            print("  Loaded lifecycle bindings for " + bindingsByPackaging.size()
                                    + " packaging type(s).");
                        }
                    }
                    int added = 0;
                    for (String packaging : reactorPackagings) {
                        List<DefaultArtifact> plugins = bindingsByPackaging.get(packaging);
                        if (plugins == null) continue;
                        for (DefaultArtifact p : plugins) {
                            String key = p.getGroupId() + ":" + p.getArtifactId();
                            if (!pluginCandidates.containsKey(key)) {
                                pluginCandidates.put(key, p);
                                added++;
                            }
                        }
                    }
                    print("  Added " + added + " lifecycle plugin(s) for packagings: "
                            + reactorPackagings);
                } catch (Exception e) {
                    print("  Warning: could not load maven-core " + mavenVersion + ": "
                            + e.getMessage());
                }
                print("");
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
                        String eg = directText(ext, "groupId");
                        String ea = directText(ext, "artifactId");
                        String ev = directText(ext, "version");
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
                String coords = artifactCoords(candidate);
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
                                String depCoords = artifactCoords(depArtifact);
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
                String coords = artifactCoords(candidate);
                if (resolvedExtensionParentPoms.containsKey(coords)) continue;
                if (resolvedParentPoms.containsKey(coords)) continue;
                if (resolvedPluginParentPoms.containsKey(coords)) continue;
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
                String coords = artifactCoords(mavenDistroCandidate);
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
            byScope.values().forEach(scope -> scope.forEach(this::printArtifactLine));
            print("");

            if (!resolvedParentPoms.isEmpty()) {
                print("Parent POMs:");
                print("");
                resolvedParentPoms.forEach(this::printArtifactLine);
                print("");
            }

            if (!resolvedPlugins.isEmpty()) {
                print("Plugins:");
                print("");
                resolvedPlugins.forEach(this::printArtifactLine);
                print("");
            }

            if (!resolvedPluginParentPoms.isEmpty()) {
                print("Plugin parent POMs:");
                print("");
                resolvedPluginParentPoms.forEach(this::printArtifactLine);
                print("");
            }

            if (!resolvedPluginDeps.isEmpty()) {
                print("Plugin dependencies:");
                print("");
                resolvedPluginDeps.forEach(this::printArtifactLine);
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

            int depCount = byScope.values().stream().mapToInt(Map::size).sum();
            int mavenDistCount = resolvedMavenDistribution != null ? 1 : 0;
            int artifactCount = depCount + resolvedParentPoms.size() + resolvedPlugins.size()
                    + resolvedPluginParentPoms.size() + resolvedPluginDeps.size()
                    + resolvedExtensions.size() + resolvedExtensionParentPoms.size()
                    + resolvedExtensionDeps.size() + mavenDistCount;
            StringBuilder summary = new StringBuilder("Found ").append(artifactCount)
                    .append(" artifact(s) (")
                    .append(depCount).append(" project deps, ")
                    .append(resolvedParentPoms.size()).append(" project parent POMs, ")
                    .append(resolvedPlugins.size()).append(" plugins, ")
                    .append(resolvedPluginParentPoms.size()).append(" plugin parent POMs, ")
                    .append(resolvedPluginDeps.size()).append(" plugin deps, ")
                    .append(resolvedExtensions.size()).append(" extensions, ")
                    .append(resolvedExtensionParentPoms.size()).append(" extension parent POMs, ")
                    .append(resolvedExtensionDeps.size()).append(" extension deps");
            if (mavenDistCount > 0) {
                summary.append(", ").append(mavenDistCount).append(" Maven distribution");
            }
            summary.append(").");
            print(summary.toString());
            print("");

            Files.write(coordsFile, allCoords);
            print("Coordinates saved to:  " + coordsFile.toAbsolutePath());
            print("");

            print("Analysis results saved to: " + outputFile.toAbsolutePath());
        }

        writer.close();
        return 0;
    }

    /// Collects explicitly-versioned plugins from a DOM `<plugins>` element.
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

    /// Extracts plugin coordinates from a `<phases>` element in `default-bindings.xml`.
    /// Each phase child element's text is one or more comma-separated mojo coordinates
    /// (`g:a:v:goal`); we keep the GAV and dedupe per phases block by `g:a`.
    private void extractPluginsFromPhases(Element phases, List<DefaultArtifact> out) {
        NodeList children = phases.getChildNodes();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < children.getLength(); i++) {
            if (!(children.item(i) instanceof Element phaseEl)) continue;
            String txt = phaseEl.getTextContent().trim();
            for (String coord : txt.split(",")) {
                coord = coord.trim();
                if (coord.isEmpty()) continue;
                String[] parts = coord.split(":");
                if (parts.length < 3) continue;
                String g = parts[0], a = parts[1], v = parts[2];
                if (isBlank(g) || isBlank(a) || isBlank(v)) continue;
                String key = g + ":" + a;
                if (seen.add(key)) {
                    out.add(new DefaultArtifact(g, a, "jar", v));
                }
            }
        }
    }

    /// Parses a Maven repository URL (e.g. the wrapper's distributionUrl) into a Maven
    /// artifact. Expects the canonical `<groupPath>/<artifactId>/<version>/<filename>` suffix
    /// and strips common repo-base prefixes (e.g. `maven2/`). Returns `null` if the URL does
    /// not match the expected layout.
    private DefaultArtifact artifactFromMavenUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isEmpty()) return null;
            if (path.startsWith("/")) path = path.substring(1);
            String[] segs = path.split("/");

            // Strip well-known Maven repository path prefixes so the remaining
            // segments are groupPath / artifactId / version / filename.
            int start = 0;
            if (segs.length > 0 && "maven2".equals(segs[0])) start = 1;
            else if (segs.length > 1 && "repository".equals(segs[0])) start = 2;

            if (segs.length - start < 4) return null;
            String filename = segs[segs.length - 1];
            String version = segs[segs.length - 2];
            String artifactId = segs[segs.length - 3];
            String[] groupSegs = Arrays.copyOfRange(segs, start, segs.length - 3);
            String groupId = String.join(".", groupSegs);
            if (isBlank(groupId) || isBlank(artifactId) || isBlank(version)) return null;

            String prefix = artifactId + "-" + version;
            String classifier = null;
            String ext;
            if (filename.startsWith(prefix + "-")) {
                String rest = filename.substring(prefix.length() + 1);
                int dot = rest.lastIndexOf('.');
                if (dot <= 0) return null;
                classifier = rest.substring(0, dot);
                ext = rest.substring(dot + 1);
            } else if (filename.startsWith(prefix + ".")) {
                ext = filename.substring(prefix.length() + 1);
            } else {
                return null;
            }
            return classifier != null
                    ? new DefaultArtifact(groupId, artifactId, classifier, ext, version)
                    : new DefaultArtifact(groupId, artifactId, ext, version);
        } catch (Exception e) {
            return null;
        }
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new BuildRequirementsAnalyzer()).execute(args));
    }
}
