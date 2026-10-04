package io.souqly.seller.events;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.souqly.seller.config.OnboardingProperties;

import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 of normalised identifiers. A plain hash of an IBAN or phone number could be reversed
 * by hashing every plausible value; with a secret key it cannot, yet equal inputs still give equal
 * hashes, which is all a consumer looking for shared identifiers needs.
 */
@Component
public class IdentifierHasher {

    private final SecretKeySpec key;

    public IdentifierHasher(OnboardingProperties properties) {
        if (properties.identifierKey() == null || properties.identifierKey().length() < 16) {
            throw new IllegalStateException("souqly.onboarding.identifier-key must be at least 16 characters");
        }
        this.key = new SecretKeySpec(properties.identifierKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public String hash(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalised = value.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(normalised.getBytes(StandardCharsets.UTF_8)));
        }
        catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
