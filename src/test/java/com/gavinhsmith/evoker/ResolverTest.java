package com.gavinhsmith.evoker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ResolverTest {
    /** Projects whose slug is their id; versions are listed oldest first, "latest" is the last one. */
    static final class FakeSource implements Source {
        record V(String id, String project, String version, List<Dependency> deps) {}

        final Map<String, List<V>> projects = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();

        FakeSource version(String project, String version, Dependency... deps) {
            String id = project + "@" + version;
            projects.computeIfAbsent(project, k -> new ArrayList<>()).add(new V(id, project, version, List.of(deps)));
            return this;
        }

        @Override
        public Resolution resolve(String ref, Manifest.Content wanted, String exactVersionId, Manifest.ServerSpec server) {
            String version = wanted.version();
            calls.add(ref);
            List<V> versions = projects.get(ref);
            if (versions.isEmpty()) throw new EvokerException(ref + " has no version");
            V v = versions.getLast();
            for (V candidate : versions) {
                if (candidate.id().equals(exactVersionId) || candidate.version().equals(version)) v = candidate;
            }
            var entry = new Lock.Entry("mod", v.project(), v.id(), v.version(), "https://x/" + v.id(), null, null, null);
            return new Resolution(ref, entry, v.version(), v.deps(), null, null);
        }
    }

    static Source.Dependency dep(String project) {
        return new Source.Dependency(project, null, false);
    }

    static Source.Dependency exact(String project, String version) {
        return new Source.Dependency(project, project + "@" + version, false);
    }

    final FakeSource source = new FakeSource();
    final Resolver resolver = new Resolver(Map.of("modrinth", source));

    private static Manifest manifest(String... keyVersion) {
        var content = new LinkedHashMap<String, Manifest.Content>();
        for (int i = 0; i < keyVersion.length; i += 2) {
            content.put("modrinth:" + keyVersion[i], new Manifest.Content(keyVersion[i + 1], null, null));
        }
        return new Manifest(new Manifest.ServerSpec("fabric", "1.21.4", null), false, null, content, null);
    }

    private Map<String, Lock.Entry> resolve(Manifest m, Map<String, Lock.Entry> locked, Predicate<String> refresh) {
        var out = new LinkedHashMap<String, Lock.Entry>();
        resolver.resolve(m, locked, refresh).forEach((k, r) -> out.put(k, r.entry()));
        return out;
    }

    private Map<String, Lock.Entry> resolve(Manifest m) {
        return resolve(m, Map.of(), k -> false);
    }

    @Test
    void resolvesDependenciesRecursively() {
        source.version("a", "1", dep("b")).version("b", "1", dep("c")).version("c", "1");

        var entries = resolve(manifest("a", "latest"));

        assertEquals(Set.of("modrinth:a", "modrinth:b", "modrinth:c"), entries.keySet());
        assertNull(entries.get("modrinth:a").requiredBy());
        assertEquals(List.of("modrinth:a"), entries.get("modrinth:b").requiredBy());
        assertEquals(List.of("modrinth:b"), entries.get("modrinth:c").requiredBy());
    }

    @Test
    void cyclesTerminate() {
        source.version("a", "1", dep("b")).version("b", "1", dep("a"));

        var entries = resolve(manifest("a", "latest"));

        assertEquals(List.of("modrinth:b"), entries.get("modrinth:a").requiredBy());
        assertEquals(List.of("a", "b"), source.calls);
    }

    @Test
    void sharedDependencyIsResolvedOnceWithEveryDependent() {
        source.version("a", "1", dep("c")).version("b", "1", dep("c")).version("c", "1");

        var entries = resolve(manifest("a", "latest", "b", "latest"));

        assertEquals(List.of("modrinth:a", "modrinth:b"), entries.get("modrinth:c").requiredBy());
        assertEquals(1, source.calls.stream().filter("c"::equals).count());
    }

    @Test
    void explicitEntryWinsOverADependency() {
        source.version("a", "1", exact("b", "2")).version("b", "1").version("b", "2");

        var entries = resolve(manifest("a", "latest", "b", "1"));

        assertEquals("1", entries.get("modrinth:b").version());
        assertEquals(List.of("modrinth:a"), entries.get("modrinth:b").requiredBy());
    }

    @Test
    void conflictingExactDependenciesUseTheNewerOne() {
        source.version("a", "1", exact("c", "1")).version("b", "1", exact("c", "2"))
                .version("c", "1").version("c", "2");

        String err = Output.err(() -> assertEquals("2", resolve(manifest("a", "latest", "b", "latest"))
                .get("modrinth:c").version()));

        assertTrue(err.contains("using the newer 2"), err);
    }

    @Test
    void warnsAboutIncompatibleProjects() {
        source.version("a", "1", new Source.Dependency("b", null, true)).version("b", "1");

        String err = Output.err(() -> resolve(manifest("a", "latest", "b", "latest")));

        assertTrue(err.contains("modrinth:a is marked incompatible with modrinth:b"), err);
    }

    @Test
    void lockedVersionsStayUnlessRefreshed() {
        source.version("a", "1", dep("b")).version("b", "1");
        Map<String, Lock.Entry> locked = resolve(manifest("a", "latest"));
        source.version("a", "2", dep("b")).version("b", "2");

        var kept = resolve(manifest("a", "latest"), locked, k -> false);
        assertEquals("1", kept.get("modrinth:a").version());
        assertEquals("1", kept.get("modrinth:b").version());

        var refreshed = resolve(manifest("a", "latest"), locked, k -> true);
        assertEquals("2", refreshed.get("modrinth:a").version());
        assertEquals("2", refreshed.get("modrinth:b").version());
    }

    @Test
    void failedResolutionKeepsTheLockedEntryAndItsDependencies() {
        source.version("a", "1", dep("b")).version("b", "1");
        Map<String, Lock.Entry> locked = resolve(manifest("a", "latest"));
        source.projects.get("a").clear(); // a vanished upstream: resolving it now throws

        String err = Output.err(() -> {
            var entries = resolve(manifest("a", "latest"), locked, k -> true);
            assertEquals("1", entries.get("modrinth:a").version());
            assertEquals(List.of("modrinth:a"), entries.get("modrinth:b").requiredBy());
        });

        assertTrue(err.contains("keeping modrinth:a 1"), err);
    }

    @Test
    void pinChangeIsResolvedAgain() {
        source.version("a", "1").version("a", "2");
        Map<String, Lock.Entry> locked = resolve(manifest("a", "2"));

        assertEquals("1", resolve(manifest("a", "1"), locked, k -> false).get("modrinth:a").version());
    }

    @Test
    void pruneKeepsOnlyWhatIsReachable() {
        var e = new LinkedHashMap<String, Lock.Entry>();
        e.put("modrinth:a", entry());
        e.put("modrinth:b", entry("modrinth:a"));
        e.put("modrinth:c", entry("modrinth:b", "modrinth:gone"));
        e.put("modrinth:orphan", entry("modrinth:gone"));
        e.put("modrinth:x", entry("modrinth:y"));
        e.put("modrinth:y", entry("modrinth:x"));

        var kept = Resolver.prune(e, Set.of("modrinth:a"));

        assertEquals(Set.of("modrinth:a", "modrinth:b", "modrinth:c"), kept.keySet());
        assertEquals(List.of("modrinth:b"), kept.get("modrinth:c").requiredBy());
    }

    private static Lock.Entry entry(String... requiredBy) {
        return new Lock.Entry("mod", "p", "v", "1", "u", null, null, requiredBy.length == 0 ? null : List.of(requiredBy));
    }
}
