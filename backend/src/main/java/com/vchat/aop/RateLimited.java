package com.vchat.aop;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Fixed-window rate limit backed by Redis, shared across all backend instances.
 * key: "" = current user (or client IP when anonymous), "ip" = client IP, otherwise a SpEL expression over the
 * method parameters, e.g. "#r.username()".
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {
    String name();
    int limit();
    int windowSeconds();
    String key() default "";
    String message() default "Too many requests, please slow down";
}
