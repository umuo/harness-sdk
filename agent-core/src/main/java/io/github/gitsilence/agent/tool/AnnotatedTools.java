package io.github.gitsilence.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.gitsilence.agent.tool.annotation.ToolParam;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

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
        Set<String> names = new LinkedHashSet<String>();
        for (Method method : methods) {
            Tool tool = new ReflectiveTool(target, method);
            if (!names.add(tool.definition().getName())) {
                throw new IllegalArgumentException(
                    "Duplicate annotated tool name: " + tool.definition().getName()
                );
            }
            tools.add(tool);
        }
        return Collections.unmodifiableList(tools);
    }

    private static final class ReflectiveTool implements Tool {
        private final Object target;
        private final Method method;
        private final ToolDefinition definition;
        private final List<ParameterBinding> bindings;
        private final Set<String> parameterNames;
        private final boolean supportsParallelToolCalls;

        private ReflectiveTool(Object target, Method method) {
            if (!Modifier.isPublic(method.getModifiers())) {
                throw new IllegalArgumentException("Annotated tool method must be public: " + method);
            }
            try {
                if (!method.isAccessible()) {
                    method.setAccessible(true);
                }
            } catch (SecurityException error) {
                throw new IllegalArgumentException(
                    "Annotated tool method is not accessible: " + method,
                    error
                );
            }
            this.target = target;
            this.method = method;
            io.github.gitsilence.agent.tool.annotation.Tool annotation =
                method.getAnnotation(io.github.gitsilence.agent.tool.annotation.Tool.class);
            String name = annotation.name().trim().isEmpty()
                ? method.getName()
                : annotation.name().trim();
            this.bindings = bindings(method);
            this.parameterNames = parameterNames(bindings);
            this.supportsParallelToolCalls = annotation.parallel();
            this.definition = ToolDefinition.builder()
                .name(name)
                .description(toolDescription(annotation, method))
                .inputSchema(schema(bindings))
                .build();
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public boolean supportsParallelToolCalls() {
            return supportsParallelToolCalls;
        }

        @Override
        public CompletableFuture<ToolResult> execute(final ToolArguments arguments,
                                                     final ToolContext context) {
            CompletableFuture<Object> invoked = CompletableFuture.supplyAsync(() -> {
                arguments.rejectUnknown(parameterNames);
                Object[] values = new Object[bindings.size()];
                for (int i = 0; i < bindings.size(); i++) {
                    ParameterBinding binding = bindings.get(i);
                    if (binding.context) {
                        values[i] = context;
                        continue;
                    }
                    if (binding.arguments) {
                        values[i] = arguments;
                        continue;
                    }
                    JsonNode value = arguments.node(binding.name);
                    if (value == null || value.isNull()) {
                        if (ToolSchemas.rawType(binding.type) == java.util.Optional.class) {
                            values[i] = java.util.Optional.empty();
                        } else if (binding.required
                                || ToolSchemas.rawType(binding.type).isPrimitive()) {
                            throw new IllegalArgumentException(
                                "Missing required argument: " + binding.name
                            );
                        } else {
                            values[i] = null;
                        }
                    } else {
                        try {
                            values[i] = ToolInputBinding.convert(value, binding.type);
                        } catch (IllegalArgumentException error) {
                            throw new IllegalArgumentException(
                                "Invalid argument '" + binding.name + "': "
                                    + error.getMessage(),
                                error
                            );
                        }
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

            return adapt(invoked);
        }
    }

    private static String toolDescription(
            io.github.gitsilence.agent.tool.annotation.Tool annotation,
            Method method) {
        String value = annotation.value().trim();
        String description = annotation.description().trim();
        if (!value.isEmpty() && !description.isEmpty()) {
            throw new IllegalArgumentException(
                "@Tool value and description are aliases; use only one: " + method
            );
        }
        String effective = description.isEmpty() ? value : description;
        if (effective.isEmpty()) {
            throw new IllegalArgumentException(
                "Annotated tool must have a description: " + method
            );
        }
        return effective;
    }

    private static CompletableFuture<ToolResult> adapt(
            CompletableFuture<Object> invoked) {
        CompletableFuture<ToolResult> result =
            new CompletableFuture<ToolResult>();
        AtomicReference<CompletableFuture<?>> active =
            new AtomicReference<CompletableFuture<?>>(invoked);
        invoked.whenComplete((value, invocationError) -> {
            if (invocationError != null) {
                result.completeExceptionally(invocationError);
                return;
            }
            if (value instanceof CompletionStage) {
                @SuppressWarnings("unchecked")
                CompletionStage<Object> stage = (CompletionStage<Object>) value;
                CompletableFuture<Object> async = stage.toCompletableFuture();
                active.set(async);
                if (result.isCancelled()) {
                    async.cancel(true);
                    return;
                }
                async.whenComplete((asyncValue, asyncError) -> {
                    if (asyncError != null) {
                        result.completeExceptionally(asyncError);
                    } else {
                        completeResult(result, asyncValue);
                    }
                });
                return;
            }
            completeResult(result, value);
        });
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                active.get().cancel(true);
            }
        });
        return result;
    }

    private static void completeResult(CompletableFuture<ToolResult> target,
                                       Object value) {
        try {
            target.complete(toResult(value));
        } catch (Throwable error) {
            target.completeExceptionally(error);
        }
    }

    private static Set<String> parameterNames(List<ParameterBinding> bindings) {
        Set<String> names = new LinkedHashSet<String>();
        for (ParameterBinding binding : bindings) {
            if (!binding.context && !binding.arguments) {
                names.add(binding.name);
            }
        }
        return Collections.unmodifiableSet(names);
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
        boolean contextSeen = false;
        boolean argumentsSeen = false;
        for (Parameter parameter : parameters) {
            if (parameter.getType() == ToolContext.class) {
                if (contextSeen) {
                    throw new IllegalArgumentException(
                        "Tool method can declare ToolContext only once: " + method
                    );
                }
                contextSeen = true;
                result.add(ParameterBinding.context());
                continue;
            }
            if (parameter.getType() == ToolArguments.class) {
                if (argumentsSeen) {
                    throw new IllegalArgumentException(
                        "Tool method can declare ToolArguments only once: " + method
                    );
                }
                argumentsSeen = true;
                result.add(ParameterBinding.arguments());
                continue;
            }
            ToolParam annotation = parameter.getAnnotation(ToolParam.class);
            String name;
            String description;
            boolean required;
            if (annotation != null) {
                name = annotation.name().trim().isEmpty()
                    ? parameter.getName()
                    : annotation.name();
                description = ToolSchemas.description(annotation);
                required = ToolSchemas.required(
                    parameter.getParameterizedType(), annotation
                );
            } else {
                if (!parameter.isNamePresent()) {
                    throw new IllegalArgumentException(
                        "Tool parameter name is unavailable; add @ToolParam(name=...) to "
                            + method
                    );
                }
                name = parameter.getName();
                description = "";
                required = ToolSchemas.required(parameter.getParameterizedType(), null);
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("Duplicate tool parameter name: " + name);
            }
            result.add(new ParameterBinding(
                name, description, required, parameter.getParameterizedType(), false, false
            ));
        }
        return result;
    }

    private static String schema(List<ParameterBinding> bindings) {
        List<ToolSchemas.Parameter> parameters =
            new ArrayList<ToolSchemas.Parameter>();
        for (ParameterBinding binding : bindings) {
            if (!binding.context && !binding.arguments) {
                parameters.add(new ToolSchemas.Parameter(
                    binding.name,
                    binding.description,
                    binding.required,
                    binding.type,
                    null
                ));
            }
        }
        return ToolSchemas.forParameters(parameters);
    }

    private static final class ParameterBinding {
        private final String name;
        private final String description;
        private final boolean required;
        private final Type type;
        private final boolean context;
        private final boolean arguments;

        private ParameterBinding(String name,
                                 String description,
                                 boolean required,
                                 Type type,
                                 boolean context,
                                 boolean arguments) {
            this.name = name;
            this.description = description;
            this.required = required;
            this.type = type;
            this.context = context;
            this.arguments = arguments;
        }

        private static ParameterBinding context() {
            return new ParameterBinding(
                null, "", false, ToolContext.class, true, false
            );
        }

        private static ParameterBinding arguments() {
            return new ParameterBinding(
                null, "", false, ToolArguments.class, false, true
            );
        }
    }
}
