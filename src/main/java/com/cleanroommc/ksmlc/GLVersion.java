package com.cleanroommc.ksmlc;

import chaos.unity.nenggao.AbstractSpan;
import org.jspecify.annotations.Nullable;

import java.util.Set;

public record GLVersion(int number, @Nullable String profile) implements Comparable<GLVersion> {

    private static final Set<Integer> VERSIONS = Set.of(
            110,
            120,
            130,
            140,
            150,
            330,
            400,
            410,
            420,
            430,
            440,
            450,
            460);

    public static String normalizeProfile(String profile) {
        if ("core".equals(profile)) {
            return "core";
        }
        if ("compat".equals(profile) || "compatibility".equals(profile)) {
            return "compat";
        }
        throw new IllegalArgumentException("Profile must be \"core\", \"compat\", or \"compatibility\": \"" + profile + "\"");
    }

    static @Nullable GLVersion parse(
            SourceFile source,
            String number,
            @Nullable String profile,
            AbstractSpan span,
            boolean shader) {

        int version;
        try {
            version = Integer.parseInt(number);
        } catch (NumberFormatException e) {
            Utils.error(source, span, "Invalid GLSL version: \"%s\"", number);
            return null;
        }

        if (!VERSIONS.contains(version)) {
            Utils.error(source, span, "Unsupported GLSL version: \"%s\"", number);
            return null;
        }

        if (version < 150) {
            if (profile != null) {
                Utils.error(source, span,
                        "Profile \"%s\" cannot be declared for GL%d. Profiles require GL150 or later", profile, version);
                return null;
            }
            return new GLVersion(version, null);
        }

        if (profile != null && !("core".equals(profile)
                || "compatibility".equals(profile)
                || (!shader && "compat".equals(profile)))) {
            Utils.error(source, span, "Invalid GLSL profile: \"%s\"", profile);
            return null;
        }

        return new GLVersion(version, normalizeProfile(profile == null ? "core" : profile));
    }

    @Override
    public int compareTo(GLVersion other) {
        int versions = Integer.compare(number, other.number);
        return versions != 0 ? versions : Boolean.compare("compat".equals(profile), "compat".equals(other.profile));
    }

    @Override
    public String toString() {
        return "GL" + number + (profile == null ? "" : " " + profile);
    }
}
