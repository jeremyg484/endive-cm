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
    static List<ValType> flattenTypes(LiftLowerContext ctx, List<run.endive.cm.types.ValType> ts) {
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
    static TupleType tupleTypeOf(List<run.endive.cm.types.ValType> ts) {
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

    static long loadInt(Memory memory, int ptr, int nbytes, boolean signed) {
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

    static int convertI32ToChar(long i) {
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
        var range = stringRange(ctx, taggedCodeUnits);
        return decodeStrict(readStringBytes(ctx, ptr, range), range.charset);
    }

    /**
     * Resolves a {@code (ptr, tagged_code_units)} pair against the context's string encoding
     * into the concrete byte range and charset it denotes, without touching memory. The
     * {@code latin1+utf16} encoding picks its charset from the high bit of {@code
     * taggedCodeUnits}; the other two are fixed.
     */
    static StringRange stringRange(LiftLowerContext ctx, long taggedCodeUnits) {
        switch (ctx.stringEncoding()) {
            case UTF8:
                return new StringRange(1, taggedCodeUnits, StandardCharsets.UTF_8);
            case UTF16:
                return new StringRange(2, 2 * taggedCodeUnits, StandardCharsets.UTF_16LE);
            case LATIN1_UTF16:
                long tag = utf16Tag(ctx.ptrType());
                if ((taggedCodeUnits & tag) != 0) {
                    return new StringRange(
                            2, 2 * (taggedCodeUnits ^ tag), StandardCharsets.UTF_16LE);
                }
                return new StringRange(taggedCodeUnits, StandardCharsets.ISO_8859_1);
            default:
                throw new IllegalStateException(
                        "unhandled string encoding " + ctx.stringEncoding());
        }
    }

    /** Applies the length, alignment and bounds traps guarding a string range, then reads it. */
    static byte[] readStringBytes(LiftLowerContext ctx, int ptr, StringRange range) {
        if (range.byteLength > MAX_STRING_BYTE_LENGTH) {
            throw new TrapException(
                    "string byte length exceeds the maximum of " + MAX_STRING_BYTE_LENGTH);
        }
        if (ptr != DefValType.alignTo(ptr, range.alignment)) {
            throw new TrapException(
                    "string pointer " + ptr + " is not aligned to " + range.alignment);
        }
        if (ptr + range.byteLength > Memory.bytes(ctx.memory().pages())) {
            throw new TrapException(
                    "string of byte length "
                            + range.byteLength
                            + " at "
                            + ptr
                            + " is out of bounds");
        }
        return ctx.memory().readBytes(ptr, (int) range.byteLength);
    }

    /** The byte range and charset a {@code (ptr, tagged_code_units)} pair resolves to. */
    static final class StringRange {
        final int alignment;
        final long byteLength;
        final Charset charset;

        StringRange(int alignment, long byteLength, Charset charset) {
            this.alignment = alignment;
            this.byteLength = byteLength;
            this.charset = charset;
        }

        StringRange(long byteLength, Charset charset) {
            this(2, byteLength, charset);
        }
    }

    private static String decodeStrict(byte[] bytes, Charset charset) {
        return decodeStrictToChars(bytes, charset).toString();
    }

    /**
     * Strictly decodes {@code bytes}, trapping on any malformed or unmappable input. Returns
     * the {@link CharBuffer} rather than a {@link String} so the transfer path can re-encode
     * without ever materializing one.
     */
    static CharBuffer decodeStrictToChars(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
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

    static void storeInt(Memory memory, long v, int ptr, int nbytes) {
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
    static PtrAndCodeUnits storeStringIntoRange(LiftLowerContext ctx, CharSequence v) {
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
            LiftLowerContext ctx, CharSequence src, int dstCodeUnitSize, Charset dstCharset) {
        byte[] encoded = encodeStrict(src, dstCharset);
        int ptr = allocateAndWrite(ctx, dstCodeUnitSize, encoded);
        return new PtrAndCodeUnits(ptr, encoded.length / dstCodeUnitSize);
    }

    private static PtrAndCodeUnits storeStringToLatin1OrUtf16(
            LiftLowerContext ctx, CharSequence src) {
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

    static byte[] encodeStrict(CharSequence src, Charset charset) {
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

    static int allocateAndWrite(LiftLowerContext ctx, int alignment, byte[] bytes) {
        if (bytes.length > MAX_STRING_BYTE_LENGTH) {
            throw new TrapException(
                    "string byte length exceeds the maximum of " + MAX_STRING_BYTE_LENGTH);
        }
        int ptr = allocate(ctx, alignment, bytes.length);
        ctx.memory().write(ptr, bytes);
        return ptr;
    }

    /** {@code (ptr, code_units)} pair returned by the {@code store_string*} family. */
    static final class PtrAndCodeUnits {
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

    // -------------------------------------------------------------------------------------
    // Direct transfer
    //
    // When a lifted component function is immediately lowered back into another core
    // module — the shape produced by wiring two core modules together through the
    // component's type — the lift/lower pair above round-trips every value through a Java
    // String, List, Map or boxed Number that nothing ever observes. The methods below move
    // the same values straight from the source memory into the destination memory,
    // collapsing whole records and lists into single copies where the Canonical ABI
    // guarantees the bytes are identical, and doing real work only where it does not
    // (string transcoding, pointer re-allocation, and the normalizations catalogued in
    // {@link Transferability}).
    // -------------------------------------------------------------------------------------

    /**
     * Bulk copies move through an intermediate {@code byte[]} because {@link Memory} exposes
     * no memory-to-memory primitive; chunking bounds that buffer instead of letting it scale
     * with the list being copied.
     */
    private static final int COPY_CHUNK_BYTES = 64 * 1024;

    /**
     * Whether {@code ft}'s values can move directly between {@code src} and {@code dst}
     * rather than through the lift/lower pair. A {@code false} result is always safe — the
     * caller simply keeps using {@link #liftFlatParams}/{@link #lowerFlatParams} — so this
     * rejects anything the transfer path does not yet model rather than trying to be
     * exhaustive.
     */
    public static boolean canTransfer(LiftLowerContext src, LiftLowerContext dst, FuncType ft) {
        if (src.isAsync() || dst.isAsync() || ft.isAsync()) {
            return false;
        }
        if (src.ptrType() != dst.ptrType()) {
            return false;
        }
        if (src.memory() == null || dst.memory() == null) {
            return false;
        }
        for (LabelValType p : ft.params()) {
            if (!Transferability.isSupported(
                    src.typeResolver(), src.typeResolver().resolveDefValType(p.valType()))) {
                return false;
            }
        }
        return !ft.hasResult()
                || Transferability.isSupported(
                        src.typeResolver(), src.typeResolver().resolveDefValType(ft.result()));
    }

    /**
     * Moves the value of type {@code t} at {@code srcPtr} in {@code src}'s memory to {@code
     * dstPtr} in {@code dst}'s memory, without materializing it as a Java value.
     *
     * <p>Observably equivalent to {@code store(dst, load(src, srcPtr, t), t, dstPtr)}, with
     * one deliberate exception: {@code f32}/{@code f64} NaN payloads are preserved rather
     * than canonicalized, which the Canonical ABI explicitly permits ("hosts may instead
     * choose to canonicalize to an arbitrary fixed NaN value, or even to the original value
     * of the NaN before lifting") and which is what lets float-bearing aggregates copy in
     * bulk. Every trap the lift path raises is raised here too.
     *
     * <p>Both contexts must agree on {@link LiftLowerContext#ptrType()}, since the whole
     * approach rests on a value occupying the same layout in both memories; {@code dst} must
     * supply a {@code realloc} if {@code t} contains a string or an unbounded list.
     */
    public static void transfer(
            LiftLowerContext src, LiftLowerContext dst, int srcPtr, int dstPtr, DefValType t) {
        requireTransferable(src, dst);
        var d = despecialize(t);
        if (Transferability.isBitwiseCopyable(src.typeResolver(), src.ptrType(), d)) {
            copyBytes(src, dst, srcPtr, dstPtr, d.elementSize(src.typeResolver(), src.ptrType()));
            return;
        }
        transferValue(src, dst, srcPtr, dstPtr, d);
    }

    /**
     * Transfers a function's flat core <em>parameters</em> {@code flatArgs} of types {@code
     * ts} from {@code src} to {@code dst}, returning the destination's flat parameters.
     *
     * @see #transfer
     */
    public static long[] transferFlatParams(
            LiftLowerContext src,
            LiftLowerContext dst,
            long[] flatArgs,
            List<run.endive.cm.types.ValType> ts) {
        requireTransferable(src, dst);
        return transferFlatValues(src, dst, MAX_FLAT_PARAMS, new CoreValues(flatArgs), ts, null);
    }

    /**
     * Transfers a function's flat core <em>results</em> {@code flatResults} of types {@code
     * ts} from {@code src} to {@code dst}. When the results spill into linear memory,
     * {@code outParam}'s first element is the destination's caller-provided spill pointer
     * and no flat values are returned; when it is null, destination storage is freshly
     * allocated and the returned list is the spill pointer.
     *
     * @see #transfer
     */
    public static long[] transferFlatResults(
            LiftLowerContext src,
            LiftLowerContext dst,
            long[] flatResults,
            List<run.endive.cm.types.ValType> ts,
            long[] outParam) {
        requireTransferable(src, dst);
        return transferFlatValues(
                src, dst, MAX_FLAT_RESULTS, new CoreValues(flatResults), ts, outParam);
    }

    /**
     * Rejects context pairs the transfer path cannot serve. A differing pointer width would
     * give the same value a different layout on each side, which every offset computation
     * below assumes away; async lifting and lowering are not modeled here yet.
     */
    static void requireTransferable(LiftLowerContext src, LiftLowerContext dst) {
        if (src.ptrType() != dst.ptrType()) {
            throw new IllegalArgumentException(
                    "cannot transfer between contexts with different pointer widths: "
                            + src.ptrType()
                            + " and "
                            + dst.ptrType());
        }
        if (src.isAsync() || dst.isAsync()) {
            throw new UnsupportedOperationException(
                    "transferring values between async contexts is not implemented yet");
        }
    }

    /**
     * Transfers a value that is known not to be wholly bitwise copyable, dispatching on its
     * kind. Source and destination offsets stay in lockstep throughout: the two memories lay
     * the value out identically, so only the base pointers differ.
     */
    static void transferValue(
            LiftLowerContext src, LiftLowerContext dst, int srcPtr, int dstPtr, DefValType t) {
        var d = despecialize(t);
        switch (d.kind()) {
            case BOOL:
                transferBool(src, dst, srcPtr, dstPtr);
                return;
            case U8:
            case S8:
                copyScalar(src, dst, srcPtr, dstPtr, 1);
                return;
            case U16:
            case S16:
                copyScalar(src, dst, srcPtr, dstPtr, 2);
                return;
            case U32:
            case S32:
            case F32:
                copyScalar(src, dst, srcPtr, dstPtr, 4);
                return;
            case U64:
            case S64:
            case F64:
                copyScalar(src, dst, srcPtr, dstPtr, 8);
                return;
            case CHAR:
                transferChar(src, dst, srcPtr, dstPtr);
                return;
            case STRING:
                transferString(src, dst, srcPtr, dstPtr);
                return;
            case LIST:
                transferList(src, dst, srcPtr, dstPtr, d);
                return;
            case RECORD:
                transferRecord(src, dst, srcPtr, dstPtr, (RecordType) d);
                return;
            case VARIANT:
                transferVariant(src, dst, srcPtr, dstPtr, (VariantType) d);
                return;
            case FLAGS:
                var flags = (FlagsType) d;
                transferFlags(
                        src,
                        dst,
                        srcPtr,
                        dstPtr,
                        flags.elementSize(src.typeResolver(), src.ptrType()),
                        Transferability.flagsMask(flags));
                return;
            case ERROR_CONTEXT:
            case OWN:
            case BORROW:
            case STREAM:
            case FUTURE:
                throw new UnsupportedOperationException(
                        "transferring " + d.kind() + " values is not implemented yet");
            default:
                throw new IllegalStateException("unhandled kind " + d.kind());
        }
    }

    /** Copies {@code length} bytes verbatim, in bounded chunks. */
    static void copyBytes(
            LiftLowerContext src, LiftLowerContext dst, int srcPtr, int dstPtr, int length) {
        var srcMemory = src.memory();
        var dstMemory = dst.memory();
        int copied = 0;
        while (copied < length) {
            int n = Math.min(COPY_CHUNK_BYTES, length - copied);
            dstMemory.write(dstPtr + copied, srcMemory.readBytes(srcPtr + copied, n));
            copied += n;
        }
    }

    /** Copies a single naturally-sized scalar, avoiding the {@code byte[]} bulk path. */
    static void copyScalar(
            LiftLowerContext src, LiftLowerContext dst, int srcPtr, int dstPtr, int nbytes) {
        switch (nbytes) {
            case 1:
                dst.memory().writeByte(dstPtr, src.memory().read(srcPtr));
                return;
            case 2:
                dst.memory().writeShort(dstPtr, src.memory().readShort(srcPtr));
                return;
            case 4:
                dst.memory().writeI32(dstPtr, src.memory().readInt(srcPtr));
                return;
            case 8:
                dst.memory().writeLong(dstPtr, src.memory().readLong(srcPtr));
                return;
            default:
                throw new IllegalArgumentException("unsupported scalar width " + nbytes);
        }
    }

    /** Normalizes any non-zero source byte to {@code 1}, as lifting then lowering would. */
    static void transferBool(LiftLowerContext src, LiftLowerContext dst, int srcPtr, int dstPtr) {
        dst.memory().writeByte(dstPtr, (byte) (src.memory().readU8(srcPtr) != 0 ? 1 : 0));
    }

    /** Copies a {@code char}, trapping on surrogates and out-of-range scalar values. */
    static void transferChar(LiftLowerContext src, LiftLowerContext dst, int srcPtr, int dstPtr) {
        int c = convertI32ToChar(loadInt(src.memory(), srcPtr, 4, false));
        storeInt(dst.memory(), c, dstPtr, 4);
    }

    /** Copies a {@code flags} value, dropping the slack bits lifting would discard. */
    static void transferFlags(
            LiftLowerContext src,
            LiftLowerContext dst,
            int srcPtr,
            int dstPtr,
            int size,
            long mask) {
        storeInt(dst.memory(), loadInt(src.memory(), srcPtr, size, false) & mask, dstPtr, size);
    }

    private static void transferRecord(
            LiftLowerContext src, LiftLowerContext dst, int srcPtr, int dstPtr, RecordType t) {
        int off = 0;
        for (LabelValType f : t.fields()) {
            var fieldType = src.typeResolver().resolveDefValType(f.valType());
            off = DefValType.alignTo(off, fieldType.alignment(src.typeResolver(), src.ptrType()));
            transferValue(src, dst, srcPtr + off, dstPtr + off, fieldType);
            off += fieldType.elementSize(src.typeResolver(), src.ptrType());
        }
    }

    /**
     * Transfers a variant, validating the discriminant and then only the payload of the case
     * it selects. The bytes of the wider cases the source did not use are left as the
     * destination allocator produced them, exactly as {@code store} leaves them.
     *
     * <p>The payload offset is computed relative to the value's own base rather than by
     * aligning the absolute pointer as {@code load}/{@code store} do. The two agree whenever
     * the pointer is aligned to the variant's alignment — which the Canonical ABI requires —
     * and only the relative form keeps the source and destination offsets identical.
     */
    private static void transferVariant(
            LiftLowerContext src, LiftLowerContext dst, int srcPtr, int dstPtr, VariantType t) {
        var cases = t.cases();
        int discSize = t.discriminantType().elementSize(src.typeResolver(), src.ptrType());
        long caseIndex = loadInt(src.memory(), srcPtr, discSize, false);
        if (caseIndex >= cases.size()) {
            throw new TrapException("variant case index " + caseIndex + " is out of range");
        }
        storeInt(dst.memory(), caseIndex, dstPtr, discSize);
        var c = cases.get((int) caseIndex);
        if (!c.hasValType()) {
            return;
        }
        int payloadOff =
                DefValType.alignTo(discSize, t.maxCaseAlignment(src.typeResolver(), src.ptrType()));
        transferValue(
                src,
                dst,
                srcPtr + payloadOff,
                dstPtr + payloadOff,
                src.typeResolver().resolveDefValType(c.valType()));
    }

    private static void transferList(
            LiftLowerContext src, LiftLowerContext dst, int srcPtr, int dstPtr, DefValType d) {
        if (d instanceof ListType) {
            var t = (ListType) d;
            var elemType = src.typeResolver().resolveDefValType(t.elementType());
            if (t.isFixedSize()) {
                transferListElements(src, dst, srcPtr, dstPtr, t.size(), elemType);
                return;
            }
            transferUnboundedList(src, dst, srcPtr, dstPtr, elemType);
            return;
        }
        if (d instanceof MapType.DespecializedMapType) {
            transferUnboundedList(
                    src, dst, srcPtr, dstPtr, ((MapType.DespecializedMapType) d).recordType());
            return;
        }
        throw new IllegalStateException("unhandled LIST-kind type " + d.getClass());
    }

    static void transferUnboundedList(
            LiftLowerContext src,
            LiftLowerContext dst,
            int srcPtr,
            int dstPtr,
            DefValType elemType) {
        int ptrSize = src.ptrType().size();
        int begin = (int) loadInt(src.memory(), srcPtr, ptrSize, false);
        int length = (int) loadInt(src.memory(), srcPtr + ptrSize, ptrSize, false);
        int dstBegin = transferListIntoRange(src, dst, begin, length, elemType);
        storeInt(dst.memory(), dstBegin, dstPtr, ptrSize);
        storeInt(dst.memory(), length, dstPtr + ptrSize, ptrSize);
    }

    /**
     * Validates a source list's range, allocates the matching range in the destination and
     * moves the elements into it, returning the destination pointer.
     *
     * <p>The length is treated as unsigned, so a list claiming more than {@code 2^31}
     * elements traps on the byte-length check. {@link #loadListFromRange} computes the same
     * product with a signed length and lets such a list through as if it were empty.
     */
    static int transferListIntoRange(
            LiftLowerContext src,
            LiftLowerContext dst,
            int begin,
            int length,
            DefValType elemType) {
        int elemSize = elemType.elementSize(src.typeResolver(), src.ptrType());
        int elemAlignment = elemType.alignment(src.typeResolver(), src.ptrType());
        long byteLength = Integer.toUnsignedLong(length) * elemSize;
        if (byteLength > MAX_LIST_BYTE_LENGTH) {
            throw new TrapException(
                    "list byte length exceeds the maximum of " + MAX_LIST_BYTE_LENGTH);
        }
        if (begin != DefValType.alignTo(begin, elemAlignment)) {
            throw new TrapException(
                    "list pointer " + begin + " is not aligned to " + elemAlignment);
        }
        if (Integer.toUnsignedLong(begin) + byteLength > Memory.bytes(src.memory().pages())) {
            throw new TrapException(
                    "list of length " + length + " at " + begin + " is out of bounds");
        }
        int dstBegin = allocate(dst, elemAlignment, (int) byteLength);
        transferListElements(src, dst, begin, dstBegin, length, elemType);
        return dstBegin;
    }

    /**
     * Moves {@code length} elements. When the element type copies verbatim the whole block
     * becomes one bulk copy — the case that makes {@code list<u8>}, {@code string} payloads
     * and {@code list<f32>} cheap.
     */
    private static void transferListElements(
            LiftLowerContext src,
            LiftLowerContext dst,
            int srcPtr,
            int dstPtr,
            int length,
            DefValType elemType) {
        int elemSize = elemType.elementSize(src.typeResolver(), src.ptrType());
        if (Transferability.isBitwiseCopyable(src.typeResolver(), src.ptrType(), elemType)) {
            copyBytes(src, dst, srcPtr, dstPtr, length * elemSize);
            return;
        }
        for (int i = 0; i < length; i++) {
            transferValue(src, dst, srcPtr + i * elemSize, dstPtr + i * elemSize, elemType);
        }
    }

    static void transferString(LiftLowerContext src, LiftLowerContext dst, int srcPtr, int dstPtr) {
        int ptrSize = src.ptrType().size();
        int begin = (int) loadInt(src.memory(), srcPtr, ptrSize, false);
        long taggedCodeUnits = loadInt(src.memory(), srcPtr + ptrSize, ptrSize, false);
        var result = transferStringIntoRange(src, dst, begin, taggedCodeUnits);
        storeInt(dst.memory(), result.ptr, dstPtr, ptrSize);
        storeInt(dst.memory(), result.codeUnits, dstPtr + ptrSize, ptrSize);
    }

    /**
     * Moves a string's bytes into a fresh destination allocation, transcoding only when the
     * two encodings genuinely disagree.
     *
     * <p>When the source bytes are already in the destination's encoding they are validated
     * in place and copied — no decode, no re-encode, no {@link String}. That covers the
     * common case of two modules that agreed on UTF-8. The {@code latin1+utf16} pairings are
     * handled at the byte level too, because a tagged UTF-16 source still has to be re-checked
     * against Latin-1 to reproduce what {@code store_string} would have chosen. Everything
     * else decodes to a {@link CharBuffer} and re-encodes from it, still without a {@code
     * String}.
     */
    static PtrAndCodeUnits transferStringIntoRange(
            LiftLowerContext src, LiftLowerContext dst, int ptr, long taggedCodeUnits) {
        var range = stringRange(src, taggedCodeUnits);
        byte[] bytes = readStringBytes(src, ptr, range);
        var dstEncoding = dst.stringEncoding();
        if (range.charset == StandardCharsets.UTF_8) {
            if (dstEncoding == StringEncoding.UTF8) {
                validateUtf8(bytes);
                return new PtrAndCodeUnits(allocateAndWrite(dst, 1, bytes), bytes.length);
            }
        } else if (range.charset == StandardCharsets.UTF_16LE) {
            if (dstEncoding == StringEncoding.UTF16) {
                validateUtf16Le(bytes);
                return new PtrAndCodeUnits(allocateAndWrite(dst, 2, bytes), bytes.length / 2);
            }
            if (dstEncoding == StringEncoding.LATIN1_UTF16) {
                return transferUtf16ToLatin1OrUtf16(dst, bytes);
            }
        } else if (dstEncoding == StringEncoding.LATIN1_UTF16) {
            // A Latin-1 source can only come from a latin1+utf16 context; every byte is
            // valid and fits, so it stays untagged and copies as-is.
            return new PtrAndCodeUnits(allocateAndWrite(dst, 2, bytes), bytes.length);
        }
        return storeStringIntoRange(dst, decodeStrictToChars(bytes, range.charset));
    }

    private static PtrAndCodeUnits transferUtf16ToLatin1OrUtf16(
            LiftLowerContext dst, byte[] utf16Bytes) {
        validateUtf16Le(utf16Bytes);
        if (!fitsLatin1Utf16(utf16Bytes)) {
            return new PtrAndCodeUnits(
                    allocateAndWrite(dst, 2, utf16Bytes),
                    (utf16Bytes.length / 2) | utf16Tag(dst.ptrType()));
        }
        byte[] latin1 = new byte[utf16Bytes.length / 2];
        for (int i = 0; i < latin1.length; i++) {
            latin1[i] = utf16Bytes[2 * i];
        }
        return new PtrAndCodeUnits(allocateAndWrite(dst, 2, latin1), latin1.length);
    }

    /** Whether every UTF-16LE code unit is below {@code 0x100}, i.e. its high byte is zero. */
    private static boolean fitsLatin1Utf16(byte[] utf16Bytes) {
        for (int i = 1; i < utf16Bytes.length; i += 2) {
            if (utf16Bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Accepts exactly what a strict {@link StandardCharsets#UTF_8} decoder accepts, without
     * producing any characters: rejects continuation bytes out of place, truncated sequences,
     * overlong encodings, surrogates and scalar values above {@code U+10FFFF}.
     */
    static void validateUtf8(byte[] bytes) {
        int i = 0;
        while (i < bytes.length) {
            int b0 = bytes[i] & 0xFF;
            if (b0 < 0x80) {
                i++;
                continue;
            }
            int length;
            int codePoint;
            if (b0 >= 0xC2 && b0 <= 0xDF) {
                length = 2;
                codePoint = b0 & 0x1F;
            } else if (b0 >= 0xE0 && b0 <= 0xEF) {
                length = 3;
                codePoint = b0 & 0x0F;
            } else if (b0 >= 0xF0 && b0 <= 0xF4) {
                length = 4;
                codePoint = b0 & 0x07;
            } else {
                throw invalidUtf8();
            }
            if (i + length > bytes.length) {
                throw invalidUtf8();
            }
            for (int k = 1; k < length; k++) {
                int bk = bytes[i + k] & 0xFF;
                if ((bk & 0xC0) != 0x80) {
                    throw invalidUtf8();
                }
                codePoint = (codePoint << 6) | (bk & 0x3F);
            }
            if (length == 3 && (codePoint < 0x800 || isSurrogate(codePoint))) {
                throw invalidUtf8();
            }
            if (length == 4 && (codePoint < 0x10000 || codePoint > 0x10FFFF)) {
                throw invalidUtf8();
            }
            i += length;
        }
    }

    private static boolean isSurrogate(int codePoint) {
        return codePoint >= MIN_SURROGATE && codePoint <= MAX_SURROGATE;
    }

    private static TrapException invalidUtf8() {
        return new TrapException("invalid " + StandardCharsets.UTF_8.name() + " byte sequence");
    }

    /**
     * Accepts exactly what a strict {@link StandardCharsets#UTF_16LE} decoder accepts:
     * every high surrogate must be followed by a low surrogate, and no low surrogate may
     * stand alone.
     */
    static void validateUtf16Le(byte[] bytes) {
        int i = 0;
        while (i + 1 < bytes.length) {
            int unit = codeUnitAt(bytes, i);
            i += 2;
            if (unit >= 0xDC00 && unit <= MAX_SURROGATE) {
                // A low surrogate can only appear as the second half of a pair, which the
                // high-surrogate branch below consumes.
                throw invalidUtf16();
            }
            if (unit < MIN_SURROGATE || unit > 0xDBFF) {
                continue;
            }
            if (i + 1 >= bytes.length) {
                throw invalidUtf16();
            }
            int low = codeUnitAt(bytes, i);
            i += 2;
            if (low < 0xDC00 || low > MAX_SURROGATE) {
                throw invalidUtf16();
            }
        }
    }

    private static int codeUnitAt(byte[] bytes, int i) {
        return (bytes[i] & 0xFF) | ((bytes[i + 1] & 0xFF) << 8);
    }

    private static TrapException invalidUtf16() {
        return new TrapException("invalid " + StandardCharsets.UTF_16LE.name() + " byte sequence");
    }

    /**
     * Transfers a flat parameter or result list. Because neither context is async and both
     * share a pointer width, the two sides always agree on whether the values spill into
     * linear memory: either both pass them flat, or both pass a pointer to the same tuple
     * layout, which reduces to a single {@link #transfer} of that tuple.
     */
    private static long[] transferFlatValues(
            LiftLowerContext src,
            LiftLowerContext dst,
            int maxFlat,
            CoreValues vi,
            List<run.endive.cm.types.ValType> ts,
            long[] outParam) {
        if (ts.isEmpty()) {
            return EMPTY_CORE_VALUES;
        }
        List<ValType> flatTypes = flattenTypes(src, ts);
        if (flatTypes.size() > maxFlat) {
            var tupleType = tupleTypeOf(ts);
            return transferSpilledValues(
                    src,
                    dst,
                    vi,
                    tupleType.alignment(src.typeResolver(), src.ptrType()),
                    tupleType.elementSize(src.typeResolver(), src.ptrType()),
                    outParam,
                    (s, d, srcPtr, dstPtr) -> transfer(s, d, srcPtr, dstPtr, tupleType));
        }
        LongList out = new LongList();
        for (run.endive.cm.types.ValType t : ts) {
            transferFlat(src, dst, vi, out, src.typeResolver().resolveDefValType(t));
        }
        return out.toArray();
    }

    /**
     * Handles the spilled case shared by the interpreted and compiled transfer paths: reads
     * the source's spill pointer, obtains the destination's (freshly allocated, or the
     * caller-provided out-param), validates both, and hands them to {@code body} to move the
     * tuple across.
     */
    static long[] transferSpilledValues(
            LiftLowerContext src,
            LiftLowerContext dst,
            CoreValues vi,
            int align,
            int size,
            long[] outParam,
            TransferPlan.Step body) {
        int srcPtr = (int) vi.next();
        if (srcPtr != DefValType.alignTo(srcPtr, align)) {
            throw new TrapException(
                    "spilled values pointer " + srcPtr + " is not aligned to " + align);
        }
        if ((long) srcPtr + size > Memory.bytes(src.memory().pages())) {
            throw new TrapException("spilled values at " + srcPtr + " are out of bounds");
        }
        int dstPtr;
        long[] flatVals;
        if (outParam == null) {
            dstPtr = allocate(dst, align, size);
            flatVals = new long[] {Integer.toUnsignedLong(dstPtr)};
        } else {
            dstPtr = (int) outParam[0];
            if (dstPtr != DefValType.alignTo(dstPtr, align)) {
                throw new TrapException(
                        "spill out-param pointer " + dstPtr + " is not aligned to " + align);
            }
            if ((long) dstPtr + size > Memory.bytes(dst.memory().pages())) {
                throw new TrapException("spill out-param at " + dstPtr + " is out of bounds");
            }
            flatVals = EMPTY_CORE_VALUES;
        }
        body.run(src, dst, srcPtr, dstPtr);
        return flatVals;
    }

    /**
     * Transfers one value's worth of flat core values from {@code vi} to {@code out}.
     *
     * <p>Scalars mostly pass straight through, but not unconditionally: lifting narrows
     * {@code u8}/{@code u16} to their type's width and sign-extends {@code s8}/{@code s16}
     * before lowering re-encodes them, so a source core value with bits set above the type's
     * width must be normalized here rather than forwarded. {@code bool} collapses to
     * {@code 0}/{@code 1}, {@code char} is validated, {@code flags} drops its slack bits,
     * and {@code f32}/{@code f64} keep their exact bit pattern.
     */
    static void transferFlat(
            LiftLowerContext src, LiftLowerContext dst, CoreValues vi, LongList out, DefValType t) {
        var d = despecialize(t);
        switch (d.kind()) {
            case BOOL:
                out.add((vi.next() & 0xFFFFFFFFL) != 0 ? 1L : 0L);
                return;
            case U8:
                out.add(vi.next() & 0xFFL);
                return;
            case U16:
                out.add(vi.next() & 0xFFFFL);
                return;
            case S8:
                out.add(Integer.toUnsignedLong((byte) vi.next()));
                return;
            case S16:
                out.add(Integer.toUnsignedLong((short) vi.next()));
                return;
            case U32:
            case S32:
            case F32:
                out.add(vi.next() & 0xFFFFFFFFL);
                return;
            case U64:
            case S64:
            case F64:
                out.add(vi.next());
                return;
            case CHAR:
                out.add(convertI32ToChar(vi.next() & 0xFFFFFFFFL));
                return;
            case STRING:
                transferFlatString(src, dst, vi, out);
                return;
            case LIST:
                transferFlatList(src, dst, vi, out, d);
                return;
            case RECORD:
                for (LabelValType f : ((RecordType) d).fields()) {
                    transferFlat(
                            src, dst, vi, out, src.typeResolver().resolveDefValType(f.valType()));
                }
                return;
            case VARIANT:
                transferFlatVariant(src, dst, vi, out, (VariantType) d);
                return;
            case FLAGS:
                out.add(vi.next() & Transferability.flagsMask((FlagsType) d) & 0xFFFFFFFFL);
                return;
            case ERROR_CONTEXT:
            case OWN:
            case BORROW:
            case STREAM:
            case FUTURE:
                throw new UnsupportedOperationException(
                        "transferring " + d.kind() + " values is not implemented yet");
            default:
                throw new IllegalStateException("unhandled kind " + d.kind());
        }
    }

    static void transferFlatString(
            LiftLowerContext src, LiftLowerContext dst, CoreValues vi, LongList out) {
        int ptr = (int) vi.next();
        long taggedCodeUnits = vi.next() & 0xFFFFFFFFL;
        var result = transferStringIntoRange(src, dst, ptr, taggedCodeUnits);
        out.add(Integer.toUnsignedLong(result.ptr));
        out.add(result.codeUnits & 0xFFFFFFFFL);
    }

    private static void transferFlatList(
            LiftLowerContext src, LiftLowerContext dst, CoreValues vi, LongList out, DefValType d) {
        if (d instanceof ListType) {
            var t = (ListType) d;
            var elemType = src.typeResolver().resolveDefValType(t.elementType());
            if (t.isFixedSize()) {
                for (int i = 0; i < t.size(); i++) {
                    transferFlat(src, dst, vi, out, elemType);
                }
                return;
            }
            transferFlatUnboundedList(src, dst, vi, out, elemType);
            return;
        }
        if (d instanceof MapType.DespecializedMapType) {
            transferFlatUnboundedList(
                    src, dst, vi, out, ((MapType.DespecializedMapType) d).recordType());
            return;
        }
        throw new IllegalStateException("unhandled LIST-kind type " + d.getClass());
    }

    static void transferFlatUnboundedList(
            LiftLowerContext src,
            LiftLowerContext dst,
            CoreValues vi,
            LongList out,
            DefValType elemType) {
        int ptr = (int) vi.next();
        int length = (int) vi.next();
        int dstPtr = transferListIntoRange(src, dst, ptr, length, elemType);
        out.add(Integer.toUnsignedLong(dstPtr));
        out.add(Integer.toUnsignedLong(length));
    }

    /**
     * Transfers a variant across the joined flat layout, consuming and producing exactly the
     * slots {@code flatten_variant} defines regardless of which case is present — the source
     * cursor skips the joined slots the chosen case left unused, and the destination pads
     * them with {@code 0}, mirroring {@link #liftFlatVariant} and {@link #lowerFlatVariant}.
     */
    private static void transferFlatVariant(
            LiftLowerContext src,
            LiftLowerContext dst,
            CoreValues vi,
            LongList out,
            VariantType t) {
        var cases = t.cases();
        List<ValType> flatTypes = t.flatten(src.typeResolver(), src.ptrType());
        if (flatTypes.get(0) != ValType.I32) {
            throw new IllegalStateException("variant discriminant flat type must be i32");
        }
        int payloadSlots = flatTypes.size() - 1;
        long caseIndex = vi.next() & 0xFFFFFFFFL;
        if (caseIndex >= cases.size()) {
            throw new TrapException("variant case index " + caseIndex + " is out of range");
        }
        out.add(caseIndex);
        var c = cases.get((int) caseIndex);
        int srcPayloadStart = vi.position();
        int dstPayloadStart = out.size();
        if (c.hasValType()) {
            transferFlat(src, dst, vi, out, src.typeResolver().resolveDefValType(c.valType()));
        }
        vi.skipTo(srcPayloadStart + payloadSlots);
        for (int i = out.size() - dstPayloadStart; i < payloadSlots; i++) {
            out.add(0L);
        }
    }

    /** A growable primitive-{@code long} buffer used to accumulate flat lowered values. */
    static final class LongList {
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
