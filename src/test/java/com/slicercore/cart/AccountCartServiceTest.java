package com.slicercore.cart;

import com.slicercore.config.ModelStoreProperties;
import com.slicercore.model.UploadedModel;
import com.slicercore.order.LocalOrderFileStorage;
import com.slicercore.order.OrderModelExpiredException;
import com.slicercore.service.QuoteService;
import com.slicercore.service.UploadedModelStore;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The cart is "replace the whole thing": the lines come back in the order sent, a model is
 * copied from the upload store into the order-files volume once per line, listed ids keep their
 * file, everything else goes with its file. The repository is an in-memory list, the file
 * storage and the model store are real and live in a temporary directory.
 */
class AccountCartServiceTest {

    private static final String MODEL_A = "a".repeat(64);
    private static final String MODEL_B = "b".repeat(64);

    @TempDir
    Path dir;

    private final MutableClock clock = MutableClock.at("2026-10-22T10:00:00Z");
    private final UUID account = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final List<AccountCartItem> rows = new ArrayList<>();
    private final AccountCartItemRepository repository = mock(AccountCartItemRepository.class);
    private final QuoteService quotes = mock(QuoteService.class);
    private UploadedModelStore modelStore;
    private AccountCartService service;
    private Path orderFiles;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws IOException {
        orderFiles = Files.createDirectories(dir.resolve("order-files"));
        LocalOrderFileStorage files = new LocalOrderFileStorage(orderFiles);
        modelStore = new UploadedModelStore(new ModelStoreProperties(dir.resolve("models").toString(), 60, 64), clock);
        service = new AccountCartService(repository, files, modelStore, quotes, new CartProperties(30), clock);

        when(repository.findByAccountIdOrderByPosition(any())).thenAnswer(call -> rows.stream()
                .filter(row -> row.getAccountId().equals(call.getArgument(0)))
                .sorted((a, b) -> Integer.compare(a.getPosition(), b.getPosition())).toList());
        when(repository.findById(any())).thenAnswer(call -> rows.stream()
                .filter(row -> row.getId().equals(call.getArgument(0))).findFirst());
        when(repository.findByUpdatedAtBefore(any())).thenAnswer(call -> rows.stream()
                .filter(row -> row.getUpdatedAt().isBefore(call.getArgument(0))).toList());
        when(repository.saveAll(any())).thenAnswer(call -> {
            for (AccountCartItem item : (Iterable<AccountCartItem>) call.getArgument(0)) {
                if (!rows.contains(item)) {
                    rows.add(item);
                }
            }
            return call.getArgument(0);
        });
        doAnswer(call -> {
            for (AccountCartItem item : (Iterable<AccountCartItem>) call.getArgument(0)) {
                rows.remove(item);
            }
            return null;
        }).when(repository).deleteAll(any());
    }

    @Test
    void anEmptyCartReadsEmpty() {
        assertThat(service.cart(account).items()).isEmpty();
    }

    @Test
    void newLinesCopyTheirModelFromTheUploadStoreInTheOrderSent() throws IOException {
        keep(MODEL_A, "benchy-bytes");
        keep(MODEL_B, "cube-bytes");

        CartResponse cart = service.replace(account, List.of(newLine(MODEL_B, "cube.stl", 2), newLine(MODEL_A, "benchy.stl", 1)));

        assertThat(cart.items()).extracting(CartItemResponse::fileName).containsExactly("cube.stl", "benchy.stl");
        assertThat(cart.items()).extracting(CartItemResponse::position).containsExactly(1, 2);
        assertThat(cart.items().get(0).sizeBytes()).isEqualTo("cube-bytes".length());
        assertThat(cart.items().get(0).quote()).containsEntry("totalChf", 12.5);
        assertThat(stored(0)).isEqualTo("cube-bytes");
        assertThat(stored(1)).isEqualTo("benchy-bytes");
        assertThat(rows.get(0).getContentSha256()).hasSize(64);
        assertThat(service.cart(account).items()).hasSize(2);
    }

    @Test
    void twoLinesWithTheSameModelGetTheirOwnFiles() throws IOException {
        keep(MODEL_A, "benchy-bytes");

        service.replace(account, List.of(newLine(MODEL_A, "benchy.stl", 1), newLine(MODEL_A, "benchy.stl", 3)));

        assertThat(rows).extracting(AccountCartItem::getFileKey).doesNotHaveDuplicates();
        assertThat(orderFileCount()).isEqualTo(2);
    }

