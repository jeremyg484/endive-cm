package run.endive.cm.bindgen;

import java.util.Map;
import run.endive.cm.types.DefValType;
import run.endive.cm.types.EnumType;
import run.endive.cm.types.ListType;
import run.endive.cm.types.Type;
import run.endive.cm.types.ValType;

/**
 * Maps a component value type onto the Java type carrying it, and onto the source that rebuilds the
 * value type and describes it to {@link run.endive.cm.runtime.ComponentFunction#typed}.
 *
 * <p>The Java side follows what the runtime's descriptors already accept, unsigned widening
 * included, so that a {@code u32} arrives as a {@code Long} rather than as an {@code int} it would
 * not fit.
 *
 * <p>Anything but a primitive is named by index, so resolving one needs the {@link WitScope} it was
 * written against.
 */
final class WitTypes {

    /** The package being generated into, so a reference knows whether it has to qualify. */
    private final String into;

    WitTypes(String into) {
        this.into = into;
    }

    /** The Java type carrying values of {@code valType}. */
    String javaType(ValType valType, WitScope scope) {
        if (valType.primValType() != null) {
            return primitiveJavaType(valType.primValType().kind(), valType);
        }
        int index = valType.typeIdx();
        DefValType defined = definedAt(scope, index);
        switch (defined.kind()) {
            case LIST:
                return "List<" + javaType(((ListType) defined).elementType(), scope) + ">";
            case ENUM:
                return enumJavaType(scope, index);
            default:
                throw unsupported(defined.kind().name());
        }
    }

    /** Whether values of {@code valType} need converting between Java and what the ABI carries. */
    boolean needsConversion(ValType valType, WitScope scope) {
        return valType != null
                && valType.primValType() == null
                && definedAt(scope, valType.typeIdx()).kind() == DefValType.Kind.ENUM;
    }

    /** Source turning a Java value into what the ABI carries. */
    String toComponent(String value, ValType valType, WitScope scope) {
        return needsConversion(valType, scope) ? value + ".toComponent()" : value;
    }

    /** Source turning what the ABI carries into a Java value. */
    String fromComponent(String value, ValType valType, WitScope scope) {
        if (needsConversion(valType, scope)) {
            return enumJavaType(scope, valType.typeIdx()) + ".fromComponent(" + value + ")";
        }
        return "(" + javaType(valType, scope) + ") " + value;
    }

    /**
     * Source rebuilding {@code valType}, either as a primitive written inline or as the local a
     * compound type was declared into.
     */
    String valTypeSource(ValType valType, WitScope scope, Map<Integer, String> declared) {
        if (valType.primValType() != null) {
            return "ValType.builder().withPrimValType(PrimValType."
                    + valType.primValType().kind().name()
                    + ").build()";
        }
        String local = declared.get(valType.typeIdx());
        if (local == null) {
            throw unsupported("a type that was never declared");
        }
        return local;
    }

    /** Source rebuilding a compound type, for declaring it into a host instance. */
    String defValTypeSource(DefValType defined, WitScope scope, Map<Integer, String> declared) {
        switch (defined.kind()) {
            case LIST:
                return "Type.of(ListType.builder().withElementType("
                        + valTypeSource(((ListType) defined).elementType(), scope, declared)
                        + ").build())";
            case ENUM:
                StringBuilder source = new StringBuilder("Type.of(EnumType.builder()");
                for (String label : ((EnumType) defined).labels()) {
                    source.append(".addLabel(\"").append(label).append("\")");
                }
                return source.append(".build())").toString();
            default:
                throw unsupported(defined.kind().name());
        }
    }

    /** Source describing {@code valType} to a typed function, or void when there is no type. */
    String descriptorSource(ValType valType, WitScope scope) {
        if (valType == null) {
            return "VoidHostTypeDescriptor.instance()";
        }
        if (valType.primValType() != null) {
            return "PrimitiveHostTypeDescriptor.forClass("
                    + primitiveJavaType(valType.primValType().kind(), valType)
                    + ".class)";
        }
        DefValType defined = definedAt(scope, valType.typeIdx());
        switch (defined.kind()) {
            case LIST:
                return "ListHostTypeDescriptor.instance()";
            case ENUM:
                // What crosses is the variant an enum despecializes to, not the Java enum the
                // embedder holds, because the generated code converts before it calls.
                return "VariantHostTypeDescriptor.instance()";
            default:
                throw unsupported(defined.kind().name());
        }
    }

    /** Whether carrying {@code valType} needs {@link run.endive.cm.abi.CharValue}. */
    static boolean needsCharValue(ValType valType) {
        return valType != null
                && valType.primValType() != null
                && valType.primValType().kind() == DefValType.Kind.CHAR;
    }

    /** Whether carrying {@code valType} needs {@link java.util.List}. */
    static boolean needsList(ValType valType, WitScope scope) {
        return valType != null
                && valType.primValType() == null
                && definedAt(scope, valType.typeIdx()).kind() == DefValType.Kind.LIST;
    }

    /** The kind {@code valType} resolves to, or {@code null} when it names nothing structural. */
    static DefValType.Kind kindOf(ValType valType, WitScope scope) {
        if (valType == null) {
            return null;
        }
        if (valType.primValType() != null) {
            return valType.primValType().kind();
        }
        Type type = scope.at(valType.typeIdx());
        return type == null || type.defValType() == null ? null : type.defValType().kind();
    }

    /**
     * A type is written by its simple name inside the package that declares it, and whole
     * elsewhere, since the generated units do not import from one another.
     */
    String reference(WitScope scope, String witName) {
        String simple = Names.type(witName);
        String declaredIn = scope.javaPackage();
        return declaredIn == null || declaredIn.equals(into) ? simple : declaredIn + "." + simple;
    }

    private String enumJavaType(WitScope scope, int index) {
        String name = scope.nameAt(index);
        if (name == null) {
            throw unsupported("an unnamed enum");
        }
        return reference(scope, name);
    }

    private static DefValType definedAt(WitScope scope, int index) {
        Type type = scope.at(index);
        if (type == null || type.defValType() == null) {
            throw unsupported("a named type");
        }
        return type.defValType();
    }

    private static String primitiveJavaType(DefValType.Kind kind, ValType valType) {
        switch (kind) {
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
                throw unsupported(kind.name().toLowerCase());
        }
    }

    private static BindgenException unsupported(String described) {
        return new BindgenException(described.toLowerCase() + " is not yet supported");
    }
}
