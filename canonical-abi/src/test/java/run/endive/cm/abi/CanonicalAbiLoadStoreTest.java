package run.endive.cm.abi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import run.endive.cm.types.Case;
import run.endive.cm.types.DefValType;
import run.endive.cm.types.FlagsType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.ListType;
import run.endive.cm.types.PointerType;
import run.endive.cm.types.PrimValType;
import run.endive.cm.types.RecordType;
import run.endive.cm.types.TypeResolver;
import run.endive.cm.types.ValType;
import run.endive.cm.types.VariantType;
import run.endive.runtime.ByteArrayMemory;
import run.endive.runtime.Memory;
import run.endive.runtime.TrapException;
import run.endive.wasm.types.MemoryLimits;

class CanonicalAbiLoadStoreTest {

    private static final TypeResolver STUB_RESOLVER = index -> null;

    private static ValType prim(PrimValType t) {
        return ValType.builder().withPrimValType(t).build();
    }

    private static LiftLowerContext newContext() {
        return newContext(new int[] {0});
    }

    private static LiftLowerContext newContext(int[] bumpPtr) {
        Memory memory = new ByteArrayMemory(new MemoryLimits(1));
        Realloc realloc =
                (oldPtr, oldSize, align, newSize) -> {
                    int ptr = DefValType.alignTo(bumpPtr[0], align);
                    bumpPtr[0] = ptr + newSize;
                    return ptr;
                };
        return LiftLowerContext.builder()
                .withMemory(memory)
                .withPtrType(PointerType.I32)
                .withStringEncoding(StringEncoding.UTF8)
                .withTypeResolver(STUB_RESOLVER)
                .withRealloc(realloc)
                .build();
    }

    @Test
    void roundTripsBool() {
        var ctx = newContext();
        CanonicalAbi.store(ctx, true, PrimValType.BOOL, 0);
        assertThat(CanonicalAbi.load(ctx, 0, PrimValType.BOOL)).isEqualTo(true);

        CanonicalAbi.store(ctx, false, PrimValType.BOOL, 0);
        assertThat(CanonicalAbi.load(ctx, 0, PrimValType.BOOL)).isEqualTo(false);
    }

    @Test
    void roundTripsUnsignedIntegers() {
        var ctx = newContext();
        // Integers load into the Java wrapper the host binds to each component type.
        CanonicalAbi.store(ctx, 200L, PrimValType.U8, 0);
        assertThat(CanonicalAbi.load(ctx, 0, PrimValType.U8)).isEqualTo((short) 200);

        CanonicalAbi.store(ctx, 60000L, PrimValType.U16, 8);
        assertThat(CanonicalAbi.load(ctx, 8, PrimValType.U16)).isEqualTo(60000);

        CanonicalAbi.store(ctx, 4_000_000_000L, PrimValType.U32, 16);
        assertThat(CanonicalAbi.load(ctx, 16, PrimValType.U32)).isEqualTo(4_000_000_000L);

        CanonicalAbi.store(ctx, -1L, PrimValType.U64, 24);
        assertThat(CanonicalAbi.load(ctx, 24, PrimValType.U64))
                .isEqualTo(new BigInteger(Long.toUnsignedString(-1L)));
    }