    @Test
    void aLineWithAnIdKeepsItsFileAndTakesTheNewChoicesAndPosition() throws IOException {
        keep(MODEL_A, "benchy-bytes");
        keep(MODEL_B, "cube-bytes");
        CartResponse first = service.replace(account, List.of(newLine(MODEL_A, "benchy.stl", 1)));
        UUID benchyId = first.items().get(0).id();
        String benchyKey = rows.get(0).getFileKey();
        clock.advance(Duration.ofDays(1));
        keep(MODEL_B, "cube-bytes");

        CartResponse second = service.replace(account,
                List.of(newLine(MODEL_B, "cube.stl", 1), keptLine(benchyId, "benchy.stl", 5)));

        assertThat(second.items()).extracting(CartItemResponse::fileName).containsExactly("cube.stl", "benchy.stl");
        CartItemResponse benchy = second.items().get(1);
        assertThat(benchy.id()).isEqualTo(benchyId);
        assertThat(benchy.position()).isEqualTo(2);
        assertThat(benchy.quantity()).isEqualTo(5);
        assertThat(benchy.updatedAt()).isEqualTo(clock.instant());
        assertThat(rows).filteredOn(row -> row.getId().equals(benchyId)).singleElement()
                .satisfies(row -> assertThat(row.getFileKey()).isEqualTo(benchyKey));
        assertThat(orderFileCount()).isEqualTo(2);
    }

    @Test
    void linesThatAreNotListedAreDeletedWithTheirFiles() throws IOException {
        keep(MODEL_A, "benchy-bytes");
        keep(MODEL_B, "cube-bytes");
        CartResponse first = service.replace(account,
                List.of(newLine(MODEL_A, "benchy.stl", 1), newLine(MODEL_B, "cube.stl", 1)));
        Path droppedFile = orderFiles.resolve(rows.get(0).getFileKey());

        CartResponse second = service.replace(account, List.of(keptLine(first.items().get(1).id(), "cube.stl", 1)));

        assertThat(second.items()).hasSize(1);
        assertThat(rows).hasSize(1);
        assertThat(droppedFile).doesNotExist();
        assertThat(orderFileCount()).isEqualTo(1);
    }

    @Test
    void anEmptyListEmptiesTheCart() throws IOException {
        keep(MODEL_A, "benchy-bytes");
        service.replace(account, List.of(newLine(MODEL_A, "benchy.stl", 1)));

        assertThat(service.replace(account, List.of()).items()).isEmpty();
        assertThat(orderFileCount()).isZero();
    }

    @Test
    void anUnknownOrForeignIdIsNotFoundAndChangesNothing() throws IOException {
        keep(MODEL_A, "benchy-bytes");
        keep(MODEL_B, "cube-bytes");
        service.replace(other, List.of(newLine(MODEL_A, "benchy.stl", 1)));
        UUID foreign = rows.get(0).getId();
        service.replace(account, List.of(newLine(MODEL_B, "cube.stl", 1)));

        assertThatThrownBy(() -> service.replace(account,
                List.of(newLine(MODEL_A, "benchy.stl", 1), keptLine(foreign, "x.stl", 1))))
                .isInstanceOf(CartItemNotFoundException.class);

        assertThat(rows).hasSize(2);
        assertThat(orderFileCount()).as("the file copied before the failure is removed again").isEqualTo(2);
    }

    @Test
    void anExpiredModelFailsWithItsIndexAndLeavesTheOldCartAndNoStrayFiles() throws IOException {
        keep(MODEL_A, "benchy-bytes");
        CartResponse first = service.replace(account, List.of(newLine(MODEL_A, "benchy.stl", 1)));
        keep(MODEL_B, "cube-bytes");
        UUID keptId = first.items().get(0).id();

        assertThatThrownBy(() -> service.replace(account, List.of(keptLine(keptId, "benchy.stl", 1),
                newLine(MODEL_B, "cube.stl", 1), newLine("c".repeat(64), "gone.stl", 1))))
                .isInstanceOfSatisfying(OrderModelExpiredException.class, e -> assertThat(e.itemIndex()).isEqualTo(2));

        assertThat(service.cart(account).items()).extracting(CartItemResponse::id).containsExactly(keptId);
        assertThat(orderFileCount()).isEqualTo(1);
    }

