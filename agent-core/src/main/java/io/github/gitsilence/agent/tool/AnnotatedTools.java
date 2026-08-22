package io.github.gitsilence.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.gitsilence.agent.tool.annotation.ToolParam;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class AnnotatedTools {

    private AnnotatedTools() {
    }

    public static List<Tool> from(Object target) {
        Objects.requireNonNull(target, "target");
        List<Method> methods = new ArrayList<Method>();
        for (Method method : target.getClass().getMethods()) {
            if (method.isAnnotationPresent(
                    io.github.gitsilence.agent.tool.annotation.Tool.class)) {
                methods.add(method);
            }
        }
        Collections.sort(methods, Comparator.comparing(Method::getName));

        List<Tool> tools = new ArrayList<Tool>();
        for (Method method : methods) {
            tools.add(new ReflectiveTool(target, method));
        }
        return Collections.unmodifiableList(tools);
    }

    private static final class ReflectiveTool implements Tool {
        private final Object target;
        private final Method method;
        private final ToolDefinition definition;
        private final List<ParameterBinding> bindings;

        private ReflectiveTool(Object target, Method method) {
            if (!Modifier.isPublic(method.getModifiers())) {
                throw new IllegalArgumentException("Annotated tool method must be public: " + method);
            }
            this.target = target;
            this.method = method;
            io.github.gitsilence.agent.tool.annotation.Tool annotation =
                method.getAnnotation(io.github.gitsilence.agent.tool.annotation.Tool.class);
            String name = annotation.name().trim().isEmpty()
                ? method.getName()
                : annotation.name();
            this.bindings = bindings(method);
            this.definition = ToolDefinition.builder()
                .name(name)
                .description(annotation.description())
                .inputSchema(schema(bindings))
                .build();
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public CompletableFuture<ToolResult> execute(final ToolArguments arguments,
                                                     final ToolContext context) {
            CompletableFuture<Object> invoked = CompletableFuture.supplyAsync(() -> {
                Object[] values = new Object[bindings.size()];
                for (int i = 0; i < bindings.size(); i++) {
                    ParameterBinding binding = bindings.get(i);
                    JsonNode value = arguments.node(binding.name);
                    if (value == null || value.isNull()) {
                        if (binding.required || binding.type.isPrimitive()) {
                            throw new IllegalArgumentException(
                                "Missing required argument: " + binding.name
                            );
                        }
                        values[i] = null;
                    } else {
                        values[i] = JsonSupport.MAPPER.convertValue(value, binding.type);
                    }
                }
                try {
                    return method.invoke(target, values);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException) {
                        throw (RuntimeException) cause;
                    }
                    throw new ToolExecutionException(definition.getName(), cause);
                } catch (Exception e) {
                    throw new ToolExecutionException(definition.getName(), e);
                }
            }, context.getExecutor());

            return invoked.thenCompose(value -> {
                if (value instanceof CompletableFuture) {
                    @SuppressWarnings("unchecked")
                    CompletableFuture<Object> async = (CompletableFuture<Object>) value;
                    return async.thenApply(AnnotatedTools::toResult);
                }
                return CompletableFuture.completedFuture(toResult(value));
            });
        }
    }

    private static ToolResult toResult(Object value) {
        if (value == null) {
            return ToolResult.success("Success");
        }
        if (value instanceof ToolResult) {
            return (ToolResult) value;
        }
        if (value instanceof String) {
            return ToolResult.success((String) value);
        }
        try {
            return ToolResult.success(JsonSupport.MAPPER.writeValueAsString(value));
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot serialize tool result", e);
        }
    }

    private static List<ParameterBinding> bindings(Method method) {
        Parameter[] parameters = method.getParameters();
        List<ParameterBinding> result = new ArrayList<ParameterBinding>();
        Set<String> names = new LinkedHashSet<String>();
        for (Parameter parameter : parameters) {
            ToolParam annotation = parameter.getAnnotation(ToolParam.class);
            String name;
            String description;
            boolean required;
            if (annotation != null) {
                name = annotation.name().trim().isEmpty()
                    ? parameter.getName()
                    : annotation.name();
                description = annotation.description();
                required = annotation.required();
            } else {
                if (!parameter.isNamePresent()) {
                    throw new IllegalArgumentException(
                        "Tool parameter name is unavailable; add @ToolParam(name=...) to "
                            + method
                    );
                }
                name = parameter.getName();
                description = "";
                required = true;
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("Duplicate tool parameter name: " + name);
            }
            result.add(new ParameterBinding(name, description, required, parameter.getType()));
        }
        return result;
    }

    private static String schema(List<ParameterBinding> bindings) {
        ObjectNode root = JsonSupport.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode properties = root.putObject("properties");
        ArrayNode required = root.putArray("required");
        for (ParameterBinding binding : bindings) {
            ObjectNode property = schemaFor(binding.type, new LinkedHashSet<Class<?>>());
            if (!binding.description.isEmpty()) {
                property.put("description", binding.description);
            }
            properties.set(binding.name, property);
            if (binding.required) {
                required.add(binding.name);
            }
        }
        return root.toString();
    }

    private static ObjectNode schemaFor(Class<?> type, Set<Class<?>> path) {
        ObjectNode schema = JsonSupport.MAPPER.createObjectNode();
        if (type == String.class || type == Character.class || type == char.class) {
            schema.put("type", "string");
        } else if (type == boolean.class || type == Boolean.class) {
            schema.put("type", "boolean");
        } else if (type == byte.class || type == Byte.class
            || type == short.class || type == Short.class
            || type == int.class || type == Integer.class
            || type == long.class || type == Long.class) {
            schema.put("type", "integer");
        } else if (Number.class.isAssignableFrom(type)
            || type == float.class || type == double.class) {
            schema.put("type", "number");
        } else if (type.isEnum()) {
            schema.put("type", "string");
            ArrayNode values = schema.putArray("enum");
            for (Object constant : type.getEnumConstants()) {
                values.add(((Enum<?>) constant).name());
            }
        } else if (type.isArray() || Collection.class.isAssignableFrom(type)) {
            schema.put("type", "array");
            schema.set("items", type.isArray()
                ? schemaFor(type.getComponentType(), path)
                : JsonSupport.MAPPER.createObjectNode().put("type", "object"));
        } else {
            schema.put("type", "object");
            if (!path.add(type)) {
                return schema;
            }
            ObjectNode properties = schema.putObject("properties");
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    properties.set(field.getName(), schemaFor(field.getType(), path));
                }
            }
            path.remove(type);
        }
        return schema;
    }

    private static final class ParameterBinding {
        private final String name;
        private final String description;
        private final boolean required;
        private final Class<?> type;

        private ParameterBinding(String name,
                                 String description,
                                 boolean required,
                                 Class<?> type) {
            this.name = name;
            this.description = description;
            this.required = required;
            this.type = type;
        }
    }
}
