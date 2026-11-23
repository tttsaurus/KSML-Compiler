package com.cleanroommc.ksmlc;

import chaos.unity.nenggao.AbstractSpan;
import com.cleanroommc.ksmlc.glsl.grammar.GLSLParser;
import com.cleanroommc.ksmlc.glsl.grammar.GLSLParserBaseListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.misc.Interval;
import org.jspecify.annotations.Nullable;

public class GLSLRewriteListener extends GLSLParserBaseListener implements RewriterListener {

    private static final Pattern IMPORT = Pattern.compile("//[ \\t]*@import[ \\t]+([A-Za-z_][A-Za-z_0-9]*)[ \\t]*");
    private final TokenStreamRewriter rewriter;
    private final SourceFile source;
    private final Map<String, KSMLFileContext> modules = new LinkedHashMap<>();
    private final Map<String, String> introducedBy = new LinkedHashMap<>();
    private final Map<String, AbstractSpan> importOrigins = new LinkedHashMap<>();
    private final List<KSMLFileContext> selected;
    private final GLSLPostStrippingContext.Builder builder;
    private final int fileId;
    private final int lineOffset;
    private final Set<String> imported = new HashSet<>();
    private final Set<String> nativeNames = new HashSet<>();
    private final Map<ParserRuleContext, Set<String>> structNames = new IdentityHashMap<>();
    private final List<KSMLFileContext.UnqualifiedFunctionCall> unqualifiedFunctionCalls = new ArrayList<>();
    private @Nullable GLVersion version;
    private boolean seenVersion;
    private boolean seenDeclaration;

    public GLSLRewriteListener(
            TokenStreamRewriter rewriter,
            SourceFile source,
            List<KSMLFileContext> contexts,
            List<KSMLFileContext> selected,
            GLSLPostStrippingContext.Builder builder,
            int fileId,
            int lineOffset) {

        this.rewriter = rewriter;
        this.source = source;
        this.selected = selected;
        this.builder = builder;
        this.fileId = fileId;
        this.lineOffset = lineOffset;
        contexts.forEach(ctx -> modules.putIfAbsent(ctx.moduleName, ctx));
        version = new GLVersion(110, null);
    }

    @Override
    public void enterTranslation_unit(GLSLParser.Translation_unitContext ctx) {
        for (var meta : ctx.importMeta()) {
            var matcher = IMPORT.matcher(meta.getText());
            if (!matcher.matches()) {
                Utils.error(source, Utils.spanFromToken(meta.start), "Expected \"// @import\" followed by exactly one module name");
                continue;
            }

            String name = matcher.group(1);
            if (!imported.add(name)) {
                Utils.error(source, Utils.spanFromToken(meta.start), "Duplicate import of module \"%s\"", name);
                continue;
            }
            var importSpan = Utils.spanFromToken(meta.start);
            importOrigins.put(name, importSpan);
            var target = modules.get(name);
            if (target == null) {
                Utils.error(source, Utils.spanFromToken(meta.start), "Unresolved imported module \"%s\"", name);
            } else {
                select(target, importSpan);
            }
        }
    }

    private void select(KSMLFileContext ctx, AbstractSpan importSpan) {
        if (selected.contains(ctx)) {
            return;
        }

        selected.add(ctx);
        importOrigins.putIfAbsent(ctx.moduleName, importSpan);
        for (String dependency : ctx.requiredModules) {
            var target = modules.get(dependency);
            if (target == null) {
                Utils.error(ctx.sourceFile, ctx.requiredModuleSpans.getOrDefault(dependency,
                        Utils.sourcePosition(ctx.sourceFile, 1, 0, 1)), "Unresolved required module \"%s\"", dependency);
            } else {
                introducedBy.putIfAbsent(dependency, ctx.moduleName);
                select(target, importSpan);
            }
        }
    }

    @Override
    public void enterVersion_directive(GLSLParser.Version_directiveContext ctx) {
        if (seenVersion || seenDeclaration) {
            Utils.error(source, Utils.spanFromToken(ctx.start), "\"#version\" must appear once before shader code");
        }

        seenVersion = true;
        builder.setVersion(ctx.number().getText());
        String profile = ctx.profile() == null ? null : ctx.profile().getText();
        builder.setVersionProfile(profile);
        version = GLVersion.parse(source, ctx.number().getText(), profile, Utils.spanFromToken(ctx.start), true);
        deleteTokens(ctx.start, ctx.stop);
    }

    @Override
    public void enterImportMeta(GLSLParser.ImportMetaContext ctx) {
        deleteTokens(ctx.start, ctx.stop);
    }

    @Override
    public void enterExtension_directive(GLSLParser.Extension_directiveContext ctx) {
        if (ctx.getParent().getParent() instanceof GLSLParser.Translation_unitContext) {
            builder.addExtension(rewriter.getText(Interval.of(ctx.start.getTokenIndex(), ctx.stop.getTokenIndex())));
            deleteTokens(ctx.start, ctx.stop);
        }
    }

