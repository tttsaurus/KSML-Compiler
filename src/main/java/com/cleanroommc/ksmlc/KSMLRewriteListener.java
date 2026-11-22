package com.cleanroommc.ksmlc;

import com.cleanroommc.ksmlc.glsl.grammar.KSMLParser.*;
import com.cleanroommc.ksmlc.glsl.grammar.KSMLParserBaseListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStreamRewriter;
import org.antlr.v4.runtime.misc.Interval;
import org.jspecify.annotations.Nullable;

public class KSMLRewriteListener extends KSMLParserBaseListener implements RewriterListener {

    private final TokenStreamRewriter rewriter;
    private final SourceFile source;
    private final KSMLFileContext.Builder builder;
    private final int fileId;
    private final Set<String> features;
    private final int lineOffset;
    private final Set<String> localFunctions = new HashSet<>();
    private final Set<String> dependencies = new HashSet<>();
    private @Nullable String moduleName;
    private @Nullable GLVersion moduleVersion;
    private @Nullable GLVersion functionVersion;
    private boolean enabled;
    private boolean exported;

    public KSMLRewriteListener(
            TokenStreamRewriter rewriter,
            SourceFile source,
            KSMLFileContext.Builder builder,
            int fileId,
            Set<String> features,
            int lineOffset) {

        this.rewriter = rewriter;
        this.source = source;
        this.builder = builder;
        this.fileId = fileId;
        this.features = features;
        this.lineOffset = lineOffset;
    }

    private boolean isEnabled(KsmlDeclarationContext declaration) {
        return declaration.declarationMeta().stream().allMatch(meta -> meta.featureMeta() == null || features.contains(meta.featureMeta().IDENTIFIER().getText()));
    }

    @Override
    public void enterKsmlTranslationUnit(KsmlTranslationUnitContext ctx) {
        for (var declaration : ctx.ksmlDeclaration()) {
            if (isEnabled(declaration)) {
                localFunctions.add(declaration
                        .codeBlock()
                        .function_definition()
                        .function_prototype()
                        .IDENTIFIER()
                        .getText());
            }
        }

        if (ctx.glVersionMeta().size() != 1) {
            Utils.error(source, Utils.spanFromToken(ctx.moduleMeta().start),
                    "A module must declare exactly one \"@gl_version\"");
        }
    }

    @Override
    public void enterModuleMeta(ModuleMetaContext ctx) {
        moduleName = ctx.IDENTIFIER().getText();
        builder.setModule(moduleName);
        deleteTokens(ctx.start, ctx.stop);
    }

    @Override
    public void enterRequiresMeta(RequiresMetaContext ctx) {
        builder.addRequiredModule(ctx.IDENTIFIER().getText(), Utils.spanFromToken(ctx.IDENTIFIER().getSymbol()));
        dependencies.add(ctx.IDENTIFIER().getText());
        deleteTokens(ctx.start, ctx.stop);
    }

    @Override
    public void enterGlVersionMeta(GlVersionMetaContext ctx) {
        String profile = ctx.glVersionIdent() == null ? null : ctx.glVersionIdent().getText();
        builder.setGlVersion(ctx.VERSION_NUMBER().getText(), profile);
        moduleVersion = GLVersion.parse(source, ctx.VERSION_NUMBER().getText(), profile, Utils.spanFromToken(ctx.start), false);
        deleteTokens(ctx.start, ctx.stop);
    }

    @Override
    public void enterKsmlDeclaration(KsmlDeclarationContext ctx) {
        enabled = isEnabled(ctx);
        exported = false;
        functionVersion = moduleVersion;
        if (!enabled) {
            deleteTokens(ctx.start, ctx.stop);
            return;
        }

        boolean hasRequirement = false;
        for (var meta : ctx.declarationMeta()) {
            if (meta.exportMeta() != null) {
                if (exported) {
                    Utils.error(source, Utils.spanFromToken(meta.start),
                            "A declaration may have only one \"@export\"");
                }
                exported = true;
            } else if (meta.glRequiresMeta() != null) {
                if (hasRequirement) {
                    Utils.error(source, Utils.spanFromToken(meta.start),
                            "A declaration may have only one \"@gl_requires\"");
                }
                hasRequirement = true;
                var requirement = meta.glRequiresMeta();
                functionVersion = GLVersion.parse(source, requirement.VERSION_NUMBER().getText(),
                        requirement.glVersionIdent() == null ? null : requirement.glVersionIdent().getText(),
                        Utils.spanFromToken(requirement.start), false);
            }
            deleteTokens(meta.start, meta.stop);
        }
    }

    @Override
    public void enterCodeBlock(CodeBlockContext ctx) {
        if (!enabled) {
            return;
        }

        deleteTokens(ctx.AT().getSymbol(), ctx.TRIPLE_QUOTE(0).getSymbol());
        replaceWithNewline(ctx.TRIPLE_QUOTE(1).getSymbol());
    }

