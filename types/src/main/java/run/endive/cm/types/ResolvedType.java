package run.endive.cm.types;

import java.util.ArrayList;
import java.util.List;
import run.endive.wasm.types.ValType;

/**
 * A component value type with its type indices already followed: a self-contained graph that means
 * one thing, independent of any index space.
 *
 * <p>A parsed {@link DefValType} is syntax. Its children are numbers, and a number only means
 * something relative to the space it was written in, so the same node laid out against two spaces
 * can be two different types. Grounding resolves every child once, against the space that child
 * actually belongs to, and the result no longer needs a resolver to be understood.
 *
 * <p>That is what makes memory layout a property of this class rather than of {@link DefValType}.
 * Alignment, size and flattening cannot be cached on a syntax node — the answer depends on the
 * space — but they are fixed for a grounded type, so each is computed at most once per pointer
 * width here.
 *
 * <p>Grounding also despecializes: {@code tuple}, {@code enum}, {@code option}, {@code result} and
 * {@code map} are shorthand for records and variants, and are expanded once at construction
 * instead of allocating a fresh expansion on every layout query. {@link #node()} still reports the
 * type as written, for diagnostics and for the places that distinguish the sugar from what it
 * stands for.
 *
 * <p>Handles are the exception to "self-contained": {@code own} and {@code borrow} keep the index
 * they were written with, because which runtime resource type that names is a property of the
 * component instance doing the lifting, not of the type. The Canonical ABI resolves it per call.
 */
public final class ResolvedType {

    private static final int UNCOMPUTED = Integer.MIN_VALUE;
    private static final int PTR_WIDTHS = PointerType.values().length;

    private final DefValType node;
    private final DefValType.Kind kind;
    private final List<Field> fields;
    private final List<Case> cases;
    private final ResolvedType element;

    private final int[] alignments = newMemo();
    private final int[] elementSizes = newMemo();
    private final List<ValType>[] flattenings = newFlatMemo();

    private ResolvedType(
            DefValType node,
            DefValType.Kind kind,
            List<Field> fields,
            List<Case> cases,
            ResolvedType element) {
        this.node = node;
        this.kind = kind;
        this.fields = fields;
        this.cases = cases;
        this.element = element;
    }

    /** Grounds {@code type}, whose own indices count in {@code space}. */
    public static ResolvedType of(DefValType type, TypeSpace space) {
        if (type == null) {
            return null;
        }
        if (type.kind() == DefValType.Kind.MAP) {
            return new ResolvedType(
                    type, DefValType.Kind.LIST, null, null, mapEntry((MapType) type, space));
        }
        DefValType d =
                type instanceof Specialized<?> ? ((Specialized<?>) type).despecialize() : type;
        DefValType.Kind kind = d.kind();
        switch (kind) {
            case RECORD:
                return new ResolvedType(
                        type, kind, groundFields(((RecordType) d).fields(), space), null, null);
            case VARIANT:
                return new ResolvedType(
                        type, kind, null, groundCases(((VariantType) d).cases(), space), null);
            case LIST:
            case SIZED_LIST:
                return new ResolvedType(
                        type, kind, null, null, ground(((ListType) d).elementType(), space));
            case STREAM:
                {
                    var stream = (StreamType) d;
                    return new ResolvedType(
                            type,
                            kind,
                            null,
                            null,
                            stream.hasElementType() ? ground(stream.elementType(), space) : null);
                }
            case FUTURE:
                {
                    var future = (FutureType) d;
                    return new ResolvedType(
                            type,
                            kind,
                            null,
                            null,
                            future.hasElementType() ? ground(future.elementType(), space) : null);
                }
            default:
                return new ResolvedType(type, kind, null, null, null);
        }
    }

    /**
     * The entry type of a {@code map<k, v>}: the record {@code (k, v)} despecializes to, whose
     * two fields count in the same space the map itself was written in.
     */
    private static ResolvedType mapEntry(MapType map, TypeSpace space) {
        return of(
                TupleType.builder()
                        .addElementType(map.keyType())
                        .addElementType(map.valueType())
                        .build()
                        .despecialize(),
                space);
    }

    private static ResolvedType ground(run.endive.cm.types.ValType valType, TypeSpace space) {
        TypeSpace.Resolved resolved = space.resolve(valType);
        return of(resolved.type(), resolved.space());
    }

    private static List<Field> groundFields(List<LabelValType> fields, TypeSpace space) {
        List<Field> grounded = new ArrayList<>(fields.size());
        for (LabelValType f : fields) {
            grounded.add(new Field(f.label(), ground(f.valType(), space)));
        }
        return List.copyOf(grounded);
    }

