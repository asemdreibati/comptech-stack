package io.souqly.platform.formio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/** A Form.io form: the raw definition (for browser renderers) and its input fields. */
public record FormDefinition(String path, JsonNode raw, List<FormField> fields) {

    static FormDefinition parse(String path, JsonNode form) {
        List<FormField> fields = new ArrayList<>();
        collect(form.path("components"), fields);
        return new FormDefinition(path, form, List.copyOf(fields));
    }

    /** Inputs can be nested in layout components (panels, columns, tabs), so walk the whole tree. */
    private static void collect(JsonNode components, List<FormField> fields) {
        for (JsonNode component : components) {
            if (component.path("input").asBoolean(false) && component.hasNonNull("key")) {
                fields.add(new FormField(component.get("key").asString(), component.path("label").asString(""),
                        component.path("type").asString(""), properties(component), allowedValues(component)));
            }
            collect(component.path("components"), fields);
            for (JsonNode column : component.path("columns")) {
                collect(column.path("components"), fields);
            }
        }
    }

    private static Map<String, String> properties(JsonNode component) {
        Map<String, String> properties = new LinkedHashMap<>();
        component.path("properties").properties()
                .forEach(property -> properties.put(property.getKey(), property.getValue().asString("")));
        return Map.copyOf(properties);
    }

    private static Map<String, String> allowedValues(JsonNode component) {
        JsonNode options = switch (component.path("type").asString("")) {
            case "select" -> "values".equals(component.path("dataSrc").asString("values"))
                    ? component.path("data").path("values") : null;
            case "radio" -> component.path("values");
            default -> null;
        };
        Map<String, String> allowed = new LinkedHashMap<>();
        if (options != null) {
            for (JsonNode option : options) {
                allowed.put(option.path("value").asString(), option.path("label").asString());
            }
        }
        return allowed;
    }
}
