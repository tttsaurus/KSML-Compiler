package com.cleanroommc.ksmlc;

import chaos.unity.nenggao.AbstractSpan;
import chaos.unity.nenggao.FileReportBuilder;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class KSMLFileContext {

    public record Exportable(
            String globalVisibleName,
            String ksmlVisibleName,
            @Nullable String version,
            ExportTargetType targetType) {
    }

    public enum ExportTargetType {
        FUNCTION
    }

    public record FunctionInfo(
            String name,
            int parameterCount,
            boolean exported,
            @Nullable GLVersion version,
            AbstractSpan span) {
    }

    public record ModuleMemberReference(
            String module,
            String memberName,
            AbstractSpan span,
            @Nullable GLVersion callerVersion,
            int argumentCount) {
    }

    public record UnqualifiedFunctionCall(String name, AbstractSpan span) {
    }

    public final SourceFile sourceFile;
    public final FileReportBuilder fileReportBuilder;
    // Per file context
    public final String moduleName;
    public final String glVersion;
    public final @Nullable String glProfile;
    public final List<String> requiredModules;
    public final Map<String, AbstractSpan> requiredModuleSpans;
    // Overloads share a function name
    // The functions list stores metadata for each overload
    public final HashMap<String, String> memberNames;
    public final HashMap<String, Exportable> exported;
    // Non-visible context
    public final List<String> declarationPrototypes;
    public final List<ModuleMemberReference> moduleMemberReferences;
    public final List<UnqualifiedFunctionCall> unqualifiedFunctionCalls;
    public final List<FunctionInfo> functions;
    public final List<String> exportedWrappers;
    public final String strippedSource;

    private KSMLFileContext(
            final SourceFile sourceFile,
            final String moduleName,
            final String glVersion,
            final @Nullable String glProfile,
            final List<String> requiredModules,
            final Map<String, AbstractSpan> requiredModuleSpans,
            final HashMap<String, String> memberNames,
            final HashMap<String, Exportable> exported,
            final List<String> declarationPrototypes,
            final List<ModuleMemberReference> moduleMemberReferences,
            final List<UnqualifiedFunctionCall> unqualifiedFunctionCalls,
            final List<FunctionInfo> functions,
            final List<String> exportedWrappers,
            final String strippedSource) {

        this.sourceFile = sourceFile;
        this.fileReportBuilder = sourceFile.reportBuilder();
        this.moduleName = moduleName;
        this.glVersion = glVersion;
        this.glProfile = glProfile;
        this.requiredModules = List.copyOf(requiredModules);
        this.requiredModuleSpans = Map.copyOf(requiredModuleSpans);
        this.memberNames = memberNames;
        this.exported = exported;
        this.declarationPrototypes = declarationPrototypes;
        this.moduleMemberReferences = moduleMemberReferences;
        this.unqualifiedFunctionCalls = List.copyOf(unqualifiedFunctionCalls);
        this.functions = List.copyOf(functions);
        this.exportedWrappers = List.copyOf(exportedWrappers);
        this.strippedSource = strippedSource;
    }

    public static class Builder {

        private final SourceFile sourceFile;
        private @Nullable String moduleName;
        private @Nullable String glVersion;
        private @Nullable String glProfile;
        private final List<String> requiredModules = new ArrayList<>();
        private final Map<String, AbstractSpan> requiredModuleSpans = new HashMap<>();
        private final HashMap<String, String> memberNames = new HashMap<>();
        private final HashMap<String, Exportable> exported = new HashMap<>();
        private final List<String> declarationPrototypes = new ArrayList<>();
        private final List<ModuleMemberReference> moduleMemberReferences = new ArrayList<>();
        private final List<UnqualifiedFunctionCall> unqualifiedFunctionCalls = new ArrayList<>();
        private final List<FunctionInfo> functions = new ArrayList<>();
        private final List<String> exportedWrappers = new ArrayList<>();

        public Builder(final SourceFile sourceFile) {
            this.sourceFile = sourceFile;
        }

        public void setModule(final String moduleName) {
            this.moduleName = moduleName;
        }

        public void setGlVersion(final String glVersion, final @Nullable String glProfile) {
            this.glVersion = glVersion;
            this.glProfile = glProfile;
        }

        public void addRequiredModule(String moduleName, AbstractSpan span) {
            requiredModules.add(moduleName);
            requiredModuleSpans.putIfAbsent(moduleName, span);
        }

        public boolean addMemberName(final String memberName, final String declaredName) {
            if (!memberNames.containsKey(memberName)) {
                memberNames.put(memberName, declaredName);
                return true;
            }
            return false;
        }

        public void addExportable(
                final String globalVisibleName,
                final String ksmlVisibleName,
                final @Nullable String version,
                final ExportTargetType targetType) {

            exported.put(globalVisibleName, new Exportable(globalVisibleName, ksmlVisibleName, version, targetType));
        }

        public void addDeclarationPrototype(final String declaration) {
            declarationPrototypes.add(declaration);
        }

        public void addModuleMemberReference(
                String module,
                String memberName,
                AbstractSpan span,
                @Nullable GLVersion callerVersion,
                int argumentCount) {

            moduleMemberReferences.add(new ModuleMemberReference(module, memberName, span, callerVersion, argumentCount));
        }

        public void addFunction(FunctionInfo function) {
            functions.add(function);
        }

        public void addUnqualifiedFunctionCall(String name, AbstractSpan span) {
            unqualifiedFunctionCalls.add(new UnqualifiedFunctionCall(name, span));
        }

        public void addExportedWrapper(String wrapper) {
            exportedWrappers.add(wrapper);
        }

        public KSMLFileContext build(String strippedSource) {
            return new KSMLFileContext(
                    sourceFile,
                    Objects.requireNonNull(moduleName, "moduleName"),
                    Objects.requireNonNull(glVersion, "glVersion"),
                    glProfile,
                    requiredModules,
                    requiredModuleSpans,
                    memberNames,
                    exported,
                    declarationPrototypes,
                    moduleMemberReferences,
                    unqualifiedFunctionCalls,
                    functions,
                    exportedWrappers,
                    strippedSource);
        }
    }
}
