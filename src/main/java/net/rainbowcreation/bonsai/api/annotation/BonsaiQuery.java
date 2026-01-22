package net.rainbowcreation.bonsai.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field (or entire class) as Queryable.
 * Usage:
 * 1. On Class: @BonsaiQuery -> Indexes ALL fields.
 * 2. On Field: @BonsaiQuery -> Indexes specific field.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.TYPE})
public @interface BonsaiQuery {
}
