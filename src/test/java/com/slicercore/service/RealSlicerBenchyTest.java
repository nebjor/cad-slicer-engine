package com.slicercore.service;

import com.slicercore.config.QuotePricingProperties;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteResponse;
import com.slicercore.model.SliceProfile;
import com.slicercore.model.SliceQuoteResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Slices the 3DBenchy with the real Bambu Studio CLI through the production code path
 * ({@link SlicerProfileResolver} flattens the profiles, {@link SlicerExecutionService} runs the
 * CLI, {@link QuotePricingService} derives the mass) and checks the result against the known
 * envelope of a Benchy on the A1 with 0.2 mm layers: print time between 30 and 45 minutes and
 * 10 to 14 g of PLA.
 *
 * <p>The test is tagged {@code slicer} and excluded from a plain {@code ./mvnw test}. It runs
 * only with the Maven profile of the same name and skips itself (JUnit assumption, not a
 * failure) when the CLI binary, the profile directory or the model cannot be found, so the
 * command is safe anywhere:
 *
 * <pre>
 * ./mvnw -B test -Pslicer
 * </pre>
 *
 * <p>Inside the development stack of the pod-frontend repository, whose backend image carries
 * the CLI and mounts the frontend's {@code public} folder with the Benchy at
 * {@code /workspace/public/3DBenchy.stl}:
 *
 * <pre>
 * docker compose -f docker-compose.local.yml exec backend ./mvnw -B test -Pslicer
 * </pre>
 *
 * <p>Settings come from {@code application.yml} exactly as in production ({@code slicer.*},
 * {@code quote.*}, including the {@code SLICER_BINARY_PATH} placeholder), and every value can
 * be overridden on the command line or through the environment, for example
 * {@code -Dslicer.binary-path=/opt/bambu/squashfs-root/AppRun} or
 * {@code -Dslicer.profiles-dir=...}. The model path is
 * {@code -Dslicer.benchy=/path/to/3DBenchy.stl} (or {@code SLICER_BENCHY}); its default is
 * {@code 3DBenchy.stl} inside {@code sample-models-dir} ({@code SAMPLE_MODELS_DIR}), the
 * read-only mount of the frontend's sample models in the development stack.
 */
@Tag("slicer")
class RealSlicerBenchyTest {

    private static final Logger log = LoggerFactory.getLogger(RealSlicerBenchyTest.class);
    private static final String BENCHY_FILE_NAME = "3DBenchy.stl";

    private static String binaryPath;
    private static long timeoutSeconds;
    private static Path profilesDir;
    private static Path benchy;
    private static SlicerProfileResolver resolver;
    private static QuotePricingService pricing;

    @TempDir
    Path outputDir;

    /** Reads the settings on every run, even one that is skipped, so a broken binding is noticed. */
    @BeforeAll
    static void readApplicationSettings() {
        ConfigurableEnvironment settings = applicationSettings();

        binaryPath = settings.getRequiredProperty("slicer.binary-path");
        timeoutSeconds = settings.getProperty("slicer.timeout-seconds", Long.class, 120L);
        profilesDir = Path.of(settings.getRequiredProperty("slicer.profiles-dir"));
        resolver = new SlicerProfileResolver(
                profilesDir.toString(),
                settings.getRequiredProperty("slicer.machine-profile"),
                settings.getProperty("slicer.bed-type", ""),
                settings.getProperty("slicer.enable-support", Boolean.class, true));
        pricing = new QuotePricingService(Binder.get(settings).bind("quote", QuotePricingProperties.class).get());

        String sampleModelsDir = settings.getProperty("sample-models-dir", "/workspace/public");
        benchy = Path.of(settings.getProperty("slicer.benchy", Path.of(sampleModelsDir, BENCHY_FILE_NAME).toString()));
    }

    /** Per test rather than per class, so the surefire report shows the skip and its reason. */
    @BeforeEach
    void requireRealSlicerAndModel() {
        Optional<Path> executable = findExecutable(binaryPath);
        assumeTrue(executable.isPresent(),
                "Bambu Studio CLI not found for slicer.binary-path=" + binaryPath + "; skipping the real-slicer test");
        assumeTrue(Files.isDirectory(profilesDir),
                "Slicer profiles not found at slicer.profiles-dir=" + profilesDir + "; skipping the real-slicer test");
        assumeTrue(Files.isRegularFile(benchy),
                "Benchy not found at slicer.benchy=" + benchy + "; skipping the real-slicer test");
        log.info("Real-slicer test uses {} with profiles from {} on {}", executable.get(), profilesDir, benchy);
    }

    @Test
    void benchyInPlaMediumLandsInTheKnownEnvelope() {
        // 20 s for the --info manifold check, the production default.
        SlicerExecutionService slicer = new SlicerExecutionService(binaryPath, timeoutSeconds, 20);
        SliceProfile profile = resolver.resolve(FilamentType.PLA, PrintQuality.MEDIUM);

        SliceQuoteResult slice = slicer.executeSlicingQuote(benchy, outputDir, profile);
        QuoteResponse quote = pricing.price(slice, BENCHY_FILE_NAME, resolver.machineProfileName(),
                FilamentType.PLA, null, PrintQuality.MEDIUM, 1);

        log.info("Benchy PLA medium x1: layer height {} mm, {} s print time, {} mm filament, {} g (slicer says {} g), {} plate(s)",
                slice.layerHeightMm(), Math.round(slice.totalPredicationSeconds()), Math.round(slice.filamentLengthMm()),
                quote.totalFilamentGrams(), slice.slicerReportedGrams(), slice.plateCount());

        assertThat(slice.returnCode()).isZero();
        assertThat(slice.plateCount()).isEqualTo(1);
        assertThat(slice.layerHeightMm()).as("layer height from result.json").isCloseTo(0.2, within(0.001));
        assertThat(quote.layerHeightMm()).isEqualTo(0.2);

        assertThat(quote.totalPrintTimeSeconds()).as("print time in seconds")
                .isBetween(30 * 60.0, 45 * 60.0);

        assertThat(pricing.gramsFromLength(slice.filamentLengthMm(), FilamentType.PLA)).as("mass from filament length")
                .isBetween(10.0, 14.0);
        assertThat(quote.totalFilamentGrams()).as("mass in the priced quote").isBetween(10.0, 14.0);
    }

    /**
     * The {@code slicer.*} and {@code quote.*} blocks of application.yml, with system properties
     * and environment variables taking precedence exactly as they do for the running service.
     */
    private static ConfigurableEnvironment applicationSettings() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        if (properties == null) {
            throw new IllegalStateException("application.yml not found on the test classpath");
        }
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new PropertiesPropertySource("application.yml", properties));
        return environment;
    }

    /** Same split as {@link SlicerExecutionService}: the first token is the program, looked up on PATH if relative. */
    private static Optional<Path> findExecutable(String configuredBinaryPath) {
        String program = configuredBinaryPath.trim().split("\\s+")[0];
        Path direct = Path.of(program);
        if (direct.isAbsolute() || program.contains("/")) {
            return isExecutableFile(direct) ? Optional.of(direct) : Optional.empty();
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (String entry : path.split(File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(program);
            if (isExecutableFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean isExecutableFile(Path path) {
        return Files.isExecutable(path) && !Files.isDirectory(path);
    }
}
