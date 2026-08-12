package run.endive.cm.runtime;

import java.util.Map;
import run.endive.cm.types.Type;
import run.endive.cm.types.ValType;

/**
 * Binds {@link Map} to the component types the Canonical ABI carries as a label-to-value map:
 * {@code record}, {@code tuple} — whose fields are numbered from zero when it despecializes —
 * and {@code flags}, whose values are booleans.
 *
 * <p>Deliberately <em>not</em> {@code map<k, v>}. That despecializes to a list of key/value
 * records and travels as a {@link java.util.List}, so a Java {@code Map} is precisely the wrong
 * shape for the one component type sharing its name.
 *
 * <p>Only the shape is checked. Which labels the value carries and what they hold is settled
 * field by field when the value is lowered, the same way {@link ListHostTypeDescriptor} leaves
 * the element type to the lowering of each element.
 */
public final class RecordHostTypeDescriptor extends HostTypeDescriptor {

    private static final RecordHostTypeDescriptor INSTANCE = new RecordHostTypeDescriptor();

    private RecordHostTypeDescriptor() {}

    public static RecordHostTypeDescriptor instance() {
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
            case RECORD:
            case TUPLE:
            case FLAGS:
                return true;
            default:
                return false;
        }
    }

    static boolean supports(Class<?> hostType) {
        return Map.class.isAssignableFrom(hostType);
    }
}
