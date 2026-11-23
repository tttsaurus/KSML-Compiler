package com.cleanroommc.ksmlc;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class KSMLChecker {

    // Desktop GLSL built-ins, including compatibility functions. Name recognition only;
    // signature and version validation remain the responsibility of the GLSL compiler.
    // https://github.com/KhronosGroup/GLSL/blob/main/chapters/builtinfunctions.adoc
    private static final Set<String> GLSL_BUILTIN_FUNCTIONS = Set.of(
            "EmitStreamVertex", "EmitVertex", "EndPrimitive", "EndStreamPrimitive", "abs", "acos", "acosh", "all",
            "allInvocations", "allInvocationsEqual", "any", "anyInvocation", "asin", "asinh", "atan", "atanh",
            "atomicAdd", "atomicAnd", "atomicCompSwap", "atomicCounter", "atomicCounterAdd", "atomicCounterAnd",
            "atomicCounterCompSwap", "atomicCounterDecrement", "atomicCounterExchange", "atomicCounterIncrement",
            "atomicCounterMax", "atomicCounterMin", "atomicCounterOr", "atomicCounterSubtract", "atomicCounterXor",
            "atomicExchange", "atomicMax", "atomicMin", "atomicOr", "atomicXor", "barrier", "bitCount",
            "bitfieldExtract", "bitfieldInsert", "bitfieldReverse", "ceil", "clamp", "cos", "cosh", "cross", "dFdx",
            "dFdxCoarse", "dFdxFine", "dFdy", "dFdyCoarse", "dFdyFine", "degrees", "determinant", "distance", "dot",
            "equal", "exp", "exp2", "faceforward", "findLSB", "findMSB", "floatBitsToInt", "floatBitsToUint",
            "floor", "fma", "fract", "frexp", "ftransform", "fwidth", "fwidthCoarse", "fwidthFine", "greaterThan",
            "greaterThanEqual", "groupMemoryBarrier", "imageAtomicAdd", "imageAtomicAnd", "imageAtomicCompSwap",
            "imageAtomicExchange", "imageAtomicMax", "imageAtomicMin", "imageAtomicOr", "imageAtomicXor",
            "imageLoad", "imageSamples", "imageSize", "imageStore", "imulExtended", "intBitsToFloat",
            "interpolateAtCentroid", "interpolateAtOffset", "interpolateAtSample", "inverse", "inversesqrt", "isinf",
            "isnan", "ldexp", "length", "lessThan", "lessThanEqual", "log", "log2", "matrixCompMult", "max",
            "memoryBarrier", "memoryBarrierAtomicCounter", "memoryBarrierBuffer", "memoryBarrierImage",
            "memoryBarrierShared", "min", "mix", "mod", "modf", "noise1", "noise2", "noise3", "noise4", "normalize",
            "not", "notEqual", "outerProduct", "packDouble2x32", "packHalf2x16", "packSnorm2x16", "packSnorm4x8",
            "packUnorm2x16", "packUnorm4x8", "pow", "radians", "reflect", "refract", "round", "roundEven",
            "shadow1D", "shadow1DLod", "shadow1DProj", "shadow1DProjLod", "shadow2D", "shadow2DLod", "shadow2DProj",
            "shadow2DProjLod", "sign", "sin", "sinh", "smoothstep", "sqrt", "step", "tan", "tanh", "texelFetch",
            "texelFetchOffset", "texture", "texture1D", "texture1DLod", "texture1DProj", "texture1DProjLod",
            "texture2D", "texture2DLod", "texture2DProj", "texture2DProjLod", "texture3D", "texture3DLod",
            "texture3DProj", "texture3DProjLod", "textureCube", "textureCubeLod", "textureGather",
            "textureGatherOffset", "textureGatherOffsets", "textureGrad", "textureGradOffset", "textureLod",
            "textureLodOffset", "textureOffset", "textureProj", "textureProjGrad", "textureProjGradOffset",
            "textureProjLod", "textureProjLodOffset", "textureProjOffset", "textureQueryLevels", "textureQueryLod",
            "textureSamples", "textureSize", "transpose", "trunc", "uaddCarry", "uintBitsToFloat", "umulExtended",
            "unpackDouble2x32", "unpackHalf2x16", "unpackSnorm2x16", "unpackSnorm4x8", "unpackUnorm2x16",
            "unpackUnorm4x8", "usubBorrow");

    private final List<KSMLFileContext> contexts;

    KSMLChecker(List<KSMLFileContext> contexts) {
        this.contexts = contexts;
    }

    public void check() {
        var versions = new HashMap<String, GLVersion>();
        for (var ctx : contexts) {
            var version = GLVersion.parse(ctx.sourceFile, ctx.glVersion, ctx.glProfile,
                    Utils.sourcePosition(ctx.sourceFile, 1, 0, 1), false);
            if (version != null) {
                versions.put(ctx.moduleName, version);
            }
        }

        for (var ctx : contexts) {
            var provided = versions.get(ctx.moduleName);
            for (var dependency : ctx.requiredModules) {
                var required = versions.get(dependency);
                if (provided != null && required != null && !provided.satisfies(required)) {
                    Utils.notice(ctx.sourceFile, ctx.requiredModuleSpans.getOrDefault(dependency, Utils.sourcePosition(ctx.sourceFile, 1, 0, 1)),
                            "Module \"%s\" requires %s, but module \"%s\" provides %s",
                            dependency, required, ctx.moduleName, provided);
                }
            }

            checkUnqualifiedCalls(ctx.sourceFile, ctx.unqualifiedFunctionCalls,
                    contexts.stream().filter(target -> target.moduleName.equals(ctx.moduleName)
                            || ctx.requiredModules.contains(target.moduleName)).toList(), Set.of());

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

    static void checkUnqualifiedCalls(
            SourceFile source,
            List<KSMLFileContext.UnqualifiedFunctionCall> calls,
            List<KSMLFileContext> contexts,
            Set<String> nativeNames) {

        for (var call : calls) {
            if (GLSL_BUILTIN_FUNCTIONS.contains(call.name()) || nativeNames.contains(call.name())) {
                continue;
            }
            for (var target : contexts) {
                if (target.functions.stream().anyMatch(function -> function.name().equals(call.name()))) {
                    Utils.error(source, call.span(), "Function \"%s#%s\" must be called as \"%s.%s(...)\"",
                            target.moduleName, call.name(), target.moduleName, call.name());
                    break;
                }
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

        if (ref.callerVersion() != null && functions.stream().allMatch(f -> f.version() != null && !ref.callerVersion().satisfies(f.version()))) {
            var required = functions.stream()
                    .map(KSMLFileContext.FunctionInfo::version)
                    .filter(Objects::nonNull)
                    .min(Comparator.comparingInt(GLVersion::number))
                    .orElseThrow();

            Utils.warning(source, ref.span(), "Function \"%s#%s\" requires %s, but caller in \"%s\" provides %s",
                    ref.module(), ref.memberName(), required, source.fileName(), ref.callerVersion());
        }
    }
}
