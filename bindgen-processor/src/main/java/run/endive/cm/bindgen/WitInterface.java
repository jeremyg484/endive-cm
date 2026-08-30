package run.endive.cm.bindgen;

import java.util.List;
import java.util.Objects;

/**
 * An interface a world imports, under the name the import declares.
 *
 * <p>An interface written inline in the world is named by the world, such as {@code my-custom-host}.
 * One written elsewhere is named by its fully qualified id, such as {@code example:imports/types},
 * and the Java name comes from the last segment either way.
 */
final class WitInterface {

    private final String name;
    private final List<WitFunction> functions;
    private final List<WitResource> resources;

    WitInterface(String name, List<WitFunction> functions, List<WitResource> resources) {
        this.name = Objects.requireNonNull(name, "name");
        this.functions = List.copyOf(functions);
        this.resources = List.copyOf(resources);
    }

    /** The name the world imports this under, which is the key the linker matches. */
    String name() {
        return name;
    }

    /** The functions belonging to the interface itself, rather than to one of its resources. */
    List<WitFunction> functions() {
        return functions;
    }

    List<WitResource> resources() {
        return resources;
    }

    /** The interface's own name, with any package qualification dropped. */
    String simpleName() {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }
}
