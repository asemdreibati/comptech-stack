package io.souqly.returns.workflow;

/** Names shared by the {@code return-request} model and the code that drives it. */
public final class Returns {

    public static final String PROCESS_KEY = "return-request";
    public static final String MESSAGE_PARCEL_RECEIVED = "ParcelReceived";

    public static final String TASK_SELLER_DECISION = "Task_SellerDecision";
    public static final String TASK_BUYER_RESPONSE = "Task_BuyerResponse";
    public static final String TASK_ARBITRATE = "Task_Arbitrate";
    public static final String TASK_INSPECT = "Task_Inspect";

    // Set when the process starts.
    public static final String RETURN_ID = "returnId";
    public static final String BUYER_ID = "buyerId";
    public static final String SELLER_ID = "sellerId";
    public static final String REASON = "reason";
    public static final String REFUND_AMOUNT = "refundAmount";
    public static final String CURRENCY = "currency";
    public static final String SELLER_SLA = "sellerSla";
    public static final String DISPUTE_WINDOW = "disputeWindow";
    public static final String SHIPPING_WINDOW = "shippingWindow";

    // Set by the policy and the people deciding.
    public static final String ROUTE = "route";
    public static final String SELLER_DECISION = "sellerDecision";
    public static final String BUYER_RESPONSE = "buyerResponse";
    public static final String ARBITRATION = "arbitration";
    public static final String INSPECTION = "inspection";
    public static final String REFUND_ID = "refundId";

    private Returns() {
    }
}
