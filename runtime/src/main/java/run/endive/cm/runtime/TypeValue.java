package run.endive.cm.runtime;

import java.util.Objects;
import run.endive.cm.types.Type;

/**
 * A type as a <em>value</em>: the declaration together with the index space its own indices
 * count in.
 *
 * <p>A type handed to another component — as an instantiation argument, say — does not stop
 * meaning what it meant where it was written. A record whose fields name types by index still
 * names <em>those</em> types, so the receiver has to resolve it against the sender's space
 * rather than its own, where the same numbers pick out something else entirely.
 *
 * <p>Resource types travel as their {@link ResourceTypeInstance} identity instead, which needs
 * no space: a resource declaration has no indices inside it to resolve.
 */
final class TypeValue {

    private final Type type;
    private final TypeMatcher.Space space;

    TypeValue(Type type, TypeMatcher.Space space) {
        this.type = Objects.requireNonNull(type, "type");
        this.space = Objects.requireNonNull(space, "space");
    }

    Type type() {
        return type;
    }

    /** The space {@link #type}'s own indices resolve in. */
    TypeMatcher.Space space() {
        return space;
    }

    @Override
    public String toString() {
        return String.valueOf(type);
    }
}
