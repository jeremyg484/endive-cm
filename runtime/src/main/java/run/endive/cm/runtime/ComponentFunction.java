package run.endive.cm.runtime;

import run.endive.cm.types.FuncType;

public interface ComponentFunction {

    Object[] apply(Object... args);

    FuncType funcType();

    ComponentFunction typed(
            HostTypeDescriptor resultDescriptor, HostTypeDescriptor... paramDescriptors);
}
