# KSML Compiler

A compiler for the KSML language (also known as Kirino Shader Meta Language).

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

## KSML behavior

The compiler implements the directives in the
[KSML v2 specification](https://github.com/CleanroomMC/Kirino-Engine/blob/main/docs/ksml/ksml_spec_v2.md):

## License

This project is licensed under [MIT License](./LICENSE).
