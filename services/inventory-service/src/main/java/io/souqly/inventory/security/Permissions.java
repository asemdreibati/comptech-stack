package io.souqly.inventory.security;

/**
 * Fine-grained permissions this service checks. They are Keycloak client roles of the
 * {@code inventory-service} client; Keycloak maps business roles (seller, admin) and service
 * accounts onto them, so the service never reasons about who someone is, only what they may do.
 */
public final class Permissions {

    public static final String STOCK_READ = "stock:read";
    /** Restock SKUs owned by the caller's seller account. */
    public static final String STOCK_WRITE = "stock:write";
    /** Restock any SKU, ignoring ownership. Marketplace operations only. */
    public static final String STOCK_WRITE_ANY = "stock:write-any";
    public static final String FLASH_SALE_MANAGE = "flash-sale:manage";
    public static final String RESERVATION_WRITE = "reservation:write";

    private Permissions() {
    }
}
