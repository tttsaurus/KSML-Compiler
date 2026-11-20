package com.cleanroommc.ksmlc;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KSMLCompilerTest {

    @Test
    void test() throws IOException {
        var compiler = new KSMLCompiler(
                SourceFile.fromResource("main.glsl"),
                new SourceFile[] {
                        SourceFile.fromResource("math.ksml"),
                        SourceFile.fromResource("functional.ksml") },
                null,
                "core");

        String glsl = compiler.compile();

        assertTrue(glsl.contains("#version 120"));
        assertTrue(glsl.contains("int sum = ksml_export_4_math_add_checked(1, 2, true);"));
        assertTrue(glsl.contains("return ksml_10_functional_id(a);"));
        assertFalse(glsl.contains("@module"));
        assertFalse(glsl.contains("@code"));
        assertTrue(glsl.contains("#version 120\n"));

        System.out.print(glsl);
    }
}
