package io.souqly.catalog.security;

/** Client roles of the {@code catalog-service} Keycloak client. */
public final class Permissions {

    public static final String CATEGORY_MANAGE = "category:manage";
    /** Create and edit listings owned by the caller's seller account. */
    public static final String PRODUCT_WRITE = "product:write";
    /** Edit any listing, and create marketplace-owned ones. Operations only. */
    public static final String PRODUCT_WRITE_ANY = "product:write-any";

    private Permissions() {
    }
}
