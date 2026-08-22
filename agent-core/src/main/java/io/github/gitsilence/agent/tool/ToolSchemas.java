package io.github.gitsilence.agent.tool;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gitsilence.agent.tool.annotation.ToolParam;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Shared JSON Schema generation for typed and annotation-based tools. */
final class ToolSchemas {

    private ToolSchemas() {
    }

    static String forInputType(Class<?> inputType) {
        if (inputType == Void.class || inputType == void.class) {
            return objectSchema(Collections.<Parameter>emptyList()).toString();
        }
        requireObjectInput(inputType);
        return objectSchema(parametersFor(inputType)).toString();
    }

    static String forParameters(List<Parameter> parameters) {
        return objectSchema(parameters).toString();
    }

    static List<Parameter> parametersFor(Class<?> inputType) {
        List<Parameter> parameters = new ArrayList<Parameter>();
        Set<String> names = new LinkedHashSet<String>();
        for (Field field : fieldsOf(inputType)) {
            ToolParam annotation = field.getAnnotation(ToolParam.class);
            String name = exposedName(field.getName(), annotation);
            if (!names.add(name)) {
                throw new IllegalArgumentException(
                    "Duplicate tool input field name '" + name + "' in "
                        + inputType.getName()
                );
            }
            parameters.add(new Parameter(
                name,
                description(annotation),
                required(field.getGenericType(), annotation),
                field.getGenericType(),
                field
            ));
        }
        return Collections.unmodifiableList(parameters);
    }

    static String exposedName(String fallback, ToolParam annotation) {
        if (annotation == null || annotation.name().trim().isEmpty()) {
            return fallback;
        }
        return annotation.name().trim();
    }

    static boolean required(Type type, ToolParam annotation) {
        if (rawType(type) == Optional.class) {
            return false;
        }
        return annotation == null || annotation.required();
    }

    static String description(ToolParam annotation) {
        if (annotation == null) {
            return "";
        }
        String value = annotation.value().trim();
        String description = annotation.description().trim();
        if (!value.isEmpty() && !description.isEmpty()) {
            throw new IllegalArgumentException(
                "@ToolParam value and description are aliases; use only one"
            );
        }
        return description.isEmpty() ? value : description;
    }