    @Override
    public void enterExternal_declaration(GLSLParser.External_declarationContext ctx) {
        seenDeclaration = true;
        rewriter.insertBefore(ctx.start, String.format("\n#line %d %d\n", ctx.start.getLine() + lineOffset, fileId));
    }

    @Override
    public void exitTranslation_unit(GLSLParser.Translation_unitContext ctx) {
        KSMLChecker.checkUnqualifiedCalls(source, unqualifiedFunctionCalls, selected, nativeNames);
        for (var module : selected) {
            var required = GLVersion.parse(module.sourceFile, module.glVersion, module.glProfile,
                    Utils.sourcePosition(module.sourceFile, 1, 0, 1), false);
            if (version != null && required != null && !version.satisfies(required)) {
                var introducer = introducedBy.get(module.moduleName);
                String importHint = !imported.contains(module.moduleName) && introducer != null
                        ? String.format(" (indirect import. introduced by \"%s\")", introducer)
                        : "";
                Utils.notice(source, importOrigins.getOrDefault(module.moduleName, Utils.spanFromToken(ctx.start)),
                        "Module \"%s\" requires %s, but shader \"%s\" provides %s%s",
                        module.moduleName, required, source.fileName(), version, importHint);
            }
        }
    }

    @Override
    public void enterFunction_prototype(GLSLParser.Function_prototypeContext ctx) {
        nativeNames.add(ctx.IDENTIFIER().getText());
    }

    @Override
    public void enterStruct_specifier(GLSLParser.Struct_specifierContext ctx) {
        if (ctx.IDENTIFIER() != null) {
            ParserRuleContext scope = ctx;
            while (scope.getParent() != null
                    && !(scope instanceof GLSLParser.Compound_statementContext)
                    && !(scope instanceof GLSLParser.Compound_statement_no_new_scopeContext)
                    && !(scope instanceof GLSLParser.Iteration_statementContext)
                    && !(scope instanceof GLSLParser.Switch_statementContext)
                    && !(scope instanceof GLSLParser.StatementContext && scope.getParent() instanceof GLSLParser.Selection_rest_statementContext)) {
                scope = scope.getParent();
            }
            structNames.computeIfAbsent(scope, key -> new HashSet<>()).add(ctx.IDENTIFIER().getText());
        }
    }

    @Override
    public void enterTypeConstructor(GLSLParser.TypeConstructorContext ctx) {
        var type = ctx.type_specifier().type_specifier_nonarray().type_name();
        if (type != null && !Utils.nameVisible(ctx, structNames, type.getText())) {
            unqualifiedFunctionCalls.add(new KSMLFileContext.UnqualifiedFunctionCall(type.getText(),
                    Utils.spanFromToken(type.IDENTIFIER().getSymbol())));
        }
    }

    @Override
    public void enterFunctionCall(GLSLParser.FunctionCallContext ctx) {
        if (ctx.postfix_expression() instanceof GLSLParser.PrimaryContext primary
                && primary.primary_expression().variable_identifier() != null) {
            var name = primary.primary_expression().variable_identifier().IDENTIFIER().getSymbol();
            if (!Utils.nameVisible(ctx, structNames, name.getText())) {
                unqualifiedFunctionCalls.add(new KSMLFileContext.UnqualifiedFunctionCall(name.getText(), Utils.spanFromToken(name)));
            }
            return;
        }

        if (!(ctx.postfix_expression() instanceof GLSLParser.MemberAccessContext member)
                || !(member.postfix_expression() instanceof GLSLParser.PrimaryContext primary)
                || (primary.primary_expression().variable_identifier() == null
                && primary.primary_expression().module_keyword() == null)
                || member.field_selection().variable_identifier() == null) {
            return;
        }

        String module = primary.primary_expression().getText();
        String name = member.field_selection().getText();
        var target = modules.get(module);
        var parameters = ctx.function_call_parameters();
        int count = parameters == null || parameters.VOID() != null
                ? 0
                : parameters.assignment_expression().size();
        if (target == null && "length".equals(name) && count == 0) {
            return;
        }

        if (target == null) {
            Utils.error(source, Utils.spanFromToken(ctx.start), "Unresolved module \"%s\"", module);
        } else if (!imported.contains(module)) {
            Utils.error(source, Utils.spanFromToken(ctx.start), "Module \"%s\" must be imported with \"// @import\"", module);
        } else {
            KSMLChecker.checkCall(source, new KSMLFileContext.ModuleMemberReference(module, name,
                    Utils.spanFromToken(ctx.start), version, count), target, true);
            rewriter.replace(member.start, member.stop, Utils.synthesizeExportedMemberName(module, name));
        }
    }

    @Override
    public TokenStreamRewriter getRewriter() {
        return rewriter;
    }
}
