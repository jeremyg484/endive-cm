package run.endive.cm.runtime;

import java.util.Objects;
import run.endive.cm.types.ResourceType;

public final class ResourceHandle {

    private final ResourceType resourceType;
    private final int rep;
    private final boolean own;
    private final Task borrowScope;
    private int numLends;

    ResourceHandle(ResourceType resourceType, int rep, boolean own, Task borrowScope) {
        Objects.requireNonNull(resourceType, "resourceType");
        this.resourceType = resourceType;
        this.rep = rep;
        this.own = own;
        this.borrowScope = borrowScope;
    }

    ResourceType resourceType() {
        return resourceType;
    }

    int rep() {
        return rep;
    }

    boolean own() {
        return own;
    }

    Task borrowScope() {
        return borrowScope;
    }

    int numLends() {
        return numLends;
    }

    void lend() {
        numLends++;
    }

    void returnLend() {
        numLends--;
    }
}
