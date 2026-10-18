package com.slicercore.service;

import com.slicercore.model.BoundingBox;
import com.slicercore.model.SliceProfile;
import com.slicercore.model.SliceQuoteResult;
import com.slicercore.model.SlicingException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Exercises the process handling and output parsing against a shell script that imitates the
 * Bambu CLI: it records its arguments and writes canned result.json and G-code files. The real
 * slicer is covered by the slow, tagged integration test only.
 */
class SlicerExecutionServiceTest {

    private static final String SCRIPT_PREAMBLE = """
            #!/bin/sh
            out=""; prev=""
            for a in "$@"; do
              if [ "$prev" = "--outputdir" ]; then out="$a"; fi
              prev="$a"
            done
            printf '%s\\n' "$@" > "$out/args.txt"
            """;

    private static final String TWO_PLATE_RESULT = """
            {"return_code": 0, "error_string": "Success.", "layer_height": 0.2, "sliced_plates": [
              {"total_predication": 100.0, "filaments": [{"total_used_g": 1.5}], "objects": [
                {"name": "a", "triangle_count": 10, "bbox": {"x": 0, "y": 0, "z": 0, "width": 10, "depth": 20, "height": 5}},
                {"name": "b", "triangle_count": 10, "bbox": {"x": 50, "y": 0, "z": 0, "width": 10, "depth": 20, "height": 5}}]},
              {"total_predication": 50.0, "filaments": [{"total_used_g": 0.5}], "objects": [
                {"name": "c", "triangle_count": 10, "bbox": {"x": 0, "y": 0, "z": 0, "width": 30, "depth": 5, "height": 5}}]}
            ]}
            """;

    @TempDir
    Path tempDir;

    private Path model;
    private Path outputDir;
    private SliceProfile profile;

