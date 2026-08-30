package run.endive.cm.bindgen;

import run.endive.cm.types.DefValType;
import run.endive.cm.types.ValType;

/**
 * Maps a component value type onto the Java type carrying it, and onto the source that rebuilds the
 * value type and describes it to {@link run.endive.cm.runtime.ComponentFunction#typed}.
 *
 * <p>The Java side follows what the runtime's descriptors already accept, unsigned widening
 * included, so that a {@code u32} arrives as a {@code Long} rather than as an {@code int} it would
 * not fit.
 */
final class WitTypes {

    private WitTypes() {}

    /** The Java type carrying values of {@code valType}. */
    static String javaType(ValType valType) {
        switch (kind(valType)) {
            case BOOL:
                return "Boolean";
            case S8:
                return "Byte";
            case U8:
            case S16:
                return "Short";
            case U16:
            case S32:
                return "Integer";
            case U32:
            case S64:
            case U64:
                return "Long";
            case F32:
                return "Float";
            case F64:
                return "Double";
            case CHAR:
                return "CharValue";
            case STRING:
                return "String";
            default:
                throw unsupported(valType);
        }
    }

    /** Source rebuilding {@code valType}, for a generated function type. */
    static String valTypeSource(ValType valType) {
        return "ValType.builder().withPrimValType(PrimValType."
                + kind(valType).name()
                + ").build()";
    }

    /** Source describing {@code valType} to a typed function, or void when there is no type. */
    static String descriptorSource(ValType valType) {
        if (valType == null) {
            return "VoidHostTypeDescriptor.instance()";
        }
        return "PrimitiveHostTypeDescriptor.forClass(" + javaType(valType) + ".class)";
    }

    /** Whether carrying {@code valType} needs {@link run.endive.cm.abi.CharValue}. */
    static boolean needsCharValue(ValType valType) {
        return valType != null
                && valType.primValType() != null
                && kind(valType) == DefValType.Kind.CHAR;
    }

    private static DefValType.Kind kind(ValType valType) {
        if (valType.primValType() == null) {
            throw unsupported(valType);
        }
        return valType.primValType().kind();
    }

    private static BindgenException unsupported(ValType valType) {
        String described =
                valType.primValType() == null
                        ? "a named type"
                        : valType.primValType().kind().name().toLowerCase();
        return new BindgenException(
                described + " is not yet supported, only the primitive types and string are");
    }
}