    @Override
    public void enterFunction_definition(Function_definitionContext ctx) {
        if (!enabled) {
            return;
        }

        String moduleName = Objects.requireNonNull(this.moduleName, "moduleName");
        var prototype = ctx.function_prototype();
        String name = prototype.IDENTIFIER().getText();
        builder.addMemberName(name, name);
        if (exported) {
            builder.addExportable(
                    name,
                    name,
                    functionVersion == null ? null : Integer.toString(functionVersion.number()),
                    KSMLFileContext.ExportTargetType.Function);
        }

        int count = prototype.function_parameters() == null
                ? 0
                : prototype.function_parameters().parameter_declaration().size();
        if (prototype.function_parameters() != null && "void".equals(prototype.function_parameters().getText())) {
            count = 0;
        }

        builder.addFunction(new KSMLFileContext.FunctionInfo(
                name,
                count,
                exported,
                functionVersion,
                Utils.spanFromToken(prototype.start)));

        if (exported) {
            addExportedWrapper(prototype);
        }

        rewriter.replace(prototype.IDENTIFIER().getSymbol(), Utils.synthesizeModuleMemberName(moduleName, name));
        builder.addDeclarationPrototype(rewriter.getText(Interval.of(prototype.start.getTokenIndex(), prototype.stop.getTokenIndex())));
        rewriter.insertBefore(ctx.start, String.format("\n#line %d %d\n", ctx.start.getLine() + lineOffset, fileId));
    }

    private void addExportedWrapper(Function_prototypeContext prototype) {
        String moduleName = Objects.requireNonNull(this.moduleName, "moduleName");
        String name = prototype.IDENTIFIER().getText();
        var wrapper = new TokenStreamRewriter(rewriter.getTokenStream());
        wrapper.replace(prototype.IDENTIFIER().getSymbol(), Utils.synthesizeExportedMemberName(moduleName, name));
        var arguments = new ArrayList<String>();
        var usedNames = new HashSet<String>();
        if (prototype.function_parameters() != null && !"void".equals(prototype.function_parameters().getText())) {
            for (var parameter : prototype.function_parameters().parameter_declaration()) {
                if (parameter.parameter_declarator() != null) {
                    usedNames.add(parameter.parameter_declarator().IDENTIFIER().getText());
                }
            }
            for (var parameter : prototype.function_parameters().parameter_declaration()) {
                String argument;
                if (parameter.parameter_declarator() != null) {
                    argument = parameter.parameter_declarator().IDENTIFIER().getText();
                } else {
                    argument = "ksml_arg_" + arguments.size();
                    while (usedNames.contains(argument)) {
                        argument += "_";
                    }
                    usedNames.add(argument);
                    wrapper.insertAfter(parameter.stop, " " + argument);
                }
                arguments.add(argument);
            }
        }

        boolean returnsVoid = prototype
                .fully_specified_type()
                .type_specifier()
                .type_specifier_nonarray().VOID() != null;

        String signature = wrapper.getText(Interval.of(prototype.start.getTokenIndex(), prototype.stop.getTokenIndex()));

        builder.addExportedWrapper(String.format("#line %d %d\n%s { %s%s(%s); }\n",
                prototype.start.getLine() + lineOffset,
                fileId,
                signature,
                returnsVoid ? "" : "return ",
                Utils.synthesizeModuleMemberName(moduleName, name),
                String.join(", ", arguments)));
    }

    private void rewriteLocal(Token name, int count) {
        if (!enabled || !localFunctions.contains(name.getText())) {
            return;
        }

        String moduleName = Objects.requireNonNull(this.moduleName, "moduleName");
        builder.addModuleMemberReference(moduleName, name.getText(), Utils.spanFromToken(name), functionVersion, count);
        rewriter.replace(name, Utils.synthesizeModuleMemberName(moduleName, name.getText()));
    }

    @Override
    public void enterTypeConstructor(TypeConstructorContext ctx) {
        var type = ctx.type_specifier().type_specifier_nonarray().type_name();
        if (type != null) {
            var parameters = ctx.function_call_parameters();
            int count = parameters == null || parameters.VOID() != null ? 0 : parameters.assignment_expression().size();
            rewriteLocal(type.IDENTIFIER().getSymbol(), count);
        }
    }

    @Override
    public void enterFunctionCall(FunctionCallContext ctx) {
        if (!enabled) {
            return;
        }

        var parameters = ctx.function_call_parameters();
        int count = parameters == null || parameters.VOID() != null ? 0 : parameters.assignment_expression().size();
        if (ctx.postfix_expression() instanceof PrimaryContext primary && primary.primary_expression().variable_identifier() != null) {
            rewriteLocal(primary.primary_expression().variable_identifier().IDENTIFIER().getSymbol(), count);
        } else if (ctx.postfix_expression() instanceof MemberAccessContext member
                && member.postfix_expression() instanceof PrimaryContext primary
                && (primary.primary_expression().variable_identifier() != null
                || primary.primary_expression().module_keyword() != null)
                && member.field_selection().variable_identifier() != null) {

            String module = primary.primary_expression().getText();
            String name = member.field_selection().getText();
            if ("length".equals(name) && count == 0 && !module.equals(moduleName) && !dependencies.contains(module)) {
                return;
            }

            builder.addModuleMemberReference(module, name, Utils.spanFromToken(ctx.start), functionVersion, count);
            rewriter.replace(member.start, member.stop, Utils.synthesizeModuleMemberName(module, name));
        }
    }

    @Override
    public TokenStreamRewriter getRewriter() {
        return rewriter;
    }
}
