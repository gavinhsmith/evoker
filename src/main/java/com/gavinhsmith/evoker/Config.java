package com.gavinhsmith.evoker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * How evoker itself behaves: one server's settings (.evoker/config.json) or the user's. Never a pack, a server's
 * own configuration or a game's settings.
 */
final class Config {
    static final Map<String, JsonNode> SERVER = defaults("java", "java", "jvmArgs", List.of(), "updateOnStart", true);
    /** An empty instanceDir means Prism's usual folder. */
    static final Map<String, JsonNode> USER = defaults("instanceDir", "");

    private final Path file;
    private final Map<String, JsonNode> defaults;
    private final String scope;

    private Config(Path file, Map<String, JsonNode> defaults, String scope) {
        this.file = file;
        this.defaults = defaults;
        this.scope = scope;
    }

    static Config server(Path dir) {
        return new Config(dir.resolve(ServerFolder.STATE).resolve("config.json"), SERVER, "server");
    }

    static Config user() {
        return new Config(userDir().resolve("config.json"), USER, "user");
    }

    /** Where user settings live; the evoker.configDir system property overrides it (tests). */
    static Path userDir() {
        String override = System.getProperty("evoker.configDir");
        if (override != null) return Path.of(override);
        String home = System.getProperty("user.home"), os = System.getProperty("os.name");
        if (os.startsWith("Windows")) {
            String appData = System.getenv("APPDATA");
            return (appData != null ? Path.of(appData) : Path.of(home, "AppData", "Roaming")).resolve("evoker");
        }
        if (os.startsWith("Mac")) return Path.of(home, "Library", "Application Support", "evoker");
        String xdg = System.getenv("XDG_CONFIG_HOME");
        return (xdg != null && !xdg.isEmpty() ? Path.of(xdg) : Path.of(home, ".config")).resolve("evoker");
    }

    JsonNode get(String key) {
        check(key);
        JsonNode value = stored().get(key);
        return value != null ? value : defaults.get(key);
    }

    String string(String key) {
        return get(key).asString();
    }

    List<String> strings(String key) {
        var list = new ArrayList<String>();
        get(key).forEach(n -> list.add(n.asString()));
        return list;
    }

    boolean bool(String key) {
        return get(key).asBoolean();
    }

    /** Sets key from what was typed: JSON for list and true/false settings, the text itself for the others. */
    void set(String key, String text) {
        check(key);
        JsonNode kind = defaults.get(key), value;
        if (kind.isString()) {
            value = Json.MAPPER.valueToTree(text);
        } else {
            try {
                value = Json.MAPPER.readTree(text);
            } catch (JacksonException e) {
                value = Json.MAPPER.valueToTree(text);
            }
            boolean fits = kind.isBoolean() ? value.isBoolean()
                    : value.isArray() && value.valueStream().allMatch(JsonNode::isString);
            if (!fits) {
                throw new EvokerException(key + " takes " + (kind.isBoolean() ? "true or false" : "a list like [\"-Xmx4G\"]")
                        + ", not " + text);
            }
        }
        ObjectNode stored = stored();
        stored.set(key, value);
        Json.write(file, stored);
    }

    /** Every setting, one "key = value" per line. */
    String show() {
        var out = new StringBuilder();
        defaults.keySet().forEach(key -> out.append(key).append(" = ").append(get(key)).append('\n'));
        return out.toString().stripTrailing();
    }

    private void check(String key) {
        if (!defaults.containsKey(key)) {
            throw new EvokerException("unknown " + scope + " setting " + key + "; one of " + defaults.keySet()
                    + (scope.equals("server") ? " (--user for your user settings)" : ""));
        }
    }

    private ObjectNode stored() {
        if (!Files.exists(file)) return Json.MAPPER.createObjectNode();
        try {
            return (ObjectNode) Json.MAPPER.readTree(file);
        } catch (JacksonException | ClassCastException e) {
            throw new EvokerException("invalid " + file + ": " + e.getMessage(), e);
        }
    }

    private static Map<String, JsonNode> defaults(Object... keyValues) {
        var map = new LinkedHashMap<String, JsonNode>();
        for (int i = 0; i < keyValues.length; i += 2) map.put((String) keyValues[i], Json.MAPPER.valueToTree(keyValues[i + 1]));
        return map;
    }
}
