package io.souqly.seller.application;

import java.util.Set;

public enum ApplicationStatus {
    /** Being filled in by the applicant. */
    DRAFT,
    /** Submitted; screening has not run yet. */
    SUBMITTED,
    /** Screened and waiting for (or in) compliance review. */
    IN_REVIEW,
    /** Compliance asked the applicant for more information. */
    INFORMATION_REQUESTED,
    APPROVED,
    REJECTED,
    /** The applicant did not answer a request for information in time. */
    EXPIRED;

    /** States in which the applicant may change details and documents. */
    public static final Set<ApplicationStatus> EDITABLE = Set.of(DRAFT, INFORMATION_REQUESTED);

    public boolean finished() {
        return this == APPROVED || this == REJECTED || this == EXPIRED;
    }
}
