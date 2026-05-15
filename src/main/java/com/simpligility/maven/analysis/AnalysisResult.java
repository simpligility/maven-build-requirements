package com.simpligility.maven.analysis;

import module java.base;

import org.eclipse.aether.artifact.Artifact;

/// Aggregate of every artifact the analyzers resolved during a run. The class is mutable
/// because analyzers add entries as they discover them and later analyzers query previously-
/// added entries to dedupe (e.g. don't list a plugin parent POM that already showed up as a
/// project parent POM). Add-methods also keep the sorted [#allCoords] set in sync so the
/// coords-file output is just one snapshot away.
public final class AnalysisResult {

    private final Map<String, Map<String, Artifact>> dependenciesByScope = new LinkedHashMap<>();
    private final Map<String, Artifact> parentPoms = new LinkedHashMap<>();
    private final Map<String, Artifact> plugins = new LinkedHashMap<>();
    private final Map<String, Artifact> pluginParentPoms = new LinkedHashMap<>();
    private final Map<String, Artifact> pluginDeps = new LinkedHashMap<>();
    private final Map<String, Artifact> extensions = new LinkedHashMap<>();
    private final Map<String, Artifact> extensionParentPoms = new LinkedHashMap<>();
    private final Map<String, Artifact> extensionDeps = new LinkedHashMap<>();
    private Artifact mavenDistribution;
    private String mavenDistributionCoords;
    private final TreeSet<String> allCoords = new TreeSet<>();

    public void addDependency(String scope, String coords, Artifact artifact) {
        dependenciesByScope.computeIfAbsent(scope, _ -> new LinkedHashMap<>())
                .putIfAbsent(coords, artifact);
        allCoords.add(coords);
    }

    public void addParentPom(String coords, Artifact artifact) {
        parentPoms.put(coords, artifact);
        allCoords.add(coords);
    }

    public void addPlugin(String coords, Artifact artifact) {
        plugins.put(coords, artifact);
        allCoords.add(coords);
    }

    public void addPluginParentPom(String coords, Artifact artifact) {
        pluginParentPoms.put(coords, artifact);
        allCoords.add(coords);
    }

    public void addPluginDep(String coords, Artifact artifact) {
        pluginDeps.putIfAbsent(coords, artifact);
        allCoords.add(coords);
    }

    public void addExtension(String coords, Artifact artifact) {
        extensions.put(coords, artifact);
        allCoords.add(coords);
    }

    public void addExtensionParentPom(String coords, Artifact artifact) {
        extensionParentPoms.put(coords, artifact);
        allCoords.add(coords);
    }

    public void addExtensionDep(String coords, Artifact artifact) {
        extensionDeps.putIfAbsent(coords, artifact);
        allCoords.add(coords);
    }

    public void setMavenDistribution(String coords, Artifact artifact) {
        this.mavenDistributionCoords = coords;
        this.mavenDistribution = artifact;
        allCoords.add(coords);
    }

    public Map<String, Map<String, Artifact>> dependenciesByScope() { return dependenciesByScope; }
    public Map<String, Artifact> parentPoms() { return parentPoms; }
    public Map<String, Artifact> plugins() { return plugins; }
    public Map<String, Artifact> pluginParentPoms() { return pluginParentPoms; }
    public Map<String, Artifact> pluginDeps() { return pluginDeps; }
    public Map<String, Artifact> extensions() { return extensions; }
    public Map<String, Artifact> extensionParentPoms() { return extensionParentPoms; }
    public Map<String, Artifact> extensionDeps() { return extensionDeps; }
    public Artifact mavenDistribution() { return mavenDistribution; }
    public String mavenDistributionCoords() { return mavenDistributionCoords; }
    public TreeSet<String> allCoords() { return allCoords; }

    public int dependencyCount() {
        return dependenciesByScope.values().stream().mapToInt(Map::size).sum();
    }

    public int totalArtifactCount() {
        return dependencyCount() + parentPoms.size() + plugins.size() + pluginParentPoms.size()
                + pluginDeps.size() + extensions.size() + extensionParentPoms.size()
                + extensionDeps.size() + (mavenDistribution != null ? 1 : 0);
    }
}
