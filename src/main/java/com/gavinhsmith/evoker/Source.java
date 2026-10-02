package com.gavinhsmith.evoker;

import java.util.List;

/** A content provider (Modrinth, Hangar, URL). */
interface Source {
    /**
     * Resolves one project to one version.
     *
     * @param ref            slug or project id
     * @param version        "latest" or a pinned version, used when exactVersionId is null
     * @param exactVersionId a specific version id (from the lock or a dependency), or null
     */
    Resolution resolve(String ref, String version, String exactVersionId, Manifest.ServerSpec server);

    /**
     * A resolved version. entry has no sha256/requiredBy yet; algo/hash are the upstream checksum for the download.
     * published is sortable (ISO-8601) and only used to pick the newer of two conflicting versions.
     */
    record Resolution(String slug, Lock.Entry entry, String published, List<Dependency> dependencies, String algo,
                      String hash) {}

    /** A dependency on another project of the same source. versionId is null when any version will do. */
    record Dependency(String projectId, String versionId, boolean incompatible) {}
}
