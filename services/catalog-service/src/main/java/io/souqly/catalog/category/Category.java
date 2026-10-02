package io.souqly.catalog.category;

import java.time.Instant;

import io.souqly.catalog.i18n.LocalizedText;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A product category. Its listing attributes are defined by the Form.io form at {@code formPath},
 * so adding an attribute is a form change, not a code change.
 */
@Document("categories")
public record Category(@Id String slug, LocalizedText name, String formPath, Instant createdAt, Instant updatedAt) {
}
