package io.souqly.platform.formio;

import java.util.Map;

/**
 * One input of a form.
 *
 * @param properties    custom properties set in the form builder, e.g. {@code facet = "true"}
 * @param allowedValues for selects and radios with static options: value to display label, in
 *                      form order; empty when any value is accepted
 */
public record FormField(String key, String label, String type, Map<String, String> properties,
        Map<String, String> allowedValues) {

    public boolean restricted() {
        return !allowedValues.isEmpty();
    }

    public boolean hasProperty(String name, String value) {
        return value.equals(properties.get(name));
    }
}
