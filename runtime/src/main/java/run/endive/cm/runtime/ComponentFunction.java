package run.endive.cm.runtime;

import run.endive.cm.abi.LiftLowerContext;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.TypeResolver;

public interface ComponentFunction {

    Object[] apply(Object... args);

    FuncType funcType();

    boolean isLifted();

    LiftLowerContext context();

    CoreFunction<?> liftedFunction();

    TypeResolver typeResolver();

    /**
     * Whether this function is supplied by the embedder rather than defined by a component.
     *
     * <p>A host function is a piece of Java, and Java has no component-level parameter names to
     * offer: the {@link FuncType} it is registered with is a description written to satisfy
     * importers, not a declaration the function itself makes. Two components may therefore
     * legitimately import the same host function under different parameter names, so a host
     * function is matched on structure alone and takes its names from whoever imports it.
     * Functions a component defines are matched strictly, names included.
     */
    default boolean hostProvided() {
        return false;
    }

    ComponentFunction typed(
            HostTypeDescriptor resultDescriptor, HostTypeDescriptor... paramDescriptors);
}
