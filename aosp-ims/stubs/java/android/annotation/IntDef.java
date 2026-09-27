package android.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.ANNOTATION_TYPE})
public @interface IntDef { String[] prefix() default {}; String[] suffix() default {}; int[] value() default {}; boolean flag() default false; }