    @Test
    void aLineNeedsExactlyOneOfIdAndModelId() {
        assertThatThrownBy(() -> service.replace(account, List.of(line(null, null, "a.stl", 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.replace(account, List.of(line(UUID.randomUUID(), MODEL_A, "a.stl", 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSameIdTwiceIsRejected() throws IOException {
        keep(MODEL_A, "benchy-bytes");
        UUID id = service.replace(account, List.of(newLine(MODEL_A, "benchy.stl", 1))).items().get(0).id();

        assertThatThrownBy(() -> service.replace(account, List.of(keptLine(id, "a.stl", 1), keptLine(id, "a.stl", 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aCartHoldsTenLinesAtMost() throws IOException {
        keep(MODEL_A, "benchy-bytes");
        List<CartItemRequest> ten = IntStream.range(0, 10).mapToObj(i -> newLine(MODEL_A, "benchy.stl", 1)).toList();
        List<CartItemRequest> eleven = Stream.concat(ten.stream(), Stream.of(newLine(MODEL_A, "benchy.stl", 1))).toList();

        assertThat(service.replace(account, ten).items()).hasSize(10);
        assertThatThrownBy(() -> service.replace(account, eleven)).isInstanceOf(IllegalArgumentException.class);
        assertThat(rows).hasSize(10);
    }

    @Test
    void cartsOfOtherAccountsAreNotTouched() throws IOException {
        keep(MODEL_A, "benchy-bytes");
        service.replace(other, List.of(newLine(MODEL_A, "benchy.stl", 1)));

        service.replace(account, List.of());

        assertThat(service.cart(other).items()).hasSize(1);
        assertThat(orderFileCount()).isEqualTo(1);
    }

    @Test
    void registeringAStoredFileSendsItsBytesThroughTheUploadPath() throws IOException {
        keep(MODEL_A, "benchy-bytes");
        UUID id = service.replace(account, List.of(newLine(MODEL_A, "benchy.stl", 1))).items().get(0).id();
        UploadedModel fresh = new UploadedModel(MODEL_A, "benchy.stl", 12);
        String[] seen = new String[1];
        when(quotes.storeUpload(any(InputStream.class), eq("benchy.stl"))).thenAnswer(call -> {
            seen[0] = new String(call.<InputStream>getArgument(0).readAllBytes(), StandardCharsets.UTF_8);
            return fresh;
        });

        assertThat(service.registerModel(account, id)).isEqualTo(fresh);
        assertThat(seen[0]).isEqualTo("benchy-bytes");
    }

    @Test
    void registeringAnotherAccountsLineOrAMissingFileIsNotFound() throws IOException {
        keep(MODEL_A, "benchy-bytes");
        UUID id = service.replace(other, List.of(newLine(MODEL_A, "benchy.stl", 1))).items().get(0).id();

        assertThatThrownBy(() -> service.registerModel(account, id)).isInstanceOf(CartItemNotFoundException.class);

        Files.delete(orderFiles.resolve(rows.get(0).getFileKey()));
        assertThatThrownBy(() -> service.registerModel(other, id)).isInstanceOf(CartItemNotFoundException.class);
    }

    @Test
    void clearDeletesLinesAndFilesOfThatAccountOnly() throws IOException {
        keep(MODEL_A, "benchy-bytes");
        service.replace(account, List.of(newLine(MODEL_A, "benchy.stl", 1), newLine(MODEL_A, "benchy.stl", 1)));
        service.replace(other, List.of(newLine(MODEL_A, "benchy.stl", 1)));

        service.clear(account);

        assertThat(rows).extracting(AccountCartItem::getAccountId).containsOnly(other);
        assertThat(orderFileCount()).isEqualTo(1);
    }

    @Test
    void theSweepDeletesLinesUnchangedForTheRetentionPeriodWithTheirFiles() throws IOException {
        keep(MODEL_A, "benchy-bytes");
        service.replace(account, List.of(newLine(MODEL_A, "benchy.stl", 1)));
        clock.advance(Duration.ofDays(29));
        keep(MODEL_A, "benchy-bytes");
        service.replace(other, List.of(newLine(MODEL_A, "benchy.stl", 1)));
        assertThat(service.deleteStale()).isZero();

        clock.advance(Duration.ofDays(2));
        assertThat(new CartRetentionSweep(service).sweep()).isEqualTo(1);

        assertThat(rows).extracting(AccountCartItem::getAccountId).containsOnly(other);
        assertThat(orderFileCount()).isEqualTo(1);
    }

    @Test
    void savingALineRestartsItsRetentionClock() throws IOException {
        keep(MODEL_A, "benchy-bytes");
        UUID id = service.replace(account, List.of(newLine(MODEL_A, "benchy.stl", 1))).items().get(0).id();
        clock.advance(Duration.ofDays(20));
        service.replace(account, List.of(keptLine(id, "benchy.stl", 2)));
        clock.advance(Duration.ofDays(20));

        assertThat(service.deleteStale()).isZero();
    }

    private CartItemRequest newLine(String modelId, String name, int quantity) {
        return line(null, modelId, name, quantity);
    }

    private CartItemRequest keptLine(UUID id, String name, int quantity) {
        return line(id, null, name, quantity);
    }

    private CartItemRequest line(UUID id, String modelId, String name, int quantity) {
        return new CartItemRequest(id, modelId, name, "PLA", "red", "standard", quantity, Map.of("totalChf", 12.5));
    }

    private void keep(String id, String content) throws IOException {
        Path staged = Files.writeString(dir.resolve("staged-" + id.charAt(0) + ".stl"), content);
        modelStore.put(id, staged, ".stl", "upload.stl");
    }

    private String stored(int row) throws IOException {
        return Files.readString(orderFiles.resolve(rows.get(row).getFileKey()));
    }

    private long orderFileCount() throws IOException {
        try (Stream<Path> list = Files.list(orderFiles)) {
            return list.count();
        }
    }
}
