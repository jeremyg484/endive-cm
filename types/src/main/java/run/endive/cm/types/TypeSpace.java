package run.endive.cm.types;

/**
 * A type index space: what a {@link ValType}'s index means, and which space the type it names goes
 * on counting in.
 *
 * <p>A component-level type is written against a particular index space and keeps meaning what it
 * meant there. A record aliased out of one component into another still has field types numbered
 * in the space that defined it, so resolving those fields against the space that imported it reads
 * whatever types happen to sit at those numbers — a different type, or none at all. Resolving
 * therefore hands back a space to continue in as well as a type, and a walk follows it.
 *
 * <p>{@link TypeResolver} is the degenerate case, where everything resolves in one place.
 */
public interface TypeSpace {

    Resolved resolve(ValType valType);

    /**
     * A space backed by a plain index lookup, in which every type resolves locally.
     *
     * <p>Correct only where nothing was written elsewhere — a single component's own definitions,
     * or a test's hand-built table. A space that can receive types from outside has to record
     * where each of its slots came from instead.
     */
    static TypeSpace of(TypeResolver resolver) {
        return new TypeSpace() {

            @Override
            public Resolved resolve(ValType valType) {
                return new Resolved(resolver.resolveDefValType(valType), this);
            }
        };
    }

    /** A resolved type together with the space its own type indices belong to. */
    final class Resolved {

        private final DefValType type;
        private final TypeSpace space;

        public Resolved(DefValType type, TypeSpace space) {
            this.type = type;
            this.space = space;
        }

        public DefValType type() {
            return type;
        }

        public TypeSpace space() {
            return space;
        }
    }
}
