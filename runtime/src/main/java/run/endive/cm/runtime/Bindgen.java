package run.endive.cm.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates host-side bindings for a WIT world, in the package of whatever carries this.
 *
 * <p>The world's imports become an interface the embedder implements and its exports become typed
 * methods, so that calling a component takes Java types rather than an array of objects.
 *
 * <p>WIT is read from the {@code wit} resource directory, which in a conventional Maven project
 * means {@code src/main/resources/wit}. Give {@link #inline} instead when the WIT is small enough
 * to sit beside the code that uses it.
 *
 * <pre>{@code
 * @Bindgen(world = "hello-world")                              // reads wit/hello-world.wit
 * @Bindgen(world = "calculator", path = "wit/calc.wit")
 * @Bindgen(inline = "package my:project;\nworld hello-world { ... }")
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.PACKAGE})
public @interface Bindgen {

    /**
     * The world to generate bindings for. May be left out when the package declares exactly one,
     * in which case {@link #path} or {@link #inline} has to say where the WIT is.
     */
    String world() default "";

    /** Resource path of the WIT file, {@code wit/<world>.wit} when left out. */
    String path() default "";

    /** WIT text, for a world not worth a file of its own. Not to be combined with {@link #path}. */
    String inline() default "";
}
