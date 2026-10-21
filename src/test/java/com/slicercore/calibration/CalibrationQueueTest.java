package com.slicercore.calibration;

import com.slicercore.config.ModelStoreProperties;
import com.slicercore.order.LocalOrderFileStorage;
import com.slicercore.order.Order;
import com.slicercore.order.OrderItem;
import com.slicercore.order.OrderRepository;
import com.slicercore.service.UploadedModelStore;
import com.slicercore.support.MutableClock;
import com.slicercore.support.TestOrders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Who gets queued: every item of a placed order once, and whatever the owner asks for. */
class CalibrationQueueTest {

    private static final String MODEL_ID = "c".repeat(64);
    private static final byte[] MODEL_BYTES = "solid clip endsolid clip".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private final CalibrationQueueRepository entries = mock(CalibrationQueueRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final MutableClock clock = MutableClock.at("2026-10-21T15:00:00Z");
    private LocalOrderFileStorage orderFiles;
    private UploadedModelStore modelStore;
    private CalibrationQueue queue;

    @BeforeEach
    void wire() {
        orderFiles = new LocalOrderFileStorage(dir.resolve("order-files"));
        modelStore = new UploadedModelStore(new ModelStoreProperties(dir.resolve("store").toString(), 60, 64), clock);
        when(entries.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        queue = queue(true);
    }

    private CalibrationQueue queue(boolean enabled) {
        return new CalibrationQueue(entries, orders, orderFiles, modelStore,
                new CalibrationProperties(enabled, 5, dir.resolve("calibration").toString()), clock);
    }

    @Test
    void everyItemOfAPlacedOrderIsQueuedOnceWithItsOrderFile() {
        Order order = TestOrders.twoItemOrder();

        queue.orderPlaced(order);

        ArgumentCaptor<CalibrationQueueEntry> saved = ArgumentCaptor.forClass(CalibrationQueueEntry.class);
        verify(entries, times(2)).save(saved.capture());
        List<CalibrationQueueEntry> queued = saved.getAllValues();
        assertThat(queued).extracting(CalibrationQueueEntry::getModelName).containsExactly("benchy.stl", "halter.3mf");
        assertThat(queued).extracting(CalibrationQueueEntry::getFileKey)
                .containsExactly(order.getItems().get(0).getFileKey(), order.getItems().get(1).getFileKey());
        assertThat(queued).extracting(CalibrationQueueEntry::getModelSha256)
                .containsExactly(order.getItems().get(0).getFileSha256(), order.getItems().get(1).getFileSha256());
        assertThat(queued).extracting(CalibrationQueueEntry::getSource).containsOnly(CalibrationSource.ORDER);
        assertThat(queued).extracting(CalibrationQueueEntry::getOrderNumber).containsOnly("POD-1000");
        assertThat(queued).extracting(CalibrationQueueEntry::getQueuedAt).containsOnly(clock.instant());
        assertThat(queued).extracting(CalibrationQueueEntry::status).containsOnly(CalibrationQueueEntry.Status.QUEUED);
        assertThat(queued.getFirst().extension()).isEqualTo(".stl");
    }

    @Test
    void aGeometryAlreadyQueuedOrMeasuredIsNotQueuedAgainByAnOrder() {
        Order order = TestOrders.twoItemOrder();
        when(entries.existsByModelSha256AndErrorIsNull(order.getItems().get(0).getFileSha256())).thenReturn(true);

        queue.orderPlaced(order);

        ArgumentCaptor<CalibrationQueueEntry> saved = ArgumentCaptor.forClass(CalibrationQueueEntry.class);
        verify(entries).save(saved.capture());
        assertThat(saved.getValue().getModelName()).isEqualTo("halter.3mf");
    }

    @Test
    void aDisabledCalibrationIgnoresOrders() {
        queue(false).orderPlaced(TestOrders.twoItemOrder());

        verify(entries, never()).save(any());
    }

    @Test
    void theOwnerQueuesAnOrderItemEvenWhenItWasMeasuredBefore() throws IOException {
        Order order = TestOrders.twoItemOrder();
        when(orders.findByOrderNumber("POD-1000")).thenReturn(Optional.of(order));
        when(entries.existsByModelSha256AndErrorIsNull(any())).thenReturn(true);

        CalibrationQueueEntry entry = queue.enqueueOrderItem("pod-1000", 2);

        assertThat(entry.getModelName()).isEqualTo("halter.3mf");
        assertThat(entry.getFileKey()).isEqualTo(order.getItems().get(1).getFileKey());
        assertThat(entry.getSource()).isEqualTo(CalibrationSource.ORDER);
        assertThat(entry.getOrderNumber()).isEqualTo("POD-1000");
        verify(entries).save(entry);

        assertThatThrownBy(() -> queue.enqueueOrderItem("POD-1000", 9))
                .isInstanceOf(NoSuchElementException.class).hasMessageContaining("no item 9");
        assertThatThrownBy(() -> queue.enqueueOrderItem("POD-9", 1))
                .isInstanceOf(NoSuchElementException.class).hasMessageContaining("POD-9");
    }

    @Test
    void anErasedOrderCannotBeQueuedBecauseItsFilesAreGone() {
        Order order = TestOrders.benchyOrder();
        order.eraseCustomerData(Instant.parse("2026-10-21T14:00:00Z"));
        when(orders.findByOrderNumber("POD-1000")).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> queue.enqueueOrderItem("POD-1000", 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("erased");
        verify(entries, never()).save(any());
    }

    @Test
    void aStoredModelIsCopiedIntoTheCalibrationDirectorySoItsExpiryCannotLoseIt() throws IOException {
        Path staged = Files.write(dir.resolve("staged.stl"), MODEL_BYTES);
        modelStore.put(MODEL_ID, staged, ".stl", "Clip.stl");

        CalibrationQueueEntry entry = queue.enqueueStoredModel(MODEL_ID);

        assertThat(entry.getModelSha256()).isEqualTo(MODEL_ID);
        assertThat(entry.getModelName()).isEqualTo("Clip.stl");
        assertThat(entry.getFileKey()).isEqualTo(MODEL_ID + ".stl");
        assertThat(entry.getSource()).isEqualTo(CalibrationSource.MANUAL);
        assertThat(entry.getOrderNumber()).isNull();
        assertThat(dir.resolve("calibration").resolve(MODEL_ID + ".stl")).hasBinaryContent(MODEL_BYTES);
        // The store keeps its own copy; the queue reads the calibration copy.
        assertThat(modelStore.find(MODEL_ID)).isPresent();
        try (InputStream in = queue.openModel(entry)) {
            assertThat(in.readAllBytes()).isEqualTo(MODEL_BYTES);
        }
    }

    @Test
    void anExpiredOrUnknownModelIdIsNotFound() {
        assertThatThrownBy(() -> queue.enqueueStoredModel("d".repeat(64)))
                .isInstanceOf(NoSuchElementException.class).hasMessageContaining("upload store");
        assertThatThrownBy(() -> queue.enqueueStoredModel("not-a-hash"))
                .isInstanceOf(NoSuchElementException.class);
        verify(entries, never()).save(any());
    }

    @Test
    void anOrderItemsFileIsReadFromTheOrderStorageUntilItIsErased() throws IOException {
        var stored = orderFiles.store(new ByteArrayInputStream(MODEL_BYTES), "stl");
        OrderItem item = TestOrders.benchyOrder().getItems().getFirst();
        CalibrationQueueEntry entry = CalibrationQueueEntry.queued(item.getFileSha256(), "benchy.stl", stored.key(),
                CalibrationSource.ORDER, "POD-1000", clock.instant());

        try (InputStream in = queue.openModel(entry)) {
            assertThat(in.readAllBytes()).isEqualTo(MODEL_BYTES);
        }
        orderFiles.delete(stored.key());
        assertThatThrownBy(() -> queue.openModel(entry)).isInstanceOf(java.nio.file.NoSuchFileException.class);
    }

    @Test
    void aForeignFileKeyNeverLeavesTheCalibrationDirectory() {
        CalibrationQueueEntry entry = CalibrationQueueEntry.queued(MODEL_ID, "x", "../../etc/passwd.stl",
                CalibrationSource.MANUAL, null, clock.instant());

        assertThatThrownBy(() -> queue.openModel(entry)).isInstanceOf(IllegalArgumentException.class);
    }
}
