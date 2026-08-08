package run.endive.cm.runtime;

import java.util.List;
import run.endive.cm.types.Type;
import run.endive.cm.types.ValType;

/**
 * Binds {@link List} to the component {@code list} types.
 *
 * <p>Only the shape is checked. Java erases the element type, so a {@code List} says nothing
 * about what it holds and there is nothing here to compare against the component type's element
 * — that is settled element by element when the value is lowered.
 */
public final class ListHostTypeDescriptor extends HostTypeDescriptor {

    private static final ListHostTypeDescriptor INSTANCE = new ListHostTypeDescriptor();

    private ListHostTypeDescriptor() {}

    public static ListHostTypeDescriptor instance() {
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
            case LIST:
            case SIZED_LIST:
                return true;
            default:
                return false;
        }
    }

    static boolean supports(Class<?> hostType) {
        return List.class.isAssignableFrom(hostType);
    }
}
