package com.slicercore.model;

import java.nio.file.Path;

/**
 * The three Bambu Studio profile files a slice is executed with. Profiles are always passed
 * explicitly; slicing without them silently falls back to defaults and prices against the
 * wrong machine (feasibility note V5).
 */
public record SliceProfile(Path machineProfile, Path processProfile, Path filamentProfile) {

    /** Value for {@code --load-settings}: machine and process profile separated by a semicolon. */
    public String settingsArgument() {
        return machineProfile.toAbsolutePath() + ";" + processProfile.toAbsolutePath();
    }

    /** Value for {@code --load-filaments}. */
    public String filamentsArgument() {
        return filamentProfile.toAbsolutePath().toString();
    }
}
