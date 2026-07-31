package run.endive.cm.abi;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import run.endive.cm.types.DefValType;
import run.endive.cm.types.FlagsType;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.FutureType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.ListType;
import run.endive.cm.types.MapType;
import run.endive.cm.types.PointerType;
import run.endive.cm.types.RecordType;
import run.endive.cm.types.Specialized;
import run.endive.cm.types.StreamType;
import run.endive.cm.types.TupleType;
import run.endive.cm.types.TypeResolver;
import run.endive.cm.types.VariantType;
import run.endive.runtime.Memory;
import run.endive.runtime.TrapException;
import run.endive.wasm.types.FunctionType;
import run.endive.wasm.types.ValType;

public final class CanonicalAbi {

    /**
     * The Component Model caps individual lists at this many bytes so that {@code 2 *
     * length} can never overflow the {@code i32} realloc arguments used to allocate
     * them.
     */
    private static final int MAX_LIST_BYTE_LENGTH = (1 << 28) - 1;

    /** Same overflow-avoidance reasoning as {@link #MAX_LIST_BYTE_LENGTH}, for strings. */
    private static final int MAX_STRING_BYTE_LENGTH = (1 << 28) - 1;

    private static final int CANONICAL_FLOAT32_NAN_BITS = 0x7fc00000;
    private static final long CANONICAL_FLOAT64_NAN_BITS = 0x7ff8000000000000L;
    private static final int MIN_SURROGATE = 0xD800;
    private static final int MAX_SURROGATE = 0xDFFF;
    private static final int MAX_UNICODE_SCALAR_VALUE = 0x110000;

    private static final long[] EMPTY_CORE_VALUES = new long[0];

    public static final int MAX_FLAT_PARAMS = 16;
    public static final int MAX_FLAT_ASYNC_PARAMS = 4;
    public static final int MAX_FLAT_RESULTS = 1;

    private CanonicalAbi() {}

    public static DefValType despecialize(DefValType t) {
        if (t instanceof Specialized<?>) {
            return ((Specialized<?>) t).despecialize();
        }
        return t;
    }

    public static boolean containsBorrow(TypeResolver typeResolver, DefValType t) {
        return contains(typeResolver, t, u -> u.kind() == DefValType.Kind.BORROW);
    }

    public static boolean containsAsyncValue(TypeResolver typeResolver, DefValType t) {
        return contains(
                typeResolver,
                t,
                u -> u.kind() == DefValType.Kind.STREAM || u.kind() == DefValType.Kind.FUTURE);
    }

    public static boolean contains(
            TypeResolver typeResolver, DefValType t, Predicate<DefValType> p) {
        if (t == null) {
            return false;
        }
        var d = despecialize(t);
        switch (d.kind()) {
            case LIST:
                if (d instanceof ListType) {
                    var list = (ListType) d;
                    return p.test(d)
                            || contains(
                                    typeResolver,
                                    typeResolver.resolveDefValType(list.elementType()),
                                    p);
                } else if (d instanceof MapType.DespecializedMapType) {
                    var record = ((MapType.DespecializedMapType) d).recordType();
                    return p.test(d)
                            || record.fields().stream()
                                    .anyMatch(
                                            f ->
                                                    contains(
                                                            typeResolver,
                                                            typeResolver.resolveDefValType(
                                                                    f.valType()),
                                                            p));
                }
                throw new IllegalStateException("unhandled LIST-kind type " + d.getClass());
            case STREAM:
                var stream = (StreamType) d;
                return p.test(d)
                        || (stream.hasElementType()
                                && contains(
                                        typeResolver,
                                        typeResolver.resolveDefValType(stream.elementType()),
                                        p));
            case FUTURE:
                var future = (FutureType) d;
                return p.test(d)
                        || (future.hasElementType()
                                && contains(
                                        typeResolver,
                                        typeResolver.resolveDefValType(future.elementType()),
                                        p));
            case RECORD:
                var record = (RecordType) d;
                return p.test(d)
                        || record.fields().stream()
                                .anyMatch(
                                        f ->
                                                contains(
                                                        typeResolver,
                                                        typeResolver.resolveDefValType(f.valType()),
                                                        p));
            case VARIANT:
                var variant = (VariantType) d;
                return p.test(d)
                        || variant.cases().stream()
                                .anyMatch(
                                        c ->
                                                c.hasValType()
                                                        && contains(
                                                                typeResolver,
                                                                typeResolver.resolveDefValType(
                                                                        c.valType()),
                                                                p));
            default:
                return p.test(d);
        }
    }

    /**
     * The flattened core Wasm signature a component-level function type compiles down
     * to. When the flat parameter/result list would be too large to pass in
     * registers/stack, it collapses to a single pointer (the caller/callee agree to
     * pass/receive the value(s) via memory instead) — where that pointer goes differs
     * between lifting a core function into a component function ({@link Direction#LIFT})
     * and lowering a component function into a core function ({@link Direction#LOWER}).
     */
    public static FunctionType flattenFuncType(
            LiftLowerContext ctx, FuncType ft, Direction direction) {
        // TODO - Might be able to remove direction - not sure we ever actually need this during
        // lifting unless we need
        // to validate that the signature of the actual core function being lifted matches the
        // expected ABI signature
        List<ValType> flatParams =
                new ArrayList<>(flattenParams(ctx.typeResolver(), ctx.ptrType(), ft));
        List<ValType> flatResults =
                new ArrayList<>(flattenResult(ctx.typeResolver(), ctx.ptrType(), ft));
        if (!ctx.isAsync()) {
            if (flatParams.size() > MAX_FLAT_PARAMS) {
                flatParams = new ArrayList<>(List.of(ctx.ptrType().coreValType()));
            }
            if (flatResults.size() > MAX_FLAT_RESULTS) {
                switch (direction) {
                    case LIFT:
                        flatResults = new ArrayList<>(List.of(ctx.ptrType().coreValType()));
                        break;
                    case LOWER:
                        flatParams.add(ctx.ptrType().coreValType());
                        flatResults = new ArrayList<>();
                        break;
                    default:
                        throw new IllegalStateException("unhandled direction " + direction);
                }
            }
            return FunctionType.of(flatParams, flatResults);
        }
        switch (direction) {
            case LIFT:
                if (flatParams.size() > MAX_FLAT_PARAMS) {
                    flatParams = new ArrayList<>(List.of(ctx.ptrType().coreValType()));
                }
                flatResults = new ArrayList<>(List.of(ValType.I32));
                break;
            case LOWER:
                if (flatParams.size() > MAX_FLAT_ASYNC_PARAMS) {
                    flatParams = new ArrayList<>(List.of(ctx.ptrType().coreValType()));
                }
                if (!flatResults.isEmpty()) {
                    flatParams.add(ctx.ptrType().coreValType());
                }
                flatResults = new ArrayList<>(List.of(ValType.I32));
                break;
            default:
                throw new IllegalStateException("unhandled direction " + direction);
        }
        return FunctionType.of(flatParams, flatResults);
    }

