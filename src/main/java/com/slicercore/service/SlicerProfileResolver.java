package com.slicercore.service;

import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.SliceProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves the machine, process and filament profiles for a filament and quality selection.
 *
 * The profiles bundled in the Bambu Studio AppImage are layered: a leaf such as
 * "0.12mm Fine @BBL A1" only holds a handful of keys and points to its parent via
 * {@code inherits}. The CLI does not walk that chain, so passing the leaf file alone silently
 * prices against defaults (0.2 mm layers, PLA density). This resolver flattens each chain into
 * one self-contained JSON file, parents first so the leaf wins, and caches the result.
 */
@Component
public class SlicerProfileResolver {

    private static final Logger log = LoggerFactory.getLogger(SlicerProfileResolver.class);
    private static final String INHERITS_KEY = "inherits";
    private static final int MAX_INHERITANCE_DEPTH = 16;

    private static final String BED_TYPE_KEY = "curr_bed_type";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path profilesDir;
    private final String machineProfileName;
    private final String bedType;
    private final ConcurrentMap<String, Path> flattenedProfiles = new ConcurrentHashMap<>();
    private volatile Path flattenedDir;

    public SlicerProfileResolver(
            @Value("${slicer.profiles-dir:/opt/bambu/squashfs-root/resources/profiles/BBL}") String profilesDir,
            @Value("${slicer.machine-profile:Bambu Lab A1 0.4 nozzle}") String machineProfileName,
            @Value("${slicer.bed-type:Textured PEI Plate}") String bedType) {
        this.profilesDir = Path.of(profilesDir);
        this.machineProfileName = machineProfileName;
        this.bedType = bedType;
    }

    public String machineProfileName() {
        return machineProfileName;
    }

    public SliceProfile resolve(FilamentType filament, PrintQuality quality) {
        Path machine = flattened("machine", machineProfileName);
        Path process = flattened("process", quality.processProfileName());
        Path filamentProfile = flattened("filament", filament.profileName());
        return new SliceProfile(machine, process, filamentProfile);
    }

    private Path flattened(String category, String profileName) {
        return flattenedProfiles.computeIfAbsent(category + "/" + profileName,
                key -> writeFlattened(category, profileName));
    }

    private Path writeFlattened(String category, String profileName) {
        ObjectNode merged = objectMapper.createObjectNode();
        mergeChain(category, profileName, merged, 0);
        merged.remove(INHERITS_KEY);

        // The machine profile defaults to the Cool Plate, which the CLI rejects for PETG
        // ("Cool Plate does not support filament 1"). The bed type is a process setting.
        if ("process".equals(category) && !bedType.isBlank()) {
            merged.put(BED_TYPE_KEY, bedType);
        }

        try {
            Path dir = flattenedDir().resolve(category);
            Files.createDirectories(dir);
            Path target = dir.resolve(profileName + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), merged);
            log.info("Flattened slicer profile {}/{} ({} keys) to {}", category, profileName, merged.size(), target);
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("Could not write flattened slicer profile " + profileName, e);
        }
    }

    /** Applies the parent chain first, then the child's own keys, so the leaf profile wins. */
    private void mergeChain(String category, String profileName, ObjectNode target, int depth) {
        if (depth > MAX_INHERITANCE_DEPTH) {
            throw new IllegalStateException("Slicer profile inheritance too deep at " + profileName);
        }

        ObjectNode profile = readProfile(category, profileName);
        String parent = profile.path(INHERITS_KEY).asText("");
        if (!parent.isBlank()) {
            mergeChain(category, parent, target, depth + 1);
        }

        for (Map.Entry<String, JsonNode> entry : profile.properties()) {
            target.set(entry.getKey(), entry.getValue());
        }
    }

    private ObjectNode readProfile(String category, String profileName) {
        Path file = profilesDir.resolve(category).resolve(profileName + ".json");
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("Slicer profile not found: " + file);
        }

        try {
            JsonNode node = objectMapper.readTree(file.toFile());
            if (node instanceof ObjectNode objectNode) {
                return objectNode;
            }
            throw new IllegalStateException("Slicer profile is not a JSON object: " + file);
        } catch (JacksonException e) {
            throw new IllegalStateException("Could not read slicer profile " + file, e);
        }
    }

    private Path flattenedDir() {
        Path dir = flattenedDir;
        if (dir == null) {
            synchronized (this) {
                dir = flattenedDir;
                if (dir == null) {
                    try {
                        dir = Files.createTempDirectory("slicer-profiles-");
                    } catch (IOException e) {
                        throw new IllegalStateException("Could not create directory for flattened slicer profiles", e);
                    }
                    flattenedDir = dir;
                }
            }
        }
        return dir;
    }
}