    @Test
    void roundTripsSignedIntegers() {
        var ctx = newContext();
        // Integers load into the Java wrapper the host binds to each component type.
        CanonicalAbi.store(ctx, -100L, PrimValType.S8, 0);
        assertThat(CanonicalAbi.load(ctx, 0, PrimValType.S8)).isEqualTo((byte) -100);

        CanonicalAbi.store(ctx, -30000L, PrimValType.S16, 8);
        assertThat(CanonicalAbi.load(ctx, 8, PrimValType.S16)).isEqualTo((short) -30000);

        CanonicalAbi.store(ctx, -2_000_000_000L, PrimValType.S32, 16);
        assertThat(CanonicalAbi.load(ctx, 16, PrimValType.S32)).isEqualTo(-2_000_000_000);

        CanonicalAbi.store(ctx, Long.MIN_VALUE, PrimValType.S64, 24);
        assertThat(CanonicalAbi.load(ctx, 24, PrimValType.S64)).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void roundTripsFloats() {
        var ctx = newContext();
        CanonicalAbi.store(ctx, 3.5f, PrimValType.F32, 0);
        assertThat(CanonicalAbi.load(ctx, 0, PrimValType.F32)).isEqualTo(3.5f);

        CanonicalAbi.store(ctx, 3.5, PrimValType.F64, 8);
        assertThat(CanonicalAbi.load(ctx, 8, PrimValType.F64)).isEqualTo(3.5);
    }

    @Test
    void canonicalizesNanOnLoad() {
        var ctx = newContext();
        ctx.memory().writeI32(0, 0x7fa00001); // a non-canonical NaN bit pattern
        var loaded = (Float) CanonicalAbi.load(ctx, 0, PrimValType.F32);
        assertThat(loaded.isNaN()).isTrue();
        assertThat(Float.floatToRawIntBits(loaded)).isEqualTo(0x7fc00000);
    }

    @Test
    void roundTripsChar() {
        var ctx = newContext();
        CanonicalAbi.store(ctx, (int) 'A', PrimValType.CHAR, 0);
        assertThat(CanonicalAbi.load(ctx, 0, PrimValType.CHAR)).isEqualTo((int) 'A');

        CanonicalAbi.store(ctx, 0x1F600, PrimValType.CHAR, 8); // outside the BMP
        assertThat(CanonicalAbi.load(ctx, 8, PrimValType.CHAR)).isEqualTo(0x1F600);
    }

    @Test
    void charLoadTrapsOnSurrogateOrOutOfRange() {
        var ctx = newContext();
        ctx.memory().writeI32(0, 0xD800);
        assertThatThrownBy(() -> CanonicalAbi.load(ctx, 0, PrimValType.CHAR))
                .isInstanceOf(TrapException.class);

        ctx.memory().writeI32(8, 0x110000);
        assertThatThrownBy(() -> CanonicalAbi.load(ctx, 8, PrimValType.CHAR))
                .isInstanceOf(TrapException.class);
    }

    @Test
    void roundTripsFixedSizeList() {
        var ctx = newContext();
        var list =
                ListType.builder().withElementType(prim(PrimValType.U32)).withFixedSize(3).build();
        CanonicalAbi.store(ctx, List.of(1L, 2L, 3L), list, 0);
        assertThat(CanonicalAbi.load(ctx, 0, list)).isEqualTo(List.of(1L, 2L, 3L));
    }

    @Test
    void roundTripsUnboundedList() {
        var bump = new int[] {100};
        var ctx = newContext(bump);
        var list = ListType.builder().withElementType(prim(PrimValType.U32)).build();
        CanonicalAbi.store(ctx, List.of(10L, 20L, 30L, 40L), list, 0);
        assertThat(CanonicalAbi.load(ctx, 0, list)).isEqualTo(List.of(10L, 20L, 30L, 40L));
    }

    @Test
    void roundTripsRecord() {
        var ctx = newContext();
        var record =
                RecordType.builder()
                        .addField(
                                LabelValType.builder()
                                        .withLabel("a")
                                        .withValType(prim(PrimValType.U8))
                                        .build())
                        .addField(
                                LabelValType.builder()
                                        .withLabel("b")
                                        .withValType(prim(PrimValType.U32))
                                        .build())
                        .build();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("a", 7L);
        value.put("b", 12345L);
        CanonicalAbi.store(ctx, value, record, 0);
        // The u8 field loads as a Short and the u32 field as a Long, matching the host bindings.
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("a", (short) 7);
        expected.put("b", 12345L);
        assertThat(CanonicalAbi.load(ctx, 0, record)).isEqualTo(expected);
    }

    @Test
    void roundTripsVariantWithAndWithoutPayload() {
        var ctx = newContext();
        var variant =
                VariantType.builder()
                        .addCase(Case.builder().withLabel("none").build())
                        .addCase(
                                Case.builder()
                                        .withLabel("some")
                                        .withValType(prim(PrimValType.U32))
                                        .build())
                        .build();

        CanonicalAbi.store(ctx, VariantValue.of("none", null), variant, 0);
        assertThat(CanonicalAbi.load(ctx, 0, variant)).isEqualTo(VariantValue.of("none", null));

        CanonicalAbi.store(ctx, VariantValue.of("some", 99L), variant, 16);
        assertThat(CanonicalAbi.load(ctx, 16, variant)).isEqualTo(VariantValue.of("some", 99L));
    }

    @Test
    void variantLoadTrapsOnOutOfRangeCaseIndex() {
        var ctx = newContext();
        var variant =
                VariantType.builder().addCase(Case.builder().withLabel("only").build()).build();
        ctx.memory().writeByte(0, (byte) 5);
        assertThatThrownBy(() -> CanonicalAbi.load(ctx, 0, variant))
                .isInstanceOf(TrapException.class);
    }

    @Test
    void roundTripsFlags() {
        var ctx = newContext();
        var flags =
                FlagsType.builder().addLabel("read").addLabel("write").addLabel("execute").build();
        Map<String, Boolean> value = new LinkedHashMap<>();
        value.put("read", true);
        value.put("write", false);
        value.put("execute", true);
        CanonicalAbi.store(ctx, value, flags, 0);
        assertThat(CanonicalAbi.load(ctx, 0, flags)).isEqualTo(value);
    }
}
