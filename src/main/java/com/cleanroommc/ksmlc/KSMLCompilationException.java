package com.cleanroommc.ksmlc;

import java.util.List;

public class KSMLCompilationException extends IllegalStateException {

    private final List<SourceFile> sources;

    public KSMLCompilationException(List<SourceFile> sources) {
        super("KSML compilation failed.");
        this.sources = List.copyOf(sources);
    }

    public List<SourceFile> getSources() {
        return sources;
    }
}
