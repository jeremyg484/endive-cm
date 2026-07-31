package run.endive.cm.runtime;

import run.endive.cm.types.ValType;

public abstract class HostTypeDescriptor {

    abstract boolean matches(Class<?> hostType);

    abstract boolean isCompatibleWith(ComponentStore store, ValType componentType);
}
