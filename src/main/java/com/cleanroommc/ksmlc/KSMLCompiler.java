package com.cleanroommc.ksmlc;

import com.cleanroommc.ksmlc.glsl.grammar.GLSLLexer;
import com.cleanroommc.ksmlc.glsl.grammar.GLSLParser;
import com.cleanroommc.ksmlc.glsl.grammar.KSMLLexer;
import com.cleanroommc.ksmlc.glsl.grammar.KSMLParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.jspecify.annotations.Nullable;

import java.io.PrintStream;
import java.util.*;
import java.util.function.Function;

public class KSMLCompiler {

    private final SourceFile glslSource;
    private final SourceFile[] ksmlSources;
    private final String[] features;
    private final LineDirectiveMode lineDirectiveMode;

    private List<SourceFile> lastSources = List.of();

    public KSMLCompiler(
            SourceFile glslSource,
            SourceFile[] ksmlSources,
            String @Nullable [] features) {

        this(glslSource, ksmlSources, features, LineDirectiveMode.AUTO);
    }

    public KSMLCompiler(
            SourceFile glslSource,
            SourceFile[] ksmlSources,
            String @Nullable [] features,
            LineDirectiveMode lineDirectiveMode) {

        this.glslSource = Objects.requireNonNull(glslSource, "glslSource");
        this.ksmlSources = Objects.requireNonNull(ksmlSources, "ksmlSources").clone();
        this.features = features == null ? new String[0] : features.clone();
        this.lineDirectiveMode = Objects.requireNonNull(lineDirectiveMode, "lineDirectiveMode");
    }

    public String compile() {
        return compile(System.err, true);
    }

    public String compile(PrintStream diagnosticStream, boolean enableColor) {
        Objects.requireNonNull(diagnosticStream, "diagnosticStream");
        var contexts = new ArrayList<KSMLFileContext>(ksmlSources.length);
        var sources = new ArrayList<SourceFile>();
        lastSources = Collections.unmodifiableList(sources);
        var enabledFeatures = Set.copyOf(Arrays.asList(features));
        var modules = new HashSet<String>();

        for (SourceFile input : ksmlSources) {
            sources.add(SourceFile.fromString(input.fileName(), input.source()));
        }
        var shader = SourceFile.fromString(glslSource.fileName(), glslSource.source());
        sources.add(shader);

        var parsedShader = parseSource(
                shader,
                GLSLLexer::new,
                GLSLParser::new,
                GLSLParser::translation_unit,
                GeneralErrorListener::new);

        failOnErrors(sources, diagnosticStream, enableColor);

        int shaderVersion = 110;
        for (var directive : parsedShader.tree().compiler_directive()) {
            var version = directive.version_directive();
            if (version != null) {
                var parsedVersion = GLVersion.parse(shader, version.number().getText(),
                        version.profile() == null ? null : version.profile().getText(),
                        Utils.spanFromToken(version.start), true);
                if (parsedVersion != null) {
                    shaderVersion = parsedVersion.number();
                }
                break;
            }
        }

        failOnErrors(sources, diagnosticStream, enableColor);

        int lineOffset = lineDirectiveMode.lineOffset(shaderVersion);

        for (int fileId = 0; fileId < ksmlSources.length; fileId++) {
            SourceFile source = sources.get(fileId);
            var contextBuilder = new KSMLFileContext.Builder(source);
            var parsed = parseSource(
                    source,
                    KSMLLexer::new,
                    KSMLParser::new,
                    KSMLParser::ksmlTranslationUnit,
                    GeneralErrorListener::new);

            if (source.reportBuilder().containsError()) {
                continue;
            }

            new ParseTreeWalker().walk(new KSMLRewriteListener(parsed.rewriter(), source, contextBuilder,
                    fileId, enabledFeatures, lineOffset), parsed.tree());

            if (source.reportBuilder().containsError()) {
                continue;
            }

            var context = contextBuilder.build(parsed.rewriter().getText());
            if (!modules.add(context.moduleName)) {
                Utils.error(source, Utils.sourcePosition(source, 1, 0, 1),
                        "Duplicate module \"%s\"", context.moduleName);
            }

            contexts.add(context);
        }

        failOnErrors(sources, diagnosticStream, enableColor);

        var selected = new ArrayList<KSMLFileContext>();
        var postBuilder = new GLSLPostStrippingContext.Builder();
        new ParseTreeWalker().walk(new GLSLRewriteListener(parsedShader.rewriter(), shader, contexts,
                selected, postBuilder, ksmlSources.length, lineOffset), parsedShader.tree());
        var strippedGLSL = parsedShader.rewriter().getText();

        new KSMLChecker(selected).check();

        failOnErrors(sources, diagnosticStream, enableColor);

        sources.forEach(source -> source.printDiagnostics(diagnosticStream, enableColor));

        var result = new StringBuilder();
        assembleGLSLVersion(postBuilder.build(), result);
        injectFileIdMappingComment(sources, result);
        assembleKSMLModuleHeaders(selected, result);
        assembleKSMLModuleBodies(selected, result);
        assembleKSMLExportedStubs(selected, result);
        result.append("/* GLSL SHADER DEFINITION */\n\n");
        result.append(strippedGLSL.stripLeading());
        return result.toString();
    }

