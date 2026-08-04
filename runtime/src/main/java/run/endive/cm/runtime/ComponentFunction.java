package run.endive.cm.runtime;

import run.endive.cm.abi.LiftLowerContext;
import run.endive.cm.types.FuncType;

public interface ComponentFunction {

    Object[] apply(Object... args);

    FuncType funcType();

    boolean isLifted();

    LiftLowerContext context();

    CoreFunction<?> liftedFunction();

    ComponentFunction typed(
            HostTypeDescriptor resultDescriptor, HostTypeDescriptor... paramDescriptors);
}
