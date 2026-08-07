package run.endive.cm.runtime;

import run.endive.cm.abi.ResourceValue;
import run.endive.cm.types.Type;
import run.endive.cm.types.ValType;

/**
 * Binds {@link ResourceValue} to the {@code own} and {@code borrow} handle types.
 *
 * <p>The match is on the shape of the component type only. Which resource type a value belongs
 * to is carried by the value rather than by its Java class, so it cannot be settled here; the
 * lift and lower paths compare resource type identities on every handle they touch, which is
 * where a mismatch is caught.
 */
public final class ResourceHostTypeDescriptor extends HostTypeDescriptor {

    private static final ResourceHostTypeDescriptor INSTANCE = new ResourceHostTypeDescriptor();

    private ResourceHostTypeDescriptor() {}

    public static ResourceHostTypeDescriptor instance() {
        return INSTANCE;
    }

    @Override
    boolean matches(Class<?> hostType) {
        return supports(hostType);
    }

    @Override
    boolean isCompatibleWith(ComponentStore store, ValType componentType) {
        if (componentType.primValType() != null) {
            return false;
        }
        Type type = store.getType(componentType.typeIdx());
        if (type == null || type.defValType() == null) {
            return false;
        }
        switch (type.defValType().kind()) {
            case OWN:
            case BORROW:
                return true;
            default:
                return false;
        }
    }

    static boolean supports(Class<?> hostType) {
        return ResourceValue.class.isAssignableFrom(hostType);
    }
}
