package com.slicercore.service;

import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.SliceProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Bambu CLI applies only the keys present in the file it is given, so the resolver must
 * fold the whole {@code inherits} chain into one file. Getting this wrong silently prices every
 * quote against default settings.
 */
class SlicerProfileResolverTest {

    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path profilesDir;

    private SlicerProfileResolver resolver;

    @BeforeEach
    void writeProfileTree() throws IOException {
        write("machine/Test Machine.json", """
                {"type": "machine", "inherits": "machine_base", "printer_model": "Test A1"}
                """);
        write("machine/machine_base.json", """
                {"type": "machine", "nozzle_diameter": ["0.4"], "printer_model": "base"}
                """);
        write("process/0.12mm Fine @BBL A1.json", """
                {"type": "process", "inherits": "process_base", "layer_height": "0.12"}
                """);
        write("process/process_base.json", """
                {"type": "process", "layer_height": "0.2", "wall_loops": "2"}
                """);
        write("process/0.24mm Draft @BBL A1.json", """
                {"type": "process", "inherits": "0.24mm Draft @BBL A1", "layer_height": "0.24"}
                """);
        write("filament/Bambu PLA Basic @BBL A1.json", """
                {"type": "filament", "inherits": "pla_base", "nozzle_temperature": ["220"]}
                """);
        write("filament/pla_base.json", """
                {"type": "filament", "filament_density": ["1.24"], "filament_type": ["PLA"]}
                """);

        resolver = new SlicerProfileResolver(profilesDir.toString(), "Test Machine", "Textured PEI Plate", true);
    }

    @Test
    void automaticSupportsAreSwitchedOnInTheProcessProfile() throws IOException {
        SliceProfile profile = resolver.resolve(FilamentType.PLA, PrintQuality.HIGH);
        JsonNode process = json.readTree(profile.processProfile().toFile());

        assertThat(process.path("enable_support").asText()).isEqualTo("1");
        assertThat(process.path("support_type").asText()).isEqualTo("tree(auto)");
    }

    @Test
    void supportsStayOffWhenDisabled() throws IOException {
        SlicerProfileResolver noSupports =
                new SlicerProfileResolver(profilesDir.toString(), "Test Machine", "Textured PEI Plate", false);

        JsonNode process = json.readTree(noSupports.resolve(FilamentType.PLA, PrintQuality.HIGH).processProfile().toFile());

        assertThat(process.has("enable_support")).isFalse();
    }

    @Test
    void leafOverridesParentAndInheritsIsDropped() throws IOException {
        SliceProfile profile = resolver.resolve(FilamentType.PLA, PrintQuality.HIGH);

        JsonNode process = json.readTree(profile.processProfile().toFile());
        assertThat(process.path("layer_height").asText()).isEqualTo("0.12");
        assertThat(process.path("wall_loops").asText()).isEqualTo("2");
        assertThat(process.has("inherits")).isFalse();

        JsonNode machine = json.readTree(profile.machineProfile().toFile());
        assertThat(machine.path("printer_model").asText()).isEqualTo("Test A1");
        assertThat(machine.path("nozzle_diameter").get(0).asText()).isEqualTo("0.4");

        JsonNode filament = json.readTree(profile.filamentProfile().toFile());
        assertThat(filament.path("filament_type").get(0).asText()).isEqualTo("PLA");
        assertThat(filament.path("nozzle_temperature").get(0).asText()).isEqualTo("220");
    }

    @Test
    void bedTypeIsInjectedIntoProcessProfileOnly() throws IOException {
        SliceProfile profile = resolver.resolve(FilamentType.PLA, PrintQuality.HIGH);

        assertThat(json.readTree(profile.processProfile().toFile()).path("curr_bed_type").asText())
                .isEqualTo("Textured PEI Plate");
        assertThat(json.readTree(profile.machineProfile().toFile()).has("curr_bed_type")).isFalse();
        assertThat(json.readTree(profile.filamentProfile().toFile()).has("curr_bed_type")).isFalse();
    }

    @Test
    void settingsArgumentJoinsMachineAndProcessWithSemicolon() {
        SliceProfile profile = resolver.resolve(FilamentType.PLA, PrintQuality.HIGH);

        assertThat(profile.settingsArgument())
                .isEqualTo(profile.machineProfile().toAbsolutePath() + ";" + profile.processProfile().toAbsolutePath());
        assertThat(profile.filamentsArgument()).isEqualTo(profile.filamentProfile().toAbsolutePath().toString());
    }

    @Test
    void flattenedFilesAreReused() {
        SliceProfile first = resolver.resolve(FilamentType.PLA, PrintQuality.HIGH);
        SliceProfile second = resolver.resolve(FilamentType.PLA, PrintQuality.HIGH);

        assertThat(second.processProfile()).isEqualTo(first.processProfile());
        assertThat(second.filamentProfile()).isEqualTo(first.filamentProfile());
    }

    @Test
    void missingProfileFailsLoudly() {
        assertThatThrownBy(() -> resolver.resolve(FilamentType.PETG, PrintQuality.HIGH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PolyLite PETG @BBL A1");
    }

    @Test
    void selfReferencingProfileDoesNotLoopForever() {
        assertThatThrownBy(() -> resolver.resolve(FilamentType.PLA, PrintQuality.COARSE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too deep");
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = profilesDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
