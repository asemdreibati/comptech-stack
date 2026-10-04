package io.souqly.seller.application;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Applications, their documents and review decisions in PostgreSQL. Status changes are guarded by
 * the expected current status, so a step that lost a race changes nothing.
 */
@Repository
public class ApplicationStore {

    private static final String COLUMNS = """
            id, applicant_id, seller_handle, kyc::text as kyc, status, risk_tier, escalated, version,
            created_at, updated_at, submitted_at, decided_at""";

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ApplicationStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(SellerApplication application) {
        jdbc.sql("""
                insert into seller_application (id, applicant_id, seller_handle, kyc, status, version, created_at,
                    updated_at)
                values (:id, :applicant, :handle, cast(:kyc as jsonb), :status, :version, :created, :updated)""")
                .param("id", application.id())
                .param("applicant", application.applicantId())
                .param("handle", application.sellerHandle())
                .param("kyc", json.writeValueAsString(application.kyc()))
                .param("status", application.status().name())
                .param("version", application.version())
                .param("created", time(application.createdAt()))
                .param("updated", time(application.updatedAt()))
                .update();
    }

    public Optional<SellerApplication> find(UUID id) {
        return jdbc.sql("select " + COLUMNS + " from seller_application where id = :id")
                .param("id", id)
                .query(this::application)
                .optional();
    }

    public List<SellerApplication> forApplicant(String applicantId) {
        return jdbc.sql("select " + COLUMNS + " from seller_application where applicant_id = :applicant "
                        + "order by created_at desc")
                .param("applicant", applicantId)
                .query(this::application)
                .list();
    }

    /** Replaces handle and KYC details if the application is still editable and unchanged since read. */
    public Optional<SellerApplication> updateDetails(UUID id, long expectedVersion, String handle,
            Map<String, Object> kyc, Instant now) {
        return jdbc.sql("update seller_application set seller_handle = :handle, kyc = cast(:kyc as jsonb), "
                        + "version = version + 1, updated_at = :now "
                        + "where id = :id and version = :version and status in ('DRAFT', 'INFORMATION_REQUESTED') "
                        + "returning " + COLUMNS)
                .param("id", id)
                .param("version", expectedVersion)
                .param("handle", handle)
                .param("kyc", json.writeValueAsString(kyc))
                .param("now", time(now))
                .query(this::application)
                .optional();
    }

    /**
     * Moves the application to {@code to} if it is in one of {@code from}.
     *
     * @return whether it moved
     */
    public boolean transition(UUID id, Collection<ApplicationStatus> from, ApplicationStatus to, Instant now) {
        return jdbc.sql("update seller_application set status = :to, version = version + 1, updated_at = :now, "
                        + "submitted_at = case when :to = 'SUBMITTED' then :now else submitted_at end, "
                        + "decided_at = case when :to in ('APPROVED', 'REJECTED', 'EXPIRED') then :now "
                        + "else decided_at end "
                        + "where id = :id and status in (:from)")
                .param("id", id)
                .param("to", to.name())
                .param("from", from.stream().map(Enum::name).toList())
                .param("now", time(now))
                .update() == 1;
    }

    public void recordRisk(UUID id, RiskTier tier, Instant now) {
        jdbc.sql("update seller_application set risk_tier = :tier, updated_at = :now where id = :id")
                .param("id", id).param("tier", tier.name()).param("now", time(now)).update();
    }

    public void markEscalated(UUID id, Instant now) {
        jdbc.sql("update seller_application set escalated = true, updated_at = :now where id = :id")
                .param("id", id).param("now", time(now)).update();
    }

    /**
     * Other people's submitted applications, in any later state, that share this bank account or
     * trade licence: one business behind several seller accounts, or a stolen identity. Drafts do
     * not count, so a half-filled form cannot flag someone else.
     */
    public List<UUID> sharingIdentifiers(SellerApplication application) {
        return jdbc.sql("""
                select id from seller_application
                where applicant_id <> :applicant
                  and status <> 'DRAFT'
                  and (kyc ->> 'iban' = :iban or kyc ->> 'tradeLicenceNumber' = :licence)
                order by created_at""")
                .param("applicant", application.applicantId())
                .param("iban", application.kycText("iban"))
                .param("licence", application.kycText("tradeLicenceNumber"))
                .query(UUID.class)
                .list();
    }

