package run.endive.cm.runtime;

import run.endive.cm.abi.BorrowScope;
import run.endive.runtime.TrapException;

/**
 * One invocation of a lifted component function, from the callee's side.
 *
 * <p>Its job here is to hold the borrows lowered into the call to account: every {@code borrow}
 * handed to the callee is counted on the way in and uncounted when the callee drops it, and the
 * call is not allowed to return while any remain. That check is what stops a borrowed handle
 * from outliving the call it was lent for.
 */
public final class Task implements BorrowScope.Task {

    private int numBorrows;

    @Override
    public void borrow() {
        numBorrows++;
    }

    @Override
    public void unborrow() {
        numBorrows--;
    }

    @Override
    public int numBorrows() {
        return numBorrows;
    }

    /** Traps unless every borrow lowered into this call has since been dropped. */
    void requireBorrowsReleased() {
        if (numBorrows > 0) {
            throw new TrapException(
                    "cannot return from a call with " + numBorrows + " undropped borrow(s)");
        }
    }
}
