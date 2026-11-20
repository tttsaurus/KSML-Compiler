package com.cleanroommc.ksmlc;

import chaos.unity.nenggao.AbstractSpan;
import com.diogonunes.jcolor.Attribute;
import org.antlr.v4.runtime.Token;
import org.jspecify.annotations.Nullable;

public final class Utils {

    public static String synthesizeModuleMemberName(String moduleName, String memberName) {
        return String.format("ksml_%d_%s_%s", moduleName.length(), moduleName, memberName);
    }

    public static String synthesizeExportedMemberName(String moduleName, String memberName) {
        return String.format("ksml_export_%d_%s_%s", moduleName.length(), moduleName, memberName);
    }

    public static void error(SourceFile source, AbstractSpan span, String message, @Nullable Object... args) {
        source.reportBuilder()
                .error(span, message, args)
                .label(span, "Error occurs here")
                .color(Attribute.RED_TEXT())
                .build()
                .build();
    }

    public static void warning(SourceFile source, AbstractSpan span, String message, @Nullable Object... args) {
        source.reportBuilder()
                .warning(span, message, args)
                .label(span, "Version requirement")
                .color(Attribute.YELLOW_TEXT())
                .build()
                .build();
    }

    public static AbstractSpan spanFromToken(Token token) {
        String text = token.getText();
        int length = token.getType() == Token.EOF || text == null
                ? 0
                : text.split("\\r?\\n", -1)[0].length();
        return AbstractSpan.singleLine(
                Math.max(1, token.getLine()),
                Math.max(0, token.getCharPositionInLine()),
                Math.max(0, token.getCharPositionInLine()) + length);
    }

    public static AbstractSpan sourcePosition(SourceFile source, int line, int column, int length) {
        String[] lines = source.source().split("\\r\\n|\\r|\\n", -1);
        int count = lines.length;
        if (line > count) {
            line = count;
            column = lines[line - 1].length();
        }
        line = Math.max(1, line);
        column = Math.clamp(column, 0, lines[line - 1].length());
        return AbstractSpan.singleLine(
                line,
                column,
                Math.min(lines[line - 1].length(), column + Math.max(0, length)));
    }
}
