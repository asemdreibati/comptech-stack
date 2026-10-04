package io.souqly.seller.security;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.souqly.platform.security.Caller;

/** Client roles of the {@code seller-service} Keycloak client, and the review groups they open. */
public final class Permissions {

    /** Apply to become a seller and manage one's own application. Buyers hold it. */
    public static final String APPLICATION_SUBMIT = "application:submit";
    /** Review applications. Compliance officers hold it. */
    public static final String APPLICATION_REVIEW = "application:review";
    /** Second review of high-risk applications, and escalated reviews. Compliance leads hold it. */
    public static final String APPLICATION_REVIEW_SENIOR = "application:review-senior";

    /** Workflow candidate groups. */
    public static final String COMPLIANCE = "compliance";
    public static final String COMPLIANCE_LEADS = "compliance-leads";

    private Permissions() {
    }

    /** The review task groups a caller may work in. */
    public static List<String> reviewGroups(Caller caller) {
        Set<String> groups = new LinkedHashSet<>();
        if (caller.has(APPLICATION_REVIEW) || caller.has(APPLICATION_REVIEW_SENIOR)) {
            groups.add(COMPLIANCE);
        }
        if (caller.has(APPLICATION_REVIEW_SENIOR)) {
            groups.add(COMPLIANCE_LEADS);
        }
        return List.copyOf(groups);
    }

    public static boolean isReviewer(Caller caller) {
        return !reviewGroups(caller).isEmpty();
    }
}
