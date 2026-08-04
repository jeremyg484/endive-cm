package run.endive.cm.abi;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import run.endive.cm.types.FuncType;
import run.endive.cm.types.LabelValType;
import run.endive.cm.types.ValType;

/**
 * A component function's argument and result transfer, compiled once against a fixed source
 * and destination context.
 *
 * <p>This is the form the direct transfer path is meant to be used in when two core modules
 * are wired together: the contexts come from a pair of {@code canon lift} and {@code canon
 * lower} definitions and never change, so the type walk, the flat-versus-spilled decision,
 * the copy-span coalescing and the string strategy can all be settled at link time and
 * replayed per call.
 *
 * <p>{@link CanonicalAbi#transferFlatParams} and {@link CanonicalAbi#transferFlatResults}
 * compute the same results by re-deriving everything on each call. They are the readable
 * definition of what a plan means, and the two are cross-checked against each other in the
 * tests.
 *
 * <p>Compile only after {@link #canTransfer} accepts the same three arguments; otherwise
 * compilation may throw for a function the lift/lower path would have handled.
 */
public final class ValueTransfer {

    private final LiftLowerContext caller;
    private final LiftLowerContext callee;
    private final FlatTransferPlan params;
    private final FlatTransferPlan result;

    private ValueTransfer(
            LiftLowerContext caller,
            LiftLowerContext callee,
            FlatTransferPlan params,
            FlatTransferPlan result) {
        this.caller = caller;
        this.callee = callee;
        this.params = params;
        this.result = result;
    }

    /**
     * Whether {@code ft}'s values can move directly between the two contexts. A {@code false}
     * result means the caller should keep using the lift/lower pair; it is never wrong, only
     * slower. The check is symmetric, so the order of the two contexts does not matter here.
     *
     * @see CanonicalAbi#canTransfer
     */
    public static boolean canTransfer(
            LiftLowerContext caller, LiftLowerContext callee, FuncType ft) {
        return CanonicalAbi.canTransfer(caller, callee, ft);
    }

    /**
     * Compiles the transfer of {@code ft}'s parameters and result.
     *
     * @param caller the context the call arrives from — the {@code canon lower} side, whose
     *     memory holds the arguments and receives the results
     * @param callee the context the call is dispatched into — the {@code canon lift} side,
     *     whose memory receives the arguments and produces the results
     * @param ft the component-level function type both sides agree on
     */
    public static ValueTransfer compile(
            LiftLowerContext caller, LiftLowerContext callee, FuncType ft) {
        CanonicalAbi.requireTransferable(caller, callee);
        List<ValType> paramTypes =
                ft.params().stream().map(LabelValType::valType).collect(Collectors.toList());
        FlatTransferPlan paramPlan =
                FlatTransferPlan.compile(
                        caller.typeResolver(),
                        caller.ptrType(),
                        paramTypes,
                        CanonicalAbi.MAX_FLAT_PARAMS);
        FlatTransferPlan resultPlan = null;
        if (ft.hasResult()) {
            List<ValType> resultTypes = new ArrayList<>();
            resultTypes.add(ft.result());
            resultPlan =
                    FlatTransferPlan.compile(
                            caller.typeResolver(),
                            caller.ptrType(),
                            resultTypes,
                            CanonicalAbi.MAX_FLAT_RESULTS);
        }
        return new ValueTransfer(caller, callee, paramPlan, resultPlan);
    }

    /**
     * Transfers the caller's flat core arguments into the callee's, allocating in the callee
     * whatever the values need. Call this on the way in, and pass the result to the callee's
     * core function.
     */
    public long[] transferParams(long[] flatArgs) {
        return params.run(caller, callee, flatArgs, null);
    }

    /**
     * Transfers the callee's flat core results back into the caller — note the direction is
     * the reverse of {@link #transferParams}, so this allocates in the <em>caller</em>.
     *
     * <p>When the results spill into linear memory, {@code outParam}'s first element is the
     * caller-provided spill pointer (the extra out-pointer argument {@code flattenFuncType}
     * appends in the {@link Direction#LOWER} direction) and no flat values are returned; when
     * it is null, caller storage is freshly allocated and the returned list is the spill
     * pointer.
     *
     * <p>Call this <em>before</em> the callee's {@code post_return}, which is free to release
     * the buffers the returned values point into.
     */
    public long[] transferResults(long[] flatResults, long[] outParam) {
        if (result == null) {
            return new long[0];
        }
        return result.run(callee, caller, flatResults, outParam);
    }

    /** Whether the compiled function produces a result at all. */
    public boolean hasResult() {
        return result != null;
    }
}
