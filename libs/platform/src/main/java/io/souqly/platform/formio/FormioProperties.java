package io.souqly.platform.formio;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The Form.io server holding the platform's business forms (category schemas, seller KYC).
 *
 * @param schemaCacheTtl how long a form definition is reused before it is fetched again
 */
@ConfigurationProperties("souqly.formio")
public record FormioProperties(
        URI baseUrl,
        String email,
        String password,
        @DefaultValue("PT5M") Duration schemaCacheTtl,
        @DefaultValue("PT3S") Duration timeout) {
}
