package io.souqly.catalog.formio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Checks listing attributes against their category's Form.io form.
 *
 * <p>Form.io's dry run does most of the work: required fields, numeric ranges, lengths, patterns,
 * and stripping fields the form does not define (so sellers cannot smuggle in extra data). Two
 * gaps are closed here:
 * <ul>
 *   <li>The community edition does not enforce {@code onlyAvailableItems} on the server, so a
 *       select accepts any value. Values of selects and radios with static options are checked
 *       against the form here.</li>
 *   <li>Form.io coerces numeric-looking option values to numbers ({@code "128"} becomes 128). They
 *       are mapped back to the option's canonical string, so stored data matches the form.</li>
 * </ul>
 */
@Component
public class AttributeValidator {

    private final FormioClient formio;

    public AttributeValidator(FormioClient formio) {
        this.formio = formio;
    }

    public record ValidatedAttributes(Map<String, Object> attributes, List<Facet> facets) {
    }

    public ValidatedAttributes validate(String formPath, Map<String, Object> submitted) {
        FormDefinition form = formio.form(formPath);
        Map<String, Object> clean = new LinkedHashMap<>(formio.validate(formPath, submitted));

        List<FieldError> errors = new ArrayList<>();
        List<Facet> facets = new ArrayList<>();
        for (FormField field : form.fields()) {
            Object value = clean.get(field.key());
            if (value == null || "".equals(value)) {
                continue;
            }
            String text = canonical(value);
            if (field.restricted()) {
                if (!field.allowedValues().containsKey(text)) {
                    errors.add(new FieldError(field.key(),
                            field.label() + " must be one of " + String.join(", ", field.allowedValues().keySet())));
                    continue;
                }
                clean.put(field.key(), text);
            }
            if (field.facet()) {
                facets.add(new Facet(field.key(), field.label(), text,
                        field.allowedValues().getOrDefault(text, text)));
            }
        }
        if (!errors.isEmpty()) {
            throw new AttributeValidationException(errors);
        }
        return new ValidatedAttributes(clean, List.copyOf(facets));
    }

    /** 128 and 128.0 both mean the option "128". */
    private static String canonical(Object value) {
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString(d.longValue());
        }
        return String.valueOf(value);
    }
}
