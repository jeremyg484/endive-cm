package run.endive.cm.abi;

import java.util.Objects;

/**
 * The component-level value an {@code own} or {@code borrow} type lifts to.
 *
 * <p>The Python reference lifts a handle to its bare {@code rep} and lowers straight from that
 * integer, because in a reference implementation nothing else ever looks at the value. Here the
 * rep travels with the resource type it belongs to and with whether it was owned or borrowed,
 * so that a host function receiving a resource has something self-describing to work with and
 * so that a lifted value cannot be silently confused with a plain {@code u32}. Lowering still
 * reads only {@link #rep}, which keeps the round trip identical to the reference's.
 *
 * <p>Instances are values: two describing the same handle of the same resource type are equal.
 * Note that this is <em>not</em> a handle — the table index the value came from is consumed by
 * lifting and a fresh one is minted by lowering, so no index appears here.
 */
public final class ResourceValue {

    private final ResourceTypeRef resourceType;
    private final int rep;
    private final boolean own;

    private ResourceValue(ResourceTypeRef resourceType, int rep, boolean own) {
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
        this.rep = rep;
        this.own = own;
    }

    /** A value lifted from, or to be lowered into, an {@code own} handle. */
    public static ResourceValue owned(ResourceTypeRef resourceType, int rep) {
        return new ResourceValue(resourceType, rep, true);
    }

    /** A value lifted from, or to be lowered into, a {@code borrow} handle. */
    public static ResourceValue borrowed(ResourceTypeRef resourceType, int rep) {
        return new ResourceValue(resourceType, rep, false);
    }

    /** The resource type this value belongs to, compared by reference identity. */
    public ResourceTypeRef resourceType() {
        return resourceType;
    }

    /** The resource's representation, opaque to the component the value is passed to. */
    public int rep() {
        return rep;
    }

    /** Whether this came from an {@code own} type rather than a {@code borrow}. */
    public boolean own() {
        return own;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ResourceValue)) {
            return false;
        }
        ResourceValue that = (ResourceValue) o;
        return rep == that.rep && own == that.own && resourceType == that.resourceType;
    }

    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(resourceType), rep, own);
    }

    @Override
    public String toString() {
        return (own ? "own" : "borrow") + "{rep=" + rep + '}';
    }
}
