package run.endive.cm.bindgen;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import run.endive.cm.types.Type;

/**
 * A type index space, together with the WIT names given to the types in it.
 *
 * <p>A value type names anything but a primitive by index, so resolving one needs the space it was
 * written against. Names matter because a Java type has to be called something, and only the export
 * declaring a type says what its name is.
 */
final class WitScope {

    private final List<Type> types = new ArrayList<>();
    private final Map<Integer, String> names = new HashMap<>();
    private String owner;

    /**
     * Names the Java type the types in this space are generated inside, so that a reference from
     * outside it is written whole. A world's own space has no owner.
     */
    void withOwner(String owner) {
        this.owner = owner;
    }

    /** The Java type enclosing what this space declares, or {@code null} at the top of a world. */
    String owner() {
        return owner;
    }

    /** Appends a type, at the next index. */
    int add(Type type) {
        types.add(type);
        return types.size() - 1;
    }

    /** Appends a type under the WIT name that declares it. */
    int add(Type type, String name) {
        int index = add(type);
        names.put(index, name);
        return index;
    }

    int size() {
        return types.size();
    }

    /** The type at {@code index}, or {@code null} for a resource, which has no structure here. */
    Type at(int index) {
        if (index < 0 || index >= types.size()) {
            throw new BindgenException("type " + index + " was never declared");
        }
        return types.get(index);
    }

    /** The WIT name of the type at {@code index}, or {@code null} if it was never named. */
    String nameAt(int index) {
        return names.get(index);
    }
}
