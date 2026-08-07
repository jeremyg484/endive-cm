package run.endive.cm.abi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import run.endive.cm.types.BorrowType;
import run.endive.cm.types.DefValType;
import run.endive.cm.types.OwnType;
import run.endive.cm.types.PointerType;
import run.endive.cm.types.Type;
import run.endive.cm.types.TypeResolver;
import run.endive.cm.types.ValType;
import run.endive.runtime.ByteArrayMemory;
import run.endive.runtime.TrapException;
import run.endive.wasm.types.MemoryLimits;

/**
 * Lifting and lowering of {@code own} and {@code borrow} handles.
 *
 * <p>Unlike the other value types, a handle's meaning lives in a table rather than in its bytes,
 * so these tests are as much about what happens to the table as about the value produced: which
 * entries a lift consumes, which a lower creates, and which of the borrow counters move.
 */
class CanonicalAbiResourceTest {

    private static final int REP = 42;

    // ---------------------------------------------------------------------------------
    // own
    // ---------------------------------------------------------------------------------

    @Test
    void lowerThenLiftAnOwnHandleRoundTripsTheRep() {
        var f = new Fixture();
        int index = CanonicalAbi.lowerOwn(f.ctx, ResourceValue.owned(f.rt, REP), f.ownType);

        assertThat(index).isEqualTo(1);
        assertThat(CanonicalAbi.liftOwn(f.ctx, index, f.ownType))
                .isEqualTo(ResourceValue.owned(f.rt, REP));
    }

    @Test
    void liftingAnOwnHandleTransfersItOutOfTheTable() {
        // Ownership moves to the lowering side, so the index must stop naming anything here —
        // otherwise both components would believe they own the resource.
        var f = new Fixture();
        int index = f.handles.add(f.rt, REP, true, null);

        CanonicalAbi.liftOwn(f.ctx, index, f.ownType);

        assertThat(f.handles.contains(index)).isFalse();
        assertThatThrownBy(() -> CanonicalAbi.liftOwn(f.ctx, index, f.ownType))
                .isInstanceOf(TrapException.class)
                .hasMessageContaining("unknown handle index");
    }

    @Test
    void liftingAnOwnHandleTrapsWhenTheHandleIsOnlyBorrowed() {
        var f = new Fixture();
        int index = f.handles.add(f.rt, REP, false, new FakeTask());

        assertThatThrownBy(() -> CanonicalAbi.liftOwn(f.ctx, index, f.ownType))
                .isInstanceOf(TrapException.class)
                .hasMessageContaining("borrowed handle");
    }

    @Test
    void liftingAnOwnHandleTrapsWhileItIsLentOut() {
        // Giving the resource away while a callee still holds a borrow of it would leave that
        // borrow dangling, so the transfer is refused until the lend is returned.
        var f = new Fixture();
        int index = f.handles.add(f.rt, REP, true, null);
        f.handles.handleAt(index).lend();

        assertThatThrownBy(() -> CanonicalAbi.liftOwn(f.ctx, index, f.ownType))
                .isInstanceOf(TrapException.class)
                .hasMessageContaining("cannot remove owned resource while borrowed");
    }

    @Test
    void liftingTrapsWhenTheHandleBelongsToADifferentResourceType() {
        // Two resource types with identical declarations are still distinct types; only
        // reference identity decides, so a handle from one cannot be used against the other.
        var f = new Fixture();
        var other = new FakeResourceType();
        int index = f.handles.add(other, REP, true, null);

        assertThatThrownBy(() -> CanonicalAbi.liftOwn(f.ctx, index, f.ownType))
                .isInstanceOf(TrapException.class)
                .hasMessageContaining("wrong type");
    }

    @Test
    void liftingTrapsWhenTheIndexNamesSomethingOtherThanAResourceHandle() {
        // A handle table also holds waitables and error contexts.
        var f = new Fixture();
        int index = f.handles.put(new Object());

        assertThatThrownBy(() -> CanonicalAbi.liftOwn(f.ctx, index, f.ownType))
                .isInstanceOf(TrapException.class)
                .hasMessageContaining("not a resource handle");
    }

    // ---------------------------------------------------------------------------------
    // borrow
    // ---------------------------------------------------------------------------------

