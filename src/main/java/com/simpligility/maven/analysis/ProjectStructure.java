package com.simpligility.maven.analysis;

import module java.base;

import org.eclipse.aether.graph.Dependency;

/// Output of [ProjectStructureLoader]: every fact the downstream analyzers need from the
/// reactor's POM tree without needing a mima context.
///
/// `pomFiles` is the recursively-walked list of `pom.xml` files (root first, then sub-modules
/// in the order they were discovered). `reactorCoords` is the `groupId:artifactId:version`
/// set used to exclude reactor modules from resolved-artifact output. `properties` collects
/// every `<properties>` entry across modules plus a few synthesized keys
/// (`project.version`, `project.groupId`, `revision`). `declaredDependencies` is the
/// DOM-collected `<dependency>` set used as a fallback for transitive resolution when the
/// effective-POM Build doesn't surface a dependency list.
public record ProjectStructure(
        List<Path> pomFiles,
        Set<String> reactorCoords,
        Map<String, String> properties,
        Map<String, Dependency> declaredDependencies
) {}
