package com.gavinhsmith.evoker;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** The one JSON mapper: declaration order, nulls omitted, unknown fields ignored, conventional formatting. */
final class Json {
    static final JsonMapper MAPPER = JsonMapper.builder()
            .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT)
            .changeDefaultPropertyInclusion(i -> i.withValueInclusion(JsonInclude.Include.NON_NULL))
            .defaultPrettyPrinter(new DefaultPrettyPrinter(Separators.createDefaultInstance()
                    .withObjectNameValueSpacing(Separators.Spacing.AFTER)
                    .withObjectEmptySeparator("")
                    .withArrayEmptySeparator(""))
                    .withObjectIndenter(new DefaultIndenter("  ", "\n")))
            .build();

    private Json() {}

    /** Writes value as pretty JSON with a trailing newline, creating missing folders. */
    static void write(Path file, Object value) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, MAPPER.writeValueAsString(value) + "\n");
        } catch (IOException e) {
            throw new EvokerException("cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