    private static List<Case> groundCases(List<run.endive.cm.types.Case> cases, TypeSpace space) {
        List<Case> grounded = new ArrayList<>(cases.size());
        for (run.endive.cm.types.Case c : cases) {
            grounded.add(new Case(c.label(), c.hasValType() ? ground(c.valType(), space) : null));
        }
        return List.copyOf(grounded);
    }

    /** The type as written, before despecialization. */
    public DefValType node() {
        return node;
    }

    /**
     * The kind of the type this stands for, with the shorthands expanded — so a {@code tuple}
     * reports {@code RECORD}, an {@code option} reports {@code VARIANT} and a {@code map}
     * reports {@code LIST}. This is what the ABI switches on.
     */
    public DefValType.Kind kind() {
        return kind;
    }

    /** The fields of a {@code record}, in declaration order. */
    public List<Field> fields() {
        if (fields == null) {
            throw new IllegalStateException(kind() + " is not a record");
        }
        return fields;
    }

    /** The cases of a {@code variant}, in declaration order. */
    public List<Case> cases() {
        if (cases == null) {
            throw new IllegalStateException(kind() + " is not a variant");
        }
        return cases;
    }

    /**
     * The element type of a list, or the payload of a {@code stream} or {@code future};
     * {@code null} when a stream or future carries none.
     */
    public ResolvedType element() {
        return element;
    }

    public boolean isFixedSizeList() {
        return node instanceof ListType && ((ListType) node).isFixedSize();
    }

    public int fixedSize() {
        return ((ListType) node).size();
    }

    /** The labels of a {@code flags}. */
    public List<String> labels() {
        return ((FlagsType) node).labels();
    }

    public int alignment(PointerType ptrType) {
        int memo = alignments[ptrType.ordinal()];
        if (memo == UNCOMPUTED) {
            memo = computeAlignment(ptrType);
            alignments[ptrType.ordinal()] = memo;
        }
        return memo;
    }

    public int elementSize(PointerType ptrType) {
        int memo = elementSizes[ptrType.ordinal()];
        if (memo == UNCOMPUTED) {
            memo = computeElementSize(ptrType);
            elementSizes[ptrType.ordinal()] = memo;
        }
        return memo;
    }

    public List<ValType> flatten(PointerType ptrType) {
        List<ValType> memo = flattenings[ptrType.ordinal()];
        if (memo == null) {
            memo = computeFlatten(ptrType);
            flattenings[ptrType.ordinal()] = memo;
        }
        return memo;
    }

    /** The alignment of the widest case payload, which a variant's own layout is built on. */
    public int maxCaseAlignment(PointerType ptrType) {
        int a = 1;
        for (Case c : cases()) {
            if (c.hasType()) {
                a = Math.max(a, c.type().alignment(ptrType));
            }
        }
        return a;
    }

    private int computeAlignment(PointerType ptrType) {
        switch (kind()) {
            case BOOL:
            case S8:
            case U8:
                return 1;
            case S16:
            case U16:
                return 2;
            case S32:
            case U32:
            case F32:
            case CHAR:
            case ERROR_CONTEXT:
            case OWN:
            case BORROW:
            case STREAM:
            case FUTURE:
                return 4;
            case S64:
            case U64:
            case F64:
                return 8;
            case STRING:
                return ptrType.size();
            case FLAGS:
                return flagsWidth();
            case LIST:
            case SIZED_LIST:
                return isFixedSizeList() ? element.alignment(ptrType) : ptrType.size();
            case RECORD:
                {
                    int a = 1;
                    for (Field f : fields) {
                        a = Math.max(a, f.type().alignment(ptrType));
                    }
                    return a;
                }
            case VARIANT:
                return Math.max(discriminantSize(), maxCaseAlignment(ptrType));
            default:
                throw new IllegalStateException("unhandled kind " + kind());
        }
    }

    private int computeElementSize(PointerType ptrType) {
        switch (kind()) {
            case BOOL:
            case S8:
            case U8:
                return 1;
            case S16:
            case U16:
                return 2;
            case S32:
            case U32:
            case F32:
            case CHAR:
            case ERROR_CONTEXT:
            case OWN:
            case BORROW:
            case STREAM:
            case FUTURE:
                return 4;
            case S64:
            case U64:
            case F64:
                return 8;
            case STRING:
                return 2 * ptrType.size();
            case FLAGS:
                return flagsWidth();
            case LIST:
            case SIZED_LIST:
                return isFixedSizeList()
                        ? fixedSize() * element.elementSize(ptrType)
                        : 2 * ptrType.size();
            case RECORD:
                {
                    int s = 0;
                    for (Field f : fields) {
                        s = DefValType.alignTo(s, f.type().alignment(ptrType));
                        s += f.type().elementSize(ptrType);
                    }
                    return DefValType.alignTo(s, alignment(ptrType));
                }
            case VARIANT:
                {
                    int s = discriminantSize();
                    s = DefValType.alignTo(s, maxCaseAlignment(ptrType));
                    int cs = 0;
                    for (Case c : cases) {
                        if (c.hasType()) {
                            cs = Math.max(cs, c.type().elementSize(ptrType));
                        }
                    }
                    s += cs;
                    return DefValType.alignTo(s, alignment(ptrType));
                }
            default:
                throw new IllegalStateException("unhandled kind " + kind());
        }
    }

