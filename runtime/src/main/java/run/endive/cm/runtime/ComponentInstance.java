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
    private final Table<Object> handles = new Table<>(new Object());

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

    ComponentStore store() {
        return store;
    }

    WasmComponent definition() {
        return definition;
    }

    @Override
    public int add(
            ResourceTypeRef resourceType, int rep, boolean own, BorrowScope.Task borrowScope) {
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
     * Index 0 is permanently occupied by a reserved element so that handle indices start at 1
     * and a zeroed-out {@code i32} never names a live handle.
     *
     * <p>A missing index traps rather than throwing: it is reachable from guest code, which can
     * pass any {@code i32} where a handle is expected.
     */
    private static final class Table<T> {
        private static final int MAX_SIZE = (1 << 28) - 1;

        private final Map<Integer, T> refs = new ConcurrentHashMap<>();
        private final Deque<Integer> freeSlots = new ArrayDeque<>();

        private Table(T reserved) {
            refs.put(0, reserved);
        }

        T get(int index) {
            var ref = refs.get(index);
            if (ref == null) {
                throw new TrapException("unknown handle index " + index);
            }
            return ref;
        }

        int add(T ref) {
            Integer freeSlot = freeSlots.pollFirst();
            int index = freeSlot != null ? freeSlot : refs.size();
            if (index != refs.size() && refs.containsKey(index)) {
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
