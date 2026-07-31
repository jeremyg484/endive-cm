package run.endive.cm.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import run.endive.cm.types.EnumType;
import run.endive.cm.types.FlagsType;
import run.endive.cm.types.PrimValType;
import run.endive.cm.types.Type;
import run.endive.cm.types.ValType;
import run.endive.cm.types.WasmComponent;

public class ComponentFunctionTests {

    private static final ValType BOOL_VAL_TYPE =
            ValType.builder().withPrimValType(PrimValType.BOOL).build();
    private static final ValType S8_VAL_TYPE =
            ValType.builder().withPrimValType(PrimValType.S8).build();
    private static final ValType U8_VAL_TYPE =
            ValType.builder().withPrimValType(PrimValType.U8).build();
    private static final ValType S16_VAL_TYPE =
            ValType.builder().withPrimValType(PrimValType.S16).build();
    private static final ValType U16_VAL_TYPE =
            ValType.builder().withPrimValType(PrimValType.U16).build();
    private static final ValType S32_VAL_TYPE =
            ValType.builder().withPrimValType(PrimValType.S32).build();
    private static final ValType U32_VAL_TYPE =
            ValType.builder().withPrimValType(PrimValType.U32).build();
    private static final ValType S64_VAL_TYPE =
            ValType.builder().withPrimValType(PrimValType.S64).build();
    private static final ValType U64_VAL_TYPE =
            ValType.builder().withPrimValType(PrimValType.U64).build();
    private static final ValType F32_VAL_TYPE =
            ValType.builder().withPrimValType(PrimValType.F32).build();
    private static final ValType F64_VAL_TYPE =
            ValType.builder().withPrimValType(PrimValType.F64).build();
    private static final ValType CHAR_VAL_TYPE =
            ValType.builder().withPrimValType(PrimValType.CHAR).build();
    private static final ValType STRING_VAL_TYPE =
            ValType.builder().withPrimValType(PrimValType.STRING).build();

    private static final ValType ERROR_CONTEXT_VAL_TYPE =
            ValType.builder().withPrimValType(PrimValType.ERROR_CONTEXT).build();

    private static final ValType ENUM_VAL_TYPE = ValType.builder().withTypeIdx(0).build();

    private static final ValType FLAGS_VAL_TYPE = ValType.builder().withTypeIdx(1).build();

    private static final List<ValType> checkedValTypes =
            List.of(
                    BOOL_VAL_TYPE,
                    S8_VAL_TYPE,
                    U8_VAL_TYPE,
                    S16_VAL_TYPE,
                    U16_VAL_TYPE,
                    S32_VAL_TYPE,
                    U32_VAL_TYPE,
                    S64_VAL_TYPE,
                    U64_VAL_TYPE,
                    F32_VAL_TYPE,
                    F64_VAL_TYPE,
                    CHAR_VAL_TYPE,
                    STRING_VAL_TYPE,
                    ERROR_CONTEXT_VAL_TYPE,
                    ENUM_VAL_TYPE,
                    FLAGS_VAL_TYPE);

    private static final ComponentStore store =
            new ComponentStore(WasmComponent.builder().build(), true);

    @BeforeAll
    static void setup() {
        var enumType =
                EnumType.builder()
                        .addLabel("foo")
                        .addLabel("bar")
                        .addLabel("baz-kebab-case")
                        .build();
        store.addType(Type.of(enumType));

        var flagsType =
                FlagsType.builder()
                        .addLabel("foo")
                        .addLabel("bar")
                        .addLabel("baz-kebab-case")
                        .build();
        store.addType(Type.of(flagsType));
    }

