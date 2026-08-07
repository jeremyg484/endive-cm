package run.endive.cm.runtime;

import java.util.ArrayList;
import java.util.List;
import run.endive.cm.abi.BorrowScope;
import run.endive.cm.abi.Handle;

/**
 * One invocation of a lowered component function, from the caller's side.
 *
 * <p>Its job here is the mirror of {@link Task}'s: it remembers which of the caller's handles
 * were lent to the call as {@code borrow}s and keeps them marked as lent for its duration, so
 * that the caller cannot hand the underlying resource away — or destroy it — while the callee
 * still holds a borrow. The marks come off once the call resolves.
 */
public final class Subtask implements BorrowScope.Subtask {

    private final List<Handle> lenders = new ArrayList<>();
    private boolean resolved;

    @Override
    public void addLender(Handle handle) {
        if (resolved) {
            throw new IllegalStateException("cannot lend to a subtask that has already resolved");
        }
        handle.lend();
        lenders.add(handle);
    }

    @Override
    public void releaseLenders() {
        if (resolved) {
            return;
        }
        resolved = true;
        for (Handle handle : lenders) {
            handle.returnLend();
        }
        lenders.clear();
    }
}
