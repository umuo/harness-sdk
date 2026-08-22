package io.github.gitsilence.agent.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a public method as an LLM-callable Tool. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {

    String name() default "";

    /** Shorthand alias for {@link #description()}. */
    String value() default "";

    String description() default "";
}
