package com.cleanroommc.ksmlc;

import org.jspecify.annotations.Nullable;

public class GLSLPostStrippingContext {

    public final @Nullable String version;
    public final @Nullable String versionProfile;
    public final String extensions;

    private GLSLPostStrippingContext(
            final @Nullable String version,
            final @Nullable String versionProfile,
            final String extensions) {

        this.version = version;
        this.versionProfile = versionProfile;
        this.extensions = extensions;
    }

    public static class Builder {

        private @Nullable String version;
        private @Nullable String versionProfile;
        private final StringBuilder extensions = new StringBuilder();

        public void addExtension(String directive) {
            extensions.append(directive).append('\n');
        }

        public void setVersion(final String version) {
            this.version = version;
        }

        public void setVersionProfile(final @Nullable String versionProfile) {
            this.versionProfile = versionProfile;
        }

        public GLSLPostStrippingContext build() {
            return new GLSLPostStrippingContext(version, versionProfile, extensions.toString());
        }
    }
}
