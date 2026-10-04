package io.souqly.seller.workflow;

/** Names shared by the {@code seller-onboarding} model and the code that drives it. */
public final class Onboarding {

    public static final String PROCESS_KEY = "seller-onboarding";

    public static final String TASK_REVIEW = "Task_Review";
    public static final String TASK_SECOND_REVIEW = "Task_SecondReview";
    public static final String TASK_PROVIDE_INFORMATION = "Task_ProvideInformation";

    // Set when the process starts.
    public static final String APPLICATION_ID = "applicationId";
    public static final String APPLICANT_ID = "applicantId";
    public static final String REVIEW_SLA = "reviewSla";
    public static final String INFORMATION_DEADLINE = "informationDeadline";

    // Set by screening, read by the risk decision.
    public static final String DUPLICATE_COUNT = "duplicateCount";
    public static final String DUPLICATES = "duplicates";
    public static final String COUNTRY = "country";
    public static final String BUSINESS_TYPE = "businessType";
    public static final String EXPECTED_MONTHLY_ORDERS = "expectedMonthlyOrders";
    public static final String REVIEW_DUE_AT = "reviewDueAt";
    public static final String RISK_TIER = "riskTier";

    // Set by reviewers.
    public static final String DECISION = "decision";
    public static final String SECOND_DECISION = "secondDecision";
    public static final String FIRST_REVIEWER = "firstReviewer";

    public static final int ESCALATED_PRIORITY = 80;

    private Onboarding() {
    }
}