    @Test
    void liftingABorrowLeavesTheHandleInPlaceAndLendsIt() {
        var f = new Fixture();
        int index = f.handles.add(f.rt, REP, true, null);
        var subtask = new FakeSubtask();

        var value = CanonicalAbi.liftBorrow(f.ctx.withBorrowScope(subtask), index, f.borrowType);

        assertThat(value).isEqualTo(ResourceValue.borrowed(f.rt, REP));
        assertThat(f.handles.contains(index)).isTrue();
        assertThat(f.handles.handleAt(index).numLends()).isEqualTo(1);
    }

    @Test
    void resolvingTheCallReturnsEveryHandleItBorrowed() {
        // Two borrows of the same handle in one call both have to come back, which is why the
        // count is a count rather than a flag.
        var f = new Fixture();
        int index = f.handles.add(f.rt, REP, true, null);
        var subtask = new FakeSubtask();
        var ctx = f.ctx.withBorrowScope(subtask);

        CanonicalAbi.liftBorrow(ctx, index, f.borrowType);
        CanonicalAbi.liftBorrow(ctx, index, f.borrowType);
        assertThat(f.handles.handleAt(index).numLends()).isEqualTo(2);

        subtask.releaseLenders();

        assertThat(f.handles.handleAt(index).numLends()).isZero();
        // ...and only now may the resource be given away.
        assertThat(CanonicalAbi.liftOwn(f.ctx, index, f.ownType))
                .isEqualTo(ResourceValue.owned(f.rt, REP));
    }

    @Test
    void loweringABorrowMintsAHandleAndChargesItToTheCall() {
        var f = new Fixture();
        var task = new FakeTask();

        int index =
                CanonicalAbi.lowerBorrow(
                        f.ctx.withBorrowScope(task),
                        ResourceValue.borrowed(f.rt, REP),
                        f.borrowType);

        assertThat(task.numBorrows()).isEqualTo(1);
        var handle = f.handles.handleAt(index);
        assertThat(handle.rep()).isEqualTo(REP);
        assertThat(handle.own()).isFalse();
        assertThat(handle.borrowScope()).isSameAs(task);
    }

    @Test
    void loweringABorrowIntoTheImplementingComponentPassesTheRepItself() {
        // The one case where the value crossing the boundary is not a table index: the
        // component that implements the resource type is handed the representation directly,
        // so there is no handle to mint and nothing to charge.
        var f = new Fixture();
        f.rt.impl = f.handles;
        var task = new FakeTask();

        int lowered =
                CanonicalAbi.lowerBorrow(
                        f.ctx.withBorrowScope(task),
                        ResourceValue.borrowed(f.rt, REP),
                        f.borrowType);

        assertThat(lowered).isEqualTo(REP);
        assertThat(task.numBorrows()).isZero();
        assertThat(f.handles.size()).isZero();
    }

    // ---------------------------------------------------------------------------------
    // Through the flat and memory paths
    // ---------------------------------------------------------------------------------

    @Test
    void storesAndLoadsAnOwnHandleThroughMemory() {
        var f = new Fixture();
        CanonicalAbi.store(f.ctx, ResourceValue.owned(f.rt, REP), f.ownType, 0);

        // Only the table index reaches memory; the rep stays behind.
        assertThat(f.ctx.memory().readInt(0)).isEqualTo(1);
        assertThat(CanonicalAbi.load(f.ctx, 0, f.ownType))
                .isEqualTo(ResourceValue.owned(f.rt, REP));
    }

    @Test
    void lowersAndLiftsAnOwnHandleThroughTheFlatPath() {
        var f = new Fixture();
        long[] flat =
                CanonicalAbi.lowerFlatParams(
                        f.ctx, List.of(ResourceValue.owned(f.rt, REP)), List.of(f.ownValType));

        assertThat(flat).containsExactly(1L);
        assertThat(CanonicalAbi.liftFlatParams(f.ctx, flat, List.of(f.ownValType)))
                .containsExactly(ResourceValue.owned(f.rt, REP));
    }

    @Test
    void handlesAreNeverMovedByTheDirectTransferPath() {
        // A handle index means something only relative to one instance's table, so no byte copy
        // can carry it: canTransfer has to reject these and fall back to lift/lower.
        var f = new Fixture();
        assertThat(Transferability.isSupported(f.types, f.ownType)).isFalse();
        assertThat(Transferability.isSupported(f.types, f.borrowType)).isFalse();
        assertThat(Transferability.isBitwiseCopyable(f.types, PointerType.I32, f.ownType))
                .isFalse();
        assertThat(Transferability.isFlatIdentity(f.types, PointerType.I32, f.borrowType))
                .isFalse();
    }

