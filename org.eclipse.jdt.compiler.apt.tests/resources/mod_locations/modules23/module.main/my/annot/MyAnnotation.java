package my.annot;
import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
@Target(ElementType.TYPE_USE)
public @interface MyAnnotation {
    String value() default "";
}