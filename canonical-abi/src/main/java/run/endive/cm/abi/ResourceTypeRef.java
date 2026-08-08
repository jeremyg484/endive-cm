package run.endive.cm.abi;

/**
 * The runtime identity of a resource type, as lifting and lowering see it.
 *
 * <p>Resource types in the Component Model are <em>generative</em>: each instantiation of a
 * component that declares {@code (type (resource ...))} brings a distinct resource type into
 * existence, even though every instantiation shares the one declaration the binary was parsed
 * into. The Canonical ABI's guards turn on that distinction — a handle minted against one
 * instantiation's resource type must not be usable against another's — so they compare these
 * references with {@code ==} rather than {@code equals}, exactly as the Python reference
 * compares {@code h.rt is not t.rt}.
 *
 * <p>Implementations therefore have to give reference identity the meaning the spec asks of
 * it, and must <em>not</em> override {@code equals}: one instance per resource type per
 * instantiation, shared by every component that imports it.
 *
 * @see run.endive.cm.types.ResourceType the static declaration this is an instantiation of
 */
public interface ResourceTypeRef {

    /**
     * The component instance that declared this resource type — {@code rt.impl} in the
     * reference — identified by its handle table.
     *
     * <p>{@code lower_borrow} compares this against the context's own table to recognize a
     * {@code borrow} being handed back to the component that implements the resource. See
     * {@link CanonicalAbi#lowerBorrow} for why that case bypasses the table entirely.
     */
    HandleTable impl();

    /**
     * How the spec's diagnostics name this kind of resource type — by who implements it, which
     * is the only thing distinguishing two otherwise identical resource types to a reader.
     */
    default String describe() {
        return impl() == null ? "host-defined resource" : "guest-defined resource";
    }

    /**
     * The clause reporting a handle used against the wrong resource type.
     *
     * <p>Two resource types that differ only by identity describe themselves identically, so
     * saying merely "expected guest-defined resource, found guest-defined resource" would read
     * as no mismatch at all. Naming the second "a different" one is what makes the report
     * intelligible.
     */
    static String mismatch(ResourceTypeRef expected, ResourceTypeRef found) {
        String want = expected.describe();
        if (found == null) {
            return "expected " + want + " but found a value that is not a resource";
        }
        String got = found.describe();
        return "expected " + want + " but found " + (want.equals(got) ? "a different " : "") + got;
    }

    /**
     * Resolves the type index carried by an {@code own} or {@code borrow} type against the
     * type index space of the instance doing the lifting or lowering.
     *
     * <p>This is separate from {@link run.endive.cm.types.TypeResolver} because that resolves
     * to the static {@link run.endive.cm.types.ResourceType} declaration, which is shared
     * across instantiations and so cannot answer the identity question above.
     */
    @FunctionalInterface
    interface Resolver {

        /**
         * @throws run.endive.runtime.TrapException if the index does not denote a resource type
         */
        ResourceTypeRef resolveResourceType(int typeIdx);
    }
}
