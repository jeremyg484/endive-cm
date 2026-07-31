package run.endive.cm.runtime;

import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import run.endive.cm.types.WasmComponent;

public final class ComponentInstance {

    private final ComponentStore store;
    private final WasmComponent definition;
    private final Table<Object> handles = new Table<>();

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

    int addHandle(Object handle) {
        return handles.add(handle);
    }

    Object getHandle(int index) {
        return handles.get(index);
    }

    Object removeHandle(int index) {
        return handles.remove(index);
    }

    private static final class Table<T> {
        private static final int MAX_SIZE = 2 ^ 28 - 1;

        private final Map<Integer, T> refs = new ConcurrentHashMap<>();
        private final NavigableSet<Integer> freeSlots = new TreeSet<>();

        T get(int index) {
            var ref = refs.get(index);
            if (ref == null) {
                throw new IllegalStateException("ref not found at index " + index);
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
                throw new IllegalStateException("table max size exceeded");
            }
            refs.put(index, ref);
            return index;
        }

        T remove(int index) {
            var ref = refs.remove(index);
            if (ref == null) {
                throw new IllegalStateException("ref not found at index " + index);
            }
            freeSlots.add(index);
            return ref;
        }
    }
}
