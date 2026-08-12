package run.endive.cm.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import run.endive.cm.abi.BorrowScope;
import run.endive.cm.abi.HandleTable;
import run.endive.cm.abi.ResourceTypeRef;
import run.endive.cm.types.WasmComponent;
import run.endive.runtime.TrapException;

public final class ComponentInstance implements HandleTable {

    private final ComponentStore store;
    private final WasmComponent definition;
    private final Table<Object> handles = new Table<>();
    private boolean mayEnter = true;

    ComponentInstance(ComponentStore store, WasmComponent definition) {
        this.store = store;
        this.definition = definition;
    }

    public ComponentFunction export(String name) {
        Object value = store.getExport(name);
        if (value == null) {
            throw new LinkageException("No export named \"" + name + "\"");
        }
        if (!(value instanceof ComponentFunction)) {
            throw new LinkageException(
                    "Export \""
                            + name
                            + "\" is not a function (got "
                            + value.getClass().getName()
                            + ")");
        }
        return (ComponentFunction) value;
    }

    /**
     * Whether a call may enter this instance right now.
     *
     * <p>Currently false only while the instance is being instantiated. A core {@code start}
     * function runs during instantiation and can reach a lowered function whose callee is lifted out of the
     * very instance being built. The component's state is not yet in place at that point, so
     * the instance traps rather than letting the call through. See Component Invariant #2
     * in the Explainer.
     *
     * <p>Deliberately simpler than the specification's model, which tracks the caller and its
     * ancestors so that a parent may re-enter a child it wraps. This flag flips once, at the end
     * of instantiation, and never goes back; the caller-relative rules only start to matter once
     * blocking calls are modelled.
     */
    boolean mayEnter() {
        return mayEnter;
    }

    void setMayEnter(boolean mayEnter) {
        this.mayEnter = mayEnter;
    }

    ComponentStore store() {
        return store;
    }

    WasmComponent definition() {
        return definition;
    }

    @Override
    public int add(
            ResourceTypeRef resourceType, int rep, boolean own, BorrowScope.Callee borrowScope) {
        return handles.add(new ResourceHandle(resourceType, rep, own, borrowScope));
    }

    @Override
    public Object get(int index) {
        return handles.get(index);
    }

    @Override
    public Object remove(int index) {
        return handles.remove(index);
    }

    int addHandle(Object handle) {
        return handles.add(handle);
    }

    Object getHandle(int index) {
        return handles.get(index);
    }

    Object removeHandle(int index) {
        return handles.remove(index);
    }

    /**
     * Index 0 is never handed out, so a zeroed-out {@code i32} names nothing and reads as an
     * unknown index rather than as some element of the wrong kind.
     *
     * <p>A missing index traps rather than throwing: it is reachable from guest code, which can
     * pass any {@code i32} where a handle is expected.
     */
    private static final class Table<T> {
        private static final int MAX_SIZE = (1 << 28) - 1;

        private final Map<Integer, T> refs = new ConcurrentHashMap<>();
        private final Deque<Integer> freeSlots = new ArrayDeque<>();

        /** Freed slots are reused before fresh ones, so this only ever grows past reuse. */
        private int next = 1;

        T get(int index) {
            var ref = refs.get(index);
            if (ref == null) {
                throw new TrapException("unknown handle index " + index);
            }
            return ref;
        }

        int add(T ref) {
            Integer freeSlot = freeSlots.pollFirst();
            int index = freeSlot != null ? freeSlot : next++;
            if (refs.containsKey(index)) {
                throw new IllegalStateException("ref already found at index " + index);
            }
            if (index >= MAX_SIZE) {
                throw new TrapException("handle table max size exceeded");
            }
            refs.put(index, ref);
            return index;
        }

        T remove(int index) {
            var ref = refs.remove(index);
            if (ref == null) {
                throw new TrapException("unknown handle index " + index);
            }
            freeSlots.addFirst(index);
            return ref;
        }
    }
}
