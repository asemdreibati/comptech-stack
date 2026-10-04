package io.souqly.returns.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param window         how long after confirmation an order can be returned
 * @param sellerSla      how long a seller has to decide; silence approves the return
 * @param disputeWindow  how long a buyer has to dispute a seller's rejection
 * @param shippingWindow how long the buyer has to send the parcel once a return is approved
 */
@ConfigurationProperties("souqly.returns")
public record ReturnsProperties(
        @DefaultValue("P14D") Duration window,
        @DefaultValue("P2D") Duration sellerSla,
        @DefaultValue("P7D") Duration disputeWindow,
        @DefaultValue("P14D") Duration shippingWindow,
        @DefaultValue Topics topics,
        Payments payments) {

    public record Topics(
            @DefaultValue("returns.return-requests.v1") String returns,
            @DefaultValue("orders.order-events.v1") String orders,
            @DefaultValue("6") int partitions,
            @DefaultValue("1") short replicas) {
    }

    public record Payments(URI baseUrl, String apiKey, @DefaultValue("PT3S") Duration timeout) {
    }
}
