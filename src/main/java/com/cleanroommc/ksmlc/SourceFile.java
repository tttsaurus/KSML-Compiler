package com.cleanroommc.ksmlc;

import chaos.unity.nenggao.CharacterSet;
import chaos.unity.nenggao.FileReportBuilder;
import chaos.unity.nenggao.Source;
import chaos.unity.nenggao.SourceCache;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Arrays;
import java.util.UUID;

public record SourceFile(String fileName, String source, FileReportBuilder reportBuilder) {

    public SourceFile {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reportBuilder, "reportBuilder");
    }

    public static SourceFile fromString(String fileName, String source) {
        var key = new File(System.getProperty("java.io.tmpdir"),
                "ksmlc_memory/" + UUID.nameUUIDFromBytes((fileName + '\0' + source).getBytes(StandardCharsets.UTF_8)));

        synchronized (SourceCache.INSTANCE) {
            SourceCache.INSTANCE.put(key, Source.fromStrings(Arrays.asList(source.split("\\r\\n|\\r|\\n", -1))));
        }

        FileReportBuilder builder = FileReportBuilder.sourceFile(key).sourceName(fileName).characterSet(CharacterSet.ASCII);
        return new SourceFile(fileName, source, builder);
    }

    public static SourceFile fromPath(Path path) throws IOException {
        return fromString(path.toString(), Files.readString(path, StandardCharsets.UTF_8));
    }

    public static SourceFile fromResource(String filePath) throws IOException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        try (var stream = classLoader.getResourceAsStream(filePath)) {
            if (stream == null) {
                throw new IllegalArgumentException("File not found: \"" + filePath + "\"");
            }
            return fromString(filePath, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
