package io.souqly.order.security;

/** Client roles of the {@code order-service} Keycloak client. */
public final class Permissions {

    /** Check out and read one's own orders. Buyers hold it. */
    public static final String ORDER_PLACE = "order:place";
    /** Read anyone's orders. Operations only. */
    public static final String ORDER_READ_ANY = "order:read-any";

    private Permissions() {
    }
}
