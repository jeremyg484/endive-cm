package run.endive.cm.abi;

/**
 * Whether a {@code canon} definition is lifting a core function into a component
 * function or lowering a component function into a core function; affects how {@link
 * CanonicalAbi#flattenFuncType} handles an over-large flat parameter/result list.
 */
public enum Direction {
    LIFT,
    LOWER
}
