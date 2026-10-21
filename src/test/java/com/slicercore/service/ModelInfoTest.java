package com.slicercore.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing of the {@code --info} report, one key = value block per object. */
class ModelInfoTest {

    @Test
    void readsTheVerifiedSingleObjectReport() {
        ModelInfo info = ModelInfo.parse(List.of(
                "[cube.stl]", "size_x = 10.000000", "size_y = 10.000000", "size_z = 10.000000",
                "min_x = 0.000000", "max_z = 10.000000", "number_of_facets = 4", "manifold = yes",
                "number_of_parts =  1", "volume = 166.666672"));

        assertThat(info.manifold()).isTrue();
        assertThat(info.facets()).isEqualTo(4);
        assertThat(info.volumeMm3()).isEqualTo(166.666672);
        assertThat(info.warnings()).isEmpty();
    }

    @Test
    void oneLeakyObjectMakesTheModelNonManifoldAndTheRepairCountersAWarning() {
        ModelInfo info = ModelInfo.parse(List.of(
                "[body.stl]", "number_of_facets = 1200", "manifold = yes", "volume = 100.5",
                "[lid.stl]", "number_of_facets = 300", "manifold = no", "open_edges = 8",
                "facets_reversed = 2", "edges_fixed = 0", "volume = 20.25",
                "error: XDG_RUNTIME_DIR is invalid or not set in the environment."));

        assertThat(info.manifold()).isFalse();
        assertThat(info.facets()).isEqualTo(1500);
        assertThat(info.volumeMm3()).isEqualTo(120.75);
        assertThat(info.warnings()).containsExactly("Mesh errors reported by the slicer: open_edges = 8, facets_reversed = 2");
    }

    @Test
    void unknownOutputLeavesTheCheckOpen() {
        ModelInfo info = ModelInfo.parse(List.of("[error] glfwInit return error, code 65544", "done"));

        assertThat(info.manifold()).isNull();
        assertThat(info.facets()).isZero();
        assertThat(info.warnings()).isEmpty();
        assertThat(ModelInfo.parse(List.of()).manifold()).isNull();
    }
}
