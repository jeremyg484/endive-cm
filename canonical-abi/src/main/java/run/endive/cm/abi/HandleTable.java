package run.endive.cm.abi;

/**
 * A component instance's handle table, as lifting and lowering see it ({@code cx.inst.handles}
 * in the Python reference).
 *
 * <p>Handles are the only names a component ever has for a resource: the representation itself
 * stays on this side of the boundary and the component works with table indices. Lifting a
 * value therefore <em>consumes</em> an index out of the source instance's table and lowering it
 * <em>mints</em> one in the destination's, which is why a handle can never be moved between
 * two memories as a byte copy the way an integer or a record can — see {@link Transferability}
 * for the consequence.
 *
 * <p>The table is heterogeneous in the full Component Model: alongside resource handles it
 * holds waitables, waitable sets and error contexts. {@link #get} and {@link #remove}
 * accordingly return {@link Object}, and the lift paths check for {@link Handle} themselves so
 * that an index naming the wrong kind of element traps rather than being misread.
 *
 * <p>Implementations are compared by reference identity — {@link ResourceTypeRef#impl} returns
 * one of these, and {@code lower_borrow} recognizes the resource type's implementing instance
 * by {@code ==}.
 */
public interface HandleTable {

    /**
     * Creates a resource handle and adds it to the table, returning its index.
     *
     * <p>The table constructs the handle rather than accepting one so that this module needs no
     * concrete {@link Handle} of its own.
     *
     * @param borrowScope the call to charge a borrowed handle to; {@code null} for an owning one
     * @throws run.endive.runtime.TrapException if the table is full
     */
    int add(ResourceTypeRef resourceType, int rep, boolean own, BorrowScope.Task borrowScope);

    /**
     * The element at {@code index}, left in the table.
     *
     * @throws run.endive.runtime.TrapException if no element is present at {@code index}
     */
    Object get(int index);

    /**
     * Removes the element at {@code index} and returns it.
     *
     * @throws run.endive.runtime.TrapException if no element is present at {@code index}
     */
    Object remove(int index);
}
