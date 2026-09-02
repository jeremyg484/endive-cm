package run.endive.cm.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import run.endive.runtime.Machine;

/**
 * The top-level thing a component runtime hands out, and the boundary of what can interact.
 *
 * <p>A store holds any number of {@link ComponentInstance}s at any nesting depth, and only
 * instances within one store may be wired to each other. A resource handle indexes some instance's
 * table and a resource type is compared by identity, so neither means anything in another store.
 *
 * <p>What lives here is what outlives any one instantiation. A component's index spaces and exports
 * belong to the instantiation that built them, not to the store.
 *
 * @see <a href="https://github.com/WebAssembly/component-model/blob/706074c96bc14cfc58469e1bdc452bb4d91921c7/design/mvp/Concurrency.md#threads-and-tasks">Concurrency.md, stores and instances</a>
 */
public final class ComponentStore {

    private final List<ComponentInstance> instances = new ArrayList<>();
    private final Function<run.endive.runtime.Instance, Machine> machineFactory;

    public ComponentStore() {
        this(null);
    }

    /**
     * @param machineFactory how core modules instantiated into this store execute, {@code null} for
     *     the engine's default
     */
    public ComponentStore(Function<run.endive.runtime.Instance, Machine> machineFactory) {
        this.machineFactory = machineFactory;
    }

    /** Called once per {@link ComponentInstance}, from its constructor. */
    void register(ComponentInstance instance) {
        instances.add(instance);
    }

    /** Every instance in this store, at any nesting depth, in creation order. */
    public List<ComponentInstance> instances() {
        return Collections.unmodifiableList(instances);
    }

    Function<run.endive.runtime.Instance, Machine> machineFactory() {
        return machineFactory;
    }

    /**
     * Rejects a value that came from another store. Resource identities and handle indices are
     * store-relative, so wiring one across would silently mean something else.
     */
    void requireOwns(ComponentInstance instance) {
        if (instance != null && instance.store() != this) {
            throw new LinkageException("component instance belongs to a different component store");
        }
    }
}
