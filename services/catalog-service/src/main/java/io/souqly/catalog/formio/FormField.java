package io.souqly.catalog.formio;

import java.util.Map;

/**
 * One input of a category form.
 *
 * @param allowedValues for selects and radios with static options: value to display label,
 *                      in form order; empty when any value is accepted
 * @param facet         marked in the form builder ({@code properties.facet = "true"}) as a search filter
 */
public record FormField(String key, String label, String type, boolean facet, Map<String, String> allowedValues) {

    public boolean restricted() {
        return !allowedValues.isEmpty();
    }
}
