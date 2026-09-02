package run.endive.cm.bindgen;

import java.util.List;
import java.util.Objects;

/**
 * A resource an interface declares, together with the functions reaching it.
 *
 * <p>The Canonical ABI names those functions rather than nesting them, so an interface exports
 * {@code [constructor]file} and {@code [method]file.get-name} alongside its ordinary functions.
 * Reading one back means splitting those names apart again.
 *
 * @see <a href="https://github.com/WebAssembly/component-model/blob/706074c96bc14cfc58469e1bdc452bb4d91921c7/design/mvp/Explainer.md#import-and-export-definitions">Explainer.md, resource method names</a>
 */
final class WitResource {

    private final String name;
    private final WitFunction constructor;
    private final List<WitFunction> methods;

    WitResource(String name, WitFunction constructor, List<WitFunction> methods) {
        this.name = Objects.requireNonNull(name, "name");
        this.constructor = constructor;
        this.methods = List.copyOf(methods);
    }

    String name() {
        return name;
    }

    /** {@code null} when the resource is only ever handed over rather than made by the host. */
    WitFunction constructor() {
        return constructor;
    }

    /** Each method's first parameter is the borrowed receiver, which Java carries as {@code this}. */
    List<WitFunction> methods() {
        return methods;
    }
}
