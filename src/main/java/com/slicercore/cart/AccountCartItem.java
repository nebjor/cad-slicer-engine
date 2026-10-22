package com.slicercore.cart;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One line of a customer's cart: a private copy of the model file and the choices the
 * customer made for it. The quote is whatever JSON the frontend holds for the line; the
 * backend re-quotes at checkout and never reads it.
 */
@Entity
@Table(name = "account_cart_items")
public class AccountCartItem {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(nullable = false)
    private int position;

    @Column(name = "file_key", nullable = false)
    private String fileKey;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_sha256", nullable = false)
    private String contentSha256;

    @Column(nullable = false)
    private String filament;

    @Column(name = "color_id", nullable = false)
    private String colorId;

    @Column(nullable = false)
    private String quality;

    @Column(nullable = false)
    private int quantity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quote_json", nullable = false)
    private Map<String, Object> quote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountCartItem() {
    }

    /** The choices of a line, without its file. */
    public record Choice(String fileName, String filament, String colorId, String quality, int quantity,
                         Map<String, Object> quote) {
    }

    public static AccountCartItem create(UUID accountId, int position, String fileKey, long sizeBytes,
                                         String contentSha256, Choice choice, Instant now) {
        AccountCartItem item = new AccountCartItem();
        item.id = UUID.randomUUID();
        item.accountId = accountId;
        item.fileKey = fileKey;
        item.sizeBytes = sizeBytes;
        item.contentSha256 = contentSha256;
        item.createdAt = now;
        item.update(position, choice, now);
        return item;
    }

    /** Applies a new position and choices; saving a line counts as touching it for the retention clock. */
    public void update(int position, Choice choice, Instant now) {
        this.position = position;
        this.fileName = choice.fileName();
        this.filament = choice.filament();
        this.colorId = choice.colorId();
        this.quality = choice.quality();
        this.quantity = choice.quantity();
        this.quote = choice.quote();
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public int getPosition() {
        return position;
    }

    public String getFileKey() {
        return fileKey;
    }

    public String getFileName() {
        return fileName;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public String getFilament() {
        return filament;
    }

    public String getColorId() {
        return colorId;
    }

    public String getQuality() {
        return quality;
    }

    public int getQuantity() {
        return quantity;
    }

    public Map<String, Object> getQuote() {
        return quote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
