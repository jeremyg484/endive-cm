package run.endive.cm.abi;

import java.util.ArrayList;
import java.util.List;
import run.endive.cm.abi.CanonicalAbi.CoreValues;
import run.endive.cm.abi.CanonicalAbi.LongList;
import run.endive.cm.types.Case;
import run.endive.cm.types.DefValType;
import run.endive.cm.types.FlagsType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.ListType;
import run.endive.cm.types.MapType;
import run.endive.cm.types.PointerType;
import run.endive.cm.types.RecordType;
import run.endive.cm.types.TupleType;
import run.endive.cm.types.TypeResolver;
import run.endive.cm.types.VariantType;
import run.endive.runtime.TrapException;
import run.endive.wasm.types.ValType;

/**
 * A compiled transfer for a function's flat parameter or result list — the counterpart of
 * {@link TransferPlan} for values travelling as core Wasm values rather than in memory.
 *
 * <p>Whether the values spill into linear memory is decided once, at compile time. Both
 * sides always reach the same answer: flattening depends only on the type and the pointer
 * width, and the transfer path requires those to match. So a plan is either a
 * <em>spilled</em> one, holding a {@link TransferPlan} for the tuple both sides agree on, or
 * a <em>direct</em> one, holding a step per value.
 */
final class FlatTransferPlan {

    /** One step of a compiled flat transfer, reading from {@code vi} and writing to {@code out}. */
    interface Step {
        void run(LiftLowerContext src, LiftLowerContext dst, CoreValues vi, LongList out);
    }

    private final Step[] steps;
    private final TransferPlan spillPlan;
    private final int spillAlign;
    private final int spillSize;

    private FlatTransferPlan(Step[] steps, TransferPlan spillPlan, int spillAlign, int spillSize) {
        this.steps = steps;
        this.spillPlan = spillPlan;
        this.spillAlign = spillAlign;
        this.spillSize = spillSize;
    }

    static FlatTransferPlan compile(
            TypeResolver typeResolver,
            PointerType ptrType,
            List<run.endive.cm.types.ValType> ts,
            int maxFlat) {
        List<ValType> flatTypes = new ArrayList<>();
        for (run.endive.cm.types.ValType t : ts) {
            flatTypes.addAll(typeResolver.resolveDefValType(t).flatten(typeResolver, ptrType));
        }
        if (flatTypes.size() > maxFlat) {
            TupleType tupleType = CanonicalAbi.tupleTypeOf(ts);
            return new FlatTransferPlan(
                    null,
                    TransferPlan.compile(typeResolver, ptrType, tupleType),
                    tupleType.alignment(typeResolver, ptrType),
                    tupleType.elementSize(typeResolver, ptrType));
        }
        var builder = new Builder(typeResolver, ptrType);
        for (run.endive.cm.types.ValType t : ts) {
            builder.append(typeResolver.resolveDefValType(t));
        }
        return new FlatTransferPlan(builder.build(), null, 0, 0);
    }

    long[] run(LiftLowerContext src, LiftLowerContext dst, long[] flat, long[] outParam) {
        var vi = new CoreValues(flat);
        if (spillPlan != null) {
            return CanonicalAbi.transferSpilledValues(
                    src, dst, vi, spillAlign, spillSize, outParam, spillPlan::run);
        }
        LongList out = new LongList();
        for (Step step : steps) {
            step.run(src, dst, vi, out);
        }
        return out.toArray();
    }

    /** The number of compiled steps, or {@code 1} when spilled; exposed for tests. */
    int stepCount() {
        return spillPlan != null ? 1 : steps.length;
    }

    /** Builds the per-value step list for the direct (non-spilled) case. */
    private static final class Builder {

        private final TypeResolver typeResolver;
        private final PointerType ptrType;
        private final List<Step> steps = new ArrayList<>();

        Builder(TypeResolver typeResolver, PointerType ptrType) {
            this.typeResolver = typeResolver;
            this.ptrType = ptrType;
        }

        Step[] build() {
            return steps.toArray(new Step[0]);
        }

