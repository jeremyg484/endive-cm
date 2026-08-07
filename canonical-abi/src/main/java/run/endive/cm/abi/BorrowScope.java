package run.endive.cm.abi;

/**
 * The call a {@code borrow} handle is scoped to. Borrowing is the Component Model's answer to
 * lending a resource across a component boundary without transferring it, and its safety rests
 * entirely on a borrow never outliving the call that created it. Two counters, maintained from
 * opposite ends of that call, enforce it:
 *
 * <ul>
 *   <li>the <em>callee</em> side ({@link Task}) counts the borrows lowered into the call and
 *       refuses to let it return until every one has been dropped, so no borrow escapes;
 *   <li>the <em>caller</em> side ({@link Subtask}) remembers the handles those borrows were
 *       lent from and holds them lent for the duration, so the resource behind a live borrow
 *       cannot be given away or destroyed underneath it.
 * </ul>
 *
 * <p>A single {@link LiftLowerContext} carries one scope, whose direction is fixed by the
 * canonical definition it belongs to: a {@code canon lift} lowers parameters and lifts results
 * on behalf of a {@link Task}, a {@code canon lower} does the reverse on behalf of a {@link
 * Subtask}. The two sub-interfaces keep that direction checkable, matching the reference's
 * {@code assert(isinstance(cx.borrow_scope, ...))}.
 *
 * <p>May be absent altogether when the types being lifted and lowered contain no {@code
 * borrow}, which {@link CanonicalAbi#containsBorrow} decides.
 */
public interface BorrowScope {

    /**
     * The callee side of a call — the reference's {@code Task}. Borrows lowered into the call
     * are counted here and must all be dropped before it returns.
     */
    interface Task extends BorrowScope {

        /** Counts a borrow lowered into this call ({@code num_borrows += 1}). */
        void borrow();

        /** Counts a borrowed handle dropped by this call ({@code num_borrows -= 1}). */
        void unborrow();

        /** How many borrows lowered into this call are still undropped. */
        int numBorrows();
    }

    /**
     * The caller side of a call — the reference's {@code Subtask}. Handles lent to the call are
     * remembered here and released once it resolves.
     */
    interface Subtask extends BorrowScope {

        /**
         * Records that {@code handle} was lent to this call, holding it lent until {@link
         * #releaseLenders}.
         */
        void addLender(Handle handle);

        /**
         * Releases every handle lent to this call, once it has resolved ({@code
         * Subtask.deliver_resolve}).
         */
        void releaseLenders();
    }
}
