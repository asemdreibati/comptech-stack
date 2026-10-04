package io.souqly.returns.orders;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Local copy of the orders that can be returned. */
@Repository
public class OrderReplica {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public OrderReplica(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Stores an order event if it is newer than what is held, so late, duplicate and replayed events
     * change nothing. The confirmation time is kept once known.
     *
     * @return whether the event was applied
     */
    public boolean apply(PurchasedOrder order) {
        return jdbc.sql("""
                insert into purchased_order (order_id, buyer_id, status, currency, payment_id, lines, confirmed_at,
                    version)
                values (:id, :buyer, :status, :currency, :payment, cast(:lines as jsonb), :confirmed, :version)
                on conflict (order_id) do update set
                    status = excluded.status,
                    payment_id = coalesce(excluded.payment_id, purchased_order.payment_id),
                    lines = excluded.lines,
                    confirmed_at = coalesce(purchased_order.confirmed_at, excluded.confirmed_at),
                    version = excluded.version
                where purchased_order.version < excluded.version""")
                .param("id", order.orderId())
                .param("buyer", order.buyerId())
                .param("status", order.status())
                .param("currency", order.currency())
                .param("payment", order.paymentId())
                .param("lines", json.writeValueAsString(order.lines()))
                .param("confirmed", time(order.confirmedAt()))
                .param("version", order.version())
                .update() == 1;
    }

    public Optional<PurchasedOrder> find(String orderId) {
        return query("select * from purchased_order where order_id = :id", orderId);
    }

    /**
     * Reads the order and locks its row until the transaction ends, so two return requests for the
     * same order are checked one after the other and cannot together return more than was bought.
     */
    public Optional<PurchasedOrder> lock(String orderId) {
        return query("select * from purchased_order where order_id = :id for update", orderId);
    }

    private Optional<PurchasedOrder> query(String sql, String orderId) {
        return jdbc.sql(sql).param("id", orderId).query(this::order).optional();
    }

    private PurchasedOrder order(ResultSet rs, int row) throws SQLException {
        OffsetDateTime confirmed = rs.getObject("confirmed_at", OffsetDateTime.class);
        return new PurchasedOrder(rs.getString("order_id"), rs.getString("buyer_id"), rs.getString("status"),
                rs.getString("currency"), rs.getString("payment_id"),
                json.readValue(rs.getString("lines"), new TypeReference<List<PurchasedOrder.Line>>() { }),
                confirmed != null ? confirmed.toInstant() : null, rs.getLong("version"));
    }

    private static OffsetDateTime time(Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
}