        /**
         * Appends the steps for one value. The normalizations are the same ones {@link
         * CanonicalAbi#transferFlat} applies — narrow integers masked or sign-extended,
         * {@code bool} collapsed, {@code char} validated, {@code flags} stripped of slack
         * bits — but resolved to a concrete mask here instead of re-dispatched per call.
         */
        void append(DefValType t) {
            var d = CanonicalAbi.despecialize(t);
            switch (d.kind()) {
                case BOOL:
                    steps.add(
                            (src, dst, vi, out) ->
                                    out.add((vi.next() & 0xFFFFFFFFL) != 0 ? 1L : 0L));
                    return;
                case U8:
                    steps.add((src, dst, vi, out) -> out.add(vi.next() & 0xFFL));
                    return;
                case U16:
                    steps.add((src, dst, vi, out) -> out.add(vi.next() & 0xFFFFL));
                    return;
                case S8:
                    steps.add(
                            (src, dst, vi, out) ->
                                    out.add(Integer.toUnsignedLong((byte) vi.next())));
                    return;
                case S16:
                    steps.add(
                            (src, dst, vi, out) ->
                                    out.add(Integer.toUnsignedLong((short) vi.next())));
                    return;
                case U32:
                case S32:
                case F32:
                    steps.add((src, dst, vi, out) -> out.add(vi.next() & 0xFFFFFFFFL));
                    return;
                case U64:
                case S64:
                case F64:
                    steps.add((src, dst, vi, out) -> out.add(vi.next()));
                    return;
                case CHAR:
                    steps.add(
                            (src, dst, vi, out) ->
                                    out.add(
                                            CanonicalAbi.convertI32ToChar(
                                                    vi.next() & 0xFFFFFFFFL)));
                    return;
                case FLAGS:
                    long mask = Transferability.flagsMask((FlagsType) d);
                    steps.add((src, dst, vi, out) -> out.add(vi.next() & mask & 0xFFFFFFFFL));
                    return;
                case STRING:
                    steps.add(CanonicalAbi::transferFlatString);
                    return;
                case RECORD:
                    for (LabelValType f : ((RecordType) d).fields()) {
                        append(typeResolver.resolveDefValType(f.valType()));
                    }
                    return;
                case VARIANT:
                    appendVariant((VariantType) d);
                    return;
                case LIST:
                    appendList(d);
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

        private void appendList(DefValType d) {
            if (d instanceof ListType) {
                var t = (ListType) d;
                var elemType = typeResolver.resolveDefValType(t.elementType());
                if (t.isFixedSize()) {
                    for (int i = 0; i < t.size(); i++) {
                        append(elemType);
                    }
                    return;
                }
                appendUnboundedList(elemType);
                return;
            }
            if (d instanceof MapType.DespecializedMapType) {
                appendUnboundedList(((MapType.DespecializedMapType) d).recordType());
                return;
            }
            throw new IllegalStateException("unhandled LIST-kind type " + d.getClass());
        }

        private void appendUnboundedList(DefValType elemType) {
            steps.add(
                    (src, dst, vi, out) ->
                            CanonicalAbi.transferFlatUnboundedList(src, dst, vi, out, elemType));
        }

        /**
         * Compiles a nested plan per case. At run time the discriminant selects one, and the
         * joined payload slots the chosen case leaves unused are skipped on the source cursor
         * and zero-padded on the destination, exactly as the interpreted path does.
         */
        private void appendVariant(VariantType t) {
            var cases = t.cases();
            List<ValType> flatTypes = t.flatten(typeResolver, ptrType);
            if (flatTypes.get(0) != ValType.I32) {
                throw new IllegalStateException("variant discriminant flat type must be i32");
            }
            int payloadSlots = flatTypes.size() - 1;
            FlatTransferPlan[] casePlans = new FlatTransferPlan[cases.size()];
            for (int i = 0; i < cases.size(); i++) {
                Case c = cases.get(i);
                if (!c.hasValType()) {
                    continue;
                }
                var caseBuilder = new Builder(typeResolver, ptrType);
                caseBuilder.append(typeResolver.resolveDefValType(c.valType()));
                casePlans[i] = new FlatTransferPlan(caseBuilder.build(), null, 0, 0);
            }
            steps.add(
                    (src, dst, vi, out) -> {
                        long caseIndex = vi.next() & 0xFFFFFFFFL;
                        if (caseIndex >= casePlans.length) {
                            throw new TrapException("invalid variant discriminant");
                        }
                        out.add(caseIndex);
                        int srcPayloadStart = vi.position();
                        int dstPayloadStart = out.size();
                        FlatTransferPlan payload = casePlans[(int) caseIndex];
                        if (payload != null) {
                            for (Step step : payload.steps) {
                                step.run(src, dst, vi, out);
                            }
                        }
                        vi.skipTo(srcPayloadStart + payloadSlots);
                        for (int i = out.size() - dstPayloadStart; i < payloadSlots; i++) {
                            out.add(0L);
                        }
                    });
        }
    }
}
