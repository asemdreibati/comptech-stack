package io.souqly.returns.security;

import java.util.ArrayList;
import java.util.List;

import io.souqly.platform.security.Caller;

/** Client roles of the {@code returns-service} Keycloak client, and the work queues they open. */
public final class Permissions {

    /** Return items from one's own orders, and answer a seller's rejection. Buyers hold it. */
    public static final String RETURN_REQUEST = "return:request";
    /** Decide returns of one's own products. Sellers hold it. */
    public static final String RETURN_DECIDE = "return:decide";
    /** Settle disputes between buyers and sellers. Marketplace operations hold it. */
    public static final String RETURN_ARBITRATE = "return:arbitrate";
    /** Receive and inspect returned parcels. The warehouse holds it. */
    public static final String RETURN_HANDLE = "return:handle";

    /** Workflow candidate groups. */
    public static final String MARKETPLACE_OPS = "marketplace-ops";
    public static final String WAREHOUSE = "warehouse";

    private Permissions() {
    }

    /** Each seller has their own queue, so one seller never sees another's returns. */
    public static String sellerGroup(String sellerId) {
        return "seller-" + sellerId;
    }

    /** The work queues a caller may see and act in. */
    public static List<String> workGroups(Caller caller) {
        List<String> groups = new ArrayList<>();
        if (caller.has(RETURN_DECIDE) && caller.sellerId() != null) {
            groups.add(sellerGroup(caller.sellerId()));
        }
        if (caller.has(RETURN_ARBITRATE)) {
            groups.add(MARKETPLACE_OPS);
        }
        if (caller.has(RETURN_HANDLE)) {
            groups.add(WAREHOUSE);
        }
        return List.copyOf(groups);
    }
}
