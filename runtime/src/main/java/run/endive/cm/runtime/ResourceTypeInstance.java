package run.endive.cm.runtime;

import java.util.Objects;
import java.util.function.IntConsumer;
import run.endive.cm.abi.HandleTable;
import run.endive.cm.abi.ResourceTypeRef;
import run.endive.cm.types.ResourceType;

/**
 * A resource type brought into existence by instantiating the component that declares it, or
 * supplied ready-made by the host.
 *
 * <p>{@link ResourceType} is the static declaration parsed out of the binary, shared by every
 * instantiation of that component. Resource types, though, are <em>generative</em>: two
 * instantiations declare two distinct types, and a handle minted against one must not be usable
 * against the other. That distinction lives here — one instance per declaration per
 * instantiation, compared by reference identity, which is why this deliberately does not
 * override {@code equals}.
 */
final class ResourceTypeInstance implements ResourceTypeRef {

    private final ResourceType declared;
    private final ComponentInstance impl;
    private final ComponentStore store;
    private final IntConsumer hostDtor;
    private CoreFunction<?> dtor;

    ResourceTypeInstance(
            ResourceType declared,
            ComponentStore store,
            ComponentInstance impl,
            IntConsumer hostDtor) {
        this.declared = Objects.requireNonNull(declared, "declared");
        this.store = Objects.requireNonNull(store, "store");
        this.impl = impl;
        this.hostDtor = hostDtor;
    }

    /**
     * {@code null} for a resource type the host defines, which no component instance
     * implements — so {@code lower_borrow}'s shortcut for the implementing component never
     * applies to one and its handles always go through a table.
     */
    @Override
    public HandleTable impl() {
        return impl;
    }

    /** The static declaration this is an instantiation of. */
    ResourceType declared() {
        return declared;
    }

    /**
     * Whether dropping the last owning handle of this type runs anything.
     *
     * <p>A guest-declared type names its destructor by core function index, which only means
     * something in the index space of the component that declared it; a host-defined type
     * carries a Java callback instead. A type this runtime merely adopted — imported from
     * somewhere it cannot see — has neither, whatever its declaration happens to say.
     */
    boolean hasDtor() {
        return hostDtor != null || (impl != null && declared.hasDtor());
    }

    /**
     * Destroys the resource behind {@code rep}. Resolved on first use rather than at
     * construction, because a resource type may name a core function the containing component
     * defines after the type section.
     */
    void runDtor(int rep) {
        if (hostDtor != null) {
            hostDtor.accept(rep);
            return;
        }
        if (dtor == null) {
            dtor = store.getCoreFunction((int) declared.dtor());
        }
        dtor.apply(rep);
    }

    @Override
    public String toString() {
        return "resource type " + declared.rep() + "@" + Integer.toHexString(hashCode());
    }
}
