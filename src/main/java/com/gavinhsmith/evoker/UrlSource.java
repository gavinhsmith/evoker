package com.gavinhsmith.evoker;

import java.util.List;

/** A file at a fixed URL. No versions and no dependencies: the lock pins it by hash. */
final class UrlSource implements Source {
    static final List<String> TYPES = List.of("mod", "plugin", "datapack", "resourcepack");

    @Override
    public Resolution resolve(String ref, Manifest.Content wanted, String exactVersionId, Manifest.ServerSpec server) {
        if (wanted.url() == null || !TYPES.contains(wanted.type())) {
            throw new EvokerException("url:" + ref + " needs \"url\" and a \"type\" (one of " + TYPES + ")");
        }
        var entry = new Lock.Entry(wanted.type(), ref, null, null, wanted.url(), null, null, null);
        return new Resolution(ref, entry, "", List.of(), null, null);
    }
}
