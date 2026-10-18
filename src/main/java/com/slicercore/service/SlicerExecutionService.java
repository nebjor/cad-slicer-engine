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
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs the headless Bambu Studio CLI for one model and parses what it produced.
 *
 * Output contract (verified in docs/feasibility/bambu-studio-cli-headless.md): the CLI writes
 * plate_1.gcode and result.json into the output directory. The exit code and the
 * return_code inside result.json are the success signals; stderr noise about missing displays
 * is expected and ignored.
 */
@Service
public class SlicerExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SlicerExecutionService.class);
    private static final String GENERATED_GCODE_NAME = "plate_1.gcode";
    private static final String GENERATED_RESULT_NAME = "result.json";
    private static final String GCODE_FILAMENT_LENGTH_PREFIX = "; total filament length [mm] :";
    private static final int CAPTURED_LOG_LINES = 20;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${slicer.binary-path:bambu-studio-cli}")
    private String slicerBinaryPath;

    @Value("${slicer.timeout-seconds:120}")
    private long timeoutSeconds;

    /**
     * Slices one STL/3MF file with explicit profiles and returns the parsed quote data.
     *
     * @param inputFilePath absolute path to the input STL/3MF file
     * @param outputDirPath absolute path of an existing directory for G-code and result.json
     * @param profile       machine, process and filament profiles to slice with
     */
    public SliceQuoteResult executeSlicingQuote(Path inputFilePath, Path outputDirPath, SliceProfile profile) {
        if (!Files.isRegularFile(inputFilePath)) {
            throw new IllegalArgumentException("Input file does not exist: " + inputFilePath);
        }

        String inputName = inputFilePath.getFileName().toString();
        String lowerName = inputName.toLowerCase(Locale.ROOT);
        if (!lowerName.endsWith(".stl") && !lowerName.endsWith(".3mf")) {
            throw new IllegalArgumentException("Unsupported input format. Only .stl and .3mf are supported: " + inputName);
        }

        Path outputGcodePath = outputDirPath.resolve(GENERATED_GCODE_NAME);
        Path resultJsonPath = outputDirPath.resolve(GENERATED_RESULT_NAME);

        ProcessBuilder processBuilder = buildCommand(inputFilePath, outputDirPath, profile);
        log.info("Starting headless slicing for {} with process profile {} and filament profile {}",
                inputName, profile.processProfile().getFileName(), profile.filamentProfile().getFileName());

        Deque<String> recentOutput = new ArrayDeque<>();
        try {
            Process process = processBuilder.start();

            // Drain stdout/stderr on a separate thread so a chatty or hung CLI cannot block the timeout below.
            Thread drainer = Thread.ofVirtual().start(() -> drainOutput(process, recentOutput));

            boolean finishedInTime = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finishedInTime) {
                process.destroyForcibly();
                log.error("Slicing timed out after {} seconds for file {}", timeoutSeconds, inputName);
                throw new SlicingException("Slicing timed out after " + timeoutSeconds + " seconds.");
            }
            drainer.join(TimeUnit.SECONDS.toMillis(5));

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String tail;
                synchronized (recentOutput) {
                    tail = String.join(System.lineSeparator(), recentOutput);
                }
                log.error("Slicer exited with code {} for file {}. Last output: {}", exitCode, inputName, tail);
                throw new SlicingException("Slicer CLI failed with exit code " + exitCode + ".");
            }

            if (!Files.exists(outputGcodePath)) {
                throw new SlicingException("Slicer finished without generating " + GENERATED_GCODE_NAME + ".");
            }
            if (!Files.exists(resultJsonPath)) {
                throw new SlicingException("Slicer finished without generating " + GENERATED_RESULT_NAME + ".");
            }

            SliceQuoteResult result = parseSliceQuoteResult(inputName, outputGcodePath, resultJsonPath);
            if (result.returnCode() != 0) {
                throw new SlicingException("Slicer reported failure: " + result.errorString());
            }

            log.info("Slicing completed for {}: {} s print time, {} mm filament, {} triangles",
                    inputName, Math.round(result.totalPredicationSeconds()), Math.round(result.filamentLengthMm()),
                    result.triangleCount());
            return result;

        } catch (SlicingException e) {
            throw e;
        } catch (IOException e) {
            throw new SlicingException("Failed to run or read the slicer process.", e);
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

    private SliceQuoteResult parseSliceQuoteResult(String inputFileName, Path gcodePath, Path resultJsonPath)
            throws IOException {
        JsonNode root = objectMapper.readTree(resultJsonPath.toFile());
        JsonNode plate = root.path("sliced_plates").path(0);

        double slicerGrams = 0;
        for (JsonNode filament : plate.path("filaments")) {
            slicerGrams += filament.path("total_used_g").asDouble(0);
        }

        int triangleCount = plate.path("triangle_count").asInt(0);
        if (triangleCount == 0) {
            for (JsonNode object : plate.path("objects")) {
                triangleCount += object.path("triangle_count").asInt(0);
            }
        }

        return new SliceQuoteResult(
                inputFileName,
                gcodePath,
                resultJsonPath,
                root.path("return_code").asInt(-1),
                root.path("error_string").asText("Unknown slicer error"),
                root.path("layer_height").asDouble(0),
                plate.path("total_predication").asDouble(0),
                plate.path("main_predication").asDouble(0),
                triangleCount,
                readFilamentLengthMm(gcodePath),
                slicerGrams,
                unionBoundingBox(plate.path("objects")));
    }

    /** Union of all object extents on the plate; a 3MF may contain several objects. */
    private static BoundingBox unionBoundingBox(JsonNode objects) {
        if (!objects.isArray() || objects.isEmpty()) {
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
     * Reads the extruded filament length from the G-code trailer. The slicer's gram figure is
     * zero for many models, so mass is derived from this length by the pricing service.
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

    private ProcessBuilder buildCommand(Path inputFilePath, Path outputDirPath, SliceProfile profile) {
        List<String> command = new ArrayList<>();
        Collections.addAll(command, slicerBinaryPath.trim().split("\\s+"));

        command.add("--slice");
        command.add("0"); // plate index 0
        command.add("--load-settings");
        command.add(profile.settingsArgument());
        command.add("--load-filaments");
        command.add(profile.filamentsArgument());
        command.add("--outputdir");
        command.add(outputDirPath.toAbsolutePath().toString());
        command.add(inputFilePath.toAbsolutePath().toString());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        return processBuilder;
    }
}
