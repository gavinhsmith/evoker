package com.gavinhsmith.evoker;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Hangar (https://hangar.papermc.io/api-docs): Paper plugins. */
final class Hangar implements Source {
    private final Http http;
    private final String api;

    Hangar(Http http, String base) {
        this.http = http;
        this.api = base + "/api/v1";
    }

    @Override
    public Resolution resolve(String ref, Manifest.Content wanted, String exactVersionId, Manifest.ServerSpec server) {
        if (!List.of("paper", "purpur").contains(server.software())) {
            throw new EvokerException("hangar:" + ref + ": Hangar plugins need paper or purpur, not " + server.software());
        }
        JsonNode project = http.jsonOrNull(api + "/projects/" + enc(ref));
        if (project == null) throw new EvokerException("no Hangar project \"" + ref + "\"");
        String slug = project.path("namespace").path("slug").asString();
        String key = "hangar:" + slug;

        JsonNode chosen;
        if (exactVersionId != null) {
            chosen = http.json(api + "/versions/" + enc(exactVersionId));
        } else if (wanted.version().equals("latest")) {
            // Newest release; only if there is none, the newest of any channel (snapshots, betas).
            String query = api + "/projects/" + enc(slug) + "/versions?limit=1&platform=PAPER&platformVersion="
                    + enc(server.version());
            JsonNode versions = http.json(query + "&channel=Release").path("result");
            if (versions.isEmpty()) versions = http.json(query).path("result");
            if (versions.isEmpty()) throw new EvokerException(key + " has no version for paper " + server.version());
            chosen = versions.get(0);
        } else {
            chosen = http.jsonOrNull(api + "/projects/" + enc(slug) + "/versions/" + enc(wanted.version()));
            if (chosen == null) throw new EvokerException(key + " has no version " + wanted.version());
            boolean compatible = false;
            for (JsonNode v : chosen.path("platformDependencies").path("PAPER")) {
                compatible |= v.asString().equals(server.version());
            }
            if (!compatible) Main.warn(key + " " + wanted.version() + " is not marked compatible with paper " + server.version());
        }

        JsonNode download = chosen.path("downloads").path("PAPER");
        if (download.isMissingNode()) throw new EvokerException(key + " " + chosen.path("name").asString() + " has no Paper download");
        String url = download.path("downloadUrl").asString(null);
        if (url == null) url = download.path("externalUrl").asString();
        String sha256 = download.path("fileInfo").path("sha256Hash").asString(null);

        var dependencies = new ArrayList<Dependency>();
        for (JsonNode d : chosen.path("pluginDependencies").path("PAPER")) {
            if (!d.path("required").asBoolean()) continue;
            if (d.path("projectId").isNumber()) {
                dependencies.add(new Dependency(d.path("projectId").asString(), null, false));
            } else {
                Main.warn(key + " requires " + d.path("name").asString() + " from outside Hangar ("
                        + d.path("externalUrl").asString("no link") + "); add it yourself");
            }
        }

        var entry = new Lock.Entry("plugin", project.path("id").asString(), chosen.path("id").asString(),
                chosen.path("name").asString(), url, null, null, null);
        return new Resolution(slug, entry, chosen.path("createdAt").asString(), dependencies,
                sha256 == null ? null : "SHA-256", sha256);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
