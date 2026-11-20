package com.cleanroommc.ksmlc;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.LexerNoViableAltException;
import org.jspecify.annotations.Nullable;

public class GeneralErrorListener extends BaseErrorListener {

    private final SourceFile source;

    public GeneralErrorListener(SourceFile source) {
        this.source = source;
    }

    @Override
    public void syntaxError(
        Recognizer<?, ?> recognizer,
        @Nullable Object offendingSymbol,
        int line,
        int charPositionInLine,
        String msg,
        @Nullable RecognitionException e) {

        int length = offendingSymbol instanceof Token token && token.getType() != Token.EOF
                ? token.getText().split("\\r?\\n", -1)[0].length()
                : e instanceof LexerNoViableAltException ? 1 : 0;

        Utils.error(source, Utils.sourcePosition(source, line, charPositionInLine, length),
                "Syntax error: %s", msg);
    }
}
