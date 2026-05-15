package com.simpligility.maven.analysis;

import module java.base;
import module java.xml;

import com.simpligility.maven.util.Dom;
import eu.maveniverse.maven.mima.context.Context;
import eu.maveniverse.maven.mima.extensions.mmr.MavenModelReader;

/// Infrastructure shared across all analyzers: the mima [Context] (for repository access),
/// the [MavenModelReader] that builds effective POMs, the [ProgressLogger] both stdout and
/// report-file writes flow through, a reusable [DocumentBuilder], and the project root.
///
/// This intentionally does **not** hold analysis state — each analyzer returns its own
/// product and the orchestrator threads them together via explicit arguments.
public record AnalysisContext(
        Context mima,
        MavenModelReader modelReader,
        ProgressLogger logger,
        DocumentBuilder documentBuilder,
        Path projectDir
) {

    public static AnalysisContext create(Context mima, ProgressLogger logger, Path projectDir)
            throws ParserConfigurationException {
        return new AnalysisContext(mima, new MavenModelReader(mima), logger,
                Dom.newDocumentBuilder(), projectDir);
    }
}
