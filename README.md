# KSML Compiler

A compiler for the KSML language (also known as Kirino Shader Meta Language).

- KSML Specification: https://github.com/CleanroomMC/KSML-Spec
- IntelliJ IDEA Plugin: https://github.com/tttsaurus/ksml-intellij-plugin

## How to use it?

`KSMLCompiler` accepts a GLSL shader, the available KSML source files, and the
feature names to enable. It also requires an argument to specify the program profile context (`"core"`,
`"compat"`, or `"compatibility"`).

```java
KSMLCompiler compiler = new KSMLCompiler(
    SourceFile.fromResource("main.glsl"),
    new SourceFile[] {
        SourceFile.fromResource("math.ksml"),
        SourceFile.fromResource("functional.ksml")
    },
    new String[] { "PBR" },
    "core"
);

String glsl = compiler.compile();
```

The resources used in the example are available on the test classpath. 
Applications should supply their own shader sources.

## KSML compiler behavior

The compiler implements the directives in the [KSML Specification v2.1](https://github.com/CleanroomMC/KSML-Spec/blob/master/ksml_spec_v2.1.md).

Module level version requirement violations produce cyan `notice` diagnostics.
Function level version requirement violations produce yellow `warning` but not `error` with an explicit crash.

## License

This project is licensed under [MIT License](./LICENSE).
