package com.slicercore.order;

import com.slicercore.catalog.ColorOption;
import com.slicercore.catalog.PrintQuality;
import com.slicercore.quote.QuoteResponse;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * One model on an order: its file, print parameters, the slice figures, what the slicer said
 * about the mesh, and the line price. Each item is quoted and sliced on its own; the setup fee
 * belongs to the order. Like the order, everything is copied from the quote at creation and
 * never updated.
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** 1-based, stable for the life of the order; what the admin download addresses. */
    @Column(nullable = false)
    private int position;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @Column(name = "file_key", nullable = false, length = 64)
    private String fileKey;

    @Column(name = "file_sha256", nullable = false, length = 64)
    private String fileSha256;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(nullable = false, length = 16)
    private String filament;

    @Column(name = "color_code", nullable = false, length = 32)
    private String colorCode;

    @Column(name = "color_label", nullable = false, length = 64)
    private String colorLabel;

    /** Null on orders placed before colours were stored with their swatch. */
    @Column(name = "color_hex", length = 16)
    private String colorHex;

    /** Null on orders placed before material was priced per colour. */
    @Column(name = "color_price_per_kg_chf", precision = 10, scale = 2)
    private BigDecimal colorPricePerKgChf;

    @Column(nullable = false, length = 16)
    private String quality;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "machine_profile", nullable = false, length = 120)
    private String machineProfile;

    @Column(name = "layer_height_mm", nullable = false, precision = 4, scale = 2)
    private BigDecimal layerHeightMm;

    @Column(name = "plate_count", nullable = false)
    private int plateCount;

    /** Print time for all copies of this item. */
    @Column(name = "print_time_seconds", nullable = false)
    private int printTimeSeconds;

    /** Material for all copies of this item. */
    @Column(name = "filament_grams", nullable = false, precision = 10, scale = 1)
    private BigDecimal filamentGrams;

    /** What the slicer warned about, as it printed it; empty on items ordered before it was recorded. */
    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "slicer_warnings", nullable = false, columnDefinition = "text")
    private List<String> slicerWarnings = List.of();

    /** The slicer's manifold check; null when it did not run, including every older item. */
    @Column(name = "manifold")
    private Boolean manifold;

    @Column(name = "unit_price_chf", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "line_total_chf", nullable = false, precision = 10, scale = 2)
    private BigDecimal lineTotal;

    protected OrderItem() {
        // JPA
    }

    /**
     * An item not yet attached to an order; {@link Order#create} attaches it.
     *
     * @param fileName what the customer called the model; shown to them and to the owner
     */
    public static OrderItem create(int position, String fileName, QuoteResponse quote, OrderFileStorage.StoredFile file) {
        if (position < 1) {
            throw new IllegalArgumentException("Item positions start at 1.");
        }
        ItemTotals totals = ItemTotals.from(quote);

        OrderItem item = new OrderItem();
        item.id = UUID.randomUUID();
        item.position = position;
        item.originalFileName = fileName;
        item.fileKey = file.key();
        item.fileSha256 = file.sha256();
        item.fileSizeBytes = file.sizeBytes();

        item.filament = quote.filament();
        item.colorCode = quote.color().code();
        item.colorLabel = quote.color().label();
        item.colorHex = quote.color().hex();
        item.colorPricePerKgChf = quote.color().pricePerKgChf() == null
                ? null
                : BigDecimal.valueOf(quote.color().pricePerKgChf()).setScale(2, RoundingMode.HALF_UP);
        item.quality = quote.quality();
        item.quantity = quote.quantity();
        item.machineProfile = quote.machineProfile();
        item.layerHeightMm = BigDecimal.valueOf(quote.layerHeightMm()).setScale(2, RoundingMode.HALF_UP);
        item.plateCount = quote.plateCount();
        item.printTimeSeconds = (int) Math.round(quote.totalPrintTimeSeconds());
        item.filamentGrams = BigDecimal.valueOf(quote.totalFilamentGrams()).setScale(1, RoundingMode.HALF_UP);
        item.slicerWarnings = List.copyOf(quote.warnings());
        item.manifold = quote.manifold();

        item.unitPrice = totals.unitPrice();
        item.lineTotal = totals.lineTotal();
        return item;
    }

    void attachTo(Order owner) {
        if (order != null && order != owner) {
            throw new IllegalStateException("Item " + position + " already belongs to order " + order.getOrderNumber() + ".");
        }
        order = owner;
    }

    /** The ordered colour as the catalogue describes it. */
    public ColorOption getColor() {
        return new ColorOption(colorCode, colorLabel, colorHex,
                colorPricePerKgChf == null ? null : colorPricePerKgChf.doubleValue());
    }

    /** Customer-facing name of the quality tier; falls back to the stored code if the tier is gone. */
    public String getQualityLabel() {
        try {
            return PrintQuality.fromInput(quality).label();
        } catch (IllegalArgumentException e) {
            return quality;
        }
    }

    public ItemTotals getTotals() {
        return new ItemTotals(unitPrice, quantity, lineTotal);
    }

    /** True when the owner should open this model in Bambu Studio before printing it. */
    public boolean needsInspection() {
        return !getWarnings().isEmpty() || Boolean.FALSE.equals(manifold);
    }

    public List<String> getWarnings() {
        return slicerWarnings == null ? List.of() : slicerWarnings;
    }

    public Boolean getManifold() {
        return manifold;
    }

    public UUID getId() {
        return id;
    }

    public Order getOrder() {
        return order;
    }

    public int getPosition() {
        return position;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public String getFileKey() {
        return fileKey;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public long getFileSizeBytes() {
        return fileSizeBytes;
    }

    public String getFilament() {
        return filament;
    }

    public String getColorLabel() {
        return colorLabel;
    }

    public String getQuality() {
        return quality;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getMachineProfile() {
        return machineProfile;
    }

    public BigDecimal getLayerHeightMm() {
        return layerHeightMm;
    }

    public int getPlateCount() {
        return plateCount;
    }

    public int getPrintTimeSeconds() {
        return printTimeSeconds;
    }

    public BigDecimal getFilamentGrams() {
        return filamentGrams;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getLineTotal() {
        return lineTotal;
    }
}
