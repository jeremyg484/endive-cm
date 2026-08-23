package run.endive.cm.runtime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.PrimValType;
import run.endive.cm.types.ValType;
import run.endive.wasm.WasmEngineException;

/**
 * {@link ComponentFunction#typed} narrows a component function to a set of Java types, so that an
 * embedder holding one can be handed values of the classes it expects rather than {@code Object}.
 *
 * <p>The descriptors are checked twice over, and the two checks answer different questions. Casting
 * checks the descriptors against the component type, asking whether this Java type could ever carry
 * that component type, and fails at cast time before any call. Calling then checks each argument's
 * actual class against its descriptor, which is what an embedder encounters when passing the wrong
 * thing.
 */
public class TypedComponentFunctionTests {

    /** {@code u32} is carried by {@code Long}, since it does not fit an {@code int}. */
    private static final ValType U32 = prim(PrimValType.U32);

    @Test
    public void aTypedFunctionPassesArgumentsAndResultsThrough() {
        ComponentFunction doubler = doubler();

        ComponentFunction typed =
                doubler.typed(
                        PrimitiveHostTypeDescriptor.forClass(Long.class),
                        PrimitiveHostTypeDescriptor.forClass(Long.class));

        assertArrayEquals(new Object[] {42L}, typed.apply(21L));
    }

    /**
     * The parameter descriptors have to survive construction. They were once dropped on the floor,
     * which left every argument unchecked however wrong its class.
     */
    @Test
    public void callingWithAnArgumentOfTheWrongClassIsRejected() {
        ComponentFunction typed =
                doubler()
                        .typed(
                                PrimitiveHostTypeDescriptor.forClass(Long.class),
                                PrimitiveHostTypeDescriptor.forClass(Long.class));

        var thrown = assertThrows(WasmEngineException.class, () -> typed.apply("not a number"));
        assertTrue(
                thrown.getMessage().contains("Argument 0"),
                "expected the failing position to be named, got: " + thrown.getMessage());
    }

    @Test
    public void callingWithTheWrongNumberOfArgumentsIsRejected() {
        ComponentFunction typed =
                doubler()
                        .typed(
                                PrimitiveHostTypeDescriptor.forClass(Long.class),
                                PrimitiveHostTypeDescriptor.forClass(Long.class));

        assertThrows(WasmEngineException.class, () -> typed.apply(1L, 2L));
    }

    /**
     * A descriptor that no value of the parameter's component type could ever satisfy is refused
     * when the function is cast, not when it is called. The check was once inverted, so casting
     * accepted exactly the descriptors it should have rejected.
     */
    @Test
    public void castingWithADescriptorTheParameterCannotSatisfyIsRejected() {
        ComponentFunction doubler = doubler();

        var thrown =
                assertThrows(
                        LinkageException.class,
                        () ->
                                doubler.typed(
                                        PrimitiveHostTypeDescriptor.forClass(Long.class),
                                        PrimitiveHostTypeDescriptor.forClass(String.class)));
        assertTrue(
                thrown.getMessage().contains("at position 0"),
                "expected the failing position to be named, got: " + thrown.getMessage());
    }

    @Test
    public void castingWithADescriptorTheResultCannotSatisfyIsRejected() {
        ComponentFunction doubler = doubler();

        assertThrows(
                LinkageException.class,
                () ->
                        doubler.typed(
                                PrimitiveHostTypeDescriptor.forClass(String.class),
                                PrimitiveHostTypeDescriptor.forClass(Long.class)));
    }

    @Test
    public void castingWithTheWrongNumberOfParameterDescriptorsIsRejected() {
        ComponentFunction doubler = doubler();

        assertThrows(
                LinkageException.class,
                () -> doubler.typed(PrimitiveHostTypeDescriptor.forClass(Long.class)));
    }

    /**
     * A function returning nothing still needs a result descriptor, and returning nothing is what
     * {@link VoidHostTypeDescriptor} describes. The result was once matched against the
     * {@code Object[]} the call returns rather than against the value inside it, so no descriptor
     * could ever match.
     */
    @Test
    public void aFunctionThatReturnsNothingIsDescribedByVoid() {
        var builder = ComponentInstance.builder(new ComponentStore());
        FuncType funcType = FuncType.builder().addParam(param("x", U32)).build();
        ComponentFunction sink =
                ComponentFunctionInstance.builder()
                        .withInstance(builder.instance())
                        .withTypeResolver(builder.instance())
                        .withFuncType(funcType)
                        .withCall(args -> new Object[0])
                        .build();

        ComponentFunction typed =
                sink.typed(
                        VoidHostTypeDescriptor.instance(),
                        PrimitiveHostTypeDescriptor.forClass(Long.class));

        assertArrayEquals(new Object[0], typed.apply(1L));
    }

    @Test
    public void castingAFunctionThatReturnsSomethingAsVoidIsRejected() {
        ComponentFunction doubler = doubler();

        assertThrows(
                LinkageException.class,
                () ->
                        doubler.typed(
                                VoidHostTypeDescriptor.instance(),
                                PrimitiveHostTypeDescriptor.forClass(Long.class)));
    }

    @Test
    public void aTypedFunctionCannotBeRecast() {
        ComponentFunction typed =
                doubler()
                        .typed(
                                PrimitiveHostTypeDescriptor.forClass(Long.class),
                                PrimitiveHostTypeDescriptor.forClass(Long.class));

        assertThrows(
                UnsupportedOperationException.class,
                () -> typed.typed(PrimitiveHostTypeDescriptor.forClass(Long.class)));
    }

    /** {@code func(param "n" u32) -> u32}, doubling whatever it is given. */
    private static ComponentFunction doubler() {
        var builder = ComponentInstance.builder(new ComponentStore());
        FuncType funcType = FuncType.builder().addParam(param("n", U32)).withResult(U32).build();
        return ComponentFunctionInstance.builder()
                .withInstance(builder.instance())
                .withTypeResolver(builder.instance())
                .withFuncType(funcType)
                .withCall(args -> new Object[] {((Number) args[0]).longValue() * 2})
                .build();
    }

    private static ValType prim(PrimValType t) {
        return ValType.builder().withPrimValType(t).build();
    }

    private static LabelValType param(String label, ValType valType) {
        return LabelValType.builder().withLabel(label).withValType(valType).build();
    }
}
