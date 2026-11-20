package com.cleanroommc.ksmlc;

import chaos.unity.nenggao.AbstractSpan;
import chaos.unity.nenggao.CharacterSet;
import chaos.unity.nenggao.FileReportBuilder;
import chaos.unity.nenggao.Source;
import chaos.unity.nenggao.SourceCache;
import com.diogonunes.jcolor.Ansi;
import com.diogonunes.jcolor.Attribute;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SourceFile(
        String fileName,
        String source,
        FileReportBuilder reportBuilder,
        List<Notice> notices) {

    /**
     * Less severe than <code>Warning</code>.
     */
    public record Notice(AbstractSpan span, String message) {
    }

    public SourceFile {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(reportBuilder, "reportBuilder");
        Objects.requireNonNull(notices, "notices");
    }

    public void printDiagnostics(PrintStream stream, boolean enableColor) {
        reportBuilder.enableColor(enableColor).print(stream);
        for (var notice : notices) {
            var renderer = fromString(fileName, source).reportBuilder().enableColor(enableColor);
            renderer.warning(notice.span(), "%s", notice.message())
                    .tag("notice")
                    .label(notice.span(), "Version requirement")
                    .color(Attribute.BRIGHT_CYAN_TEXT())
                    .build()
                    .build();

            var output = new ByteArrayOutputStream();
            try (var captured = new PrintStream(output, true, StandardCharsets.UTF_8)) {
                renderer.print(captured);
            }

            String text = output.toString(StandardCharsets.UTF_8);

            if (enableColor) {
                String prefix = Ansi.generateCode(Attribute.YELLOW_TEXT()) + "[notice] ";
                if (text.startsWith(prefix)) {
                    text = Ansi.generateCode(Attribute.BRIGHT_CYAN_TEXT()) + "[notice] " + text.substring(prefix.length());
                }
                String textColor = Ansi.generateCode(Attribute.CYAN_TEXT());
                text = textColor + text.replace(Ansi.RESET, Ansi.RESET + textColor) + Ansi.RESET;
            }

            stream.print(text);
        }
    }

    public static SourceFile fromString(String fileName, String source) {
        var key = new File(System.getProperty("java.io.tmpdir"),
                "ksmlc_memory/" + UUID.nameUUIDFromBytes((fileName + '\0' + source).getBytes(StandardCharsets.UTF_8)));

        synchronized (SourceCache.INSTANCE) {
            SourceCache.INSTANCE.put(key, Source.fromStrings(Arrays.asList(source.split("\\r\\n|\\r|\\n", -1))));
        }

        FileReportBuilder builder = FileReportBuilder.sourceFile(key).sourceName(fileName).characterSet(CharacterSet.ASCII);
        return new SourceFile(fileName, source, builder, new ArrayList<>());
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
