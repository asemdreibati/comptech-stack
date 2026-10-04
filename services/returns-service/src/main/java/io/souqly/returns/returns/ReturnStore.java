package io.souqly.returns.returns;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Return requests and their decisions. Status changes are guarded by the expected current status. */
@Repository
public class ReturnStore {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public ReturnStore(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insert(ReturnRequest r) {
        jdbc.sql("""
                insert into return_request (id, order_id, buyer_id, seller_id, idempotency_key, request_hash, reason,
                    comment, lines, refund_amount, currency, status, version, created_at, updated_at)
                values (:id, :order, :buyer, :seller, :key, :hash, :reason, :comment, cast(:lines as jsonb), :amount,
                    :currency, :status, :version, :created, :updated)""")
                .param("id", r.id())
                .param("order", r.orderId())
                .param("buyer", r.buyerId())
                .param("seller", r.sellerId())
                .param("key", r.idempotencyKey())
                .param("hash", r.requestHash())
                .param("reason", r.reason().name())
                .param("comment", r.comment())
                .param("lines", json.writeValueAsString(r.lines()))
                .param("amount", r.refundAmount())
                .param("currency", r.currency())
                .param("status", r.status().name())
                .param("version", r.version())
                .param("created", time(r.createdAt()))
                .param("updated", time(r.updatedAt()))
                .update();
    }

    public Optional<ReturnRequest> find(UUID id) {
        return jdbc.sql("select * from return_request where id = :id").param("id", id).query(this::request).optional();
    }

    public Optional<ReturnRequest> findByKey(String buyerId, String idempotencyKey) {
        return jdbc.sql("select * from return_request where buyer_id = :buyer and idempotency_key = :key")
                .param("buyer", buyerId).param("key", idempotencyKey)
                .query(this::request).optional();
    }

    public List<ReturnRequest> forBuyer(String buyerId) {
        return jdbc.sql("select * from return_request where buyer_id = :buyer order by created_at desc limit 50")
                .param("buyer", buyerId).query(this::request).list();
    }

    /** Quantities per SKU of this order already in returns that are still open or completed. */
    public Map<String, Integer> quantitiesInReturns(String orderId) {
        Map<String, Integer> quantities = new HashMap<>();
        jdbc.sql("select lines::text as lines from return_request where order_id = :order and status not in (:closed)")
                .param("order", orderId)
                .param("closed", ReturnStatus.CLOSED_WITHOUT_RETURN.stream().map(Enum::name).toList())
                .query((rs, row) -> json.readValue(rs.getString("lines"),
                        new TypeReference<List<ReturnRequest.Line>>() { }))
                .list()
                .forEach(lines -> lines.forEach(line -> quantities.merge(line.sku(), line.quantity(), Integer::sum)));
        return quantities;
    }

    /** @return whether the return moved; false if it was not in one of {@code from} */
    public boolean transition(UUID id, Collection<ReturnStatus> from, ReturnStatus to, Instant now) {
        return jdbc.sql("update return_request set status = :to, version = version + 1, updated_at = :now, "
                        + "decided_at = case when :to in ('REFUNDED', 'REJECTED', 'CANCELLED') then :now "
                        + "else decided_at end "
                        + "where id = :id and status in (:from)")
                .param("id", id)
                .param("to", to.name())
                .param("from", from.stream().map(Enum::name).toList())
                .param("now", time(now))
                .update() == 1;
    }

    public void recordRefund(UUID id, String refundId, Instant now) {
        jdbc.sql("update return_request set refund_id = :refund, updated_at = :now where id = :id")
                .param("id", id).param("refund", refundId).param("now", time(now)).update();
    }

    public void insertDecision(ReturnDecision decision) {
        jdbc.sql("""
                insert into return_decision (return_id, stage, actor_id, decision, note, decided_at)
                values (:return, :stage, :actor, :decision, :note, :at)""")
                .param("return", decision.returnId())
                .param("stage", decision.stage())
                .param("actor", decision.actorId())
                .param("decision", decision.decision())
                .param("note", decision.note())
                .param("at", time(decision.decidedAt()))
                .update();
    }

    public List<ReturnDecision> decisions(UUID returnId) {
        return jdbc.sql("select * from return_decision where return_id = :return order by id")
                .param("return", returnId)
                .query((rs, row) -> new ReturnDecision(rs.getObject("return_id", UUID.class), rs.getString("stage"),
                        rs.getString("actor_id"), rs.getString("decision"), rs.getString("note"),
                        instant(rs, "decided_at")))
                .list();
    }

    private ReturnRequest request(ResultSet rs, int row) throws SQLException {
        return new ReturnRequest(
                rs.getObject("id", UUID.class),
                rs.getString("order_id"),
                rs.getString("buyer_id"),
                rs.getString("seller_id"),
                rs.getString("idempotency_key"),
                rs.getString("request_hash"),
                ReturnReason.valueOf(rs.getString("reason")),
                rs.getString("comment"),
                json.readValue(rs.getString("lines"), new TypeReference<List<ReturnRequest.Line>>() { }),
                money(rs.getBigDecimal("refund_amount"), rs.getString("currency")),
                rs.getString("currency"),
                ReturnStatus.valueOf(rs.getString("status")),
                rs.getString("refund_id"),
                rs.getLong("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "decided_at"));
    }

    /** Stored with four decimals; shown with the currency's own (2499.00 AED, 12.500 KWD). */
    private static BigDecimal money(BigDecimal amount, String currency) {
        return amount.setScale(Currency.getInstance(currency).getDefaultFractionDigits(), RoundingMode.UNNECESSARY);
    }

    private static OffsetDateTime time(Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }
}
