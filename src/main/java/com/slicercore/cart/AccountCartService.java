package com.slicercore.cart;

import com.slicercore.upload.UploadedModel;
import com.slicercore.order.OrderFileStorage;
import com.slicercore.order.OrderModelExpiredException;
import com.slicercore.order.OrderRequest;
import com.slicercore.upload.UploadService;
import com.slicercore.upload.UploadedModelStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The server-side cart of a signed-in customer (ADR 0010). The client always sends the whole
 * cart; the service makes the stored cart equal to it. Every line owns its file on the
 * order-files volume, copied from the one-hour upload store when the line is first saved and
 * kept by id afterwards, so two lines with the same model still have two files and removing
 * one line never touches the other.
 *
 * Files are removed only after the database transaction committed, and files copied for a
 * request that then fails are removed again, so a failed save leaves the old cart intact.
 */
@Service
public class AccountCartService {

    private static final Logger log = LoggerFactory.getLogger(AccountCartService.class);
    /** The same ceiling as an order, so a full cart can always be ordered. */
    public static final int MAX_ITEMS = OrderRequest.MAX_ITEMS;

    private final AccountCartItemRepository items;
    private final OrderFileStorage files;
    private final UploadedModelStore modelStore;
    private final UploadService uploads;
    private final CartProperties properties;
    private final Clock clock;

    public AccountCartService(AccountCartItemRepository items, OrderFileStorage files, UploadedModelStore modelStore,
                              UploadService uploads, CartProperties properties, Clock clock) {
        this.items = items;
        this.files = files;
        this.modelStore = modelStore;
        this.uploads = uploads;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public CartResponse cart(UUID accountId) {
        return respond(items.findByAccountIdOrderByPosition(accountId));
    }

    /**
     * Makes the stored cart equal to {@code requested}, in that order. Lines of the old cart
     * that are not listed are deleted with their files.
     *
     * @throws IllegalArgumentException    more than {@value #MAX_ITEMS} lines, a line with neither or both
     *                                     of {@code id} and {@code modelId}, or an id listed twice
     * @throws CartItemNotFoundException   a line id that is not in this account's cart
     * @throws OrderModelExpiredException  a {@code modelId} that is unknown or has expired
     */
    @Transactional
    public CartResponse replace(UUID accountId, List<CartItemRequest> requested) {
        if (requested.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("A cart holds at most " + MAX_ITEMS + " lines.");
        }
        Instant now = clock.instant();
        List<AccountCartItem> current = items.findByAccountIdOrderByPosition(accountId);
        Map<UUID, AccountCartItem> currentById = new HashMap<>();
        current.forEach(item -> currentById.put(item.getId(), item));

        List<String> copiedKeys = new ArrayList<>();
        try {
            List<AccountCartItem> result = new ArrayList<>(requested.size());
            Set<UUID> kept = new HashSet<>();
            for (int index = 0; index < requested.size(); index++) {
                CartItemRequest line = requested.get(index);
                boolean hasId = line.id() != null;
                boolean hasModel = line.modelId() != null && !line.modelId().isBlank();
                if (hasId == hasModel) {
                    throw new IllegalArgumentException("Cart line " + (index + 1) + " needs either an id or a modelId.");
                }
                int position = index + 1;
                if (hasId) {
                    AccountCartItem existing = currentById.get(line.id());
                    if (existing == null) {
                        throw new CartItemNotFoundException();
                    }
                    if (!kept.add(existing.getId())) {
                        throw new IllegalArgumentException("Cart line " + (index + 1) + " repeats an earlier id.");
                    }
                    existing.update(position, line.choice(), now);
                    result.add(existing);
                } else {
                    UploadedModelStore.StoredModel model = modelStore.find(line.modelId().trim())
                            .orElseThrow(() -> new OrderModelExpiredException(result.size()));
                    OrderFileStorage.StoredFile stored = copy(model);
                    copiedKeys.add(stored.key());
                    result.add(AccountCartItem.create(accountId, position, stored.key(), stored.sizeBytes(),
                            stored.sha256(), line.choice(), now));
                }
            }
            List<AccountCartItem> removed = current.stream().filter(item -> !kept.contains(item.getId())).toList();
            items.deleteAll(removed);
            items.saveAll(result);
            deleteFilesAfterCommit(removed);
            return respond(result);
        } catch (RuntimeException e) {
            copiedKeys.forEach(this::deleteQuietly);
            throw e;
        }
    }

    /**
     * Registers the line's stored file in the upload store under a fresh id, through the same
     * path as {@code POST /api/models}, so a device that has no copy can quote and order it.
     *
     * @throws CartItemNotFoundException when the line is not in this account's cart or its file is gone
     */
    @Transactional(readOnly = true)
    public UploadedModel registerModel(UUID accountId, UUID itemId) {
        AccountCartItem item = items.findById(itemId)
                .filter(found -> found.getAccountId().equals(accountId))
                .orElseThrow(CartItemNotFoundException::new);
        String extension = extensionOf(item.getFileKey());
        String name = item.getFileName().toLowerCase(Locale.ROOT).endsWith(extension)
                ? item.getFileName() : item.getFileName() + extension;
        try (InputStream in = files.open(item.getFileKey())) {
            return uploads.storeUpload(in, name);
        } catch (NoSuchFileException e) {
            log.warn("File {} of cart line {} is missing", item.getFileKey(), itemId);
            throw new CartItemNotFoundException();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the cart file.", e);
        }
    }

    /** Empties the cart and deletes its files; also the account deletion hook. */
    @Transactional
    public void clear(UUID accountId) {
        List<AccountCartItem> current = items.findByAccountIdOrderByPosition(accountId);
        items.deleteAll(current);
        deleteFilesAfterCommit(current);
    }

    /**
     * Deletes the lines nobody changed within {@code account.cart-retention-days}, with their files.
     *
     * @return how many lines went
     */
    @Transactional
    public int deleteStale() {
        List<AccountCartItem> stale = items.findByUpdatedAtBefore(clock.instant().minus(properties.retention()));
        items.deleteAll(stale);
        deleteFilesAfterCommit(stale);
        return stale.size();
    }

    private CartResponse respond(List<AccountCartItem> lines) {
        return new CartResponse(lines.stream().map(CartItemResponse::from).toList());
    }

    private OrderFileStorage.StoredFile copy(UploadedModelStore.StoredModel model) {
        String extension = extensionOf(model.path().getFileName().toString());
        try (InputStream in = Files.newInputStream(model.path())) {
            return files.store(in, extension);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store the cart model.", e);
        }
    }

    private static String extensionOf(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".3mf") ? ".3mf" : ".stl";
    }

    private void deleteFilesAfterCommit(List<AccountCartItem> removed) {
        if (removed.isEmpty()) {
            return;
        }
        List<String> keys = removed.stream().map(AccountCartItem::getFileKey).toList();
        Runnable deletion = () -> keys.forEach(this::deleteQuietly);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deletion.run();
                }
            });
        } else {
            deletion.run();
        }
    }

    private void deleteQuietly(String key) {
        try {
            files.delete(key);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not delete cart file {}: {}", key, e.getMessage());
        }
    }
}