    @BeforeAll
    static void requiresPosixShell() {
        assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"),
                "fake slicer is a POSIX shell script");
    }

    @BeforeEach
    void stageInputs() throws IOException {
        model = Files.writeString(tempDir.resolve("model.stl"), "solid cube endsolid cube");
        outputDir = Files.createDirectory(tempDir.resolve("out"));
        profile = new SliceProfile(tempDir.resolve("machine.json"), tempDir.resolve("process.json"),
                tempDir.resolve("filament.json"));
    }

    @Test
    void sumsPlatesAndPassesCloneArgumentsForBatches() throws IOException {
        SlicerExecutionService service = service(fakeSlicer("""
                cat > "$out/result.json" <<'JSON'
                %s
                JSON
                printf '; total filament length [mm] : 1000.5\\n' > "$out/plate_1.gcode"
                printf 'G1 X1\\n; total filament length [mm] : 200\\n' > "$out/plate_2.gcode"
                """.formatted(TWO_PLATE_RESULT)), 30);

        SliceQuoteResult result = service.executeSlicingQuote(model, outputDir, profile, 3);

        assertThat(result.plateCount()).isEqualTo(2);
        assertThat(result.totalPredicationSeconds()).isEqualTo(150.0);
        assertThat(result.filamentLengthMm()).isEqualTo(1200.5);
        assertThat(result.slicerReportedGrams()).isEqualTo(2.0);
        assertThat(result.triangleCountPerCopy()).isEqualTo(10);
        assertThat(result.copies()).isEqualTo(3);
        // Several copies: the largest single object stands for one copy.
        assertThat(result.boundingBox()).isEqualTo(new BoundingBox(10, 20, 5));
        assertThat(result.gcodePaths()).extracting(path -> path.getFileName().toString())
                .containsExactly("plate_1.gcode", "plate_2.gcode");

        List<String> args = Files.readAllLines(outputDir.resolve("args.txt"));
        assertThat(args).containsSequence("--slice", "0")
                .containsSequence("--load-settings", profile.settingsArgument())
                .containsSequence("--load-filaments", profile.filamentsArgument())
                .containsSequence("--clone-objects", "3")
                .containsSequence("--arrange", "1")
                .containsSequence("--outputdir", outputDir.toAbsolutePath().toString())
                .endsWith(model.toAbsolutePath().toString());
    }

    @Test
    void singleCopyUsesUnionBoundingBoxAndNoCloneArguments() throws IOException {
        SlicerExecutionService service = service(fakeSlicer("""
                cat > "$out/result.json" <<'JSON'
                %s
                JSON
                printf '; total filament length [mm] : 12.5, 7.5\\n' > "$out/plate_1.gcode"
                """.formatted(TWO_PLATE_RESULT)), 30);

        SliceQuoteResult result = service.executeSlicingQuote(model, outputDir, profile, 1);

        // Union of a (0..10, 0..20), b (50..60) and c (0..30, 0..5): 60 x 20 x 5.
        assertThat(result.boundingBox()).isEqualTo(new BoundingBox(60, 20, 5));
        assertThat(result.triangleCountPerCopy()).isEqualTo(30);
        // Multi-filament trailers are summed.
        assertThat(result.filamentLengthMm()).isEqualTo(20.0);
        assertThat(Files.readAllLines(outputDir.resolve("args.txt")))
                .doesNotContain("--clone-objects", "--arrange");
    }

    @Test
    void cloneCountIsRepeatedPerInputFile() throws IOException {
        Path part = Files.writeString(tempDir.resolve("part.stl"), "solid part endsolid part");
        SlicerExecutionService service = service(fakeSlicer("""
                cat > "$out/result.json" <<'JSON'
                %s
                JSON
                printf '; total filament length [mm] : 1\\n' > "$out/plate_1.gcode"
                """.formatted(TWO_PLATE_RESULT)), 30);

        service.executeSlicingQuote(List.of(model, part), outputDir, profile, 4);

        assertThat(Files.readAllLines(outputDir.resolve("args.txt")))
                .containsSequence("--clone-objects", "4,4")
                .endsWith(model.toAbsolutePath().toString(), part.toAbsolutePath().toString());
    }

    @Test
    void nonZeroExitBecomesSlicingException() throws IOException {
        SlicerExecutionService service = service(fakeSlicer("echo 'got error when validate'; exit 7\n"), 30);

        assertThatThrownBy(() -> service.executeSlicingQuote(model, outputDir, profile, 1))
                .isInstanceOf(SlicingException.class)
                .hasMessageContaining("exit code 7");
    }

    @Test
    void failureReportedInsideResultJsonIsRejected() throws IOException {
        SlicerExecutionService service = service(fakeSlicer("""
                printf '{"return_code": -2, "error_string": "Invalid parameters to the slicer."}' > "$out/result.json"
                printf '' > "$out/plate_1.gcode"
                """), 30);

        assertThatThrownBy(() -> service.executeSlicingQuote(model, outputDir, profile, 1))
                .isInstanceOf(SlicingException.class)
                .hasMessageContaining("Invalid parameters");
    }

    @Test
    void missingGcodeIsRejected() throws IOException {
        SlicerExecutionService service = service(fakeSlicer(
                "printf '{\"return_code\": 0}' > \"$out/result.json\"\n"), 30);

        assertThatThrownBy(() -> service.executeSlicingQuote(model, outputDir, profile, 1))
                .isInstanceOf(SlicingException.class)
                .hasMessageContaining("plate G-code");
    }

    @Test
    void hangingSlicerIsKilledAfterTimeout() throws IOException {
        SlicerExecutionService service = service(fakeSlicer("sleep 10\n"), 1);

        long started = System.nanoTime();
        assertThatThrownBy(() -> service.executeSlicingQuote(model, outputDir, profile, 1))
                .isInstanceOf(SlicingException.class)
                .hasMessageContaining("timed out");
        assertThat((System.nanoTime() - started) / 1_000_000_000L).isLessThan(8);
    }

    @Test
    void rejectsUnsupportedInputBeforeStartingAnything() throws IOException {
        Path glb = Files.writeString(tempDir.resolve("model.glb"), "x");
        SlicerExecutionService service = service(fakeSlicer("exit 0\n"), 30);

        assertThatThrownBy(() -> service.executeSlicingQuote(glb, outputDir, profile, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only .stl and .3mf");
        assertThatThrownBy(() -> service.executeSlicingQuote(model, outputDir, profile, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(outputDir.resolve("args.txt")).doesNotExist();
    }

    @Test
    void exportListsTheStlFilesTheCliWrote() throws IOException {
        Path exportDir = Files.createDirectory(tempDir.resolve("export"));
        SlicerExecutionService service = service(fakeSlicer("""
                dir=""; prev=""
                for a in "$@"; do
                  if [ "$prev" = "--export-stls" ]; then dir="$a"; fi
                  prev="$a"
                done
                printf 'solid' > "$dir/obj_1_model.stl"
                printf 'solid' > "$dir/obj_2_model.stl"
                printf '{"return_code": 0}' > "$out/result.json"
                """), 30);

        List<Path> exported = service.exportObjectsAsStl(model, exportDir);

        assertThat(exported).extracting(path -> path.getFileName().toString())
                .containsExactly("obj_1_model.stl", "obj_2_model.stl");
        // result.json must land in the export directory, not the process working directory.
        assertThat(exportDir.resolve("result.json")).exists();
        assertThat(Files.readAllLines(exportDir.resolve("args.txt")))
                .containsSequence("--export-stls", exportDir.toAbsolutePath().toString())
                .containsSequence("--outputdir", exportDir.toAbsolutePath().toString());
    }

    private SlicerExecutionService service(Path fakeSlicer, long timeoutSeconds) {
        return new SlicerExecutionService(fakeSlicer.toAbsolutePath().toString(), timeoutSeconds);
    }

    private Path fakeSlicer(String body) throws IOException {
        Path script = tempDir.resolve("fake-slicer-" + System.nanoTime() + ".sh");
        Files.writeString(script, SCRIPT_PREAMBLE + body);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }
}
