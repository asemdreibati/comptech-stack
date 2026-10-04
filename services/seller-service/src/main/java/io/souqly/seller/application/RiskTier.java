package io.souqly.seller.application;

/** Set by the {@code kyc-risk} decision table. HIGH needs a second, different reviewer. */
public enum RiskTier {
    LOW, MEDIUM, HIGH
}
