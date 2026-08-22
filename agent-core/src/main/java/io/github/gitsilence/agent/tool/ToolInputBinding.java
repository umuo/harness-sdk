package io.github.gitsilence.agent.tool;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Binds a JSON object to a small mutable input POJO while preserving field defaults. */
final class ToolInputBinding<I> {

    private final Class<I> inputType;
    private final Constructor<I> constructor;
    private final List<ToolSchemas.Parameter> parameters;
    private final Set<String> parameterNames;
    private final String schema;

    private ToolInputBinding(Class<I> inputType,
                             Constructor<I> constructor,
                             List<ToolSchemas.Parameter> parameters,
                             String schema) {
        this.inputType = inputType;
        this.constructor = constructor;
        this.parameters = parameters;
        Set<String> names = new LinkedHashSet<String>();
        for (ToolSchemas.Parameter parameter : parameters) {
            names.add(parameter.getName());
        }
        this.parameterNames = names;
        this.schema = schema;
    }

    static <I> ToolInputBinding<I> create(Class<I> inputType) {
        if (inputType == null) {
            throw new NullPointerException("inputType");
        }
        if (inputType == Void.class || inputType == void.class) {
            return new ToolInputBinding<I>(
                inputType, null, java.util.Collections.<ToolSchemas.Parameter>emptyList(),
                ToolSchemas.forInputType(inputType)
            );
        }
        List<ToolSchemas.Parameter> parameters = ToolSchemas.parametersFor(inputType);
        Constructor<I> constructor;
        try {
            constructor = inputType.getDeclaredConstructor();
            if (!constructor.isAccessible()) {
                constructor.setAccessible(true);
            }
        } catch (Exception error) {
            throw new IllegalArgumentException(
                "Tool input type must have a no-argument constructor: "
                    + inputType.getName(),
                error
            );
        }
        for (ToolSchemas.Parameter parameter : parameters) {
            Field field = parameter.getField();
            if (!field.isAccessible()) {
                field.setAccessible(true);
            }
        }
        return new ToolInputBinding<I>(
            inputType, constructor, parameters, ToolSchemas.forParameters(parameters)
        );
    }

    String schema() {
        return schema;
    }

    I bind(ToolArguments arguments) {
        arguments.rejectUnknown(parameterNames);
        if (constructor == null) {
            return null;
        }
        final I input;
        try {
            input = constructor.newInstance();
        } catch (Exception error) {
            throw new IllegalArgumentException(
                "Cannot create tool input " + inputType.getName(), error
            );
        }
        for (ToolSchemas.Parameter parameter : parameters) {
            JsonNode value = arguments.node(parameter.getName());
            boolean absent = value == null || value.isNull();
            if (absent) {
                if (ToolSchemas.rawType(parameter.getType()) == Optional.class) {
                    set(input, parameter.getField(), Optional.empty());
                } else if (parameter.isRequired()) {
                    throw new IllegalArgumentException(
                        "Missing required argument: " + parameter.getName()
                    );
                }
                continue;
            }
            try {
                set(input, parameter.getField(), convert(value, parameter.getType()));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException(
                    "Invalid argument '" + parameter.getName() + "': "
                        + error.getMessage(),
                    error
                );
            }
        }
        return input;
    }

    static Object convert(JsonNode value, Type type) {
        if (ToolSchemas.rawType(type) == Optional.class) {
            Type wrapped = Object.class;
            if (type instanceof ParameterizedType) {
                wrapped = ((ParameterizedType) type).getActualTypeArguments()[0];
            }
            return Optional.ofNullable(convert(value, wrapped));
        }
        validateShape(value, type);
        try {
            JavaType javaType = JsonSupport.MAPPER.getTypeFactory().constructType(type);
            return JsonSupport.MAPPER.convertValue(value, javaType);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                "Cannot convert tool argument to " + type + ": "
                    + error.getMessage(),
                error
            );
        }
    }

    private static void validateShape(JsonNode value, Type type) {
        Class<?> raw = ToolSchemas.rawType(type);
        if (raw == Object.class || JsonNode.class.isAssignableFrom(raw)) {
            return;
        }
        if (raw == String.class || raw == Character.class || raw == char.class
                || raw == UUID.class || raw == URI.class || raw == URL.class
                || raw.isEnum()) {
            require(value.isTextual(), "a JSON string", type);
            return;
        }
        if (raw == boolean.class || raw == Boolean.class) {
            require(value.isBoolean(), "a JSON boolean", type);
            return;
        }
        if (raw == byte.class || raw == Byte.class
                || raw == short.class || raw == Short.class
                || raw == int.class || raw == Integer.class
                || raw == long.class || raw == Long.class
                || raw == BigInteger.class) {
            require(value.isIntegralNumber(), "a JSON integer", type);
            return;
        }
        if (raw == float.class || raw == Float.class
                || raw == double.class || raw == Double.class
                || raw == BigDecimal.class || Number.class == raw) {
            require(value.isNumber(), "a JSON number", type);
            return;
        }
        if (raw.isArray() || Collection.class.isAssignableFrom(raw)) {
            require(value.isArray(), "a JSON array", type);
            return;
        }
        if (Map.class.isAssignableFrom(raw)) {
            require(value.isObject(), "a JSON object", type);
            return;
        }
        require(value.isObject(), "a JSON object", type);
    }

    private static void require(boolean condition, String expected, Type type) {
        if (!condition) {
            throw new IllegalArgumentException(
                "Expected " + expected + " for " + type.getTypeName()
            );
        }
    }

    private static void set(Object target, Field field, Object value) {
        try {
            field.set(target, value);
        } catch (Exception error) {
            throw new IllegalArgumentException(
                "Cannot set tool input field " + field, error
            );
        }
    }
}