    @Test
    void loweringABorrowWithoutACallToChargeItToIsARuntimeError() {
        // Not a trap: a context whose types contain a borrow but which was given no scope is a
        // wiring mistake in the embedder, not something guest code can provoke.
        var f = new Fixture();
        assertThatThrownBy(
                        () ->
                                CanonicalAbi.lowerBorrow(
                                        f.ctx, ResourceValue.borrowed(f.rt, REP), f.borrowType))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("borrow scope");
    }

    // ---------------------------------------------------------------------------------
    // Fixture
    // ---------------------------------------------------------------------------------

    /** A single resource type at type index 0, with an empty handle table and no borrow scope. */
    private static final class Fixture {
        final FakeResourceType rt = new FakeResourceType();
        final FakeHandles handles = new FakeHandles();
        final OwnType ownType = OwnType.builder().withTypeIdx(0).build();
        final BorrowType borrowType = BorrowType.builder().withTypeIdx(1).build();
        final Types types = new Types();
        final ValType ownValType;
        final LiftLowerContext ctx;

        Fixture() {
            ownValType = types.add(ownType);
            types.add(borrowType);
            ctx =
                    LiftLowerContext.builder()
                            .withMemory(new ByteArrayMemory(new MemoryLimits(1)))
                            .withPtrType(PointerType.I32)
                            .withTypeResolver(types)
                            .withHandles(handles)
                            // Both own and borrow here name the same resource type; the type
                            // index only says which handle type it is used through.
                            .withResourceTypes(typeIdx -> rt)
                            .build();
        }
    }

    private static final class Types implements TypeResolver {
        private final List<Type> types = new ArrayList<>();

        @Override
        public Type getType(int index) {
            return types.get(index);
        }

        ValType add(DefValType t) {
            types.add(Type.of(t));
            return ValType.builder().withTypeIdx(types.size() - 1).build();
        }
    }

    private static final class FakeResourceType implements ResourceTypeRef {
        private HandleTable impl;

        @Override
        public HandleTable impl() {
            return impl;
        }
    }

    private static final class FakeHandles implements HandleTable {
        private final Map<Integer, Object> entries = new LinkedHashMap<>();
        private int next = 1;

        @Override
        public int add(ResourceTypeRef rt, int rep, boolean own, BorrowScope.Task borrowScope) {
            return put(new FakeHandle(rt, rep, own, borrowScope));
        }

        @Override
        public Object get(int index) {
            Object element = entries.get(index);
            if (element == null) {
                throw new TrapException("unknown handle index " + index);
            }
            return element;
        }

        @Override
        public Object remove(int index) {
            Object element = get(index);
            entries.remove(index);
            return element;
        }

        int put(Object element) {
            int index = next++;
            entries.put(index, element);
            return index;
        }

        boolean contains(int index) {
            return entries.containsKey(index);
        }

        int size() {
            return entries.size();
        }

        FakeHandle handleAt(int index) {
            return (FakeHandle) entries.get(index);
        }
    }

    private static final class FakeHandle implements Handle {
        private final ResourceTypeRef resourceType;
        private final int rep;
        private final boolean own;
        private final BorrowScope.Task borrowScope;
        private int numLends;

        FakeHandle(
                ResourceTypeRef resourceType, int rep, boolean own, BorrowScope.Task borrowScope) {
            this.resourceType = resourceType;
            this.rep = rep;
            this.own = own;
            this.borrowScope = borrowScope;
        }

        @Override
        public ResourceTypeRef resourceType() {
            return resourceType;
        }

        @Override
        public int rep() {
            return rep;
        }

        @Override
        public boolean own() {
            return own;
        }

        @Override
        public BorrowScope.Task borrowScope() {
            return borrowScope;
        }

        @Override
        public int numLends() {
            return numLends;
        }

        @Override
        public void lend() {
            numLends++;
        }

        @Override
        public void returnLend() {
            numLends--;
        }
    }

    private static final class FakeTask implements BorrowScope.Task {
        private int numBorrows;

        @Override
        public void borrow() {
            numBorrows++;
        }

        @Override
        public void unborrow() {
            numBorrows--;
        }

        @Override
        public int numBorrows() {
            return numBorrows;
        }
    }

    private static final class FakeSubtask implements BorrowScope.Subtask {
        private final List<Handle> lenders = new ArrayList<>();

        @Override
        public void addLender(Handle handle) {
            handle.lend();
            lenders.add(handle);
        }

        @Override
        public void releaseLenders() {
            lenders.forEach(Handle::returnLend);
            lenders.clear();
        }
    }
}
