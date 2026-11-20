package com.cleanroommc.ksmlc;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStreamRewriter;

public interface RewriterListener {

    TokenStreamRewriter getRewriter();

    default void deleteTokens(Token start, Token stop) {
        int end = stop.getTokenIndex();
        while (isWhitespace(end + 1)) {
            end++;
        }
        getRewriter().delete(start.getTokenIndex(), end);
    }

    default void replaceWithNewline(Token token) {
        int start = token.getTokenIndex();
        int end = start;
        while (isWhitespace(start - 1)) {
            start--;
        }
        while (isWhitespace(end + 1)) {
            end++;
        }
        getRewriter().replace(start, end, "\n");
    }

    private boolean isWhitespace(int index) {
        var tokens = getRewriter().getTokenStream();
        if (index < 0 || index >= tokens.size()) {
            return false;
        }

        Token token = tokens.get(index);
        return token.getChannel() == Token.HIDDEN_CHANNEL && token.getText().isBlank();
    }
}