    private List<ValType> computeFlatten(PointerType ptrType) {
        switch (kind()) {
            case BOOL:
            case S8:
            case U8:
            case S16:
            case U16:
            case S32:
            case U32:
            case CHAR:
            case ERROR_CONTEXT:
            case OWN:
            case BORROW:
            case STREAM:
            case FUTURE:
            case FLAGS:
                return List.of(ValType.I32);
            case S64:
            case U64:
                return List.of(ValType.I64);
            case F32:
                return List.of(ValType.F32);
            case F64:
                return List.of(ValType.F64);
            case STRING:
                return List.of(ptrType.coreValType(), ptrType.coreValType());
            case LIST:
            case SIZED_LIST:
                {
                    if (!isFixedSizeList()) {
                        return List.of(ptrType.coreValType(), ptrType.coreValType());
                    }
                    List<ValType> flat = new ArrayList<>();
                    List<ValType> elemFlat = element.flatten(ptrType);
                    for (int i = 0; i < fixedSize(); i++) {
                        flat.addAll(elemFlat);
                    }
                    return List.copyOf(flat);
                }
            case RECORD:
                {
                    List<ValType> flat = new ArrayList<>();
                    for (Field f : fields) {
                        flat.addAll(f.type().flatten(ptrType));
                    }
                    return List.copyOf(flat);
                }
            case VARIANT:
                {
                    List<ValType> payload = new ArrayList<>();
                    for (Case c : cases) {
                        if (!c.hasType()) {
                            continue;
                        }
                        List<ValType> caseFlat = c.type().flatten(ptrType);
                        for (int i = 0; i < caseFlat.size(); i++) {
                            if (i < payload.size()) {
                                payload.set(i, join(payload.get(i), caseFlat.get(i)));
                            } else {
                                payload.add(caseFlat.get(i));
                            }
                        }
                    }
                    List<ValType> flat = new ArrayList<>();
                    flat.add(ValType.I32);
                    flat.addAll(payload);
                    return List.copyOf(flat);
                }
            default:
                throw new IllegalStateException("unhandled kind " + kind());
        }
    }

    /**
     * The widest core type that can hold either operand, which is how a variant reconciles cases
     * whose payloads flatten differently at the same position.
     */
    private static ValType join(ValType a, ValType b) {
        if (a == b) {
            return a;
        }
        if ((a == ValType.I32 && b == ValType.F32) || (a == ValType.F32 && b == ValType.I32)) {
            return ValType.I32;
        }
        return ValType.I64;
    }

    /** The width in bytes of a variant's discriminant. */
    public int discriminantSize() {
        int n = cases().size();
        if (n <= 256) {
            return 1;
        }
        if (n <= 65536) {
            return 2;
        }
        return 4;
    }

    private int flagsWidth() {
        int n = labels().size();
        if (n <= 8) {
            return 1;
        }
        if (n <= 16) {
            return 2;
        }
        return 4;
    }

    private static int[] newMemo() {
        int[] memo = new int[PTR_WIDTHS];
        for (int i = 0; i < PTR_WIDTHS; i++) {
            memo[i] = UNCOMPUTED;
        }
        return memo;
    }

    @SuppressWarnings("unchecked")
    private static List<ValType>[] newFlatMemo() {
        return new List[PTR_WIDTHS];
    }

    @Override
    public String toString() {
        return "ResolvedType{" + node + '}';
    }

    /** One field of a grounded {@code record}. */
    public static final class Field {

        private final String label;
        private final ResolvedType type;

        private Field(String label, ResolvedType type) {
            this.label = label;
            this.type = type;
        }

        public String label() {
            return label;
        }

        public ResolvedType type() {
            return type;
        }
    }

    /** One case of a grounded {@code variant}; the payload is optional. */
    public static final class Case {

        private final String label;
        private final ResolvedType type;

        private Case(String label, ResolvedType type) {
            this.label = label;
            this.type = type;
        }

        public String label() {
            return label;
        }

        public boolean hasType() {
            return type != null;
        }

        /** The payload type, or {@code null} for a case that carries none. */
        public ResolvedType type() {
            return type;
        }
    }
}
