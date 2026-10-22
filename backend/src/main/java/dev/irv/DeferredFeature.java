package dev.irv;

import java.lang.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Unfinished sandbox services stay off in every profile until explicitly integrated. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Conditional(DeferredFeature.Disabled.class)
public @interface DeferredFeature {
  final class Disabled implements Condition {
    @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
      return false;
    }
  }
}
