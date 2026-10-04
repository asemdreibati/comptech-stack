package io.souqly.returns.returns;

import java.util.Set;

public enum ReturnStatus {
    /** Waiting for the return policy to route it. */
    REQUESTED,
    AWAITING_SELLER,
    /** The seller refused; the buyer may accept that or dispute it. */
    SELLER_REJECTED,
    /** Marketplace operations are arbitrating. */
    IN_DISPUTE,
    /** Approved; waiting for the parcel. */
    APPROVED,
    /** The parcel arrived and waits for inspection. */
    RECEIVED,
    REFUNDED,
    REJECTED,
    /** Approved, but the parcel never arrived. */
    CANCELLED;

    /** Returns that no longer hold on to the order's quantities. */
    public static final Set<ReturnStatus> CLOSED_WITHOUT_RETURN = Set.of(REJECTED, CANCELLED);
}
