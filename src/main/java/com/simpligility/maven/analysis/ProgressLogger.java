package com.simpligility.maven.analysis;

import module java.base;

import org.eclipse.aether.artifact.Artifact;

/// Writes analysis progress to stdout *and* tees it to the report file. The report file
/// therefore contains everything the user saw during the run — progress messages, section
/// headers, and the structured artifact lists — in the same order.
public final class ProgressLogger implements AutoCloseable {

    private final PrintWriter writer;
    private final boolean showPaths;

    public ProgressLogger(Path outputFile, boolean showPaths) throws IOException {
        this.writer = new PrintWriter(Files.newBufferedWriter(outputFile));
        this.showPaths = showPaths;
    }

    public void print(String line) {
        System.out.println(line);
        writer.println(line);
        writer.flush();
    }

    /// Prints a single resolved artifact. When `--paths` is on, the local-cache file path is
    /// appended after the GAV on the same line.
    public void printArtifactLine(String coords, Artifact artifact) {
        if (showPaths) {
            File file = artifact.getFile();
            String pathStr = file != null ? file.getAbsolutePath() : "NOT FOUND in local cache";
            print("  " + coords + "  " + pathStr);
        } else {
            print("  " + coords);
        }
    }

    @Override
    public void close() {
        writer.close();
    }
}
