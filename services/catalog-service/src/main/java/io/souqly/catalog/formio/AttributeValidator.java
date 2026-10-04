package io.souqly.catalog.formio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.souqly.platform.formio.FormField;
import io.souqly.platform.formio.FormValidator;

import org.springframework.stereotype.Component;

/**
 * Checks listing attributes against their category's Form.io form (see {@link FormValidator}) and
 * derives the listing's search facets: fields marked {@code properties.facet = "true"} in the form
 * builder, with each value's display label.
 */
@Component
public class AttributeValidator {

    private final FormValidator forms;

    public AttributeValidator(FormValidator forms) {
        this.forms = forms;
    }

    public record ValidatedAttributes(Map<String, Object> attributes, List<Facet> facets) {
    }

    public ValidatedAttributes validate(String formPath, Map<String, Object> submitted) {
        var validated = forms.validate(formPath, submitted);
        List<Facet> facets = new ArrayList<>();
        for (FormField field : validated.form().fields()) {
            Object value = validated.data().get(field.key());
            if (value == null || "".equals(value) || !field.hasProperty("facet", "true")) {
                continue;
            }
            String text = FormValidator.canonical(value);
            facets.add(new Facet(field.key(), field.label(), text, field.allowedValues().getOrDefault(text, text)));
        }
        return new ValidatedAttributes(validated.data(), List.copyOf(facets));
    }
}
