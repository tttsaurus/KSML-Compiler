package com.cleanroommc.ksmlc;

public enum LineDirectiveMode {
    LEGACY,
    MODERN,
    AUTO;

    int lineOffset(int shaderVersion) {
        return switch (this) {
            case LEGACY -> -1;
            case MODERN -> 0;
            case AUTO -> shaderVersion <= 150 ? -1 : 0;
        };
    }
}
