package com.simpligility.maven.analysis;

import module java.base;
import module java.xml;

import com.simpligility.maven.util.Dom;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;

/// Step 1 of the analysis: parses the reactor's POM files via DOM (no mima needed) and
/// collects everything downstream analyzers need to interpret the project before transitive
/// resolution kicks in. Two passes:
///
/// 1. Reactor walk + property/dependency-management gather: produces the pom list, reactor
///    coords, properties, and a managed-version table (managed is internal — used to expand
///    versions in pass 2 and then discarded).
/// 2. Declared-dependency gather: applies property and BOM-managed-version expansion and
///    builds the `declaredDependencies` map.
public final class ProjectStructureLoader {

    private final ProgressLogger logger;
    private final DocumentBuilder documentBuilder;

    public ProjectStructureLoader(ProgressLogger logger, DocumentBuilder documentBuilder) {
        this.logger = logger;
        this.documentBuilder = documentBuilder;
    }

    public ProjectStructure load(Path rootPom) throws Exception {
        logger.print("Collecting project structure...");

        List<Path> pomFiles = new ArrayList<>();
        pomFiles.add(rootPom);
        collectSubModulePoms(rootPom, pomFiles);

        Set<String> reactorCoords = new LinkedHashSet<>();
        Map<String, String> properties = new LinkedHashMap<>();
        Map<String, String> managed = new LinkedHashMap<>();
        String rootGroupId = null;
        String rootVersion = null;

        for (int i = 0; i < pomFiles.size(); i++) {
            Document doc = documentBuilder.parse(pomFiles.get(i).toFile());
            Element project = doc.getDocumentElement();

            String groupId = Dom.directText(project, "groupId");
            String artifactId = Dom.directText(project, "artifactId");
            String version = Dom.directText(project, "version");

            Element parent = Dom.directElement(project, "parent");
            if (parent != null) {
                if (isBlank(groupId)) groupId = Dom.directText(parent, "groupId");
                if (isBlank(version)) version = Dom.directText(parent, "version");
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

            Element propsEl = Dom.directElement(project, "properties");
            if (propsEl != null) {
                NodeList propNodes = propsEl.getChildNodes();
                for (int k = 0; k < propNodes.getLength(); k++) {
                    if (propNodes.item(k) instanceof Element propEl) {
                        properties.putIfAbsent(propEl.getTagName(), propEl.getTextContent().trim());
                    }
                }
            }

            Element dmEl = Dom.directElement(project, "dependencyManagement");
            if (dmEl != null) {
                Element depsEl = Dom.directElement(dmEl, "dependencies");
                if (depsEl != null) {
                    NodeList depNodes = depsEl.getElementsByTagName("dependency");
                    for (int k = 0; k < depNodes.getLength(); k++) {
                        Element dep = (Element) depNodes.item(k);
                        String dmG = Dom.directText(dep, "groupId");
                        String dmA = Dom.directText(dep, "artifactId");
                        String dmV = Dom.directText(dep, "version");
                        if (!isBlank(dmG) && !isBlank(dmA) && !isBlank(dmV)) {
                            managed.putIfAbsent(dmG + ":" + dmA, dmV);
                        }
                    }
                }
            }

            if (!isBlank(artifactId) && !isBlank(groupId) && !isBlank(version)) {
                String coord = groupId + ":" + artifactId + ":" + version;
                reactorCoords.add(coord);
                logger.print("  Reactor module: " + coord);
            }
        }
        logger.print("");

        Map<String, Dependency> uniqueDeps = collectDeclaredDependencies(
                pomFiles, properties, managed, reactorCoords);

        return new ProjectStructure(pomFiles, reactorCoords, properties, uniqueDeps);
    }

    private Map<String, Dependency> collectDeclaredDependencies(
            List<Path> pomFiles,
            Map<String, String> properties,
            Map<String, String> managed,
            Set<String> reactorCoords) throws Exception {

        Map<String, Dependency> uniqueDeps = new LinkedHashMap<>();
        for (Path pomFile : pomFiles) {
            Document doc = documentBuilder.parse(pomFile.toFile());
            Element project = doc.getDocumentElement();

            Element dependenciesEl = Dom.directElement(project, "dependencies");
            if (dependenciesEl == null) continue;

            NodeList depNodes = dependenciesEl.getElementsByTagName("dependency");
            for (int j = 0; j < depNodes.getLength(); j++) {
                Element dep = (Element) depNodes.item(j);
                String depG = Dom.directText(dep, "groupId");
                String depA = Dom.directText(dep, "artifactId");
                String depV = Dom.directText(dep, "version");
                String depScope = Dom.directText(dep, "scope");
                String depType = Dom.directText(dep, "type");

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
        return uniqueDeps;
    }

    private void collectSubModulePoms(Path pomFile, List<Path> result) throws Exception {
        Document doc = documentBuilder.parse(pomFile.toFile());
        Element project = doc.getDocumentElement();
        Element modules = Dom.directElement(project, "modules");
        if (modules == null) return;
        NodeList moduleNodes = modules.getElementsByTagName("module");
        for (int i = 0; i < moduleNodes.getLength(); i++) {
            String moduleName = moduleNodes.item(i).getTextContent().trim();
            Path modulePom = pomFile.getParent().resolve(moduleName).resolve("pom.xml");
            if (Files.exists(modulePom) && !result.contains(modulePom)) {
                result.add(modulePom);
                collectSubModulePoms(modulePom, result);
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
