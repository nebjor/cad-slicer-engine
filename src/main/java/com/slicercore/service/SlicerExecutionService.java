package com.slicercore.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class SlicerExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SlicerExecutionService.class);

    @Value("${slicer.binary-path:xvfb-run bambu-studio-cli}")
    private String slicerBinaryPath;

    @Value("${slicer.timeout-seconds:60}")
    private long timeoutSeconds;

    /**
     * Executes the CLI slicer in a headless sandboxed environment.
     *
     * @param inputFilePath  Absolute path to input STL/3MF file
     * @param outputDirPath Absolute path to directory where generated G-code should be stored
     * @return Path to generated G-code file
     */
    public Path executeSlicing(Path inputFilePath, Path outputDirPath) {
        File inputFile = inputFilePath.toFile();
        if (!inputFile.exists()) {
            throw new IllegalArgumentException("Input file does not exist: " + inputFilePath);
        }

        String outputGcodeName = inputFile.getName().replaceAll("\\.(stl|3mf)$", ".gcode");
        Path outputGcodePath = outputDirPath.resolve(outputGcodeName);

        ProcessBuilder processBuilder = getProcessBuilder(inputFilePath, outputDirPath);

        log.info("Starting headless slicing process for file: {}", inputFile.getName());

        try {
            Process process = processBuilder.start();

            // Read process output in background to avoid buffer blocking
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[SLICER CLI] {}", line);
                }
            }

            boolean finishedInTime = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finishedInTime) {
                process.destroyForcibly();
                log.error("Slicing process timed out after {} seconds for file: {}", timeoutSeconds, inputFile.getName());
                throw new RuntimeException("Slicing process timed out after " + timeoutSeconds + " seconds.");
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("Slicing process failed with exit code: {} for file: {}", exitCode, inputFile.getName());
                throw new RuntimeException("Slicer CLI failed with exit code: " + exitCode);
            }

            log.info("Slicing successfully completed. Output generated at: {}", outputGcodePath);
            return outputGcodePath;

        } catch (Exception e) {
            log.error("Failed to execute slicing command for file: {}", inputFile.getName(), e);
            throw new RuntimeException("Execution error during slicing process", e);
        }
    }

    private ProcessBuilder getProcessBuilder(Path inputFilePath, Path outputDirPath) {
        List<String> command = new ArrayList<>();

        // Split xvfb-run and binary if configured
        Collections.addAll(command, slicerBinaryPath.split(" "));

        command.add("--slice");
        command.add("0"); // Plate index 0
        command.add("--outputdir");
        command.add(outputDirPath.toAbsolutePath().toString());
        command.add(inputFilePath.toAbsolutePath().toString());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        return processBuilder;
    }
}