    public List<SourceFile> getSources() {
        return lastSources;
    }

    private void failOnErrors(List<SourceFile> sources, PrintStream diagnosticStream, boolean enableColor) {
        if (sources.stream().anyMatch(source -> source.reportBuilder().containsError())) {
            sources.forEach(source -> source.printDiagnostics(diagnosticStream, enableColor));
            throw new KSMLCompilationException(sources);
        }
    }

    private void injectFileIdMappingComment(final List<SourceFile> sources, final StringBuilder builder) {
        var fileIds = new ArrayList<Integer>(sources.size());
        for (int id = 0; id < sources.size(); id++) {
            fileIds.add(id);
        }
        fileIds.sort(Comparator.comparing((Integer id) -> sources.get(id).fileName()).thenComparingInt(Integer::intValue));
        builder.append("/* File Mapping:\n");
        for (int id : fileIds) {
            builder.append(String.format(" * %s <-> %d\n", sources.get(id).fileName(), id));
        }
        builder.append(" */\n");
    }

    private void assembleGLSLVersion(final GLSLPostStrippingContext ctx, final StringBuilder builder) {
        builder.append("/* GLSL SHADER PRE-DEFINITIONS */\n");
        if (ctx.version != null) {
            builder.append("#version ").append(ctx.version);
            if (ctx.versionProfile != null) {
                builder.append(' ').append(ctx.versionProfile);
            }
            builder.append('\n');
        }
        builder.append(ctx.extensions);
    }

    // Responsible for assembling KSML module's functions as function prototypes first so circular
    // dependencies can be properly resolved on assembled GLSL shader code.
    private void assembleKSMLModuleHeaders(final List<KSMLFileContext> contexts, final StringBuilder builder) {
        for (var ctx : contexts) {
            var glProfile = ctx.glProfile;
            if (glProfile == null && Integer.parseInt(ctx.glVersion) >= 150) {
                glProfile = "core";
            }

            builder.append(String.format("/* MODULE \"%s\" PROTOTYPE DEFINITIONS */\n", ctx.moduleName));
            builder.append("/* MODULE GL_VERSION: ").append(ctx.glVersion);
            if (glProfile != null) {
                builder.append(' ').append(glProfile);
            }
            builder.append(" */\n");
            builder.append("/* AUTO-GENERATED PROTOTYPES */\n\n");
            for (var declaration : ctx.declarationPrototypes) {
                builder.append(declaration).append(";\n");
            }
            builder.append('\n');
        }
    }

    private void assembleKSMLModuleBodies(final List<KSMLFileContext> contexts, final StringBuilder builder) {
        for (var ctx : contexts) {
            builder.append(String.format("/* MODULE \"%s\" DEFINITIONS */\n\n", ctx.moduleName));
            builder.append(ctx.strippedSource.stripLeading());
            builder.append('\n');
        }
    }

    private void assembleKSMLExportedStubs(final List<KSMLFileContext> contexts, final StringBuilder builder) {
        if (contexts.stream().allMatch(ctx -> ctx.exportedWrappers.isEmpty())) {
            return;
        }

        builder.append("/* EXPORTED STUB */\n\n");
        for (var ctx : contexts) {
            for (var stub : ctx.exportedWrappers) {
                builder.append(stub).append('\n');
            }
        }
    }

    private <LE extends Lexer, PA extends Parser, EL extends BaseErrorListener, T extends ParseTree> ParsedSource<T> parseSource(
            SourceFile source,
            Function<CharStream, LE> lexerCtor,
            Function<TokenStream, PA> parserCtor,
            Function<PA, T> topRuleApplier,
            Function<SourceFile, EL> errorListener) {

        CharStream sourceStream = new ANTLRInputStream(source.source().toCharArray(), source.source().length());
        LE lexer = lexerCtor.apply(sourceStream);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        PA parser = parserCtor.apply(tokenStream);
        lexer.removeErrorListeners();
        lexer.addErrorListener(errorListener.apply(source));
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener.apply(source));
        T parseTree = topRuleApplier.apply(parser);
        return new ParsedSource<>(new TokenStreamRewriter(tokenStream), parseTree);
    }

    private record ParsedSource<T extends ParseTree>(TokenStreamRewriter rewriter, T tree) {
    }
}
