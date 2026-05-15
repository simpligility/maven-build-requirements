package com.simpligility.maven.util;

import module java.base;

import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;

/// Maven artifact-coordinate helpers.
public final class Coords {

    private Coords() {}

    /// Formats an [Artifact] as `groupId:artifactId:extension[:classifier]:version`. Defaults
    /// the extension to `jar` when blank; omits the classifier segment when blank.
    public static String artifactCoords(Artifact artifact) {
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

    /// Parses a Maven repository URL (e.g. the wrapper's `distributionUrl`) into a Maven
    /// artifact. Expects the canonical `<groupPath>/<artifactId>/<version>/<filename>` suffix
    /// and strips common repo-base prefixes (e.g. `maven2/`). Returns `null` if the URL does
    /// not match the expected layout.
    public static DefaultArtifact artifactFromMavenUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isEmpty()) return null;
            if (path.startsWith("/")) path = path.substring(1);
            String[] segs = path.split("/");

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
        } catch (Exception _) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
