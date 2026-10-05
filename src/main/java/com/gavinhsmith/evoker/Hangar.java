package com.gavinhsmith.evoker;

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
    public Resolution resolve(String ref, Manifest.Content wanted, String exactVersionId, Manifest.Game game) {
        if (!List.of("paper", "purpur").contains(game.loader())) {
            throw new EvokerException("hangar:" + ref + ": Hangar plugins need paper or purpur, not " + game.loader());
        }
        JsonNode project = http.jsonOrNull(api + "/projects/" + Http.enc(ref));
        if (project == null) throw new EvokerException("no Hangar project \"" + ref + "\"");
        String slug = project.path("namespace").path("slug").asString();
        String key = "hangar:" + slug;

        JsonNode chosen;
        if (exactVersionId != null) {
            chosen = http.json(api + "/versions/" + Http.enc(exactVersionId));
        } else if (wanted.version().equals("latest")) {
            // Newest release; only if there is none, the newest of any channel (snapshots, betas).
            String query = api + "/projects/" + Http.enc(slug) + "/versions?limit=1&platform=PAPER&platformVersion="
                    + Http.enc(game.version());
            JsonNode versions = http.json(query + "&channel=Release").path("result");
            if (versions.isEmpty()) versions = http.json(query).path("result");
            if (versions.isEmpty()) throw new EvokerException(key + " has no version for paper " + game.version());
            chosen = versions.get(0);
        } else {
            chosen = http.jsonOrNull(api + "/projects/" + Http.enc(slug) + "/versions/" + Http.enc(wanted.version()));
            if (chosen == null) throw new EvokerException(key + " has no version " + wanted.version());
            if (chosen.path("platformDependencies").path("PAPER").valueStream()
                    .noneMatch(v -> v.asString().equals(game.version()))) Main.warn(key + " " + wanted.version() + " is not marked compatible with paper " + game.version());
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

        var entry = new Lock.Entry("plugin", List.of("server"), null, project.path("name").asString(null),
                project.path("description").asString(null), project.path("id").asString(), chosen.path("id").asString(),
                chosen.path("name").asString(), url, sha256 == null ? null : "sha256:" + sha256, null, null);
        return new Resolution(slug, entry, chosen.path("createdAt").asString(), dependencies);
    }
}
