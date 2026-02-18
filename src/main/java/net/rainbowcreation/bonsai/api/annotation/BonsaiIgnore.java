package net.rainbowcreation.bonsai.api.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Fields marked with this will NOT be saved to the database.
 * (Alternative to 'transient' keyword).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface BonsaiIgnore {
}
