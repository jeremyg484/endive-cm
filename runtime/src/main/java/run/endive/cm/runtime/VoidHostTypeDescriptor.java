package run.endive.cm.runtime;

import run.endive.cm.types.ValType;

public class VoidHostTypeDescriptor extends HostTypeDescriptor {
    @Override
    boolean matches(Class<?> hostType) {
        return hostType == null;
    }

    @Override
    boolean isCompatibleWith(ComponentStore store, ValType componentType) {
        return componentType == null;
    }
}
