package run.endive.cm.runtime;

import java.util.List;
import run.endive.cm.types.DefValType;
import run.endive.cm.types.ListType;
import run.endive.cm.types.PrimValType;
import run.endive.cm.types.Type;
import run.endive.cm.types.ValType;

/**
 * Binds {@link List} to the component {@code list} types, and {@code byte[]} to a {@code list<u8>}.
 *
 * <p>A {@code List} is checked only by shape. Java erases the element type, so a {@code List} says
 * nothing about what it holds and there is nothing here to compare against the component type's
 * element. That is settled element by element when the value is lowered. A {@code byte[]} names its
 * element, so it is checked against a {@code u8} element.
 */
public final class ListHostTypeDescriptor extends HostTypeDescriptor {

    private static final ListHostTypeDescriptor INSTANCE = new ListHostTypeDescriptor(false);
    private static final ListHostTypeDescriptor BYTES = new ListHostTypeDescriptor(true);

    private final boolean bytes;

    private ListHostTypeDescriptor(boolean bytes) {
        this.bytes = bytes;
    }

    /** Binds {@link List} to any {@code list}. */
    public static ListHostTypeDescriptor instance() {
        return INSTANCE;
    }

    /** Binds {@code byte[]} to a {@code list<u8>}, whose bytes cross the boundary in one copy. */
    public static ListHostTypeDescriptor bytes() {
        return BYTES;
    }

    @Override
    boolean matches(Class<?> hostType) {
        return bytes ? hostType == byte[].class : List.class.isAssignableFrom(hostType);
    }

    @Override
    boolean isCompatibleWith(ComponentInstance instance, ValType componentType) {
        DefValType defined = definedAt(instance, componentType);
        if (!(defined instanceof ListType)) {
            return false;
        }
        return !bytes || isU8(instance, ((ListType) defined).elementType());
    }

    /** Whether {@code element} is a {@code u8}, written inline or named. */
    private static boolean isU8(ComponentInstance instance, ValType element) {
        if (element.primValType() != null) {
            return element.primValType().kind() == DefValType.Kind.U8;
        }
        DefValType defined = definedAt(instance, element);
        return defined instanceof PrimValType && defined.kind() == DefValType.Kind.U8;
    }

    private static DefValType definedAt(ComponentInstance instance, ValType valType) {
        if (valType.primValType() != null) {
            return null;
        }
        Type type = instance.getType(valType.typeIdx());
        return type == null ? null : type.defValType();
    }

    static boolean supports(Class<?> hostType) {
        return List.class.isAssignableFrom(hostType) || hostType == byte[].class;
    }

    @Override
    public String toString() {
        return bytes ? "byte[]" : "List";
    }
}
