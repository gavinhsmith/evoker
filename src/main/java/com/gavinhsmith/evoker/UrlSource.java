package com.gavinhsmith.evoker;

import java.util.List;

/** A file at a fixed URL. No versions and no dependencies: the lock pins it by hash. */
final class UrlSource implements Source {
    @Override
    public Resolution resolve(String ref, Manifest.Content wanted, String exactVersionId, Manifest.Game game) {
        if (wanted.url() == null || wanted.type() == null) {
            throw new EvokerException("url:" + ref + " needs \"url\" and a \"type\" (one of " + Manifest.TYPES + ")");
        }
        List<String> sides = wanted.side() != null ? Manifest.sides(wanted.side()) : Manifest.defaultSides(wanted.type());
        if (sides == null) throw new EvokerException("url:" + ref + " needs a \"side\" (client, server or both)");
        var entry = new Lock.Entry(wanted.type(), sides, null, ref, null, ref, null, null, wanted.url(), null, null, null);
        return new Resolution(ref, entry, "", List.of());
    }
}
