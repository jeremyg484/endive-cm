package run.endive.cm.abi;

import java.util.ArrayList;
import java.util.List;
import run.endive.cm.types.Case;
import run.endive.cm.types.DefValType;
import run.endive.cm.types.FlagsType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.ListType;
import run.endive.cm.types.MapType;
import run.endive.cm.types.PointerType;
import run.endive.cm.types.RecordType;
import run.endive.cm.types.TypeResolver;
import run.endive.cm.types.VariantType;
import run.endive.runtime.TrapException;

/**
 * A compiled memory-to-memory transfer for one type: the traversal {@link
 * CanonicalAbi#transfer} performs per call, flattened once into a straight-line list of
 * steps at fixed offsets.
 *
 * <p>Beyond skipping the repeated type walk, compiling enables the optimization the
 * interpreted path cannot easily reach — <em>span coalescing</em>. Neighboring fields that
 * copy verbatim merge into a single {@code memcpy}, and merging continues across the padding
 * between them, so a {@code record { a: u8, b: u32 }} becomes one eight-byte copy rather
 * than two field copies. A run of copies is only broken by a field that needs real work: a
 * string to transcode, a list pointer to re-allocate, a discriminant to validate.
 *
 * <p>A plan is bound to the type, its {@link TypeResolver} and a {@link PointerType}, but
 * not to any pair of contexts — the same plan runs for any source and destination that agree
 * on those. Offsets are shared between the two sides, which holds because a matching pointer
 * width gives the value an identical layout in both memories.
 */
final class TransferPlan {

    /** One step of a compiled transfer, applied at a fixed offset from both base pointers. */
    interface Step {
        void run(LiftLowerContext src, LiftLowerContext dst, int srcPtr, int dstPtr);
    }

    private final Step[] steps;

    private TransferPlan(Step[] steps) {
        this.steps = steps;
    }

    void run(LiftLowerContext src, LiftLowerContext dst, int srcPtr, int dstPtr) {
        for (Step step : steps) {
            step.run(src, dst, srcPtr, dstPtr);
        }
    }

    /** The number of steps left after coalescing; exposed so tests can assert on the shape. */
    int stepCount() {
        return steps.length;
    }

    static TransferPlan compile(TypeResolver typeResolver, PointerType ptrType, DefValType t) {
        var builder = new Builder(typeResolver, ptrType);
        builder.append(t, 0);
        return builder.build();
    }

    /** Accumulates steps, merging adjacent verbatim copies as they are appended. */
    private static final class Builder {

        private final TypeResolver typeResolver;
        private final PointerType ptrType;
        private final List<Step> steps = new ArrayList<>();
        private int pendingStart = NO_PENDING;
        private int pendingEnd;

        private static final int NO_PENDING = -1;

        Builder(TypeResolver typeResolver, PointerType ptrType) {
            this.typeResolver = typeResolver;
            this.ptrType = ptrType;
        }

        TransferPlan build() {
            flush();
            return new TransferPlan(steps.toArray(new Step[0]));
        }

        /**
         * Extends the open copy run to cover {@code [off, off + length)}. Any gap between the
         * previous run and this one is absorbed, which is what lets a record's interior
         * padding travel with its fields.
         */
        private void copy(int off, int length) {
            if (length == 0) {
                return;
            }
            if (pendingStart == NO_PENDING) {
                pendingStart = off;
            }
            pendingEnd = Math.max(pendingEnd, off + length);
        }

        private void step(Step step) {
            flush();
            steps.add(step);
        }

        private void flush() {
            if (pendingStart == NO_PENDING) {
                return;
            }
            int off = pendingStart;
            int length = pendingEnd - pendingStart;
            pendingStart = NO_PENDING;
            if (length == 1 || length == 2 || length == 4 || length == 8) {
                steps.add(
                        (src, dst, srcPtr, dstPtr) ->
                                CanonicalAbi.copyScalar(
                                        src, dst, srcPtr + off, dstPtr + off, length));
                return;
            }
            steps.add(
                    (src, dst, srcPtr, dstPtr) ->
                            CanonicalAbi.copyBytes(src, dst, srcPtr + off, dstPtr + off, length));
        }

        private int sizeOf(DefValType t) {
            return t.elementSize(typeResolver, ptrType);
        }

        private int alignmentOf(DefValType t) {
            return t.alignment(typeResolver, ptrType);
        }

