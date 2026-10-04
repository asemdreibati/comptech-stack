package io.souqly.platform.formio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates submissions against Form.io forms on the server.
 *
 * <p>Form.io's dry run does most of the work: required fields, numeric ranges, lengths, patterns,
 * and stripping fields the form does not define (so clients cannot smuggle in extra data). Two
 * gaps of the community edition are closed here:
 * <ul>
 *   <li>{@code onlyAvailableItems} is not enforced on the server, so a select accepts any value.
 *       Values of selects and radios with static options are checked against the form here.</li>
 *   <li>Form.io coerces numeric-looking option values to numbers ({@code "128"} becomes 128). They
 *       are mapped back to the option's canonical string, so stored data matches the form.</li>
 * </ul>
 */
public class FormValidator {

    private final FormioClient formio;

    public FormValidator(FormioClient formio) {
        this.formio = formio;
    }

    /** The cleaned submission and the form it was checked against. */
    public record ValidatedForm(FormDefinition form, Map<String, Object> data) {
    }

    /**
     * @throws FormValidationException listing every failing field
     * @throws FormNotFoundException   if there is no form at {@code formPath}
     * @throws FormioUnavailableException if Form.io cannot be reached
     */
    public ValidatedForm validate(String formPath, Map<String, Object> submitted) {
        FormDefinition form = formio.form(formPath);
        Map<String, Object> clean = new LinkedHashMap<>(formio.validate(formPath, submitted));

        List<FieldError> errors = new ArrayList<>();
        for (FormField field : form.fields()) {
            Object value = clean.get(field.key());
            if (value == null || "".equals(value) || !field.restricted()) {
                continue;
            }
            String text = canonical(value);
            if (!field.allowedValues().containsKey(text)) {
                errors.add(new FieldError(field.key(),
                        field.label() + " must be one of " + String.join(", ", field.allowedValues().keySet())));
                continue;
            }
            clean.put(field.key(), text);
        }
        if (!errors.isEmpty()) {
            throw new FormValidationException(errors);
        }
        return new ValidatedForm(form, clean);
    }

    /** 128 and 128.0 both mean the option "128". */
    public static String canonical(Object value) {
        if (value instanceof Double d && d == Math.rint(d) && !Double.isInfinite(d)) {
            return Long.toString(d.longValue());
        }
        return String.valueOf(value);
    }
}
