package com.slicercore.service;

import com.slicercore.model.BoundingBox;
import com.slicercore.model.SliceProfile;
import com.slicercore.model.SliceQuoteResult;
import com.slicercore.model.SlicingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs the headless Bambu Studio CLI and parses what it produced.
 *
 * Output contract (verified in docs/feasibility/bambu-studio-cli-headless.md): the CLI writes
 * one {@code plate_N.gcode} per plate plus {@code result.json} into the output directory. The
 * exit code and the return_code inside result.json are the success signals; stderr noise about
 * missing displays is expected and ignored.
 *
 * Every quote slices a single copy of the model; quantities are derived from that slice by
 * the pricing (ADR 0006), so the CLI never clones or arranges objects here.
 *
 * After a successful slice the model is inspected once with {@code --info}, which reports
 * whether the mesh is manifold. That call is best effort: it has its own short timeout and a
 * failure only costs the information, never the quote.
 */
@Service
public class SlicerExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SlicerExecutionService.class);
    private static final Pattern GENERATED_GCODE_PATTERN = Pattern.compile("plate_\\d+\\.gcode");
    private static final String GENERATED_RESULT_NAME = "result.json";
    /** Where the {@code --info} run writes its own result.json, away from the slice's. */
    private static final String MODEL_INFO_DIR = "model-info";
    private static final String GCODE_FILAMENT_LENGTH_PREFIX = "; total filament length [mm] :";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String slicerBinaryPath;
    private final long timeoutSeconds;
    private final long infoTimeoutSeconds;

    public SlicerExecutionService(
            @Value("${slicer.binary-path:bambu-studio-cli}") String slicerBinaryPath,
            @Value("${slicer.timeout-seconds:120}") long timeoutSeconds,
            @Value("${slicer.info-timeout-seconds:20}") long infoTimeoutSeconds) {
        this.slicerBinaryPath = slicerBinaryPath;
        this.timeoutSeconds = timeoutSeconds;
        this.infoTimeoutSeconds = infoTimeoutSeconds;
    }

    /**
     * Slices one copy of the input file with explicit profiles and returns the parsed quote
     * data summed over all plates the CLI wrote (one, unless a 3MF project carries several).
     *
     * @param inputFile     absolute path to an STL or 3MF file
     * @param outputDirPath absolute path of an existing directory for G-code and result.json
     * @param profile       machine, process and filament profiles to slice with
     */
    public SliceQuoteResult executeSlicingQuote(Path inputFile, Path outputDirPath, SliceProfile profile) {
        requireModelFile(inputFile);

        String label = inputFile.getFileName().toString();
        Path resultJsonPath = outputDirPath.resolve(GENERATED_RESULT_NAME);

        List<String> command = baseCommand();
        command.add("--slice");
        command.add("0"); // all plates
        command.add("--load-settings");
        command.add(profile.settingsArgument());
        command.add("--load-filaments");
        command.add(profile.filamentsArgument());
        command.add("--outputdir");
        command.add(outputDirPath.toAbsolutePath().toString());
        command.add(inputFile.toAbsolutePath().toString());

        log.info("Starting headless slicing for {} with process profile {} and filament profile {}",
                label, profile.processProfile().getFileName(), profile.filamentProfile().getFileName());
        CliOutput sliceOutput = runSlicer(command, "slicing " + label, timeoutSeconds);

        SliceQuoteResult result;
        try {
            List<Path> gcodePaths = findGcodeFiles(outputDirPath);
            if (gcodePaths.isEmpty()) {
                throw new SlicingException("Slicer finished without generating any plate G-code.");
            }
            if (!Files.exists(resultJsonPath)) {
                throw new SlicingException("Slicer finished without generating " + GENERATED_RESULT_NAME + ".");
            }

            result = parseSliceQuoteResult(label, gcodePaths, resultJsonPath);
            if (result.returnCode() != 0) {
                throw new SlicingException("Slicer reported failure: " + result.errorString());
            }
        } catch (IOException e) {
            throw new SlicingException("Failed to read the slicer output.", e);
        }

        ModelInfo info = inspectModel(inputFile, outputDirPath.resolve(MODEL_INFO_DIR), label);
        List<String> warnings = SlicerWarnings.normalize(result.warnings(), sliceOutput.warnings(), info.warnings());
        result = result.withInspection(warnings, info.manifold());

        log.info("Slicing completed for {}: {} plate(s), {} s print time, {} mm filament, {} triangles,"
                        + " {} warning(s), manifold {}",
                label, result.plateCount(), Math.round(result.totalPredicationSeconds()),
                Math.round(result.filamentLengthMm()), result.triangleCountPerCopy(), warnings.size(),
                result.manifold() == null ? "unknown" : result.manifold());
        return result;
    }

    /**
     * Asks the CLI about the mesh itself. Its result.json goes to a directory of its own so
     * the slice's stays intact. Any failure is logged and answered with
     * {@link ModelInfo#UNAVAILABLE}: the quote never depends on this call.
     */
    private ModelInfo inspectModel(Path inputFile, Path infoDir, String label) {
        List<String> command = baseCommand();
        command.add("--info");
        command.add("--outputdir");
        command.add(infoDir.toAbsolutePath().toString());
        command.add(inputFile.toAbsolutePath().toString());

        try {
            Files.createDirectories(infoDir);
            CliOutput output = runSlicer(command, "inspecting " + label, infoTimeoutSeconds);
            ModelInfo info = ModelInfo.parse(output.lines());
            if (info.manifold() == null) {
                log.warn("Model inspection of {} printed no manifold line; output started with: {}",
                        label, String.join(" | ", output.lines().subList(0, Math.min(5, output.lines().size()))));
            } else {
                log.info("Model inspection of {}: manifold {}, {} facets, volume {} mm^3",
                        label, info.manifold(), info.facets(), Math.round(info.volumeMm3()));
            }
            return new ModelInfo(info.manifold(), info.facets(), info.volumeMm3(),
                    SlicerWarnings.normalize(info.warnings(), output.warnings()));
        } catch (IOException | SlicingException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw new SlicingException("Slicing was interrupted.", e);
            }
            log.warn("Model inspection of {} failed; the quote continues without it: {}", label, e.getMessage());
            return ModelInfo.UNAVAILABLE;
        }
    }

    private static void requireModelFile(Path inputFilePath) {
        if (!Files.isRegularFile(inputFilePath)) {
            throw new IllegalArgumentException("Input file does not exist: " + inputFilePath);
        }
        String lowerName = inputFilePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".stl") && !lowerName.endsWith(".3mf")) {
            throw new IllegalArgumentException("Unsupported input format. Only .stl and .3mf are supported: "
                    + inputFilePath.getFileName());
        }
    }

    private List<String> baseCommand() {
        List<String> command = new ArrayList<>();
        Collections.addAll(command, slicerBinaryPath.trim().split("\\s+"));
        return command;
    }

    /**
     * Runs one CLI invocation, enforcing the timeout and treating a non-zero exit as failure.
     * Returns what the CLI printed, bounded, for parsing and for warnings.
     */
    private CliOutput runSlicer(List<String> command, String activity, long timeout) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        CliOutput output = new CliOutput();
        try {
            Process process = processBuilder.start();

            // Drain stdout/stderr on a separate thread so a chatty or hung CLI cannot block the timeout below.
            Thread drainer = Thread.ofVirtual().start(() -> drainOutput(process, output));

            boolean finishedInTime = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finishedInTime) {
                process.destroyForcibly();
                log.error("Slicer timed out after {} seconds while {}", timeout, activity);
                throw new SlicingException("Slicing timed out after " + timeout + " seconds.");
            }
            drainer.join(TimeUnit.SECONDS.toMillis(5));

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("Slicer exited with code {} while {}. Last output: {}", exitCode, activity,
                        String.join(System.lineSeparator(), output.tail()));
                throw new SlicingException("Slicer CLI failed with exit code " + exitCode + ".");
            }
            return output;
        } catch (IOException e) {
            throw new SlicingException("Failed to run the slicer process.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SlicingException("Slicing was interrupted.", e);
        }
    }

    private static void drainOutput(Process process, CliOutput output) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[SLICER CLI] {}", line);
                output.add(line);
            }
        } catch (IOException e) {
            log.debug("Slicer output stream closed: {}", e.getMessage());
        }
    }

    /**
     * What one CLI run printed, kept within bounds: the first lines (enough for the short
     * {@code --info} report), the last lines for error logs, and every warning line. A slice
     * prints thousands of progress lines, which are not worth keeping.
     */
    static final class CliOutput {

        private static final int KEPT_LEADING_LINES = 1000;
        private static final int KEPT_TRAILING_LINES = 20;
        private static final int KEPT_WARNING_LINES = 50;

        private final List<String> leading = new ArrayList<>();
        private final Deque<String> trailing = new ArrayDeque<>();
        private final List<String> warnings = new ArrayList<>();

        synchronized void add(String line) {
            if (leading.size() < KEPT_LEADING_LINES) {
                leading.add(line);
            }
            trailing.addLast(line);
            if (trailing.size() > KEPT_TRAILING_LINES) {
                trailing.removeFirst();
            }
            if (warnings.size() < KEPT_WARNING_LINES) {
                SlicerWarnings.fromOutputLine(line).ifPresent(warnings::add);
            }
        }

        synchronized List<String> lines() {
            return List.copyOf(leading);
        }

        synchronized List<String> tail() {
            return List.copyOf(trailing);
        }

        synchronized List<String> warnings() {
            return List.copyOf(warnings);
        }
    }

    private static List<Path> findGcodeFiles(Path outputDirPath) throws IOException {
        try (Stream<Path> files = Files.list(outputDirPath)) {
            return files
                    .filter(path -> GENERATED_GCODE_PATTERN.matcher(path.getFileName().toString()).matches())
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private SliceQuoteResult parseSliceQuoteResult(String label, List<Path> gcodePaths, Path resultJsonPath)
            throws IOException {
        JsonNode root = objectMapper.readTree(resultJsonPath.toFile());

        double totalSeconds = 0;
        double slicerGrams = 0;
        int plateCount = 0;
        int triangleTotal = 0;
        List<JsonNode> objects = new ArrayList<>();

        for (JsonNode plate : root.path("sliced_plates")) {
            plateCount++;
            totalSeconds += plate.path("total_predication").asDouble(0);
            for (JsonNode filament : plate.path("filaments")) {
                slicerGrams += filament.path("total_used_g").asDouble(0);
            }
            for (JsonNode object : plate.path("objects")) {
                objects.add(object);
                triangleTotal += object.path("triangle_count").asInt(0);
            }
        }

        double filamentLengthMm = 0;
        for (Path gcodePath : gcodePaths) {
            filamentLengthMm += readFilamentLengthMm(gcodePath);
        }

        return new SliceQuoteResult(
                label,
                gcodePaths,
                resultJsonPath,
                root.path("return_code").asInt(-1),
                root.path("error_string").asText("Unknown slicer error"),
                root.path("layer_height").asDouble(0),
                plateCount,
                totalSeconds,
                triangleTotal,
                filamentLengthMm,
                slicerGrams,
                unionBoundingBox(objects),
                SlicerWarnings.fromResultJson(root),
                null);
    }

    /**
     * Extent of the single copy: the union of every object the slicer placed, so a 3MF that
     * holds several parts is measured as a whole. Empty when nothing was placed.
     */
    private static BoundingBox unionBoundingBox(List<JsonNode> objects) {
        if (objects.isEmpty()) {
            return BoundingBox.EMPTY;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;

        for (JsonNode object : objects) {
            JsonNode bbox = object.path("bbox");
            double x = bbox.path("x").asDouble(0);
            double y = bbox.path("y").asDouble(0);
            double z = bbox.path("z").asDouble(0);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x + bbox.path("width").asDouble(0));
            maxY = Math.max(maxY, y + bbox.path("depth").asDouble(0));
            maxZ = Math.max(maxZ, z + bbox.path("height").asDouble(0));
        }

        return new BoundingBox(maxX - minX, maxY - minY, maxZ - minZ);
    }

    /**
     * Reads the extruded filament length from a plate's G-code trailer. The slicer's gram figure
     * is zero for many models, so mass is derived from this length by the pricing service.
     */
    private static double readFilamentLengthMm(Path gcodePath) throws IOException {
        try (Stream<String> lines = Files.lines(gcodePath, StandardCharsets.ISO_8859_1)) {
            return lines
                    .filter(line -> line.startsWith(GCODE_FILAMENT_LENGTH_PREFIX))
                    .map(line -> line.substring(GCODE_FILAMENT_LENGTH_PREFIX.length()))
                    .map(SlicerExecutionService::sumCommaSeparated)
                    .findFirst()
                    .orElse(0.0);
        }
    }

    /** Multi-filament prints list one length per filament, comma separated. */
    private static double sumCommaSeparated(String raw) {
        double sum = 0;
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                sum += Double.parseDouble(trimmed);
            }
        }
        return sum;
    }
}