    // --- documents -------------------------------------------------------------------------------

    public void insertDocument(ApplicationDocument document) {
        jdbc.sql("""
                insert into application_document (id, application_id, type, content_type, size_bytes, status,
                    created_at)
                values (:id, :application, :type, :contentType, :size, :status, :created)""")
                .param("id", document.id())
                .param("application", document.applicationId())
                .param("type", document.type().name())
                .param("contentType", document.contentType())
                .param("size", document.sizeBytes())
                .param("status", document.status().name())
                .param("created", time(document.createdAt()))
                .update();
    }

    public List<ApplicationDocument> documents(UUID applicationId) {
        return jdbc.sql("select * from application_document where application_id = :application order by created_at")
                .param("application", applicationId)
                .query(ApplicationStore::document)
                .list();
    }

    public Optional<ApplicationDocument> document(UUID applicationId, UUID documentId) {
        return jdbc.sql("select * from application_document where id = :id and application_id = :application")
                .param("id", documentId)
                .param("application", applicationId)
                .query(ApplicationStore::document)
                .optional();
    }

    /** Settles a pending document; returns false if it was already settled. */
    public boolean settleDocument(UUID documentId, ApplicationDocument.Status outcome, Instant now) {
        return jdbc.sql("update application_document set status = :status, "
                        + "verified_at = case when :status = 'VERIFIED' then :now else null end "
                        + "where id = :id and status = 'PENDING'")
                .param("id", documentId)
                .param("status", outcome.name())
                .param("now", time(now))
                .update() == 1;
    }

    // --- decisions -------------------------------------------------------------------------------

    public void insertDecision(ReviewDecision decision) {
        jdbc.sql("""
                insert into review_decision (application_id, stage, reviewer_id, decision, note, decided_at)
                values (:application, :stage, :reviewer, :decision, :note, :at)""")
                .param("application", decision.applicationId())
                .param("stage", decision.stage())
                .param("reviewer", decision.reviewerId())
                .param("decision", decision.decision())
                .param("note", decision.note())
                .param("at", time(decision.decidedAt()))
                .update();
    }

    public List<ReviewDecision> decisions(UUID applicationId) {
        return jdbc.sql("select * from review_decision where application_id = :application order by id")
                .param("application", applicationId)
                .query((rs, row) -> new ReviewDecision(rs.getObject("application_id", UUID.class),
                        rs.getString("stage"), rs.getString("reviewer_id"), rs.getString("decision"),
                        rs.getString("note"), instant(rs, "decided_at")))
                .list();
    }

    // --- mapping ---------------------------------------------------------------------------------

    private SellerApplication application(ResultSet rs, int row) throws SQLException {
        String risk = rs.getString("risk_tier");
        return new SellerApplication(
                rs.getObject("id", UUID.class),
                rs.getString("applicant_id"),
                rs.getString("seller_handle"),
                json.readValue(rs.getString("kyc"), new TypeReference<Map<String, Object>>() { }),
                ApplicationStatus.valueOf(rs.getString("status")),
                risk != null ? RiskTier.valueOf(risk) : null,
                rs.getBoolean("escalated"),
                rs.getLong("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "submitted_at"),
                instant(rs, "decided_at"));
    }

    private static ApplicationDocument document(ResultSet rs, int row) throws SQLException {
        return new ApplicationDocument(
                rs.getObject("id", UUID.class),
                rs.getObject("application_id", UUID.class),
                DocumentType.valueOf(rs.getString("type")),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                ApplicationDocument.Status.valueOf(rs.getString("status")),
                instant(rs, "created_at"),
                instant(rs, "verified_at"));
    }

    private static OffsetDateTime time(Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }
}
