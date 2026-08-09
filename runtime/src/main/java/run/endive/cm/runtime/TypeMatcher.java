package run.endive.cm.runtime;

import java.util.List;
import run.endive.cm.abi.ResourceTypeRef;
import run.endive.cm.types.BorrowType;
import run.endive.cm.types.Case;
import run.endive.cm.types.DefValType;
import run.endive.cm.types.EnumType;
import run.endive.cm.types.FlagsType;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.FutureType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.ListType;
import run.endive.cm.types.MapType;
import run.endive.cm.types.OptionType;
import run.endive.cm.types.OwnType;
import run.endive.cm.types.RecordType;
import run.endive.cm.types.ResultType;
import run.endive.cm.types.StreamType;
import run.endive.cm.types.TupleType;
import run.endive.cm.types.Type;
import run.endive.cm.types.TypeSpace;
import run.endive.cm.types.ValType;
import run.endive.cm.types.VariantType;

/**
 * Compares two component-level types that live in <em>different type index spaces</em>.
 *
 * <p>An instance type written in an import declaration carries its own index space: the types
 * its declarations define are numbered from zero and mean nothing outside it. Its {@code
 * ValType{typeIdx=1}} and the providing instance's {@code ValType{typeIdx=1}} are unrelated, so
 * the two can only be compared by resolving each in its own space and walking the structures
 * together — which is what this does.
 *
 * <p>Handles are the reason the walk has to bottom out in something other than structure.
 * {@code own} and {@code borrow} name a resource type, and two resource types are the same type
 * only when they are the <em>same runtime type</em> — not when their declarations look alike.
 * Each side therefore carries a {@link ResourceTypeRef.Resolver} alongside its type space, and
 * handles compare by the identity that yields.
 */
final class TypeMatcher {

    private TypeMatcher() {}

    /**
     * One side of a comparison: a type index space, and the resource identities its indices
     * resolve to.
     *
     * <p>Resolving yields the space to continue in as well as the type, because a space can
     * borrow types from another one. An instance type that aliases a type from its enclosing
     * component holds a type whose <em>own</em> indices still count from the enclosing
     * component's space, so a walk that reached it through the alias has to switch spaces to
     * carry on correctly.
     */
    interface Space extends TypeSpace {

        ResourceTypeRef resourceType(int typeIdx);
    }

    /**
     * Whether a function offered by {@code actual} can stand in for the one {@code expected}
     * declares. Parameter labels are part of a function type and must agree, as must their
     * order — the Component Model relaxes equality into subtyping only for instance and
     * component types.
     *
     * @param matchParamNames pass {@code false} only for an embedder-supplied function, which
     *     has no parameter names of its own to compare — see {@link
     *     ComponentFunction#hostProvided()}
     */
    static boolean funcTypesMatch(
            Space expectedSpace,
            FuncType expected,
            Space actualSpace,
            FuncType actual,
            boolean matchParamNames) {
        if (expected == null || actual == null) {
            return false;
        }
        if (expected.isAsync() != actual.isAsync()) {
            return false;
        }
        List<LabelValType> expectedParams = expected.params();
        List<LabelValType> actualParams = actual.params();
        if (expectedParams.size() != actualParams.size()) {
            return false;
        }
        for (int i = 0; i < expectedParams.size(); i++) {
            LabelValType e = expectedParams.get(i);
            LabelValType a = actualParams.get(i);
            if (matchParamNames && !e.label().equals(a.label())) {
                return false;
            }
            if (!valTypesMatch(expectedSpace, e.valType(), actualSpace, a.valType())) {
                return false;
            }
        }
        if (expected.hasResult() != actual.hasResult()) {
            return false;
        }
        return !expected.hasResult()
                || valTypesMatch(expectedSpace, expected.result(), actualSpace, actual.result());
    }

    static boolean valTypesMatch(
            Space expectedSpace, ValType expected, Space actualSpace, ValType actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        TypeSpace.Resolved e = expectedSpace.resolve(expected);
        TypeSpace.Resolved a = actualSpace.resolve(actual);
        // Every space reachable from a Space is one, since only the linker builds them; the
        // resolved form is shared with the Canonical ABI, which has no use for resource identity
        // and so does not carry it in the interface.
        return defValTypesMatch((Space) e.space(), e.type(), (Space) a.space(), a.type());
    }

