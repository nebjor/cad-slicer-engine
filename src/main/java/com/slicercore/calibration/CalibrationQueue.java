package com.slicercore.calibration;

import com.slicercore.order.Order;
import com.slicercore.order.OrderFileStorage;
import com.slicercore.order.OrderItem;
import com.slicercore.order.OrderRepository;
import com.slicercore.upload.UploadedModelStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

/**
 * Which models get the full calibration matrix and where their files are. Nothing is queued
 * on its own: the owner queues an order item or a model still in the upload store by hand
 * (ADR 0008); the quote path's own slices are recorded without any queue.
 */
@Service
public class CalibrationQueue {

    private static final Logger log = LoggerFactory.getLogger(CalibrationQueue.class);
    static final int MAX_LIST_SIZE = 500;

    private final CalibrationQueueRepository entries;
    private final OrderRepository orders;
    private final OrderFileStorage orderFiles;
    private final UploadedModelStore modelStore;
    private final Path dir;
    private final Clock clock;

    public CalibrationQueue(CalibrationQueueRepository entries, OrderRepository orders, OrderFileStorage orderFiles,
                            UploadedModelStore modelStore, CalibrationProperties properties, Clock clock) {
        this.entries = entries;
        this.orders = orders;
        this.orderFiles = orderFiles;
        this.modelStore = modelStore;
        this.dir = Path.of(properties.dir()).toAbsolutePath().normalize();
        this.clock = clock;
    }

    /** The owner's request for one order item; the file stays in the order-files storage until its turn. */
    @Transactional
    public CalibrationQueueEntry enqueueOrderItem(String orderNumber, int position) {
        Order order = orders.findByOrderNumber(orderNumber.trim().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new NoSuchElementException("Order " + orderNumber + " not found."));
        OrderItem item = order.item(position)
                .orElseThrow(() -> new NoSuchElementException("Order " + order.getOrderNumber()
                        + " has no item " + position + "."));
        if (order.isErased()) {
            throw new IllegalStateException("Order " + order.getOrderNumber() + " was erased; its model files are gone.");
        }
        CalibrationQueueEntry entry = entries.save(CalibrationQueueEntry.queued(item.getFileSha256(),
                item.getOriginalFileName(), item.getFileKey(), CalibrationSource.ORDER, order.getOrderNumber(),
                clock.instant()));
        log.info("Queued {} of order {} for calibration", item.getOriginalFileName(), order.getOrderNumber());
        return entry;
    }

    /**
     * The owner's request for a model kept by {@code POST /api/models}. The file is copied into
     * the calibration directory under its hash, so the store's expiry cannot lose it.
     */
    @Transactional
    public CalibrationQueueEntry enqueueStoredModel(String modelId) {
        UploadedModelStore.StoredModel model = modelStore.find(modelId)
                .orElseThrow(() -> new NoSuchElementException("Model " + modelId
                        + " is not in the upload store any more (expired or never uploaded)."));
        String extension = model.path().getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".3mf") ? ".3mf" : ".stl";
        String fileKey = model.id() + extension;
        try {
            Files.createDirectories(dir);
            Files.copy(model.path(), dir.resolve(fileKey), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not copy model " + modelId + " into the calibration directory.", e);
        }
        CalibrationQueueEntry entry = entries.save(CalibrationQueueEntry.queued(model.id(), model.displayName(),
                fileKey, CalibrationSource.MANUAL, null, clock.instant()));
        log.info("Queued {} ({}) from the upload store for calibration", model.displayName(), model.id());
        return entry;
    }

    /** The model's file as it is now; {@link java.nio.file.NoSuchFileException} when it is gone. */
    public InputStream openModel(CalibrationQueueEntry entry) throws IOException {
        return switch (entry.getSource()) {
            case ORDER -> orderFiles.open(entry.getFileKey());
            case MANUAL -> Files.newInputStream(resolveManual(entry.getFileKey()));
            case QUOTE -> throw new IllegalArgumentException("Quote slices are recorded, never queued.");
        };
    }

    /** Only names this class generated are accepted, so no key can reach outside the directory. */
    private Path resolveManual(String fileKey) {
        Path path = dir.resolve(fileKey).normalize();
        if (!path.startsWith(dir) || !UploadedModelStore.isValidId(fileKey.substring(0, Math.max(0, fileKey.length() - 4)))) {
            throw new IllegalArgumentException("Invalid calibration file key.");
        }
        return path;
    }

    @Transactional(readOnly = true)
    public List<CalibrationQueueEntry> nextBatch(int max) {
        return entries.findAllByStartedAtIsNullOrderByQueuedAtAsc(PageRequest.of(0, Math.max(1, max)));
    }

    @Transactional(readOnly = true)
    public List<CalibrationQueueEntry> recent(int limit) {
        return entries.findAllByOrderByQueuedAtDesc(PageRequest.of(0, Math.clamp(limit, 1, MAX_LIST_SIZE)));
    }

    @Transactional(readOnly = true)
    public long waiting() {
        return entries.countByStartedAtIsNull();
    }

    @Transactional
    public CalibrationQueueEntry save(CalibrationQueueEntry entry) {
        return entries.save(entry);
    }
}