        void append(DefValType t, int off) {
            var d = CanonicalAbi.despecialize(t);
            if (Transferability.isBitwiseCopyable(typeResolver, ptrType, d)) {
                copy(off, sizeOf(d));
                return;
            }
            switch (d.kind()) {
                case BOOL:
                    step(
                            (src, dst, srcPtr, dstPtr) ->
                                    CanonicalAbi.transferBool(
                                            src, dst, srcPtr + off, dstPtr + off));
                    return;
                case CHAR:
                    step(
                            (src, dst, srcPtr, dstPtr) ->
                                    CanonicalAbi.transferChar(
                                            src, dst, srcPtr + off, dstPtr + off));
                    return;
                case FLAGS:
                    appendFlags((FlagsType) d, off);
                    return;
                case STRING:
                    step(
                            (src, dst, srcPtr, dstPtr) ->
                                    CanonicalAbi.transferString(
                                            src, dst, srcPtr + off, dstPtr + off));
                    return;
                case RECORD:
                    appendRecord((RecordType) d, off);
                    return;
                case VARIANT:
                    appendVariant((VariantType) d, off);
                    return;
                case LIST:
                    appendList(d, off);
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

        private void appendFlags(FlagsType t, int off) {
            int size = sizeOf(t);
            long mask = Transferability.flagsMask(t);
            step(
                    (src, dst, srcPtr, dstPtr) ->
                            CanonicalAbi.transferFlags(
                                    src, dst, srcPtr + off, dstPtr + off, size, mask));
        }

        private void appendRecord(RecordType t, int off) {
            int fieldOff = off;
            for (LabelValType f : t.fields()) {
                var fieldType = typeResolver.resolveDefValType(f.valType());
                fieldOff = DefValType.alignTo(fieldOff, alignmentOf(fieldType));
                append(fieldType, fieldOff);
                fieldOff += sizeOf(fieldType);
            }
        }

        /**
         * Compiles a plan per case and selects between them at run time. See {@link
         * CanonicalAbi#transferValue} on why the payload offset is computed relative to the
         * variant's own base.
         */
        private void appendVariant(VariantType t, int off) {
            var cases = t.cases();
            int discSize = sizeOf(t.discriminantType());
            int payloadOff =
                    off + DefValType.alignTo(discSize, t.maxCaseAlignment(typeResolver, ptrType));
            TransferPlan[] casePlans = new TransferPlan[cases.size()];
            for (int i = 0; i < cases.size(); i++) {
                Case c = cases.get(i);
                casePlans[i] =
                        c.hasValType()
                                ? compile(
                                        typeResolver,
                                        ptrType,
                                        typeResolver.resolveDefValType(c.valType()))
                                : null;
            }
            step(
                    (src, dst, srcPtr, dstPtr) -> {
                        long caseIndex =
                                CanonicalAbi.loadInt(src.memory(), srcPtr + off, discSize, false);
                        if (caseIndex >= casePlans.length) {
                            throw new TrapException("invalid variant discriminant");
                        }
                        CanonicalAbi.storeInt(dst.memory(), caseIndex, dstPtr + off, discSize);
                        TransferPlan payload = casePlans[(int) caseIndex];
                        if (payload != null) {
                            payload.run(src, dst, srcPtr + payloadOff, dstPtr + payloadOff);
                        }
                    });
        }

        private void appendList(DefValType d, int off) {
            if (d instanceof ListType) {
                var t = (ListType) d;
                var elemType = typeResolver.resolveDefValType(t.elementType());
                if (t.isFixedSize()) {
                    // A fixed-size list of copyable elements is itself copyable and was
                    // handled by the caller, so this loop only ever walks elements that
                    // genuinely need work.
                    appendFixedSizeList(elemType, t.size(), off);
                    return;
                }
                appendUnboundedList(elemType, off);
                return;
            }
            if (d instanceof MapType.DespecializedMapType) {
                appendUnboundedList(((MapType.DespecializedMapType) d).recordType(), off);
                return;
            }
            throw new IllegalStateException("unhandled LIST-kind type " + d.getClass());
        }

        private void appendFixedSizeList(DefValType elemType, int length, int off) {
            int elemSize = sizeOf(elemType);
            TransferPlan elemPlan = compile(typeResolver, ptrType, elemType);
            step(
                    (src, dst, srcPtr, dstPtr) -> {
                        for (int i = 0; i < length; i++) {
                            int delta = off + i * elemSize;
                            elemPlan.run(src, dst, srcPtr + delta, dstPtr + delta);
                        }
                    });
        }

        private void appendUnboundedList(DefValType elemType, int off) {
            step(
                    (src, dst, srcPtr, dstPtr) ->
                            CanonicalAbi.transferUnboundedList(
                                    src, dst, srcPtr + off, dstPtr + off, elemType));
        }
    }
}
