package com.cleanroommc.ksmlc;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public class KSMLChecker {

    private final List<KSMLFileContext> contexts;
    private final String profileHint;

    KSMLChecker(List<KSMLFileContext> contexts, String profileHint) {
        this.contexts = contexts;
        this.profileHint = profileHint;
    }

    public void check() {
        var versions = new HashMap<String, GLVersion>();
        for (var ctx : contexts) {
            var version = GLVersion.parse(ctx.sourceFile, ctx.glVersion, ctx.glProfile, profileHint,
                    Utils.sourcePosition(ctx.sourceFile, 1, 0, 1), false);
            if (version != null) {
                versions.put(ctx.moduleName, version);
            }
        }

        for (var ctx : contexts) {
            var provided = versions.get(ctx.moduleName);
            for (var dependency : new LinkedHashSet<>(ctx.requiredModules)) {
                var required = versions.get(dependency);
                if (provided != null && required != null && provided.compareTo(required) < 0) {
                    Utils.warning(ctx.sourceFile, ctx.requiredModuleSpans.getOrDefault(dependency,
                                    Utils.sourcePosition(ctx.sourceFile, 1, 0, 1)),
                            "Module \"%s\" requires %s, but module \"%s\" provides %s",
                            dependency, required, ctx.moduleName, provided);
                }
            }

            for (var ref : ctx.moduleMemberReferences) {
                if (!ctx.moduleName.equals(ref.module()) && !ctx.requiredModules.contains(ref.module())) {
                    Utils.error(ctx.sourceFile, ref.span(), "Module \"%s\" must be declared with \"@requires\"", ref.module());
                    continue;
                }

                var target = contexts.stream().filter(c -> c.moduleName.equals(ref.module())).findFirst().orElse(null);
                if (target == null) {
                    Utils.error(ctx.sourceFile, ref.span(), "Unresolved module \"%s\"", ref.module());
                    continue;
                }

                checkCall(ctx.sourceFile, ref, target, false);
            }
        }
    }

    static void checkCall(
            SourceFile source,
            KSMLFileContext.ModuleMemberReference ref,
            KSMLFileContext target,
            boolean shader) {

        var functions = target.functions.stream().filter(f -> f.name().equals(ref.memberName())).toList();
        if (functions.isEmpty()) {
            Utils.error(source, ref.span(), "Unresolved function \"%s#%s\"", ref.module(), ref.memberName());
            return;
        }

        if (shader) {
            functions = functions.stream().filter(KSMLFileContext.FunctionInfo::exported).toList();
            if (functions.isEmpty()) {
                Utils.error(source, ref.span(), "Function \"%s#%s\" is internal (missing \"@export\")", ref.module(), ref.memberName());
                return;
            }
        }

        final int count = ref.argumentCount();
        functions = functions.stream().filter(f -> count < 0 || f.parameterCount() == count).toList();
        if (functions.isEmpty()) {
            Utils.error(source, ref.span(), "No overload of \"%s#%s\" accepts %d arguments", ref.module(), ref.memberName(), count);
            return;
        }

        if (ref.callerVersion() != null && functions.stream().allMatch(f -> f.version() != null && ref.callerVersion().compareTo(f.version()) < 0)) {
            var required = functions.stream()
                    .map(KSMLFileContext.FunctionInfo::version)
                    .filter(Objects::nonNull)
                    .min(GLVersion::compareTo)
                    .orElseThrow();

            Utils.warning(source, ref.span(), "Function \"%s#%s\" requires %s, but caller in \"%s\" provides %s",
                    ref.module(), ref.memberName(), required, source.fileName(), ref.callerVersion());
        }
    }
}
