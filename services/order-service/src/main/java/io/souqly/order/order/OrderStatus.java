package io.souqly.order.order;

/**
 * The checkout saga's states. The happy path is PLACED, STOCK_RESERVED, PAID, CONFIRMED. A failure
 * after something was done moves to a compensating state that undoes it:
 * <ul>
 *   <li>payment declined: RELEASING_STOCK, then CANCELLED;</li>
 *   <li>paid, but the reservation expired before it was confirmed: REFUNDING, then CANCELLED.</li>
 * </ul>
 * REJECTED means nothing was done (for example, out of stock). NEEDS_ATTENTION means the saga gave
 * up retrying a step and a person has to look.
 */
public enum OrderStatus {
    PLACED, STOCK_RESERVED, PAID, CONFIRMED,
    RELEASING_STOCK, REFUNDING, CANCELLED, REJECTED,
    NEEDS_ATTENTION;

    public boolean terminal() {
        return this == CONFIRMED || this == CANCELLED || this == REJECTED || this == NEEDS_ATTENTION;
    }
}
