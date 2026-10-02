package com.gavinhsmith.evoker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/** Resolves evoker.json content, and every required dependency recursively, into lock entries. */
final class Resolver {
    private final Map<String, Source> sources;

    Resolver(Map<String, Source> sources) {
        this.sources = sources;
    }

    /**
     * Breadth-first from the evoker.json entries. A locked entry that still satisfies evoker.json keeps its
     * version unless refresh says otherwise; everything else resolves to the newest compatible version.
     * Explicit entries win over dependencies; two different exact dependency versions resolve to the newer one.
     */
    Map<String, Source.Resolution> resolve(Manifest manifest, Map<String, Lock.Entry> locked,
                                           Predicate<String> refresh) {
        record Job(String key, String source, String ref, String version, String exactVersionId, String parent) {}

        var lockedByProject = new HashMap<String, String>();
        locked.forEach((key, e) -> lockedByProject.put(source(key) + ":" + e.projectId(), key));

        var queue = new ArrayDeque<Job>();
        manifest.content().forEach((key, content) -> {
            Lock.Entry have = locked.get(key);
            String exact = have != null && !refresh.test(key) && satisfies(have, content) ? have.versionId() : null;
            queue.add(new Job(key, source(key), ref(key), content.version(), exact, null));
        });

        var resolved = new LinkedHashMap<String, Source.Resolution>();
        var keyByProject = new HashMap<String, String>();
        var requiredBy = new HashMap<String, Set<String>>();
        var incompatible = new ArrayList<String[]>();

        while (!queue.isEmpty()) {
            Job job = queue.poll();
            String key = job.key() != null ? job.key() : keyByProject.get(job.source() + ":" + job.ref());
            Source.Resolution r;
            if (key != null && resolved.containsKey(key)) {
                // Already resolved: just record who needs it, and settle exact-version conflicts.
                requiredBy.computeIfAbsent(key, k -> new TreeSet<>()).add(job.parent());
                Source.Resolution have = resolved.get(key);
                if (job.exactVersionId() == null || job.exactVersionId().equals(have.entry().versionId())
                        || manifest.content().containsKey(key)) continue;
                r = sourceNamed(job.source()).resolve(job.ref(), "latest", job.exactVersionId(), manifest.server());
                String newer = r.published().compareTo(have.published()) > 0 ? r.entry().version() : have.entry().version();
                Main.warn(key + ": " + job.parent() + " wants " + r.entry().version() + ", another entry wants "
                        + have.entry().version() + "; using the newer " + newer);
                // ponytail: dependencies of the losing version stay in the lock; prune by re-running update if it matters
                if (r.published().compareTo(have.published()) <= 0) continue;
            } else {
                String lockedKey = job.key() != null
                        ? (locked.containsKey(job.key()) ? job.key() : null)
                        : lockedByProject.get(job.source() + ":" + job.ref());
                String exact = job.exactVersionId();
                if (exact == null && job.key() == null && lockedKey != null && !refresh.test(lockedKey)) {
                    // A dependency that was locked before keeps its locked version unless refreshed.
                    exact = locked.get(lockedKey).versionId();
                }
                try {
                    r = sourceNamed(job.source()).resolve(job.ref(), job.version(), exact, manifest.server());
                } catch (EvokerException e) {
                    // Nothing compatible (or upstream trouble): keep what is installed and let the user decide.
                    if (lockedKey == null) throw e;
                    Main.warn(e.getMessage() + "; keeping " + lockedKey + " " + locked.get(lockedKey).version());
                    r = keep(lockedKey, locked);
                }
                if (key == null) key = job.source() + ":" + r.slug();
                keyByProject.put(job.source() + ":" + r.entry().projectId(), key);
                if (job.parent() != null) requiredBy.computeIfAbsent(key, k -> new TreeSet<>()).add(job.parent());
            }
            resolved.put(key, r);
            for (Source.Dependency d : r.dependencies()) {
                if (d.incompatible()) {
                    incompatible.add(new String[] {key, job.source() + ":" + d.projectId()});
                } else {
                    queue.add(new Job(null, job.source(), d.projectId(), "latest", d.versionId(), key));
                }
            }
        }

        for (String[] pair : incompatible) {
            String other = keyByProject.get(pair[1]);
            if (other != null) Main.warn(pair[0] + " is marked incompatible with " + other);
        }

        var result = new TreeMap<String, Source.Resolution>();
        resolved.forEach((key, r) -> {
            Set<String> by = requiredBy.get(key);
            Lock.Entry e = r.entry();
            var entry = new Lock.Entry(e.type(), e.projectId(), e.versionId(), e.version(), e.url(), e.sha256(),
                    e.sha1(), by == null ? null : List.copyOf(by));
            result.put(key, new Source.Resolution(r.slug(), entry, r.published(), r.dependencies(), r.algo(), r.hash()));
        });
        return result;
    }

    /** A locked entry as a resolution, depending on whatever the lock says it required. */
    private static Source.Resolution keep(String key, Map<String, Lock.Entry> locked) {
        Lock.Entry e = locked.get(key);
        var deps = new ArrayList<Source.Dependency>();
        locked.values().forEach(d -> {
            if (d.requiredBy() != null && d.requiredBy().contains(key)) {
                deps.add(new Source.Dependency(d.projectId(), d.versionId(), false));
            }
        });
        var entry = new Lock.Entry(e.type(), e.projectId(), e.versionId(), e.version(), e.url(), e.sha256(), e.sha1(), null);
        return new Source.Resolution(ref(key), entry, "", deps, null, null);
    }

    /** Keeps the evoker.json entries and whatever they (transitively) require; drops the rest, cycles included. */
    static Map<String, Lock.Entry> prune(Map<String, Lock.Entry> entries, Set<String> roots) {
        var kept = new TreeMap<String, Lock.Entry>();
        entries.forEach((key, e) -> {
            if (roots.contains(key)) kept.put(key, e);
        });
        boolean grew = true;
        while (grew) {
            grew = false;
            for (var e : entries.entrySet()) {
                List<String> by = e.getValue().requiredBy();
                if (!kept.containsKey(e.getKey()) && by != null && by.stream().anyMatch(kept::containsKey)) {
                    kept.put(e.getKey(), e.getValue());
                    grew = true;
                }
            }
        }
        // drop requiredBy links to removed entries
        kept.replaceAll((key, e) -> {
            if (e.requiredBy() == null) return e;
            List<String> by = e.requiredBy().stream().filter(kept::containsKey).toList();
            return new Lock.Entry(e.type(), e.projectId(), e.versionId(), e.version(), e.url(), e.sha256(), e.sha1(),
                    by.isEmpty() ? null : by);
        });
        return kept;
    }

    /** Does the locked entry still match what evoker.json asks for? */
    static boolean satisfies(Lock.Entry locked, Manifest.Content wanted) {
        return wanted.version() == null || wanted.version().equals("latest")
                || wanted.version().equals(locked.version()) || wanted.version().equals(locked.versionId());
    }

    static String source(String key) {
        return key.substring(0, key.indexOf(':'));
    }

    static String ref(String key) {
        return key.substring(key.indexOf(':') + 1);
    }

    private Source sourceNamed(String name) {
        Source s = sources.get(name);
        if (s == null) throw new EvokerException("unknown source \"" + name + "\" (known: " + sources.keySet() + ")");
        return s;
    }
}