    /**
     * Compares resolved types structurally, without despecializing: {@code tuple<u32, u32>} and
     * the record it lowers to share a memory layout but are different types, and an import
     * asking for one is not satisfied by the other.
     */
    private static boolean defValTypesMatch(
            Space expectedSpace, DefValType expected, Space actualSpace, DefValType actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected.kind() != actual.kind()) {
            return false;
        }
        switch (expected.kind()) {
            case BOOL:
            case S8:
            case U8:
            case S16:
            case U16:
            case S32:
            case U32:
            case S64:
            case U64:
            case F32:
            case F64:
            case CHAR:
            case STRING:
            case ERROR_CONTEXT:
                return true;
            case OWN:
                return resourceTypesMatch(
                        expectedSpace,
                        ((OwnType) expected).typeIdx(),
                        actualSpace,
                        ((OwnType) actual).typeIdx());
            case BORROW:
                return resourceTypesMatch(
                        expectedSpace,
                        ((BorrowType) expected).typeIdx(),
                        actualSpace,
                        ((BorrowType) actual).typeIdx());
            case RECORD:
                return fieldsMatch(
                        expectedSpace,
                        ((RecordType) expected).fields(),
                        actualSpace,
                        ((RecordType) actual).fields());
            case VARIANT:
                return casesMatch(
                        expectedSpace,
                        ((VariantType) expected).cases(),
                        actualSpace,
                        ((VariantType) actual).cases());
            case TUPLE:
                return elementsMatch(
                        expectedSpace,
                        ((TupleType) expected).elementTypes(),
                        actualSpace,
                        ((TupleType) actual).elementTypes());
            case FLAGS:
                return ((FlagsType) expected).labels().equals(((FlagsType) actual).labels());
            case ENUM:
                return ((EnumType) expected).labels().equals(((EnumType) actual).labels());
            case LIST:
            case SIZED_LIST:
                {
                    var e = (ListType) expected;
                    var a = (ListType) actual;
                    return e.isFixedSize() == a.isFixedSize()
                            && (!e.isFixedSize() || e.size() == a.size())
                            && valTypesMatch(
                                    expectedSpace, e.elementType(), actualSpace, a.elementType());
                }
            case OPTION:
                return valTypesMatch(
                        expectedSpace,
                        ((OptionType) expected).valType(),
                        actualSpace,
                        ((OptionType) actual).valType());
            case RESULT:
                {
                    var e = (ResultType) expected;
                    var a = (ResultType) actual;
                    return e.hasOk() == a.hasOk()
                            && e.hasError() == a.hasError()
                            && (!e.hasOk()
                                    || valTypesMatch(expectedSpace, e.ok(), actualSpace, a.ok()))
                            && (!e.hasError()
                                    || valTypesMatch(
                                            expectedSpace, e.error(), actualSpace, a.error()));
                }
            case MAP:
                {
                    var e = (MapType) expected;
                    var a = (MapType) actual;
                    return valTypesMatch(expectedSpace, e.keyType(), actualSpace, a.keyType())
                            && valTypesMatch(
                                    expectedSpace, e.valueType(), actualSpace, a.valueType());
                }
            case STREAM:
                {
                    var e = (StreamType) expected;
                    var a = (StreamType) actual;
                    return e.hasElementType() == a.hasElementType()
                            && (!e.hasElementType()
                                    || valTypesMatch(
                                            expectedSpace,
                                            e.elementType(),
                                            actualSpace,
                                            a.elementType()));
                }
            case FUTURE:
                {
                    var e = (FutureType) expected;
                    var a = (FutureType) actual;
                    return e.hasElementType() == a.hasElementType()
                            && (!e.hasElementType()
                                    || valTypesMatch(
                                            expectedSpace,
                                            e.elementType(),
                                            actualSpace,
                                            a.elementType()));
                }
            default:
                throw new IllegalStateException("unhandled kind " + expected.kind());
        }
    }

    /**
     * Two handles name the same type only when they resolve to the same runtime resource type.
     * Structural equality is deliberately not enough: separate declarations, and separate
     * instantiations of one declaration, are separate types however alike they look.
     */
    private static boolean resourceTypesMatch(
            Space expectedSpace, int expectedIdx, Space actualSpace, int actualIdx) {
        return expectedSpace.resourceType(expectedIdx) == actualSpace.resourceType(actualIdx);
    }

    private static boolean fieldsMatch(
            Space expectedSpace,
            List<LabelValType> expected,
            Space actualSpace,
            List<LabelValType> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            LabelValType e = expected.get(i);
            LabelValType a = actual.get(i);
            if (!e.label().equals(a.label())
                    || !valTypesMatch(expectedSpace, e.valType(), actualSpace, a.valType())) {
                return false;
            }
        }
        return true;
    }

    private static boolean casesMatch(
            Space expectedSpace, List<Case> expected, Space actualSpace, List<Case> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            Case e = expected.get(i);
            Case a = actual.get(i);
            if (!e.label().equals(a.label()) || e.hasValType() != a.hasValType()) {
                return false;
            }
            if (e.hasValType()
                    && !valTypesMatch(expectedSpace, e.valType(), actualSpace, a.valType())) {
                return false;
            }
        }
        return true;
    }

    private static boolean elementsMatch(
            Space expectedSpace, List<ValType> expected, Space actualSpace, List<ValType> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < expected.size(); i++) {
            if (!valTypesMatch(expectedSpace, expected.get(i), actualSpace, actual.get(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Compares two type definitions that are not necessarily value types — the form a {@code
     * (type (eq ...))} bound takes, which may name a function, instance or resource type as
     * well as a value type.
     */
    static boolean typesMatch(Space expectedSpace, Type expected, Space actualSpace, Type actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected.funcType() != null || actual.funcType() != null) {
            return funcTypesMatch(
                    expectedSpace, expected.funcType(), actualSpace, actual.funcType(), true);
        }
        if (expected.defValType() != null || actual.defValType() != null) {
            return defValTypesMatch(
                    expectedSpace, expected.defValType(), actualSpace, actual.defValType());
        }
        // Component and instance types have no structural comparison here yet; fall back to the
        // declaration-level equality the linker used before.
        return expected.equals(actual);
    }
}
