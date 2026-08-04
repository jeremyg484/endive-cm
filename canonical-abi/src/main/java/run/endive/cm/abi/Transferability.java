package run.endive.cm.abi;

import java.util.IdentityHashMap;
import java.util.Map;
import run.endive.cm.types.DefValType;
import run.endive.cm.types.FlagsType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.ListType;
import run.endive.cm.types.MapType;
import run.endive.cm.types.PointerType;
import run.endive.cm.types.RecordType;
import run.endive.cm.types.TypeResolver;

/**
 * Static analysis backing the direct memory-to-memory transfer path: decides which
 * component-level types can move between two core module memories as a verbatim byte copy,
 * and which have to be walked because lifting and lowering are not each other's exact
 * inverse for them.
 *
 * <p>A type is <em>bitwise copyable</em> only if, for every possible byte pattern in the
 * source, {@code store(dst, load(src, t), t)} would reproduce those bytes unchanged. That
 * rules out more than it might first appear:
 *
 * <ul>
 *   <li>{@code bool} — a source byte of {@code 0x02} lifts to {@code true} and lowers back
 *       to {@code 0x01}.
 *   <li>{@code char} — lifting traps on surrogates and on values at or above {@code
 *       0x110000}, so the bytes must be inspected even though a valid one copies unchanged.
 *   <li>{@code flags} — lifting keeps only the low {@code labels.size()} bits and lowering
 *       re-packs the rest as zero, so a type with slack bits in its storage width needs
 *       masking. Flags that exactly fill their width ({@code 8}, {@code 16} or {@code 32}
 *       labels) have no slack and do copy verbatim.
 *   <li>{@code string} and unbounded {@code list} — the payload lives behind a pointer that
 *       must be re-allocated in the destination, and strings may additionally need
 *       transcoding.
 *   <li>{@code variant} (and its {@code enum}/{@code option}/{@code result} specializations)
 *       — the discriminant has to be range-checked against the case list.
 * </ul>
 *
 * <p>Integers copy verbatim. So do {@code f32}/{@code f64}: the Canonical ABI explicitly
 * permits a host to preserve "the original value of the NaN before lifting, allowing them
 * to optimize away both the canonicalization of lifting and the randomization of lowering",
 * which is what the transfer path does. See {@link CanonicalAbi#transfer} for the
 * consequence — it differs from {@link CanonicalAbi#load}/{@link CanonicalAbi#store} for
 * non-canonical NaN payloads, and only for those.
 *
 * <p>Aggregates are copyable when all of their leaves are. Padding inside a record and
 * between the elements of a fixed-size list is copied along with the fields, which
 * substitutes the source's undefined bytes for the destination allocator's — both are
 * undefined, and coalescing across them is what turns a record of integers into a single
 * copy.
 *
 * <p>All results assume the source and destination agree on {@link PointerType}; callers
 * establish that before consulting this class, because a differing pointer width changes
 * the layout of every pointer-sized field.
 */
final class Transferability {

    private Transferability() {}

    /**
     * Whether a value of {@code t} occupies the same bytes in both memories and can be moved
     * with a single verbatim copy, with no inspection, validation or normalization.
     */
    static boolean isBitwiseCopyable(TypeResolver typeResolver, PointerType ptrType, DefValType t) {
        return isBitwiseCopyable(typeResolver, ptrType, t, new IdentityHashMap<>());
    }

    private static boolean isBitwiseCopyable(
            TypeResolver typeResolver,
            PointerType ptrType,
            DefValType t,
            Map<DefValType, Boolean> cache) {
        var d = CanonicalAbi.despecialize(t);
        Boolean cached = cache.get(d);
        if (cached != null) {
            return cached;
        }
        boolean result = computeBitwiseCopyable(typeResolver, ptrType, d, cache);
        cache.put(d, result);
        return result;
    }

    private static boolean computeBitwiseCopyable(
            TypeResolver typeResolver,
            PointerType ptrType,
            DefValType d,
            Map<DefValType, Boolean> cache) {
        switch (d.kind()) {
            case U8:
            case U16:
            case U32:
            case U64:
            case S8:
            case S16:
            case S32:
            case S64:
            case F32:
            case F64:
                return true;
            case BOOL:
            case CHAR:
            case STRING:
            case VARIANT:
            case ERROR_CONTEXT:
            case OWN:
            case BORROW:
            case STREAM:
            case FUTURE:
                return false;
            case FLAGS:
                return flagsFillWidth(typeResolver, ptrType, (FlagsType) d);
            case RECORD:
                for (LabelValType f : ((RecordType) d).fields()) {
                    if (!isBitwiseCopyable(
                            typeResolver,
                            ptrType,
                            typeResolver.resolveDefValType(f.valType()),
                            cache)) {
                        return false;
                    }
                }
                return true;
            case LIST:
                // Only a fixed-size list is stored inline; an unbounded one is a
                // (pointer, length) pair whose payload must be re-allocated in the
                // destination.
                if (d instanceof ListType) {
                    var list = (ListType) d;
                    return list.isFixedSize()
                            && isBitwiseCopyable(
                                    typeResolver,
                                    ptrType,
                                    typeResolver.resolveDefValType(list.elementType()),
                                    cache);
                }
                if (d instanceof MapType.DespecializedMapType) {
                    return false;
                }
                throw new IllegalStateException("unhandled LIST-kind type " + d.getClass());
            default:
                throw new IllegalStateException("unhandled kind " + d.kind());
        }
    }

