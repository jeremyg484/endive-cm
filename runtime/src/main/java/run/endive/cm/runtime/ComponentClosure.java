package run.endive.cm.runtime;

import java.util.Objects;
import run.endive.cm.types.WasmComponent;

/**
 * A component as a <em>value</em>: its definition together with the scope it was written in.
 *
 * <p>The definition alone is not enough to instantiate from, because a nested component may
 * reach outward through an {@code alias outer} to something its enclosing component holds — and
 * what that resolves to depends on which <em>instantiation</em> of the enclosing component the
 * value came from, not merely which component defined it. A component instantiated twice with
 * different arguments hands out two components that share a definition and close over different
 * environments, and instantiating those must give different results.
 *
 * <p>So a component value travels with its scope from the moment it is defined, through any
 * export, alias or instantiation argument that carries it elsewhere.
 */
public final class ComponentClosure {

    private final WasmComponent definition;
    private final ComponentStore definingScope;

    ComponentClosure(WasmComponent definition, ComponentStore definingScope) {
        this.definition = Objects.requireNonNull(definition, "definition");
        this.definingScope = definingScope;
    }

    public WasmComponent definition() {
        return definition;
    }

    /**
     * The store of the instantiation this component was defined inside, which its outer
     * aliases resolve against. {@code null} for a component the host supplied, which encloses
     * nothing.
     */
    ComponentStore definingScope() {
        return definingScope;
    }

    @Override
    public String toString() {
        return "component@" + Integer.toHexString(System.identityHashCode(definition));
    }
}
