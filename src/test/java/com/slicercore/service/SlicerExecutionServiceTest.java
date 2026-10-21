package com.slicercore.service;

import com.slicercore.model.BoundingBox;
import com.slicercore.model.SliceProfile;
import com.slicercore.model.SliceQuoteResult;
import com.slicercore.model.SlicingException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the process handling and output parsing against a shell script that imitates the
 * Bambu CLI: it records its arguments, writes canned result.json and G-code files for a slice
 * and prints a canned report for {@code --info}. The real slicer is covered by the slow,
 * tagged integration test only. On Windows the script runs through Git Bash's {@code sh};
 * without one on the PATH the class is skipped.
 */
class SlicerExecutionServiceTest {

    private static final String SCRIPT_PREAMBLE = """
            #!/bin/sh
            out=""; prev=""; mode="slice"
            for a in "$@"; do
              if [ "$prev" = "--outputdir" ]; then out="$a"; fi
              if [ "$a" = "--info" ]; then mode="info"; fi
              prev="$a"
            done
            printf '%s\\n' "$@" > "$out/args.txt"
            if [ "$mode" = "info" ]; then
            """;

    private static final String SCRIPT_AFTER_INFO = """
            exit 0
            fi
            """;

    /** What --info printed for the feasibility note's tetrahedron. */
    private static final String MANIFOLD_INFO = """
            printf '[model.stl]\\nsize_x = 10.000000\\nnumber_of_facets = 4\\nmanifold = yes\\nvolume = 166.666672\\n'
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

    private static final String TWO_PLATE_SLICE = """
            cat > "$out/result.json" <<'JSON'
            %s
            JSON
            printf '; total filament length [mm] : 1000.5\\n' > "$out/plate_1.gcode"
            printf 'G1 X1\\n; total filament length [mm] : 200\\n' > "$out/plate_2.gcode"
            """.formatted(TWO_PLATE_RESULT);

    /** Null where scripts execute directly; "sh" on Windows, where they run through Git Bash. */
    private static String shell;

    @TempDir
    Path tempDir;

    private Path model;
    private Path outputDir;
    private SliceProfile profile;

    @BeforeAll
    static void requiresPosixShell() {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            boolean shOnPath = Arrays.stream(System.getenv("PATH").split(File.pathSeparator))
                    .anyMatch(dir -> new File(dir, "sh.exe").isFile());
            assumeTrue(shOnPath, "fake slicer is a POSIX shell script; needs sh (Git Bash) on the PATH");
            shell = "sh";
        }
    }

    @BeforeEach
    void stageInputs() throws IOException {
        model = Files.writeString(tempDir.resolve("model.stl"), "solid cube endsolid cube");
        outputDir = Files.createDirectory(tempDir.resolve("out"));
        profile = new SliceProfile(tempDir.resolve("machine.json"), tempDir.resolve("process.json"),
                tempDir.resolve("filament.json"));
    }

    @Test
    void sumsPlatesAndPassesExplicitProfilesForOneCopy() throws IOException {
        SlicerExecutionService service = service(fakeSlicer(TWO_PLATE_SLICE), 30);

        SliceQuoteResult result = service.executeSlicingQuote(model, outputDir, profile);

        assertThat(result.plateCount()).isEqualTo(2);
        assertThat(result.totalPredicationSeconds()).isEqualTo(150.0);
        assertThat(result.filamentLengthMm()).isEqualTo(1200.5);
        assertThat(result.slicerReportedGrams()).isEqualTo(2.0);
        assertThat(result.triangleCountPerCopy()).isEqualTo(30);
        // Union of a (0..10, 0..20), b (50..60) and c (0..30, 0..5): 60 x 20 x 5.
        assertThat(result.boundingBox()).isEqualTo(new BoundingBox(60, 20, 5));
        assertThat(result.gcodePaths()).extracting(path -> path.getFileName().toString())
                .containsExactly("plate_1.gcode", "plate_2.gcode");
        // A clean slice of a watertight model.
        assertThat(result.warnings()).isEmpty();
        assertThat(result.manifold()).isTrue();

        // One copy, no cloning or arranging: quantities are arithmetic on top of this slice.
        List<String> args = Files.readAllLines(outputDir.resolve("args.txt"));
        assertThat(args).containsSequence("--slice", "0")
                .containsSequence("--load-settings", profile.settingsArgument())
                .containsSequence("--load-filaments", profile.filamentsArgument())
                .containsSequence("--outputdir", outputDir.toAbsolutePath().toString())
                .doesNotContain("--clone-objects", "--arrange")
                .endsWith(model.toAbsolutePath().toString());
        // The inspection covers the same input in one call.
        assertThat(Files.readAllLines(outputDir.resolve("model-info").resolve("args.txt")))
                .endsWith(model.toAbsolutePath().toString());
    }

    @Test
    void multiFilamentTrailersAreSummed() throws IOException {
        SlicerExecutionService service = service(fakeSlicer("""
                cat > "$out/result.json" <<'JSON'
                %s
                JSON
                printf '; total filament length [mm] : 12.5, 7.5\n' > "$out/plate_1.gcode"
                """.formatted(TWO_PLATE_RESULT)), 30);

        SliceQuoteResult result = service.executeSlicingQuote(model, outputDir, profile);

        assertThat(result.filamentLengthMm()).isEqualTo(20.0);
    }

    @Test
    void collectsWarningsFromResultJsonTheConsoleAndTheManifoldCheck() throws IOException {
        String result = """
                {"return_code": 0, "error_string": "Success.", "layer_height": 0.2,
                 "warnings": ["Object model.stl is partly outside the plate"],
                 "sliced_plates": [{"total_predication": 100.0, "warning_message": "Empty layer at 3.2 mm",
                   "filaments": [], "objects": [
                     {"name": "a", "triangle_count": 10, "bbox": {"x": 0, "y": 0, "z": 0, "width": 10, "depth": 20, "height": 5}}]}]}
                """;
        SlicerExecutionService service = service(fakeSlicer("""
                cat > "$out/result.json" <<'JSON'
                %s
                JSON
                echo 'warning: thin wall detected'
                echo '[error] glfwInit return error, code 65544'
                echo 'warning: thin wall detected'
                printf '; total filament length [mm] : 10\\n' > "$out/plate_1.gcode"
                """.formatted(result), """
                printf '[model.stl]\\nnumber_of_facets = 10\\nmanifold = no\\nopen_edges = 4\\nvolume = 1.5\\n'
                printf 'warning: object model.stl was repaired\\n'
                """), 30);

        SliceQuoteResult sliced = service.executeSlicingQuote(model, outputDir, profile);

        assertThat(sliced.warnings()).containsExactly(
                "Object model.stl is partly outside the plate",
                "Empty layer at 3.2 mm",
                "thin wall detected",
                "Mesh errors reported by the slicer: open_edges = 4",
                "object model.stl was repaired");
        assertThat(sliced.manifold()).isFalse();
        assertThat(sliced.totalPredicationSeconds()).isEqualTo(100.0);

        // The info run gets a directory of its own, so its result.json cannot replace the slice's.
        Path infoDir = outputDir.resolve("model-info");
        assertThat(Files.readAllLines(infoDir.resolve("args.txt")))
                .containsSequence("--info")
                .containsSequence("--outputdir", infoDir.toAbsolutePath().toString())
                .doesNotContain("--slice", "--load-settings")
                .endsWith(model.toAbsolutePath().toString());
        assertThat(Files.readString(outputDir.resolve("result.json"))).contains("Empty layer at 3.2 mm");
    }

    @Test
    void aFailingManifoldCheckLeavesTheQuoteIntact() throws IOException {
        SlicerExecutionService service = service(fakeSlicer(TWO_PLATE_SLICE,
                "echo 'got error when loading the model'; exit 3\n"), 30);

        SliceQuoteResult result = service.executeSlicingQuote(model, outputDir, profile);

        assertThat(result.plateCount()).isEqualTo(2);
        assertThat(result.manifold()).isNull();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void aHangingManifoldCheckIsKilledAfterItsOwnShortTimeout() throws IOException {
        SlicerExecutionService service = service(fakeSlicer(TWO_PLATE_SLICE, "sleep 10\n"), 30, 1);

        long started = System.nanoTime();
        SliceQuoteResult result = service.executeSlicingQuote(model, outputDir, profile);

        assertThat((System.nanoTime() - started) / 1_000_000_000L).isLessThan(8);
        assertThat(result.manifold()).isNull();
        assertThat(result.totalPredicationSeconds()).isEqualTo(150.0);
    }

    @Test
    void nonZeroExitBecomesSlicingException() throws IOException {
        SlicerExecutionService service = service(fakeSlicer("echo 'got error when validate'; exit 7\n"), 30);

        assertThatThrownBy(() -> service.executeSlicingQuote(model, outputDir, profile))
                .isInstanceOf(SlicingException.class)
                .hasMessageContaining("exit code 7");
        // A failed slice is not inspected.
        assertThat(outputDir.resolve("model-info")).doesNotExist();
    }

    @Test
    void failureReportedInsideResultJsonIsRejected() throws IOException {
        SlicerExecutionService service = service(fakeSlicer("""
                printf '{"return_code": -2, "error_string": "Invalid parameters to the slicer."}' > "$out/result.json"
                printf '' > "$out/plate_1.gcode"
                """), 30);

        assertThatThrownBy(() -> service.executeSlicingQuote(model, outputDir, profile))
                .isInstanceOf(SlicingException.class)
                .hasMessageContaining("Invalid parameters");
        assertThat(outputDir.resolve("model-info")).doesNotExist();
    }

    @Test
    void missingGcodeIsRejected() throws IOException {
        SlicerExecutionService service = service(fakeSlicer(
                "printf '{\"return_code\": 0}' > \"$out/result.json\"\n"), 30);

        assertThatThrownBy(() -> service.executeSlicingQuote(model, outputDir, profile))
                .isInstanceOf(SlicingException.class)
                .hasMessageContaining("plate G-code");
    }

    @Test
    void hangingSlicerIsKilledAfterTimeout() throws IOException {
        SlicerExecutionService service = service(fakeSlicer("sleep 10\n"), 1);

        long started = System.nanoTime();
        assertThatThrownBy(() -> service.executeSlicingQuote(model, outputDir, profile))
                .isInstanceOf(SlicingException.class)
                .hasMessageContaining("timed out");
        assertThat((System.nanoTime() - started) / 1_000_000_000L).isLessThan(8);
    }

    @Test
    void rejectsUnsupportedInputBeforeStartingAnything() throws IOException {
        Path glb = Files.writeString(tempDir.resolve("model.glb"), "x");
        SlicerExecutionService service = service(fakeSlicer("exit 0\n"), 30);

        assertThatThrownBy(() -> service.executeSlicingQuote(glb, outputDir, profile))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only .stl and .3mf");
        assertThat(outputDir.resolve("args.txt")).doesNotExist();
    }

    private SlicerExecutionService service(Path fakeSlicer, long timeoutSeconds) {
        return service(fakeSlicer, timeoutSeconds, 20);
    }

    private SlicerExecutionService service(Path fakeSlicer, long timeoutSeconds, long infoTimeoutSeconds) {
        String binary = fakeSlicer.toAbsolutePath().toString();
        return new SlicerExecutionService(shell == null ? binary : shell + " " + binary, timeoutSeconds, infoTimeoutSeconds);
    }

    /** A fake that slices as told and reports a manifold model. */
    private Path fakeSlicer(String sliceBody) throws IOException {
        return fakeSlicer(sliceBody, MANIFOLD_INFO);
    }

    private Path fakeSlicer(String sliceBody, String infoBody) throws IOException {
        Path script = tempDir.resolve("fake-slicer-" + System.nanoTime() + ".sh");
        Files.writeString(script, SCRIPT_PREAMBLE + infoBody + SCRIPT_AFTER_INFO + sliceBody);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return script;
    }
}