    private static List<ValType> flattenParams(
            TypeResolver typeResolver, PointerType ptrType, FuncType ft) {
        List<ValType> flat = new ArrayList<>();
        for (LabelValType p : ft.params()) {
            flat.addAll(typeResolver.resolveDefValType(p.valType()).flatten(typeResolver, ptrType));
        }
        return flat;
    }

    private static List<ValType> flattenResult(
            TypeResolver typeResolver, PointerType ptrType, FuncType ft) {
        if (!ft.hasResult()) {
            return List.of();
        }
        return typeResolver.resolveDefValType(ft.result()).flatten(typeResolver, ptrType);
    }

    /** Flattens each of {@code ts} in turn and concatenates the results ({@code flatten_types}). */
    private static List<ValType> flattenTypes(
            LiftLowerContext ctx, List<run.endive.cm.types.ValType> ts) {
        List<ValType> flat = new ArrayList<>();
        for (run.endive.cm.types.ValType t : ts) {
            flat.addAll(
                    ctx.typeResolver()
                            .resolveDefValType(t)
                            .flatten(ctx.typeResolver(), ctx.ptrType()));
        }
        return flat;
    }

    /**
     * Bundles {@code ts} into a {@link TupleType} ({@code "0"}, {@code "1"}, … fields),
     * the type used to load/store a whole parameter or result list from a single spill
     * buffer when it is too large to pass as flat core values.
     */
    private static TupleType tupleTypeOf(List<run.endive.cm.types.ValType> ts) {
        var builder = TupleType.builder();
        for (run.endive.cm.types.ValType t : ts) {
            builder.addElementType(t);
        }
        return builder.build();
    }

    public static Object load(LiftLowerContext ctx, int ptr, DefValType t) {
        var d = despecialize(t);
        switch (d.kind()) {
            case BOOL:
                return convertIntToBool(loadInt(ctx.memory(), ptr, 1, false));
            case U8:
                return boxUnsigned(d.kind(), loadInt(ctx.memory(), ptr, 1, false));
            case U16:
                return boxUnsigned(d.kind(), loadInt(ctx.memory(), ptr, 2, false));
            case U32:
                return boxUnsigned(d.kind(), loadInt(ctx.memory(), ptr, 4, false));
            case U64:
                return boxUnsigned(d.kind(), loadInt(ctx.memory(), ptr, 8, false));
            case S8:
                return boxSigned(d.kind(), loadInt(ctx.memory(), ptr, 1, true));
            case S16:
                return boxSigned(d.kind(), loadInt(ctx.memory(), ptr, 2, true));
            case S32:
                return boxSigned(d.kind(), loadInt(ctx.memory(), ptr, 4, true));
            case S64:
                return boxSigned(d.kind(), loadInt(ctx.memory(), ptr, 8, true));
            case F32:
                return canonicalizeNan32(ctx.memory().readFloat(ptr));
            case F64:
                return canonicalizeNan64(ctx.memory().readDouble(ptr));
            case CHAR:
                return convertI32ToChar(loadInt(ctx.memory(), ptr, 4, false));
            case STRING:
                return loadString(ctx, ptr);
            case LIST:
                return loadList(ctx, ptr, d);
            case RECORD:
                return loadRecord(ctx, ptr, (RecordType) d);
            case VARIANT:
                return loadVariant(ctx, ptr, (VariantType) d);
            case FLAGS:
                return loadFlags(ctx, ptr, (FlagsType) d);
            case ERROR_CONTEXT:
            case OWN:
            case BORROW:
            case STREAM:
            case FUTURE:
                throw new UnsupportedOperationException(
                        "loading " + d.kind() + " values is not implemented yet");
            default:
                throw new IllegalStateException("unhandled kind " + d.kind());
        }
    }

    private static long loadInt(Memory memory, int ptr, int nbytes, boolean signed) {
        switch (nbytes) {
            case 1:
                return signed ? memory.read(ptr) : memory.readU8(ptr);
            case 2:
                return signed ? memory.readShort(ptr) : memory.readU16(ptr);
            case 4:
                return signed ? memory.readInt(ptr) : memory.readU32(ptr);
            case 8:
                return memory.readLong(ptr);
            default:
                throw new IllegalArgumentException("unsupported int width " + nbytes);
        }
    }

    private static boolean convertIntToBool(long i) {
        return i != 0;
    }

    private static float canonicalizeNan32(float f) {
        return Float.isNaN(f) ? Float.intBitsToFloat(CANONICAL_FLOAT32_NAN_BITS) : f;
    }

    private static double canonicalizeNan64(double f) {
        return Double.isNaN(f) ? Double.longBitsToDouble(CANONICAL_FLOAT64_NAN_BITS) : f;
    }

    private static int convertI32ToChar(long i) {
        if (i >= MAX_UNICODE_SCALAR_VALUE) {
            throw new TrapException("char codepoint " + i + " exceeds the maximum scalar value");
        }
        if (i >= MIN_SURROGATE && i <= MAX_SURROGATE) {
            throw new TrapException("char codepoint " + i + " is a surrogate, which is invalid");
        }
        return (int) i;
    }

    private static List<Object> loadList(LiftLowerContext ctx, int ptr, DefValType d) {
        if (d instanceof ListType) {
            var t = (ListType) d;
            var elemType = ctx.typeResolver().resolveDefValType(t.elementType());
            if (t.isFixedSize()) {
                return loadListElements(ctx, ptr, t.size(), elemType);
            }
            return loadUnboundedList(ctx, ptr, elemType);
        }
        if (d instanceof MapType.DespecializedMapType) {
            return loadUnboundedList(ctx, ptr, ((MapType.DespecializedMapType) d).recordType());
        }
        throw new IllegalStateException("unhandled LIST-kind type " + d.getClass());
    }

    private static List<Object> loadUnboundedList(
            LiftLowerContext ctx, int ptr, DefValType elemType) {
        int ptrSize = ctx.ptrType().size();
        int begin = (int) loadInt(ctx.memory(), ptr, ptrSize, false);
        int length = (int) loadInt(ctx.memory(), ptr + ptrSize, ptrSize, false);
        return loadListFromRange(ctx, begin, length, elemType);
    }

    private static List<Object> loadListFromRange(
            LiftLowerContext ctx, int ptr, int length, DefValType elemType) {
        int elemSize = elemType.elementSize(ctx.typeResolver(), ctx.ptrType());
        int elemAlignment = elemType.alignment(ctx.typeResolver(), ctx.ptrType());
        if ((long) length * elemSize > MAX_LIST_BYTE_LENGTH) {
            throw new TrapException(
                    "list byte length exceeds the maximum of " + MAX_LIST_BYTE_LENGTH);
        }
        if (ptr != DefValType.alignTo(ptr, elemAlignment)) {
            throw new TrapException("list pointer " + ptr + " is not aligned to " + elemAlignment);
        }
        if ((long) ptr + (long) length * elemSize > Memory.bytes(ctx.memory().pages())) {
            throw new TrapException(
                    "list of length " + length + " at " + ptr + " is out of bounds");
        }
        return loadListElements(ctx, ptr, length, elemType);
    }