    /**
     * Whether transferring a value of {@code t} through the flat (core value) path leaves
     * every slot alone — no masking, no sign extension, no validation, no memory access —
     * so the source's core values can be handed to the destination as they stand.
     *
     * <p>This is <em>not</em> {@link #isBitwiseCopyable}, and the two disagree in the
     * direction that catches people out. In memory a {@code u8} occupies a single byte and
     * copies verbatim, and eight {@code flags} labels exactly fill their byte; both are
     * bitwise copyable. Flattened, each occupies a whole {@code i32} slot with slack bits
     * above it that lifting discards and lowering re-zeroes, so neither is flat-identity.
     * Flat identity is the stricter of the two for every narrow type.
     *
     * <p>"Unchanged" means unchanged <em>at the slot's core value type</em>. Endive carries
     * an {@code i32} in a {@code long} without guaranteeing which way the upper 32 bits are
     * extended — {@code InterpreterMachine.I32_ADD} pushes an {@code int}, which Java
     * sign-extends — whereas the Canonical ABI's own lowering zero-extends. So for an
     * {@code i32}-shaped slot the two paths can disagree above bit 31. Every consumer of an
     * {@code i32} slot narrows to the low 32 bits first, so that difference is unobservable;
     * a consumer that reads such a slot as a full 64-bit value would see it.
     */
    static boolean isFlatIdentity(TypeResolver typeResolver, PointerType ptrType, DefValType t) {
        return isFlatIdentity(typeResolver, ptrType, t, new IdentityHashMap<>());
    }

    private static boolean isFlatIdentity(
            TypeResolver typeResolver,
            PointerType ptrType,
            DefValType t,
            Map<DefValType, Boolean> cache) {
        var d = CanonicalAbi.despecialize(t);
        Boolean cached = cache.get(d);
        if (cached != null) {
            return cached;
        }
        boolean result = computeFlatIdentity(typeResolver, ptrType, d, cache);
        cache.put(d, result);
        return result;
    }

    private static boolean computeFlatIdentity(
            TypeResolver typeResolver,
            PointerType ptrType,
            DefValType d,
            Map<DefValType, Boolean> cache) {
        switch (d.kind()) {
            case U32:
            case S32:
            case U64:
            case S64:
            case F32:
            case F64:
                return true;
            case BOOL:
            case CHAR:
            case U8:
            case U16:
            case S8:
            case S16:
            case STRING:
            case VARIANT:
            case ERROR_CONTEXT:
            case OWN:
            case BORROW:
            case STREAM:
            case FUTURE:
                // Narrow integers are masked or sign-extended to their type's width, bool
                // collapses to 0/1, char is range-checked, and a variant's discriminant is
                // validated and its unused joined slots zero-padded.
                return false;
            case FLAGS:
                return flagsFillFlatSlot((FlagsType) d);
            case RECORD:
                for (LabelValType f : ((RecordType) d).fields()) {
                    if (!isFlatIdentity(
                            typeResolver,
                            ptrType,
                            typeResolver.resolveDefValType(f.valType()),
                            cache)) {
                        return false;
                    }
                }
                return true;
            case LIST:
                // A fixed-size list flattens to its elements laid end to end; an unbounded
                // one is a (pointer, length) pair whose payload has to be re-allocated.
                if (d instanceof ListType) {
                    var list = (ListType) d;
                    return list.isFixedSize()
                            && isFlatIdentity(
                                    typeResolver,
                                    ptrType,
                                    typeResolver.resolveDefValType(list.elementType()),
                                    cache);
                }
                if (d instanceof MapType.DespecializedMapType) {
                    return false;
                }
                throw new IllegalStateException("unhandled LIST-kind type " + d.getClass());
            default:
                throw new IllegalStateException("unhandled kind " + d.kind());
        }
    }

    /**
     * Whether every type reachable from {@code t} is one the lift/lower and transfer paths
     * actually implement. Resource handles and the async value types are rejected here so a
     * caller can decide up front to fall back rather than trapping partway through a
     * transfer.
     */
    static boolean isSupported(TypeResolver typeResolver, DefValType t) {
        return !CanonicalAbi.contains(typeResolver, t, Transferability::isUnsupportedKind);
    }

    private static boolean isUnsupportedKind(DefValType d) {
        switch (d.kind()) {
            case ERROR_CONTEXT:
            case OWN:
            case BORROW:
            case STREAM:
            case FUTURE:
                return true;
            default:
                return false;
        }
    }

    /** The bit mask lifting a {@code flags} value applies, i.e. one bit per label. */
    static long flagsMask(FlagsType t) {
        int n = t.labels().size();
        return n >= Long.SIZE ? -1L : (1L << n) - 1;
    }

    /**
     * Whether {@code t}'s labels exactly fill its <em>in-memory</em> storage width, leaving
     * no slack bits for lifting to drop and lowering to re-zero. That width is as narrow as
     * one byte, so eight labels are enough.
     */
    static boolean flagsFillWidth(TypeResolver typeResolver, PointerType ptrType, FlagsType t) {
        return t.labels().size() >= t.elementSize(typeResolver, ptrType) * Byte.SIZE;
    }

    /**
     * Whether {@code t}'s labels fill its <em>flattened</em> slot, which is always an
     * {@code i32} regardless of how few labels there are — so this needs a full 32 of them,
     * where {@link #flagsFillWidth} is satisfied by 8.
     */
    static boolean flagsFillFlatSlot(FlagsType t) {
        return t.labels().size() >= Integer.SIZE;
    }
}
