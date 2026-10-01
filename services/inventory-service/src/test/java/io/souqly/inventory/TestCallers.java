package io.souqly.inventory;

import io.souqly.inventory.security.Caller;

final class TestCallers {

    /** Marketplace operations: may restock any SKU. */
    static final Caller OPERATIONS = new Caller("test-operations", null, true);

    private TestCallers() {
    }
}
