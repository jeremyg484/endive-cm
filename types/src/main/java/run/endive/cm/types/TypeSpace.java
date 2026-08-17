package run.endive.cm.types;

/**
 * A type index space where a given {@link ValType}'s index can be resolved.
 *
 * <p>A component-level type is written against a particular index space. A type aliased out
 * of one component and into another still potentially has indices numbered
 * in the space that defined it. When a type is imported, it needs a way for its indices
 * to resolve in the space where it originated. Resolving therefore hands back a space to
 * continue in as well as a type.
 *
 * <p>{@link TypeResolver} is the base case, where everything resolves in one place.
 */
public interface TypeSpace {

    Resolved resolve(ValType valType);

    /**
     * A space backed by a plain index lookup, in which every type resolves locally.
     *
     * <p>Correct only where nothing was written elsewhere, such as a single component's own definitions,
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

    /** A resolved type together with the space wherein its own type indices resolve. */
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
