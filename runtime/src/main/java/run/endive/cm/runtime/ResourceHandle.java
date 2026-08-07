package run.endive.cm.runtime;

import java.util.Objects;
import run.endive.cm.abi.BorrowScope;
import run.endive.cm.abi.Handle;
import run.endive.cm.abi.ResourceTypeRef;

public final class ResourceHandle implements Handle {

    private final ResourceTypeRef resourceType;
    private final int rep;
    private final boolean own;
    private final BorrowScope.Task borrowScope;
    private int numLends;

    ResourceHandle(
            ResourceTypeRef resourceType, int rep, boolean own, BorrowScope.Task borrowScope) {
        this.resourceType = Objects.requireNonNull(resourceType, "resourceType");
        this.rep = rep;
        this.own = own;
        this.borrowScope = borrowScope;
    }

    @Override
    public ResourceTypeRef resourceType() {
        return resourceType;
    }

    @Override
    public int rep() {
        return rep;
    }

    @Override
    public boolean own() {
        return own;
    }

    @Override
    public BorrowScope.Task borrowScope() {
        return borrowScope;
    }

    @Override
    public int numLends() {
        return numLends;
    }

    @Override
    public void lend() {
        numLends++;
    }

    @Override
    public void returnLend() {
        numLends--;
    }
}
