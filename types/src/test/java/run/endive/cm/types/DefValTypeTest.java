package run.endive.cm.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DefValTypeTest {

    private static ValType prim(PrimValType t) {
        return ValType.builder().withPrimValType(t).build();
    }

    private static ValType typeIdx(int idx) {
        return ValType.builder().withTypeIdx(idx).build();
    }

    private static final TypeResolver stubResolver = index -> null;

    private static final run.endive.wasm.types.ValType CORE_I32 = run.endive.wasm.types.ValType.I32;
    private static final run.endive.wasm.types.ValType CORE_I64 = run.endive.wasm.types.ValType.I64;
    private static final run.endive.wasm.types.ValType CORE_F32 = run.endive.wasm.types.ValType.F32;
    private static final run.endive.wasm.types.ValType CORE_F64 = run.endive.wasm.types.ValType.F64;

    @Test
    void primitiveAlignmentAndSize() {
        assertEquals(1, PrimValType.BOOL.alignment(stubResolver, PointerType.I32));
        assertEquals(1, PrimValType.BOOL.elementSize(stubResolver, PointerType.I32));

        assertEquals(1, PrimValType.U8.alignment(stubResolver, PointerType.I32));
        assertEquals(1, PrimValType.U8.elementSize(stubResolver, PointerType.I32));

        assertEquals(2, PrimValType.S16.alignment(stubResolver, PointerType.I32));
        assertEquals(2, PrimValType.S16.elementSize(stubResolver, PointerType.I32));

        assertEquals(4, PrimValType.U32.alignment(stubResolver, PointerType.I32));
        assertEquals(4, PrimValType.U32.elementSize(stubResolver, PointerType.I32));

        assertEquals(8, PrimValType.S64.alignment(stubResolver, PointerType.I32));
        assertEquals(8, PrimValType.S64.elementSize(stubResolver, PointerType.I32));

        assertEquals(4, PrimValType.F32.alignment(stubResolver, PointerType.I32));
        assertEquals(4, PrimValType.F32.elementSize(stubResolver, PointerType.I32));

        assertEquals(8, PrimValType.F64.alignment(stubResolver, PointerType.I32));
        assertEquals(8, PrimValType.F64.elementSize(stubResolver, PointerType.I32));

        assertEquals(4, PrimValType.CHAR.alignment(stubResolver, PointerType.I32));
        assertEquals(4, PrimValType.CHAR.elementSize(stubResolver, PointerType.I32));

        assertEquals(4, PrimValType.ERROR_CONTEXT.alignment(stubResolver, PointerType.I32));
        assertEquals(4, PrimValType.ERROR_CONTEXT.elementSize(stubResolver, PointerType.I32));
    }

    @Test
    void stringIsPointerLengthPair() {
        assertEquals(4, PrimValType.STRING.alignment(stubResolver, PointerType.I32));
        assertEquals(8, PrimValType.STRING.elementSize(stubResolver, PointerType.I32));
    }

    @Test
    void unboundedListIsPointerLengthPairRegardlessOfElementType() {
        var list = ListType.builder().withElementType(prim(PrimValType.U64)).build();
        assertEquals(4, list.alignment(stubResolver, PointerType.I32));
        assertEquals(8, list.elementSize(stubResolver, PointerType.I32));
    }

    @Test
    void fixedSizeListScalesWithElementType() {
        var list =
                ListType.builder().withElementType(prim(PrimValType.U32)).withFixedSize(3).build();
        assertEquals(4, list.alignment(stubResolver, PointerType.I32));
        assertEquals(12, list.elementSize(stubResolver, PointerType.I32));
    }

    @Test
    void recordAlignsToWidestFieldAndPacksWithPadding() {
        // record { a: u8, b: u32 } -> align 4, size: [0]=u8, pad to 4, [4..8)=u32 -> 8
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
        assertEquals(4, record.alignment(stubResolver, PointerType.I32));
        assertEquals(8, record.elementSize(stubResolver, PointerType.I32));
    }

    @Test
    void variantSizesDiscriminantPlusWidestCasePayload() {
        // variant { a: u8, b: u32 } -> discriminant u8 (align 1), payload align 4 ->
        // [0]=discriminant, pad to 4, [4..8)=payload -> size 8, align 4
        var variant =
                VariantType.builder()
                        .addCase(
                                Case.builder()
                                        .withLabel("a")
                                        .withValType(prim(PrimValType.U8))
                                        .build())
                        .addCase(
                                Case.builder()
                                        .withLabel("b")
                                        .withValType(prim(PrimValType.U32))
                                        .build())
                        .build();
        assertEquals(4, variant.alignment(stubResolver, PointerType.I32));
        assertEquals(8, variant.elementSize(stubResolver, PointerType.I32));
    }

    @Test
    void variantDiscriminantWidensWithCaseCount() {
        // 256 payload-less cases -> u8 discriminant (align/size 1)
        assertEquals(1, variantWithNCases(256).alignment(stubResolver, PointerType.I32));
        assertEquals(1, variantWithNCases(256).elementSize(stubResolver, PointerType.I32));

        // 257 payload-less cases -> u16 discriminant (align/size 2)
        assertEquals(2, variantWithNCases(257).alignment(stubResolver, PointerType.I32));
        assertEquals(2, variantWithNCases(257).elementSize(stubResolver, PointerType.I32));
    }

    @Test
    void flagsSizeGrowsWithLabelCount() {
        assertEquals(1, flags(4).alignment(stubResolver, PointerType.I32));
        assertEquals(1, flags(4).elementSize(stubResolver, PointerType.I32));

        assertEquals(2, flags(10).alignment(stubResolver, PointerType.I32));
        assertEquals(2, flags(10).elementSize(stubResolver, PointerType.I32));

        assertEquals(4, flags(20).alignment(stubResolver, PointerType.I32));
        assertEquals(4, flags(20).elementSize(stubResolver, PointerType.I32));
    }

    @Test
    void handleTypesAreFourBytes() {
        var own = OwnType.builder().withTypeIdx(0).build();
        var borrow = BorrowType.builder().withTypeIdx(0).build();
        assertEquals(4, own.alignment(stubResolver, PointerType.I32));
        assertEquals(4, own.elementSize(stubResolver, PointerType.I32));
        assertEquals(4, borrow.alignment(stubResolver, PointerType.I32));
        assertEquals(4, borrow.elementSize(stubResolver, PointerType.I32));
    }

    @Test
    void streamAndFutureAreFourBytes() {
        var stream = StreamType.builder().withElementType(prim(PrimValType.U8)).build();
        var future = FutureType.builder().withElementType(prim(PrimValType.U8)).build();
        assertEquals(4, stream.alignment(stubResolver, PointerType.I32));
        assertEquals(4, stream.elementSize(stubResolver, PointerType.I32));
        assertEquals(4, future.alignment(stubResolver, PointerType.I32));
        assertEquals(4, future.elementSize(stubResolver, PointerType.I32));
    }

    @Test
    void mapIsTreatedAsUnboundedList() {
        var map =
                MapType.builder()
                        .withKeyType(prim(PrimValType.STRING))
                        .withValueType(prim(PrimValType.U64))
                        .build();
        assertEquals(4, map.alignment(stubResolver, PointerType.I32));
        assertEquals(8, map.elementSize(stubResolver, PointerType.I32));
    }

    @Test
    void tupleDespecializesToRecordOfIndexedFields() {
        var tuple =
                TupleType.builder()
                        .addElementType(prim(PrimValType.U8))
                        .addElementType(prim(PrimValType.U32))
                        .build();
        var record = tuple.despecialize();
        assertEquals(
                List.of("0", "1"),
                record.fields().stream().map(LabelValType::label).collect(Collectors.toList()));
        assertEquals(4, tuple.alignment(stubResolver, PointerType.I32));
        assertEquals(8, tuple.elementSize(stubResolver, PointerType.I32));
    }

    @Test
    void enumDespecializesToVariantOfPayloadlessCases() {
        var enumType =
                EnumType.builder().addLabel("red").addLabel("green").addLabel("blue").build();
        var variant = enumType.despecialize();
        assertEquals(
                List.of("red", "green", "blue"),
                variant.cases().stream().map(Case::label).collect(Collectors.toList()));
        assertEquals(1, enumType.alignment(stubResolver, PointerType.I32));
        assertEquals(1, enumType.elementSize(stubResolver, PointerType.I32));
    }

    @Test
    void optionDespecializesToNoneSomeVariant() {
        var option = OptionType.builder().withValType(prim(PrimValType.U32)).build();
        var variant = option.despecialize();
        assertEquals(
                List.of("none", "some"),
                variant.cases().stream().map(Case::label).collect(Collectors.toList()));
        assertEquals(4, option.alignment(stubResolver, PointerType.I32));
        assertEquals(8, option.elementSize(stubResolver, PointerType.I32));
    }

    @Test
    void resultDespecializesToOkErrorVariant() {
        var result = ResultType.builder().withOk(prim(PrimValType.U32)).build();
        var variant = result.despecialize();
        assertEquals(
                List.of("ok", "error"),
                variant.cases().stream().map(Case::label).collect(Collectors.toList()));
        assertTrue(variant.cases().get(0).hasValType());
        assertFalse(variant.cases().get(1).hasValType());
    }

    @Test
    void unresolvedTypeIndexTraps() {
        var resolver =
                new TypeResolver() {
                    @Override
                    public Type getType(int index) {
                        return null;
                    }
                };
        // Unbounded lists don't need to resolve their element type for alignment/size
        // (both are constant), so use a fixed-size list to force resolution.
        var list = ListType.builder().withElementType(typeIdx(0)).withFixedSize(1).build();
        assertThrows(
                IllegalArgumentException.class, () -> list.alignment(resolver, PointerType.I32));
    }

    @Test
    void alignToRoundsUpToAlignmentBoundary() {
        assertEquals(0, DefValType.alignTo(0, 4));
        assertEquals(4, DefValType.alignTo(1, 4));
        assertEquals(4, DefValType.alignTo(4, 4));
        assertEquals(8, DefValType.alignTo(5, 8));
    }

    @Test
    void primitivesFlattenToTheExpectedCoreType() {
        assertEquals(List.of(CORE_I32), PrimValType.BOOL.flatten(stubResolver, PointerType.I32));
        assertEquals(List.of(CORE_I32), PrimValType.U32.flatten(stubResolver, PointerType.I32));
        assertEquals(List.of(CORE_I64), PrimValType.U64.flatten(stubResolver, PointerType.I32));
        assertEquals(List.of(CORE_I64), PrimValType.S64.flatten(stubResolver, PointerType.I32));
        assertEquals(List.of(CORE_F32), PrimValType.F32.flatten(stubResolver, PointerType.I32));
        assertEquals(List.of(CORE_F64), PrimValType.F64.flatten(stubResolver, PointerType.I32));
        assertEquals(List.of(CORE_I32), PrimValType.CHAR.flatten(stubResolver, PointerType.I32));
        assertEquals(
                List.of(CORE_I32, CORE_I32),
                PrimValType.STRING.flatten(stubResolver, PointerType.I32));
    }

    @Test
    void listsFlattenAccordingToFixedOrUnboundedSize() {
        var unbounded = ListType.builder().withElementType(prim(PrimValType.U64)).build();
        assertEquals(List.of(CORE_I32, CORE_I32), unbounded.flatten(stubResolver, PointerType.I32));

        var fixed =
                ListType.builder().withElementType(prim(PrimValType.U8)).withFixedSize(3).build();
        assertEquals(
                List.of(CORE_I32, CORE_I32, CORE_I32),
                fixed.flatten(stubResolver, PointerType.I32));
    }

    @Test
    void recordFlattensToConcatenatedFieldFlattenings() {
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
                                        .withValType(prim(PrimValType.F64))
                                        .build())
                        .build();
        assertEquals(List.of(CORE_I32, CORE_F64), record.flatten(stubResolver, PointerType.I32));
    }

    @Test
    void variantFlattenJoinsMismatchedCasePayloadTypes() {
        // discriminant (i32) + joined payload slot
        var i32AndF32 =
                VariantType.builder()
                        .addCase(
                                Case.builder()
                                        .withLabel("a")
                                        .withValType(prim(PrimValType.U32))
                                        .build())
                        .addCase(
                                Case.builder()
                                        .withLabel("b")
                                        .withValType(prim(PrimValType.F32))
                                        .build())
                        .build();
        // i32/f32 join -> i32
        assertEquals(List.of(CORE_I32, CORE_I32), i32AndF32.flatten(stubResolver, PointerType.I32));

        var i32AndF64 =
                VariantType.builder()
                        .addCase(
                                Case.builder()
                                        .withLabel("a")
                                        .withValType(prim(PrimValType.U32))
                                        .build())
                        .addCase(
                                Case.builder()
                                        .withLabel("b")
                                        .withValType(prim(PrimValType.F64))
                                        .build())
                        .build();
        // mismatched, neither i32/f32 -> i64
        assertEquals(List.of(CORE_I32, CORE_I64), i32AndF64.flatten(stubResolver, PointerType.I32));
    }

    @Test
    void flagsHandlesOwnBorrowStreamFutureFlattenToI32() {
        assertEquals(List.of(CORE_I32), flags(4).flatten(stubResolver, PointerType.I32));
        assertEquals(
                List.of(CORE_I32),
                OwnType.builder().withTypeIdx(0).build().flatten(stubResolver, PointerType.I32));
        assertEquals(
                List.of(CORE_I32),
                BorrowType.builder().withTypeIdx(0).build().flatten(stubResolver, PointerType.I32));
        assertEquals(
                List.of(CORE_I32),
                StreamType.builder()
                        .withElementType(prim(PrimValType.U8))
                        .build()
                        .flatten(stubResolver, PointerType.I32));
        assertEquals(
                List.of(CORE_I32),
                FutureType.builder()
                        .withElementType(prim(PrimValType.U8))
                        .build()
                        .flatten(stubResolver, PointerType.I32));
    }

    @Test
    void specializedTypesFlattenViaDespecializedForm() {
        var tuple =
                TupleType.builder()
                        .addElementType(prim(PrimValType.U8))
                        .addElementType(prim(PrimValType.U32))
                        .build();
        assertEquals(List.of(CORE_I32, CORE_I32), tuple.flatten(stubResolver, PointerType.I32));

        var map =
                MapType.builder()
                        .withKeyType(prim(PrimValType.STRING))
                        .withValueType(prim(PrimValType.U64))
                        .build();
        assertEquals(List.of(CORE_I32, CORE_I32), map.flatten(stubResolver, PointerType.I32));
    }

    private static FlagsType flags(int n) {
        var builder = FlagsType.builder();
        IntStream.range(0, n).forEach(i -> builder.addLabel("f" + i));
        return builder.build();
    }

    private static VariantType variantWithNCases(int n) {
        var builder = VariantType.builder();
        IntStream.range(0, n)
                .forEach(i -> builder.addCase(Case.builder().withLabel("c" + i).build()));
        return builder.build();
    }
}