    static List<Field> fieldsOf(Class<?> type) {
        List<Class<?>> hierarchy = new ArrayList<Class<?>>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            hierarchy.add(current);
            current = current.getSuperclass();
        }
        Collections.reverse(hierarchy);
        List<Field> fields = new ArrayList<Field>();
        Set<String> javaNames = new LinkedHashSet<String>();
        for (Class<?> owner : hierarchy) {
            for (Field field : owner.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers)
                        || Modifier.isTransient(modifiers)
                        || field.isSynthetic()) {
                    continue;
                }
                if (Modifier.isFinal(modifiers)) {
                    throw new IllegalArgumentException(
                        "Tool input fields must not be final: " + field
                    );
                }
                if (!javaNames.add(field.getName())) {
                    throw new IllegalArgumentException(
                        "Tool input field shadows an inherited field: " + field
                    );
                }
                fields.add(field);
            }
        }
        return fields;
    }

    private static ObjectNode objectSchema(List<Parameter> parameters) {
        ObjectNode root = JsonSupport.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        ArrayNode required = root.putArray("required");
        for (Parameter parameter : parameters) {
            ObjectNode property = schemaFor(
                parameter.type, new LinkedHashMap<Type, String>()
            );
            if (!parameter.description.isEmpty()) {
                property.put("description", parameter.description);
            }
            properties.set(parameter.name, property);
            if (parameter.required) {
                required.add(parameter.name);
            }
        }
        root.put("additionalProperties", false);
        return root;
    }

    private static ObjectNode schemaFor(Type type, Map<Type, String> path) {
        Type effective = unwrap(type);
        Class<?> raw = rawType(effective);
        ObjectNode schema = JsonSupport.MAPPER.createObjectNode();
        if (raw == String.class || raw == Character.class || raw == char.class
                || raw == UUID.class || raw == URI.class || raw == URL.class) {
            schema.put("type", "string");
        } else if (raw == boolean.class || raw == Boolean.class) {
            schema.put("type", "boolean");
        } else if (raw == byte.class || raw == Byte.class
                || raw == short.class || raw == Short.class
                || raw == int.class || raw == Integer.class
                || raw == long.class || raw == Long.class
                || raw == BigInteger.class) {
            schema.put("type", "integer");
        } else if (raw == float.class || raw == Float.class
                || raw == double.class || raw == Double.class
                || raw == BigDecimal.class || Number.class == raw) {
            schema.put("type", "number");
        } else if (raw.isEnum()) {
            schema.put("type", "string");
            ArrayNode values = schema.putArray("enum");
            for (Object constant : raw.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
        } else if (raw.isArray() || effective instanceof GenericArrayType) {
            schema.put("type", "array");
            Type itemType = raw.isArray()
                ? raw.getComponentType()
                : ((GenericArrayType) effective).getGenericComponentType();
            schema.set("items", schemaFor(itemType, path));
        } else if (Collection.class.isAssignableFrom(raw)) {
            schema.put("type", "array");
            schema.set("items", schemaFor(typeArgument(effective, 0), path));
        } else if (Map.class.isAssignableFrom(raw)) {
            schema.put("type", "object");
            schema.set("additionalProperties", schemaFor(typeArgument(effective, 1), path));
        } else if (raw == Object.class) {
            schema.put("type", "object");
        } else {
            schema.put("type", "object");
            if (path.containsKey(effective)) {
                return schema;
            }
            path.put(effective, raw.getName());
            ObjectNode properties = schema.putObject("properties");
            ArrayNode required = schema.putArray("required");
            Set<String> names = new LinkedHashSet<String>();
            for (Field field : fieldsOf(raw)) {
                ToolParam annotation = field.getAnnotation(ToolParam.class);
                String name = exposedName(field.getName(), annotation);
                if (!names.add(name)) {
                    throw new IllegalArgumentException(
                        "Duplicate tool input field name '" + name + "' in "
                            + raw.getName()
                    );
                }
                ObjectNode property = schemaFor(field.getGenericType(), path);
                String fieldDescription = description(annotation);
                if (!fieldDescription.isEmpty()) {
                    property.put("description", fieldDescription);
                }
                properties.set(name, property);
                if (required(field.getGenericType(), annotation)) {
                    required.add(name);
                }
            }
            schema.put("additionalProperties", false);
            path.remove(effective);
        }
        return schema;
    }

    private static Type unwrap(Type type) {
        return rawType(type) == Optional.class ? typeArgument(type, 0) : type;
    }

    private static Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType) {
            Type[] arguments = ((ParameterizedType) type).getActualTypeArguments();
            if (index < arguments.length) {
                Type argument = arguments[index];
                if (argument instanceof WildcardType) {
                    Type[] upper = ((WildcardType) argument).getUpperBounds();
                    return upper.length == 0 ? Object.class : upper[0];
                }
                return argument;
            }
        }
        return Object.class;
    }

    static Class<?> rawType(Type type) {
        if (type instanceof Class) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        }
        if (type instanceof GenericArrayType) {
            return Object[].class;
        }
        return Object.class;
    }

    private static void requireObjectInput(Class<?> inputType) {
        if (inputType.isPrimitive() || inputType.isArray() || inputType.isEnum()
                || inputType == String.class
                || Number.class.isAssignableFrom(inputType)
                || Collection.class.isAssignableFrom(inputType)
                || Map.class.isAssignableFrom(inputType)) {
            throw new IllegalArgumentException(
                "Typed tool input must be a POJO or Void: " + inputType.getName()
            );
        }
    }

    static final class Parameter {
        private final String name;
        private final String description;
        private final boolean required;
        private final Type type;
        private final Field field;

        Parameter(String name,
                  String description,
                  boolean required,
                  Type type,
                  Field field) {
            this.name = name;
            this.description = description == null ? "" : description;
            this.required = required;
            this.type = type;
            this.field = field;
        }

        String getName() {
            return name;
        }

        boolean isRequired() {
            return required;
        }

        Type getType() {
            return type;
        }

        Field getField() {
            return field;
        }
    }
}