    private static List<Object> loadListElements(
            LiftLowerContext ctx, int ptr, int length, DefValType elemType) {
        int elemSize = elemType.elementSize(ctx.typeResolver(), ctx.ptrType());
        List<Object> result = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            result.add(load(ctx, ptr + i * elemSize, elemType));
        }
        return result;
    }

    private static Map<String, Object> loadRecord(LiftLowerContext ctx, int ptr, RecordType t) {
        Map<String, Object> record = new LinkedHashMap<>();
        int p = ptr;
        for (LabelValType f : t.fields()) {
            var fieldType = ctx.typeResolver().resolveDefValType(f.valType());
            p = DefValType.alignTo(p, fieldType.alignment(ctx.typeResolver(), ctx.ptrType()));
            record.put(f.label(), load(ctx, p, fieldType));
            p += fieldType.elementSize(ctx.typeResolver(), ctx.ptrType());
        }
        return record;
    }

    private static VariantValue loadVariant(LiftLowerContext ctx, int ptr, VariantType t) {
        var cases = t.cases();
        int discSize = t.discriminantType().elementSize(ctx.typeResolver(), ctx.ptrType());
        long caseIndex = loadInt(ctx.memory(), ptr, discSize, false);
        if (caseIndex >= cases.size()) {
            throw new TrapException("variant case index " + caseIndex + " is out of range");
        }
        var c = cases.get((int) caseIndex);
        int payloadPtr =
                DefValType.alignTo(
                        ptr + discSize, t.maxCaseAlignment(ctx.typeResolver(), ctx.ptrType()));
        if (!c.hasValType()) {
            return VariantValue.of(c.label(), null);
        }
        return VariantValue.of(
                c.label(),
                load(ctx, payloadPtr, ctx.typeResolver().resolveDefValType(c.valType())));
    }

    private static Map<String, Boolean> loadFlags(LiftLowerContext ctx, int ptr, FlagsType t) {
        long i =
                loadInt(ctx.memory(), ptr, t.elementSize(ctx.typeResolver(), ctx.ptrType()), false);
        return unpackFlagsFromInt(i, t.labels());
    }

    private static Map<String, Boolean> unpackFlagsFromInt(long i, List<String> labels) {
        Map<String, Boolean> record = new LinkedHashMap<>();
        for (String label : labels) {
            record.put(label, (i & 1) != 0);
            i >>= 1;
        }
        return record;
    }

    private static long utf16Tag(PointerType ptrType) {
        return 1L << (ptrType.size() * 8 - 1);
    }

    private static String loadString(LiftLowerContext ctx, int ptr) {
        int ptrSize = ctx.ptrType().size();
        long begin = loadInt(ctx.memory(), ptr, ptrSize, false);
        long taggedCodeUnits = loadInt(ctx.memory(), ptr + ptrSize, ptrSize, false);
        return loadStringFromRange(ctx, (int) begin, taggedCodeUnits);
    }

    private static String loadStringFromRange(LiftLowerContext ctx, int ptr, long taggedCodeUnits) {
        long tag = utf16Tag(ctx.ptrType());
        int alignment;
        long byteLength;
        Charset charset;
        switch (ctx.stringEncoding()) {
            case UTF8:
                alignment = 1;
                byteLength = taggedCodeUnits;
                charset = StandardCharsets.UTF_8;
                break;
            case UTF16:
                alignment = 2;
                byteLength = 2 * taggedCodeUnits;
                charset = StandardCharsets.UTF_16LE;
                break;
            case LATIN1_UTF16:
                alignment = 2;
                if ((taggedCodeUnits & tag) != 0) {
                    byteLength = 2 * (taggedCodeUnits ^ tag);
                    charset = StandardCharsets.UTF_16LE;
                } else {
                    byteLength = taggedCodeUnits;
                    charset = StandardCharsets.ISO_8859_1;
                }
                break;
            default:
                throw new IllegalStateException(
                        "unhandled string encoding " + ctx.stringEncoding());
        }

        if (byteLength > MAX_STRING_BYTE_LENGTH) {
            throw new TrapException(
                    "string byte length exceeds the maximum of " + MAX_STRING_BYTE_LENGTH);
        }
        if (ptr != DefValType.alignTo(ptr, alignment)) {
            throw new TrapException("string pointer " + ptr + " is not aligned to " + alignment);
        }
        if (ptr + byteLength > Memory.bytes(ctx.memory().pages())) {
            throw new TrapException(
                    "string of byte length " + byteLength + " at " + ptr + " is out of bounds");
        }
        byte[] bytes = ctx.memory().readBytes(ptr, (int) byteLength);
        return decodeStrict(bytes, charset);
    }

    private static String decodeStrict(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new TrapException("invalid " + charset.name() + " byte sequence");
        }
    }

    public static void store(LiftLowerContext ctx, Object v, DefValType t, int ptr) {
        var d = despecialize(t);
        switch (d.kind()) {
            case BOOL:
                storeInt(ctx.memory(), (Boolean) v ? 1 : 0, ptr, 1);
                return;
            case U8:
            case U16:
            case U32:
            case U64:
            case S8:
            case S16:
            case S32:
            case S64:
                storeInt(ctx.memory(), ((Number) v).longValue(), ptr, elemSizeForIntKind(d.kind()));
                return;
            case F32:
                ctx.memory().writeF32(ptr, canonicalizeNan32(((Number) v).floatValue()));
                return;
            case F64:
                ctx.memory().writeF64(ptr, canonicalizeNan64(((Number) v).doubleValue()));
                return;
            case CHAR:
                storeInt(ctx.memory(), (Integer) v, ptr, 4);
                return;
            case STRING:
                storeString(ctx, (String) v, ptr);
                return;
            case LIST:
                storeList(ctx, (List<?>) v, ptr, d);
                return;
            case RECORD:
                storeRecord(ctx, (Map<?, ?>) v, ptr, (RecordType) d);
                return;
            case VARIANT:
                storeVariant(ctx, (VariantValue) v, ptr, (VariantType) d);
                return;
            case FLAGS:
                storeFlags(ctx, (Map<?, ?>) v, ptr, (FlagsType) d);
                return;
            case ERROR_CONTEXT:
            case OWN:
            case BORROW:
            case STREAM:
            case FUTURE:
                throw new UnsupportedOperationException(
                        "storing " + d.kind() + " values is not implemented yet");
            default:
                throw new IllegalStateException("unhandled kind " + d.kind());
        }
    }

    private static int elemSizeForIntKind(DefValType.Kind kind) {
        switch (kind) {
            case U8:
            case S8:
                return 1;
            case U16:
            case S16:
                return 2;
            case U32:
            case S32:
                return 4;
            case U64:
            case S64:
                return 8;
            default:
                throw new IllegalArgumentException("not an integer kind: " + kind);
        }
    }

    private static void storeInt(Memory memory, long v, int ptr, int nbytes) {
        switch (nbytes) {
            case 1:
                memory.writeByte(ptr, (byte) v);
                return;
            case 2:
                memory.writeShort(ptr, (short) v);
                return;
            case 4:
                memory.writeI32(ptr, (int) v);
                return;
            case 8:
                memory.writeLong(ptr, v);
                return;
            default:
                throw new IllegalArgumentException("unsupported int width " + nbytes);
        }
    }

    private static void storeList(LiftLowerContext ctx, List<?> v, int ptr, DefValType d) {
        if (d instanceof ListType) {
            var t = (ListType) d;
            var elemType = ctx.typeResolver().resolveDefValType(t.elementType());
            if (t.isFixedSize()) {
                if (v.size() != t.size()) {
                    throw new IllegalArgumentException(
                            "expected "
                                    + t.size()
                                    + " elements for fixed-size list, got "
                                    + v.size());
                }
                storeListElements(ctx, v, ptr, elemType);
                return;
            }
            storeUnboundedList(ctx, v, ptr, elemType);
            return;
        }
        if (d instanceof MapType.DespecializedMapType) {
            storeUnboundedList(ctx, v, ptr, ((MapType.DespecializedMapType) d).recordType());
            return;
        }
        throw new IllegalStateException("unhandled LIST-kind type " + d.getClass());
    }

    private static void storeUnboundedList(
            LiftLowerContext ctx, List<?> v, int ptr, DefValType elemType) {
        int begin = storeListIntoRange(ctx, v, elemType);
        int ptrSize = ctx.ptrType().size();
        storeInt(ctx.memory(), begin, ptr, ptrSize);
        storeInt(ctx.memory(), v.size(), ptr + ptrSize, ptrSize);
    }

    private static int storeListIntoRange(LiftLowerContext ctx, List<?> v, DefValType elemType) {
        int elemSize = elemType.elementSize(ctx.typeResolver(), ctx.ptrType());
        long byteLength = (long) v.size() * elemSize;
        if (byteLength > MAX_LIST_BYTE_LENGTH) {
            throw new TrapException(
                    "list byte length exceeds the maximum of " + MAX_LIST_BYTE_LENGTH);
        }
        int align = elemType.alignment(ctx.typeResolver(), ctx.ptrType());
        int ptr = allocate(ctx, align, (int) byteLength);
        storeListElements(ctx, v, ptr, elemType);
        return ptr;
    }

    /**
     * Calls the context's {@code realloc} to allocate {@code size} fresh bytes and
     * validates the result, matching the bounds/alignment checks the Python reference
     * repeats after every {@code cx.opts.realloc(...)} call.
     */
    private static int allocate(LiftLowerContext ctx, int align, int size) {
        var realloc =
                Objects.requireNonNull(
                        ctx.realloc(), "storing this value requires a realloc in the context");
        int ptr = realloc.realloc(0, 0, align, size);
        if (ptr != DefValType.alignTo(ptr, align)) {
            throw new TrapException("realloc returned misaligned pointer " + ptr);
        }
        if ((long) ptr + size > Memory.bytes(ctx.memory().pages())) {
            throw new TrapException("realloc returned out-of-bounds pointer " + ptr);
        }
        return ptr;
    }

    private static void storeListElements(
            LiftLowerContext ctx, List<?> v, int ptr, DefValType elemType) {
        int elemSize = elemType.elementSize(ctx.typeResolver(), ctx.ptrType());
        int i = 0;
        for (Object e : v) {
            store(ctx, e, elemType, ptr + i * elemSize);
            i++;
        }
    }

    private static void storeRecord(LiftLowerContext ctx, Map<?, ?> v, int ptr, RecordType t) {
        int p = ptr;
        for (LabelValType f : t.fields()) {
            var fieldType = ctx.typeResolver().resolveDefValType(f.valType());
            p = DefValType.alignTo(p, fieldType.alignment(ctx.typeResolver(), ctx.ptrType()));
            store(ctx, v.get(f.label()), fieldType, p);
            p += fieldType.elementSize(ctx.typeResolver(), ctx.ptrType());
        }
    }

    private static void storeVariant(LiftLowerContext ctx, VariantValue v, int ptr, VariantType t) {
        var cases = t.cases();
        int caseIndex = -1;
        for (int i = 0; i < cases.size(); i++) {
            if (cases.get(i).label().equals(v.label())) {
                caseIndex = i;
                break;
            }
        }
        if (caseIndex < 0) {
            throw new IllegalArgumentException("no case labeled '" + v.label() + "' in variant");
        }
        int discSize = t.discriminantType().elementSize(ctx.typeResolver(), ctx.ptrType());
        storeInt(ctx.memory(), caseIndex, ptr, discSize);
        var c = cases.get(caseIndex);
        int payloadPtr =
                DefValType.alignTo(
                        ptr + discSize, t.maxCaseAlignment(ctx.typeResolver(), ctx.ptrType()));
        if (c.hasValType()) {
            store(ctx, v.value(), ctx.typeResolver().resolveDefValType(c.valType()), payloadPtr);
        }
    }

    private static void storeFlags(LiftLowerContext ctx, Map<?, ?> v, int ptr, FlagsType t) {
        long i = packFlagsIntoInt(v, t.labels());
        storeInt(ctx.memory(), i, ptr, t.elementSize(ctx.typeResolver(), ctx.ptrType()));
    }

    private static long packFlagsIntoInt(Map<?, ?> v, List<String> labels) {
        long i = 0;
        int shift = 0;
        for (String label : labels) {
            if (Boolean.TRUE.equals(v.get(label))) {
                i |= 1L << shift;
            }
            shift++;
        }
        return i;
    }

    private static void storeString(LiftLowerContext ctx, String v, int ptr) {
        var result = storeStringIntoRange(ctx, v);
        int ptrSize = ctx.ptrType().size();
        storeInt(ctx.memory(), result.ptr, ptr, ptrSize);
        storeInt(ctx.memory(), result.codeUnits, ptr + ptrSize, ptrSize);
    }

    /**
     * The Python reference classifies the source value's own encoding to avoid
     * re-transcoding when it already matches the destination (e.g. a UTF-8 source
     * string copied verbatim into a UTF-8-encoded destination). That's purely a
     * performance optimization in a reference implementation with no other consumers —
     * it doesn't change the observable {@code (ptr, code_units)} result or memory
     * content for any source/destination combination, since the decoded {@link String}
     * is already the single source of truth by the time it reaches here. So this
     * dispatches on the destination encoding alone and always (re-)encodes directly
     * from {@code v}, which is simpler and produces identical results.
     */
    private static PtrAndCodeUnits storeStringIntoRange(LiftLowerContext ctx, String v) {
        switch (ctx.stringEncoding()) {
            case UTF8:
                return storeStringCopy(ctx, v, 1, StandardCharsets.UTF_8);
            case UTF16:
                return storeStringCopy(ctx, v, 2, StandardCharsets.UTF_16LE);
            case LATIN1_UTF16:
                return storeStringToLatin1OrUtf16(ctx, v);
            default:
                throw new IllegalStateException(
                        "unhandled string encoding " + ctx.stringEncoding());
        }
    }

    private static PtrAndCodeUnits storeStringCopy(
            LiftLowerContext ctx, String src, int dstCodeUnitSize, Charset dstCharset) {
        byte[] encoded = encodeStrict(src, dstCharset);
        int ptr = allocateAndWrite(ctx, dstCodeUnitSize, encoded);
        return new PtrAndCodeUnits(ptr, encoded.length / dstCodeUnitSize);
    }

    private static PtrAndCodeUnits storeStringToLatin1OrUtf16(LiftLowerContext ctx, String src) {
        boolean fitsLatin1 = src.chars().allMatch(c -> c < 0x100);
        if (fitsLatin1) {
            byte[] encoded = encodeStrict(src, StandardCharsets.ISO_8859_1);
            int ptr = allocateAndWrite(ctx, 2, encoded);
            return new PtrAndCodeUnits(ptr, encoded.length);
        }
        byte[] encoded = encodeStrict(src, StandardCharsets.UTF_16LE);
        int ptr = allocateAndWrite(ctx, 2, encoded);
        return new PtrAndCodeUnits(ptr, (encoded.length / 2) | utf16Tag(ctx.ptrType()));
    }

    private static byte[] encodeStrict(String src, Charset charset) {
        try {
            var buf =
                    charset.newEncoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .encode(CharBuffer.wrap(src));
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            return bytes;
        } catch (CharacterCodingException e) {
            throw new TrapException("string is not valid for encoding " + charset.name());
        }
    }

    private static int allocateAndWrite(LiftLowerContext ctx, int alignment, byte[] bytes) {
        if (bytes.length > MAX_STRING_BYTE_LENGTH) {
            throw new TrapException(
                    "string byte length exceeds the maximum of " + MAX_STRING_BYTE_LENGTH);
        }
        int ptr = allocate(ctx, alignment, bytes.length);
        ctx.memory().write(ptr, bytes);
        return ptr;
    }

    /** {@code (ptr, code_units)} pair returned by the {@code store_string*} family. */
    private static final class PtrAndCodeUnits {
        final int ptr;
        final long codeUnits;

        PtrAndCodeUnits(int ptr, long codeUnits) {
            this.ptr = ptr;
            this.codeUnits = codeUnits;
        }
    }

    /**
     * Consumes core Wasm values one at a time from a flat argument or result list. Endive
     * represents every core value as a primitive {@code long}: an {@code i32} in the low
     * 32 bits, an {@code i64} as the whole long, an {@code f32} as its raw bits (via
     * {@link Float#floatToRawIntBits}) in the low 32 bits, and an {@code f64} as its raw
     * bits (via {@link Double#doubleToRawLongBits}). Because each {@link #liftFlat} leaf
     * decodes its own bit pattern, the reader hands back the raw long untouched and no
     * per-value type dispatch is needed.
     *
     * <p>Package-private: callers work with plain {@code long[]} through {@link
     * #liftFlatParams}/{@link #liftFlatResults}; this cursor is an internal lifting detail.
     */
    static final class CoreValues {
        private final long[] values;
        private int pos;

        CoreValues(long[] values) {
            this.values = values;
        }

        long next() {
            if (pos >= values.length) {
                throw new IllegalStateException("no more core values to lift");
            }
            return values[pos++];
        }

        int position() {
            return pos;
        }

        void skipTo(int newPos) {
            if (newPos > values.length) {
                throw new IllegalStateException("cannot skip past the end of the core values");
            }
            pos = newPos;
        }
    }

    /**
     * The maximum number of core values a function's parameters may flatten to before they
     * are passed indirectly through linear memory ({@code MAX_FLAT_ASYNC_PARAMS} for async
     * calls, {@code MAX_FLAT_PARAMS} otherwise).
     */
    private static int maxFlatParams(LiftLowerContext ctx) {
        return ctx.isAsync() ? MAX_FLAT_ASYNC_PARAMS : MAX_FLAT_PARAMS;
    }

    /**
     * The maximum number of core values a function's results may flatten to before they are
     * passed indirectly through linear memory ({@code 0} for async calls, {@code
     * MAX_FLAT_RESULTS} otherwise).
     */
    private static int maxFlatResults(LiftLowerContext ctx) {
        return ctx.isAsync() ? 0 : MAX_FLAT_RESULTS;
    }

    /**
     * Lifts a function's flat core <em>parameters</em> {@code flatArgs} into the
     * component-level values of types {@code ts}.
     *
     * @see #liftFlatValues
     */
    public static List<Object> liftFlatParams(
            LiftLowerContext ctx, long[] flatArgs, List<run.endive.cm.types.ValType> ts) {
        return liftFlatValues(ctx, maxFlatParams(ctx), new CoreValues(flatArgs), ts);
    }

    /**
     * Lifts a function's flat core <em>results</em> {@code flatResults} into the
     * component-level values of types {@code ts}.
     *
     * @see #liftFlatValues
     */
    public static List<Object> liftFlatResults(
            LiftLowerContext ctx, long[] flatResults, List<run.endive.cm.types.ValType> ts) {
        return liftFlatValues(ctx, maxFlatResults(ctx), new CoreValues(flatResults), ts);
    }

    /**
     * Lifts a flat list of core parameters or results (delivered by {@code vi}) into the
     * component-level values of types {@code ts}. When the flattened form exceeds {@code
     * maxFlat} core values, the values were passed indirectly through linear memory and
     * {@code vi} yields only a pointer to the spilled tuple.
     */
    private static List<Object> liftFlatValues(
            LiftLowerContext ctx,
            int maxFlat,
            CoreValues vi,
            List<run.endive.cm.types.ValType> ts) {
        List<ValType> flatTypes = flattenTypes(ctx, ts);
        if (flatTypes.size() > maxFlat) {
            var tupleType = tupleTypeOf(ts);
            int align = tupleType.alignment(ctx.typeResolver(), ctx.ptrType());
            int size = tupleType.elementSize(ctx.typeResolver(), ctx.ptrType());
            int ptr = (int) vi.next();
            if (ptr != DefValType.alignTo(ptr, align)) {
                throw new TrapException(
                        "spilled values pointer " + ptr + " is not aligned to " + align);
            }
            if ((long) ptr + size > Memory.bytes(ctx.memory().pages())) {
                throw new TrapException("spilled values at " + ptr + " are out of bounds");
            }
            var record = (Map<?, ?>) load(ctx, ptr, tupleType);
            return new ArrayList<>(record.values());
        }
        List<Object> result = new ArrayList<>(ts.size());
        for (run.endive.cm.types.ValType t : ts) {
            result.add(liftFlat(ctx, vi, ctx.typeResolver().resolveDefValType(t)));
        }
        return result;
    }

    static Object liftFlat(LiftLowerContext ctx, CoreValues vi, DefValType t) {
        var d = despecialize(t);
        switch (d.kind()) {
            case BOOL:
                return convertIntToBool(vi.next() & 0xFFFFFFFFL);
            case U8:
            case U16:
            case U32:
            case U64:
                return liftFlatUnsigned(vi, d.kind());
            case S8:
            case S16:
            case S32:
            case S64:
                return liftFlatSigned(vi, d.kind());
            case F32:
                return decodeI32AsFloat(vi.next());
            case F64:
                return decodeI64AsFloat(vi.next());
            case CHAR:
                return convertI32ToChar(vi.next() & 0xFFFFFFFFL);
            case STRING:
                return liftFlatString(ctx, vi);
            case LIST:
                return liftFlatList(ctx, vi, d);
            case RECORD:
                return liftFlatRecord(ctx, vi, (RecordType) d);
            case VARIANT:
                return liftFlatVariant(ctx, vi, (VariantType) d);
            case FLAGS:
                return liftFlatFlags(vi, (FlagsType) d);
            case ERROR_CONTEXT:
            case OWN:
            case BORROW:
            case STREAM:
            case FUTURE:
                throw new UnsupportedOperationException(
                        "lifting " + d.kind() + " values is not implemented yet");
            default:
                throw new IllegalStateException("unhandled kind " + d.kind());
        }
    }

    private static Number liftFlatUnsigned(CoreValues vi, DefValType.Kind kind) {
        switch (kind) {
            case U8:
                return boxUnsigned(kind, unsignedBits(vi, 32, 8));
            case U16:
                return boxUnsigned(kind, unsignedBits(vi, 32, 16));
            case U32:
                return boxUnsigned(kind, unsignedBits(vi, 32, 32));
            case U64:
                return boxUnsigned(kind, unsignedBits(vi, 64, 64));
            default:
                throw new IllegalArgumentException("not an unsigned integer kind: " + kind);
        }
    }

    private static Number liftFlatSigned(CoreValues vi, DefValType.Kind kind) {
        switch (kind) {
            case S8:
                return boxSigned(kind, signedBits(vi, 32, 8));
            case S16:
                return boxSigned(kind, signedBits(vi, 32, 16));
            case S32:
                return boxSigned(kind, signedBits(vi, 32, 32));
            case S64:
                return boxSigned(kind, signedBits(vi, 64, 64));
            default:
                throw new IllegalArgumentException("not a signed integer kind: " + kind);
        }
    }

    /**
     * Boxes an already width-normalized unsigned integer into the Java numeric wrapper the host
     * binds to the given component type (see {@code PrimitiveHostTypeDescriptor}):
     * {@code u8 -> Short}, {@code u16 -> Integer}, {@code u32 -> Long}, and {@code u64 -> BigInteger}
     * (the ambiguous {@code u64} case, which could also bind to {@code Long}, is resolved to
     * {@code BigInteger}). Shared by the flat-lift and memory-load paths so both yield the same
     * wrapper types.
     */
    private static Number boxUnsigned(DefValType.Kind kind, long bits) {
        switch (kind) {
            case U8:
                return (short) bits;
            case U16:
                return (int) bits;
            case U32:
                return bits;
            case U64:
                return toUnsignedBigInteger(bits);
            default:
                throw new IllegalArgumentException("not an unsigned integer kind: " + kind);
        }
    }

    /**
     * Boxes an already sign-extended signed integer into the Java numeric wrapper the host binds to
     * the given component type (see {@code PrimitiveHostTypeDescriptor}): {@code s8 -> Byte},
     * {@code s16 -> Short}, {@code s32 -> Integer}, and {@code s64 -> Long}. Shared by the flat-lift
     * and memory-load paths so both yield the same wrapper types.
     */
    private static Number boxSigned(DefValType.Kind kind, long bits) {
        switch (kind) {
            case S8:
                return (byte) bits;
            case S16:
                return (short) bits;
            case S32:
                return (int) bits;
            case S64:
                return bits;
            default:
                throw new IllegalArgumentException("not a signed integer kind: " + kind);
        }
    }

    private static long unsignedBits(CoreValues vi, int coreWidth, int tWidth) {
        long i = coreWidth == 32 ? (vi.next() & 0xFFFFFFFFL) : vi.next();
        if (tWidth >= coreWidth) {
            return i;
        }
        long mask = (1L << tWidth) - 1;
        return i & mask;
    }

    private static long signedBits(CoreValues vi, int coreWidth, int tWidth) {
        long i = unsignedBits(vi, coreWidth, tWidth);
        if (tWidth == 64) {
            return i;
        }
        long signBit = 1L << (tWidth - 1);
        return (i & signBit) != 0 ? i - (1L << tWidth) : i;
    }

    private static BigInteger toUnsignedBigInteger(long bits) {
        return bits >= 0
                ? BigInteger.valueOf(bits)
                : BigInteger.valueOf(bits & Long.MAX_VALUE).setBit(Long.SIZE - 1);
    }

    /**
     * Reinterprets the raw bits of a core value as an {@code f32}. Endive packs an
     * {@code f32} into the low 32 bits of a {@code long} (see {@link CoreValues}), so a
     * value delivered through an {@code i32} or {@code i64} joined variant slot decodes
     * identically — this subsumes the {@code i32→f32} and {@code i64→f32} coercions the
     * Python reference performs explicitly.
     */
    private static float decodeI32AsFloat(long bits) {
        return canonicalizeNan32(Float.intBitsToFloat((int) bits));
    }

    private static double decodeI64AsFloat(long bits) {
        return canonicalizeNan64(Double.longBitsToDouble(bits));
    }

    /**
     * Encodes an {@code f32} into the low 32 bits of a {@code long}, zero-extended so the
     * upper 32 bits are clear. Zero-extension matters when the value lands in an
     * {@code i64} joined variant slot, where the Canonical ABI expects the unsigned
     * 32-bit bit pattern rather than a sign-extended one.
     */
    private static long encodeFloatAsI32(float f) {
        return Integer.toUnsignedLong(Float.floatToRawIntBits(canonicalizeNan32(f)));
    }

    private static long encodeFloatAsI64(double f) {
        return Double.doubleToRawLongBits(canonicalizeNan64(f));
    }

    private static String liftFlatString(LiftLowerContext ctx, CoreValues vi) {
        int ptr = (int) vi.next();
        long packedLength = vi.next() & 0xFFFFFFFFL;
        return loadStringFromRange(ctx, ptr, packedLength);
    }

    private static List<Object> liftFlatList(LiftLowerContext ctx, CoreValues vi, DefValType d) {
        if (d instanceof ListType) {
            var t = (ListType) d;
            var elemType = ctx.typeResolver().resolveDefValType(t.elementType());
            if (t.isFixedSize()) {
                List<Object> a = new ArrayList<>(t.size());
                for (int i = 0; i < t.size(); i++) {
                    a.add(liftFlat(ctx, vi, elemType));
                }
                return a;
            }
            int ptr = (int) vi.next();
            int length = (int) vi.next();
            return loadListFromRange(ctx, ptr, length, elemType);
        }
        if (d instanceof MapType.DespecializedMapType) {
            int ptr = (int) vi.next();
            int length = (int) vi.next();
            return loadListFromRange(
                    ctx, ptr, length, ((MapType.DespecializedMapType) d).recordType());
        }
        throw new IllegalStateException("unhandled LIST-kind type " + d.getClass());
    }

    private static Map<String, Object> liftFlatRecord(
            LiftLowerContext ctx, CoreValues vi, RecordType t) {
        Map<String, Object> record = new LinkedHashMap<>();
        for (LabelValType f : t.fields()) {
            var fieldType = ctx.typeResolver().resolveDefValType(f.valType());
            record.put(f.label(), liftFlat(ctx, vi, fieldType));
        }
        return record;
    }

    /**
     * Lifts a variant, consuming exactly the flattened core types produced by {@code
     * flatten_variant} regardless of which case is present. Because a variant's flattened
     * payload is the elementwise "join" across all cases, a specific case's own flat
     * types can be narrower than the shared joined layout. In Endive's raw-{@code long}
     * representation this needs no per-value coercion: each {@link #liftFlat} leaf already
     * decodes its own bit pattern (masking to 32 bits for {@code i32}-shaped types,
     * reinterpreting float bits), so the same long read out of a wider joined slot lifts
     * correctly. We only have to skip whatever joined slots the chosen case left unused.
     */
    private static VariantValue liftFlatVariant(
            LiftLowerContext ctx, CoreValues vi, VariantType t) {
        var cases = t.cases();
        List<ValType> flatTypes = t.flatten(ctx.typeResolver(), ctx.ptrType());
        if (flatTypes.get(0) != ValType.I32) {
            throw new IllegalStateException("variant discriminant flat type must be i32");
        }
        int payloadSlots = flatTypes.size() - 1;
        long caseIndex = vi.next() & 0xFFFFFFFFL;
        if (caseIndex >= cases.size()) {
            throw new TrapException("variant case index " + caseIndex + " is out of range");
        }
        var c = cases.get((int) caseIndex);
        int payloadStart = vi.position();
        Object v;
        if (!c.hasValType()) {
            v = null;
        } else {
            v = liftFlat(ctx, vi, ctx.typeResolver().resolveDefValType(c.valType()));
        }
        vi.skipTo(payloadStart + payloadSlots);
        return VariantValue.of(c.label(), v);
    }

    private static Map<String, Boolean> liftFlatFlags(CoreValues vi, FlagsType t) {
        return unpackFlagsFromInt(vi.next() & 0xFFFFFFFFL, t.labels());
    }

    static long[] lowerFlat(LiftLowerContext ctx, Object v, DefValType t) {
        LongList out = new LongList();
        lowerFlatInto(ctx, v, t, out);
        return out.toArray();
    }

    /**
     * Lowers a function's <em>parameters</em> — the component-level values {@code vs} of
     * types {@code ts} — into a flat list of core values. When the parameters spill into
     * linear memory, storage is freshly allocated via {@code realloc} and the returned list
     * is the spill pointer.
     *
     * @see #lowerFlatValues
     */
    public static long[] lowerFlatParams(
            LiftLowerContext ctx, List<?> vs, List<run.endive.cm.types.ValType> ts) {
        return lowerFlatValues(ctx, maxFlatParams(ctx), vs, ts, null);
    }

    /**
     * Lowers a function's <em>results</em> — the component-level values {@code vs} of types
     * {@code ts} — into a flat list of core values. When the results spill into linear
     * memory and {@code outParam} is non-null, its first element is the caller-provided
     * spill pointer and no flat values are returned; when {@code outParam} is null, storage
     * is freshly allocated via {@code realloc} and the returned list is the spill pointer.
     *
     * @see #lowerFlatValues
     */
    public static long[] lowerFlatResults(
            LiftLowerContext ctx,
            List<?> vs,
            List<run.endive.cm.types.ValType> ts,
            long[] outParam) {
        return lowerFlatValues(ctx, maxFlatResults(ctx), vs, ts, outParam);
    }

    /**
     * Lowers the component-level values {@code vs} of types {@code ts} into a flat list of
     * core values. When the flattened form exceeds {@code maxFlat} core values, the values
     * are spilled into linear memory as a tuple: with no {@code outParam}, storage is
     * freshly allocated via {@code realloc} and the returned list is the spill pointer;
     * otherwise the caller-provided {@code outParam}'s first element is a pre-allocated
     * pointer and no flat values are returned.
     *
     * <p>The reference's {@code may_leave} guard against reentrant lowering is instance
     * state not yet modeled here, so it is omitted.
     */
    private static long[] lowerFlatValues(
            LiftLowerContext ctx,
            int maxFlat,
            List<?> vs,
            List<run.endive.cm.types.ValType> ts,
            long[] outParam) {

        if (vs.isEmpty()) {
            return EMPTY_CORE_VALUES;
        }

        List<ValType> flatTypes = flattenTypes(ctx, ts);
        if (flatTypes.size() > maxFlat) {
            var tupleType = tupleTypeOf(ts);
            Map<String, Object> tupleValue = new LinkedHashMap<>();
            for (int i = 0; i < vs.size(); i++) {
                tupleValue.put(Integer.toString(i), vs.get(i));
            }
            int align = tupleType.alignment(ctx.typeResolver(), ctx.ptrType());
            int size = tupleType.elementSize(ctx.typeResolver(), ctx.ptrType());
            int ptr;
            long[] flatVals;
            if (outParam == null) {
                ptr = allocate(ctx, align, size);
                flatVals = new long[] {Integer.toUnsignedLong(ptr)};
            } else {
                ptr = (int) outParam[0];
                if (ptr != DefValType.alignTo(ptr, align)) {
                    throw new TrapException(
                            "spill out-param pointer " + ptr + " is not aligned to " + align);
                }
                if ((long) ptr + size > Memory.bytes(ctx.memory().pages())) {
                    throw new TrapException("spill out-param at " + ptr + " is out of bounds");
                }
                flatVals = new long[0];
            }
            store(ctx, tupleValue, tupleType, ptr);
            return flatVals;
        }
        LongList out = new LongList();
        for (int i = 0; i < vs.size(); i++) {
            lowerFlatInto(ctx, vs.get(i), ctx.typeResolver().resolveDefValType(ts.get(i)), out);
        }
        return out.toArray();
    }

    private static void lowerFlatInto(LiftLowerContext ctx, Object v, DefValType t, LongList out) {
        var d = despecialize(t);
        switch (d.kind()) {
            case BOOL:
                out.add((Boolean) v ? 1L : 0L);
                return;
            case U8:
            case U16:
            case U32:
                out.add(((Number) v).longValue() & 0xFFFFFFFFL);
                return;
            case U64:
                out.add(((Number) v).longValue());
                return;
            case S8:
            case S16:
            case S32:
                out.add(lowerFlatSigned(((Number) v).longValue(), 32));
                return;
            case S64:
                out.add(lowerFlatSigned(((Number) v).longValue(), 64));
                return;
            case F32:
                out.add(encodeFloatAsI32(((Number) v).floatValue()));
                return;
            case F64:
                out.add(encodeFloatAsI64(((Number) v).doubleValue()));
                return;
            case CHAR:
                out.add(((Integer) v).longValue() & 0xFFFFFFFFL);
                return;
            case STRING:
                lowerFlatString(ctx, (String) v, out);
                return;
            case LIST:
                lowerFlatList(ctx, v, d, out);
                return;
            case RECORD:
                lowerFlatRecord(ctx, (Map<?, ?>) v, (RecordType) d, out);
                return;
            case VARIANT:
                lowerFlatVariant(ctx, (VariantValue) v, (VariantType) d, out);
                return;
            case FLAGS:
                out.add(packFlagsIntoInt((Map<?, ?>) v, ((FlagsType) d).labels()) & 0xFFFFFFFFL);
                return;
            case ERROR_CONTEXT:
            case OWN:
            case BORROW:
            case STREAM:
            case FUTURE:
                throw new UnsupportedOperationException(
                        "lowering " + d.kind() + " values is not implemented yet");
            default:
                throw new IllegalStateException("unhandled kind " + d.kind());
        }
    }

    /**
     * Applies the Canonical ABI's 2s-complement conversion of a signed component value
     * into an unsigned core value. For a 32-bit core value this masks to the low 32 bits
     * (zero-extending the two's-complement pattern into the long); for a 64-bit core
     * value the {@code long} already carries the correct pattern.
     */
    private static long lowerFlatSigned(long i, int coreBits) {
        return coreBits == 64 ? i : (i & 0xFFFFFFFFL);
    }

    private static void lowerFlatString(LiftLowerContext ctx, String v, LongList out) {
        var result = storeStringIntoRange(ctx, v);
        out.add(Integer.toUnsignedLong(result.ptr));
        out.add(result.codeUnits & 0xFFFFFFFFL);
    }

    private static void lowerFlatList(LiftLowerContext ctx, Object v, DefValType d, LongList out) {
        var list = (List<?>) v;
        if (d instanceof ListType) {
            var t = (ListType) d;
            var elemType = ctx.typeResolver().resolveDefValType(t.elementType());
            if (t.isFixedSize()) {
                if (list.size() != t.size()) {
                    throw new IllegalArgumentException(
                            "expected "
                                    + t.size()
                                    + " elements for fixed-size list, got "
                                    + list.size());
                }
                for (Object e : list) {
                    lowerFlatInto(ctx, e, elemType, out);
                }
                return;
            }
            int ptr = storeListIntoRange(ctx, list, elemType);
            out.add(Integer.toUnsignedLong(ptr));
            out.add(Integer.toUnsignedLong(list.size()));
            return;
        }
        if (d instanceof MapType.DespecializedMapType) {
            var recordType = ((MapType.DespecializedMapType) d).recordType();
            int ptr = storeListIntoRange(ctx, list, recordType);
            out.add(Integer.toUnsignedLong(ptr));
            out.add(Integer.toUnsignedLong(list.size()));
            return;
        }
        throw new IllegalStateException("unhandled LIST-kind type " + d.getClass());
    }

    private static void lowerFlatRecord(
            LiftLowerContext ctx, Map<?, ?> v, RecordType t, LongList out) {
        for (LabelValType f : t.fields()) {
            var fieldType = ctx.typeResolver().resolveDefValType(f.valType());
            lowerFlatInto(ctx, v.get(f.label()), fieldType, out);
        }
    }

    /**
     * Lowers a variant into the flattened core types produced by {@code flatten_variant}:
     * the discriminant followed by the joined payload slots. As with {@link
     * #liftFlatVariant}, the raw-{@code long} representation makes the joined-slot
     * coercions the Python reference performs (e.g. {@code f32→i64}) unnecessary — each
     * {@link #lowerFlatInto} leaf already emits the canonical unsigned bit pattern that
     * fits any wider joined slot. Unused trailing slots are padded with {@code 0}.
     */
    private static void lowerFlatVariant(
            LiftLowerContext ctx, VariantValue v, VariantType t, LongList out) {
        var cases = t.cases();
        int caseIndex = -1;
        for (int i = 0; i < cases.size(); i++) {
            if (cases.get(i).label().equals(v.label())) {
                caseIndex = i;
                break;
            }
        }
        if (caseIndex < 0) {
            throw new IllegalArgumentException("no case labeled '" + v.label() + "' in variant");
        }
        List<ValType> flatTypes = t.flatten(ctx.typeResolver(), ctx.ptrType());
        if (flatTypes.get(0) != ValType.I32) {
            throw new IllegalStateException("variant discriminant flat type must be i32");
        }
        int payloadSlots = flatTypes.size() - 1;
        out.add(Integer.toUnsignedLong(caseIndex));
        var c = cases.get(caseIndex);
        int payloadStart = out.size();
        if (c.hasValType()) {
            lowerFlatInto(ctx, v.value(), ctx.typeResolver().resolveDefValType(c.valType()), out);
        }
        for (int i = out.size() - payloadStart; i < payloadSlots; i++) {
            out.add(0L);
        }
    }

    /** A growable primitive-{@code long} buffer used to accumulate flat lowered values. */
    private static final class LongList {
        private long[] buf = new long[8];
        private int size;

        void add(long v) {
            if (size == buf.length) {
                buf = Arrays.copyOf(buf, buf.length * 2);
            }
            buf[size++] = v;
        }

        int size() {
            return size;
        }

        long[] toArray() {
            return Arrays.copyOf(buf, size);
        }
    }
}
