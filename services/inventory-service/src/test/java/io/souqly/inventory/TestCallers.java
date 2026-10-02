package io.souqly.inventory;

import java.util.Set;

import io.souqly.platform.security.Caller;

final class TestCallers {

    /** Marketplace operations: may restock any SKU. */
    static final Caller OPERATIONS = new Caller("test-operations", null, Set.of("stock:write-any"));

    private TestCallers() {
    }
}
