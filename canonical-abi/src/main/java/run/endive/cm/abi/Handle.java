package run.endive.cm.abi;

/**
 * One resource handle in a component instance's {@link HandleTable}, as lifting and lowering
 * see it ({@code ResourceHandle} in the Python reference).
 *
 * <p>A handle is either <em>owning</em> — the sole right to the underlying resource, which
 * lifting transfers out of the table — or <em>borrowed</em>, valid only for the duration of
 * the call that lowered it. The two counters below are what make that distinction enforceable
 * at runtime:
 *
 * <ul>
 *   <li>{@link #numLends} counts how many live {@code borrow} handles were lent <em>from</em>
 *       this handle. {@code lift_own} refuses to transfer ownership while it is non-zero, so
 *       a resource cannot be given away while a callee still holds a borrow of it.
 *   <li>{@link BorrowScope.Task#numBorrows} on the {@link #borrowScope} counts borrows lowered
 *       <em>into</em> a call, and must return to zero before that call may return.
 * </ul>
 */
public interface Handle {

    /** The resource type this handle was minted against, compared by reference identity. */
    ResourceTypeRef resourceType();

    /**
     * The resource's representation — an {@code i32} today, kept opaque from the component
     * holding the handle, which only ever names it by table index.
     */
    int rep();

    /** Whether this handle came from an {@code own} type rather than a {@code borrow}. */
    boolean own();

    /**
     * The call this handle is charged to, for a borrowed handle; {@code null} for an owning
     * one. Decremented when the handle is dropped.
     */
    BorrowScope.Task borrowScope();

    /** How many live borrows were lent from this handle. */
    int numLends();

    /** Records a borrow lent from this handle ({@code num_lends += 1}). */
    void lend();

    /** Releases a borrow lent from this handle ({@code num_lends -= 1}). */
    void returnLend();
}
