package com.slicercore.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slicercore.model.BoundingBox;
import com.slicercore.model.SliceProfile;
import com.slicercore.model.SliceQuoteResult;
import com.slicercore.model.SlicingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs the headless Bambu Studio CLI and parses what it produced.
 *
 * Output contract (verified in docs/feasibility/bambu-studio-cli-headless.md): the CLI writes
 * one {@code plate_N.gcode} per plate plus {@code result.json} into the output directory. The
 * exit code and the return_code inside result.json are the success signals; stderr noise about
 * missing displays is expected and ignored.
 *
 * Several copies of a model are placed with {@code --clone-objects} and {@code --arrange 1};
 * the CLI spills onto additional plates automatically when they do not fit on one. Cloning is
 * refused for 3MF input, so callers export a 3MF to STL first via {@link #exportObjectsAsStl}.
 */
@Service
public class SlicerExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SlicerExecutionService.class);
    private static final Pattern GENERATED_GCODE_PATTERN = Pattern.compile("plate_\\d+\\.gcode");
    private static final String GENERATED_RESULT_NAME = "result.json";
    private static final String GCODE_FILAMENT_LENGTH_PREFIX = "; total filament length [mm] :";
    private static final int CAPTURED_LOG_LINES = 20;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${slicer.binary-path:bambu-studio-cli}")
    private String slicerBinaryPath;

    @Value("${slicer.timeout-seconds:120}")
    private long timeoutSeconds;

    /** Convenience overload for a single input file. */
    public SliceQuoteResult executeSlicingQuote(Path inputFilePath, Path outputDirPath, SliceProfile profile, int copies) {
        return executeSlicingQuote(List.of(inputFilePath), outputDirPath, profile, copies);
    }

    /**
     * Slices {@code copies} copies of every input file with explicit profiles and returns the
     * parsed quote data summed over all plates.
     *
     * @param inputFiles    absolute paths to STL/3MF files (3MF only with a single copy)
     * @param outputDirPath absolute path of an existing directory for G-code and result.json
     * @param profile       machine, process and filament profiles to slice with
     * @param copies        how many copies of each input to place on the plates (at least 1)
     */
    public SliceQuoteResult executeSlicingQuote(List<Path> inputFiles, Path outputDirPath, SliceProfile profile, int copies) {
        if (inputFiles == null || inputFiles.isEmpty()) {
            throw new IllegalArgumentException("At least one input file is required.");
        }
        if (copies < 1) {
            throw new IllegalArgumentException("At least one copy is required.");
        }
        for (Path inputFile : inputFiles) {
            requireModelFile(inputFile);
        }

        String label = inputFiles.getFirst().getFileName().toString()
                + (inputFiles.size() > 1 ? " (+" + (inputFiles.size() - 1) + " parts)" : "");
        Path resultJsonPath = outputDirPath.resolve(GENERATED_RESULT_NAME);

        List<String> command = baseCommand();
        command.add("--slice");
        command.add("0"); // all plates
        command.add("--load-settings");
        command.add(profile.settingsArgument());
        command.add("--load-filaments");
        command.add(profile.filamentsArgument());
        if (copies > 1) {
            // One count per input file: the total number of copies, spread over plates by --arrange.
            command.add("--clone-objects");
            command.add(Collections.nCopies(inputFiles.size(), String.valueOf(copies)).stream()
                    .collect(Collectors.joining(",")));
            command.add("--arrange");
            command.add("1");
        }
        command.add("--outputdir");
        command.add(outputDirPath.toAbsolutePath().toString());
        for (Path inputFile : inputFiles) {
            command.add(inputFile.toAbsolutePath().toString());
        }

        log.info("Starting headless slicing for {} x{} with process profile {} and filament profile {}",
                label, copies, profile.processProfile().getFileName(), profile.filamentProfile().getFileName());
        runSlicer(command, "slicing " + label);

        try {
            List<Path> gcodePaths = findGcodeFiles(outputDirPath);
            if (gcodePaths.isEmpty()) {
                throw new SlicingException("Slicer finished without generating any plate G-code.");
            }
            if (!Files.exists(resultJsonPath)) {
                throw new SlicingException("Slicer finished without generating " + GENERATED_RESULT_NAME + ".");
            }

            SliceQuoteResult result = parseSliceQuoteResult(label, gcodePaths, resultJsonPath, copies);
            if (result.returnCode() != 0) {
                throw new SlicingException("Slicer reported failure: " + result.errorString());
            }

            log.info("Slicing completed for {} x{}: {} plate(s), {} s print time, {} mm filament, {} triangles per copy",
                    label, copies, result.plateCount(), Math.round(result.totalPredicationSeconds()),
                    Math.round(result.filamentLengthMm()), result.triangleCountPerCopy());
            return result;
        } catch (IOException e) {
            throw new SlicingException("Failed to read the slicer output.", e);
        }
    }

    /**
     * Exports every object of a model (typically a 3MF) as a separate binary STL into
     * {@code exportDir}. The CLI names them {@code obj_N_<name>.stl}.
     */
    public List<Path> exportObjectsAsStl(Path inputFilePath, Path exportDir) {
        requireModelFile(inputFilePath);

        List<String> command = baseCommand();
        command.add("--export-stls");
        command.add(exportDir.toAbsolutePath().toString());
        // Without --outputdir the CLI drops its result.json into the process working directory.
        command.add("--outputdir");
        command.add(exportDir.toAbsolutePath().toString());
        command.add(inputFilePath.toAbsolutePath().toString());

        log.info("Exporting objects of {} to STL for batch slicing", inputFilePath.getFileName());
        runSlicer(command, "exporting " + inputFilePath.getFileName());

        try (Stream<Path> files = Files.list(exportDir)) {
            List<Path> exported = files
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".stl"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            if (exported.isEmpty()) {
                throw new SlicingException("Slicer exported no STL objects from " + inputFilePath.getFileName() + ".");
            }
            return exported;
        } catch (IOException e) {
            throw new SlicingException("Failed to list exported STL files.", e);
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

    /** Runs one CLI invocation, enforcing the timeout and treating a non-zero exit as failure. */
    private void runSlicer(List<String> command, String activity) {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        Deque<String> recentOutput = new ArrayDeque<>();
        try {
            Process process = processBuilder.start();

            // Drain stdout/stderr on a separate thread so a chatty or hung CLI cannot block the timeout below.
            Thread drainer = Thread.ofVirtual().start(() -> drainOutput(process, recentOutput));

            boolean finishedInTime = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finishedInTime) {
                process.destroyForcibly();
                log.error("Slicer timed out after {} seconds while {}", timeoutSeconds, activity);
                throw new SlicingException("Slicing timed out after " + timeoutSeconds + " seconds.");
            }
            drainer.join(TimeUnit.SECONDS.toMillis(5));

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String tail;
                synchronized (recentOutput) {
                    tail = String.join(System.lineSeparator(), recentOutput);
                }
                log.error("Slicer exited with code {} while {}. Last output: {}", exitCode, activity, tail);
                throw new SlicingException("Slicer CLI failed with exit code " + exitCode + ".");
            }
        } catch (IOException e) {
            throw new SlicingException("Failed to run the slicer process.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SlicingException("Slicing was interrupted.", e);
        }
    }

    private static void drainOutput(Process process, Deque<String> recentOutput) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[SLICER CLI] {}", line);
                synchronized (recentOutput) {
                    recentOutput.addLast(line);
                    if (recentOutput.size() > CAPTURED_LOG_LINES) {
                        recentOutput.removeFirst();
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Slicer output stream closed: {}", e.getMessage());
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

    private SliceQuoteResult parseSliceQuoteResult(String label, List<Path> gcodePaths, Path resultJsonPath,
                                                   int copies) throws IOException {
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
                copies,
                plateCount,
                totalSeconds,
                triangleTotal / Math.max(1, copies),
                filamentLengthMm,
                slicerGrams,
                boundingBoxPerCopy(objects, copies));
    }

    /**
     * Extent of one copy. With a single copy every object belongs to it, so the union is exact
     * (a 3MF may hold several parts). With several copies the objects cannot be attributed to a
     * copy, so the largest single object is reported instead.
     */
    private static BoundingBox boundingBoxPerCopy(List<JsonNode> objects, int copies) {
        if (objects.isEmpty()) {
            return BoundingBox.EMPTY;
        }
        return copies == 1 ? unionBoundingBox(objects) : largestBoundingBox(objects);
    }

    private static BoundingBox unionBoundingBox(List<JsonNode> objects) {
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

    private static BoundingBox largestBoundingBox(List<JsonNode> objects) {
        BoundingBox largest = BoundingBox.EMPTY;
        double largestVolume = -1;

        for (JsonNode object : objects) {
            JsonNode bbox = object.path("bbox");
            double width = bbox.path("width").asDouble(0);
            double depth = bbox.path("depth").asDouble(0);
            double height = bbox.path("height").asDouble(0);
            double volume = width * depth * height;
            if (volume > largestVolume) {
                largestVolume = volume;
                largest = new BoundingBox(width, depth, height);
            }
        }

        return largest;
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
