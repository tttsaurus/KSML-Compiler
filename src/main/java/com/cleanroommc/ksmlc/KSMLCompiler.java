package com.cleanroommc.ksmlc;

import com.cleanroommc.ksmlc.glsl.grammar.GLSLLexer;
import com.cleanroommc.ksmlc.glsl.grammar.GLSLParser;
import com.cleanroommc.ksmlc.glsl.grammar.KSMLLexer;
import com.cleanroommc.ksmlc.glsl.grammar.KSMLParser;
import org.antlr.v4.runtime.*;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeListener;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.function.Function;

public class KSMLCompiler {

    private final SourceFile glslSource;
    private final SourceFile[] ksmlSources;
    private final String[] features;

    private int fileIdCounter = 0;
    private final String profileHint;
    private List<SourceFile> lastSources = List.of();

    public KSMLCompiler(
            SourceFile glslSource,
            SourceFile[] ksmlSources,
            String @Nullable [] features,
            String profileContextHint) {

        this.glslSource = Objects.requireNonNull(glslSource, "glslSource");
        this.ksmlSources = Objects.requireNonNull(ksmlSources, "ksmlSources").clone();
        this.features = features == null ? new String[0] : features.clone();
        this.profileHint = GLVersion.normalizeProfile(profileContextHint);
    }

    public String compile() {
        fileIdCounter = 0;
        var fileIdMap = new TreeMap<String, Integer>();
        var contexts = new ArrayList<KSMLFileContext>(ksmlSources.length);
        var sources = new ArrayList<SourceFile>();
        lastSources = Collections.unmodifiableList(sources);
        var enabledFeatures = Set.copyOf(Arrays.asList(features));
        var modules = new HashSet<String>();

        for (SourceFile input : ksmlSources) {
            SourceFile source = SourceFile.fromString(input.fileName(), input.source());
            sources.add(source);

            var contextBuilder = new KSMLFileContext.Builder(source);
            int fileId = registerFileId(fileIdMap, source.fileName());
            var stripped = compileSource(
                    source,
                    KSMLLexer::new,
                    KSMLParser::new,
                    KSMLParser::ksmlTranslationUnit,
                    GeneralErrorListener::new,
                    rewriter -> new KSMLRewriteListener(rewriter, source, contextBuilder, fileId, enabledFeatures, profileHint));

            if (source.reportBuilder().containsError()) {
                continue;
            }

            var context = contextBuilder.build(stripped);
            if (!modules.add(context.moduleName)) {
                Utils.error(source, Utils.sourcePosition(source, 1, 0, 1),
                        "Duplicate module \"%s\"", context.moduleName);
            }

            contexts.add(context);
        }

        failOnErrors(sources);

        var shader = SourceFile.fromString(glslSource.fileName(), glslSource.source());
        sources.add(shader);

        var selected = new ArrayList<KSMLFileContext>();
        var postBuilder = new GLSLPostStrippingContext.Builder();
        int fileId = registerFileId(fileIdMap, shader.fileName());
        var strippedGLSL = compileSource(
                shader,
                GLSLLexer::new,
                GLSLParser::new,
                GLSLParser::translation_unit,
                GeneralErrorListener::new,
                rewriter -> new GLSLRewriteListener(rewriter, shader, contexts, selected, postBuilder, fileId, profileHint));

        new KSMLChecker(selected, profileHint).check();

        failOnErrors(sources);

        sources.forEach(source -> source.reportBuilder().print(System.err));

        var result = new StringBuilder();
        assembleGLSLVersion(postBuilder.build(), result);
        injectFileIdMappingComment(fileIdMap, result);
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

    private void failOnErrors(List<SourceFile> sources) {
        if (sources.stream().anyMatch(source -> source.reportBuilder().containsError())) {
            sources.forEach(source -> source.reportBuilder().print(System.err));
            throw new KSMLCompilationException(sources);
        }
    }

    private void injectFileIdMappingComment(final Map<String, Integer> fileIdMap, final StringBuilder builder) {
        builder.append("/* File Mapping:\n");
        fileIdMap.forEach((key, value) -> {
            builder.append(String.format(" * %s <-> %d\n", key, value));
        });
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
            if (glProfile == null) {
                glProfile = Integer.parseInt(ctx.glVersion) < 150 ? "compat" : profileHint;
            }

            builder.append(String.format("/* MODULE \"%s\" PROTOTYPE DEFINITIONS */\n", ctx.moduleName));
            builder.append(String.format("/* MODULE GL_VERSION: %s %s */\n", ctx.glVersion, glProfile));
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

    private int registerFileId(Map<String, Integer> fileIdMap, String filePath) {
        var id = fileIdMap.get(filePath);
        if (id == null) {
            fileIdMap.put(filePath, id = fileIdCounter++);
        }
        return id;
    }

    private <LE extends Lexer, PA extends Parser, EL extends BaseErrorListener, LI extends ParseTreeListener> String compileSource(
            SourceFile source,
            Function<CharStream, LE> lexerCtor,
            Function<TokenStream, PA> parserCtor,
            Function<PA, ParseTree> topRuleApplier,
            Function<SourceFile, EL> errorListener,
            Function<TokenStreamRewriter, LI> listenerCtor) {

        CharStream sourceStream = new ANTLRInputStream(source.source().toCharArray(), source.source().length());
        LE lexer = lexerCtor.apply(sourceStream);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        PA parser = parserCtor.apply(tokenStream);
        lexer.removeErrorListeners();
        lexer.addErrorListener(errorListener.apply(source));
        parser.removeErrorListeners();
        parser.addErrorListener(errorListener.apply(source));
        ParseTree parseTree = topRuleApplier.apply(parser);
        if (source.reportBuilder().containsError()) {
            return "";
        }
        TokenStreamRewriter rewriter = new TokenStreamRewriter(tokenStream);
        ParseTreeWalker walker = new ParseTreeWalker();
        walker.walk(listenerCtor.apply(rewriter), parseTree);
        return rewriter.getText();
    }
}
