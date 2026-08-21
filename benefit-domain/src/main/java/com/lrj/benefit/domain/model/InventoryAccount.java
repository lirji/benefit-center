package com.lrj.benefit.domain.model;

import java.util.Objects;

public final class InventoryAccount {
    private final String tenantId;
    private final String accountId;
    private final String skuId;
    private final InventoryOwnerType ownerType;
    private long available;
    private long reserved;
    private long issued;
    private long version;

    public InventoryAccount(String tenantId, String accountId, String skuId, InventoryOwnerType ownerType,
                            long available, long reserved, long issued, long version) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.skuId = Objects.requireNonNull(skuId, "skuId");
        this.ownerType = Objects.requireNonNull(ownerType, "ownerType");
        if (available < 0 || reserved < 0 || issued < 0) {
            throw new IllegalArgumentException("inventory balances must not be negative");
        }
        this.available = available;
        this.reserved = reserved;
        this.issued = issued;
        this.version = version;
    }

    public void reserve(long quantity) {
        requireMutable();
        requirePositive(quantity);
        if (available < quantity) {
            throw new IllegalStateException("insufficient inventory");
        }
        available -= quantity;
        reserved = Math.addExact(reserved, quantity);
        version++;
    }

    public void commit(long quantity) {
        requireMutable();
        requirePositive(quantity);
        if (reserved < quantity) {
            throw new IllegalStateException("insufficient reserved inventory");
        }
        reserved -= quantity;
        issued = Math.addExact(issued, quantity);
        version++;
    }

    public void release(long quantity) {
        requireMutable();
        requirePositive(quantity);
        if (reserved < quantity) {
            throw new IllegalStateException("insufficient reserved inventory");
        }
        reserved -= quantity;
        available = Math.addExact(available, quantity);
        version++;
    }

    public void returnIssued(long quantity) {
        requireMutable();
        requirePositive(quantity);
        if (issued < quantity) {
            throw new IllegalStateException("insufficient issued inventory");
        }
        issued -= quantity;
        available = Math.addExact(available, quantity);
        version++;
    }

    private void requireMutable() {
        if (ownerType == InventoryOwnerType.CHANNEL_SHADOW) {
            throw new IllegalStateException("channel shadow is updated only by inventory synchronization");
        }
    }

    private static void requirePositive(long quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
    }

    public String tenantId() { return tenantId; }
    public String accountId() { return accountId; }
    public String skuId() { return skuId; }
    public InventoryOwnerType ownerType() { return ownerType; }
    public long available() { return available; }
    public long reserved() { return reserved; }
    public long issued() { return issued; }
    public long version() { return version; }
}