    @Test
    void testBooleanCompatibility() {
        checkedValTypes.stream()
                .collect(Collectors.partitioningBy(t -> t == BOOL_VAL_TYPE))
                .forEach(
                        (compatible, types) -> {
                            if (compatible) {
                                for (ValType t : types) {
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Boolean.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, boolean.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Boolean.class),
                                                    t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            boolean.class),
                                                    t));
                                }
                            } else {
                                for (ValType t : types) {
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Boolean.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, boolean.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Boolean.class),
                                                    t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            boolean.class),
                                                    t));
                                }
                            }
                        });
    }

    @Test
    void testByteCompatibility() {
        checkedValTypes.stream()
                .collect(Collectors.partitioningBy(t -> t == S8_VAL_TYPE))
                .forEach(
                        (compatible, types) -> {
                            if (compatible) {
                                for (ValType t : types) {
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Byte.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, byte.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Byte.class),
                                                    t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            byte.class),
                                                    t));
                                }
                            } else {
                                for (ValType t : types) {
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Byte.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, byte.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Byte.class),
                                                    t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            byte.class),
                                                    t));
                                }
                            }
                        });
    }

    @Test
    void testShortCompatibility() {
        checkedValTypes.stream()
                .collect(
                        Collectors.partitioningBy(
                                t -> List.of(U8_VAL_TYPE, S16_VAL_TYPE).contains(t)))
                .forEach(
                        (compatible, types) -> {
                            if (compatible) {
                                for (ValType t : types) {
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Short.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, short.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Short.class),
                                                    t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            short.class),
                                                    t));
                                }
                            } else {
                                for (ValType t : types) {
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Short.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, short.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Short.class),
                                                    t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            short.class),
                                                    t));
                                }
                            }
                        });
    }

    @Test
    void testIntCompatibility() {
        checkedValTypes.stream()
                .collect(
                        Collectors.partitioningBy(
                                t -> List.of(U16_VAL_TYPE, S32_VAL_TYPE).contains(t)))
                .forEach(
                        (compatible, types) -> {
                            if (compatible) {
                                for (ValType t : types) {
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Integer.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, int.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Integer.class),
                                                    t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(int.class),
                                                    t));
                                }
                            } else {
                                for (ValType t : types) {
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Integer.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, int.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Integer.class),
                                                    t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(int.class),
                                                    t));
                                }
                            }
                        });
    }

    @Test
    void testLongCompatibility() {
        checkedValTypes.stream()
                .collect(
                        Collectors.partitioningBy(
                                t ->
                                        List.of(
                                                        U32_VAL_TYPE,
                                                        S64_VAL_TYPE,
                                                        U64_VAL_TYPE,
                                                        ERROR_CONTEXT_VAL_TYPE)
                                                .contains(t)))
                .forEach(
                        (compatible, types) -> {
                            if (compatible) {
                                for (ValType t : types) {
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Long.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, long.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Long.class),
                                                    t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            long.class),
                                                    t));
                                }
                            } else {
                                for (ValType t : types) {
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Long.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, long.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Long.class),
                                                    t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            long.class),
                                                    t));
                                }
                            }
                        });
    }

    @Test
    void testBigIntegerCompatibility() {
        checkedValTypes.stream()
                .collect(Collectors.partitioningBy(t -> t == U64_VAL_TYPE))
                .forEach(
                        (compatible, types) -> {
                            if (compatible) {
                                for (ValType t : types) {
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, BigInteger.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            BigInteger.class),
                                                    t));
                                }
                            } else {
                                for (ValType t : types) {
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, BigInteger.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            BigInteger.class),
                                                    t));
                                }
                            }
                        });
    }

    @Test
    void testFloatCompatibility() {
        checkedValTypes.stream()
                .collect(Collectors.partitioningBy(t -> t == F32_VAL_TYPE))
                .forEach(
                        (compatible, types) -> {
                            if (compatible) {
                                for (ValType t : types) {
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Float.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, float.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Float.class),
                                                    t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            float.class),
                                                    t));
                                }
                            } else {
                                for (ValType t : types) {
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Float.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, float.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Float.class),
                                                    t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            float.class),
                                                    t));
                                }
                            }
                        });
    }

    @Test
    void testDoubleCompatibility() {
        checkedValTypes.stream()
                .collect(Collectors.partitioningBy(t -> t == F64_VAL_TYPE))
                .forEach(
                        (compatible, types) -> {
                            if (compatible) {
                                for (ValType t : types) {
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Double.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, double.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Double.class),
                                                    t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            double.class),
                                                    t));
                                }
                            } else {
                                for (ValType t : types) {
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Double.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, double.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Double.class),
                                                    t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            double.class),
                                                    t));
                                }
                            }
                        });
    }

    @Test
    void testCharCompatibility() {
        checkedValTypes.stream()
                .collect(Collectors.partitioningBy(t -> t == CHAR_VAL_TYPE))
                .forEach(
                        (compatible, types) -> {
                            if (compatible) {
                                for (ValType t : types) {
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Character.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, char.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Character.class),
                                                    t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            char.class),
                                                    t));
                                }
                            } else {
                                for (ValType t : types) {
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, Character.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, char.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            Character.class),
                                                    t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            char.class),
                                                    t));
                                }
                            }
                        });
    }

    @Test
    void testStringCompatibility() {
        checkedValTypes.stream()
                .collect(Collectors.partitioningBy(t -> t == STRING_VAL_TYPE))
                .forEach(
                        (compatible, types) -> {
                            if (compatible) {
                                for (ValType t : types) {
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, String.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            String.class),
                                                    t));
                                }
                            } else {
                                for (ValType t : types) {
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, String.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    PrimitiveHostTypeDescriptor.forClass(
                                                            String.class),
                                                    t));
                                }
                            }
                        });
    }

    @Test
    void testEnumCompatibility() {
        checkedValTypes.stream()
                .collect(
                        Collectors.partitioningBy(
                                t -> List.of(ENUM_VAL_TYPE, FLAGS_VAL_TYPE).contains(t)))
                .forEach(
                        (compatible, types) -> {
                            if (compatible) {
                                for (ValType t : types) {
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, TestEnum1.class, t));
                                    assertTrue(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    EnumHostTypeDescriptor.forClass(
                                                            TestEnum1.class),
                                                    t));
                                }
                            } else {
                                for (ValType t : types) {
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store, TestEnum1.class, t));
                                    assertFalse(
                                            ComponentFunctionInstance.isTypeCompatible(
                                                    store,
                                                    EnumHostTypeDescriptor.forClass(
                                                            TestEnum1.class),
                                                    t));
                                }
                            }
                        });
    }

    @Test
    void testEnumCompatibilityWithExtraCase() {
        checkedValTypes.forEach(
                t -> {
                    assertFalse(
                            ComponentFunctionInstance.isTypeCompatible(store, TestEnum2.class, t));
                    assertFalse(
                            ComponentFunctionInstance.isTypeCompatible(
                                    store, EnumHostTypeDescriptor.forClass(TestEnum2.class), t));
                });
    }

    @Test
    void testEnumCompatibilityMissingCase() {
        checkedValTypes.forEach(
                t -> {
                    assertFalse(
                            ComponentFunctionInstance.isTypeCompatible(store, TestEnum3.class, t));
                    assertFalse(
                            ComponentFunctionInstance.isTypeCompatible(
                                    store, EnumHostTypeDescriptor.forClass(TestEnum3.class), t));
                });
    }
}
