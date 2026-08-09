package run.endive.cm.runtime;

import java.util.ArrayList;
import java.util.List;
import run.endive.cm.abi.ResourceTypeRef;
import run.endive.cm.types.CoreType;
import run.endive.cm.types.Type;
import run.endive.cm.types.TypeSpace;
import run.endive.cm.types.ValType;

/**
 * The type index space an {@code instance} type declaration builds up as it is read.
 *
 * <p>An instance type is a little scope of its own. Its declarations are numbered from zero and
 * refer to each other by those numbers, so the {@code (own 0)} inside one means "the type
 * declared at position 0 of this instance type" and has nothing to do with index 0 of whichever
 * component wrote it or of whichever instance ends up satisfying it. Both {@code type}
 * declarations and {@code type}-kinded exports occupy the space, in the order they appear, as do
 * type aliases; exports of other kinds do not.
 *
 * <p>Not every slot resolves here, though. A declaration can pull a type in from elsewhere — by
 * aliasing one out of the enclosing component, or by naming one the providing instance exports —
 * and such a type keeps the indices it was written with, which count in <em>its</em> space. Each
 * slot therefore records where its type resolves, and {@link #resolve} hands that space back so a
 * comparison can follow it.
 *
 * <p>A resource type is only ever <em>named</em> by a declaration, never defined by one: the
 * declaration says "there is some resource type here" and matching against a real instance is
 * what settles which. Those bindings land in the slot as they are established, so a later
 * declaration mentioning {@code (own 0)} resolves to the resource type the provider actually
 * exported.
 */
final class InstanceTypeSpace implements TypeMatcher.Space {

    private final List<Slot> slots = new ArrayList<>();
    private final List<CoreType> coreTypes = new ArrayList<>();

    @Override
    public TypeSpace.Resolved resolve(ValType valType) {
        if (valType.primValType() != null) {
            return new TypeSpace.Resolved(valType.primValType(), this);
        }
        Slot slot = slotAt(valType.typeIdx());
        if (slot.type.defValType() == null) {
            throw new LinkageException(
                    "Instance type index "
                            + valType.typeIdx()
                            + " must resolve to a value type but got "
                            + slot.type);
        }
        return new TypeSpace.Resolved(slot.type.defValType(), slot.space);
    }

    @Override
    public ResourceTypeRef resourceType(int typeIdx) {
        Slot slot = slotAt(typeIdx);
        if (slot.resourceType == null) {
            throw new LinkageException(
                    "Instance type index " + typeIdx + " does not name a resource type");
        }
        return slot.resourceType;
    }

    Slot slotAt(int index) {
        if (index < 0 || index >= slots.size()) {
            throw new LinkageException(
                    "Instance type index " + index + " out of bounds (size " + slots.size() + ")");
        }
        return slots.get(index);
    }

    /**
     * Core types occupy an index space of their own, separate from the value and function types
     * above, which a {@code core module} export declaration indexes into.
     */
    void addCoreType(CoreType coreType) {
        coreTypes.add(coreType);
    }

    CoreType coreTypeAt(int index) {
        if (index < 0 || index >= coreTypes.size()) {
            throw new LinkageException(
                    "Instance core type index "
                            + index
                            + " out of bounds (size "
                            + coreTypes.size()
                            + ")");
        }
        return coreTypes.get(index);
    }

    /** Appends a type the instance type declares itself, whose indices count in this space. */
    void addLocalType(Type type) {
        slots.add(new Slot(type, this, null));
    }

    /** Appends a type drawn from another space, which keeps resolving in that space. */
    void addForeignType(Type type, TypeMatcher.Space space, ResourceTypeRef resourceType) {
        slots.add(new Slot(type, space, resourceType));
    }

    /** One numbered slot: the type it holds, where that type resolves, and what it may name. */
    static final class Slot {

        private final Type type;
        private final TypeMatcher.Space space;
        private final ResourceTypeRef resourceType;

        private Slot(Type type, TypeMatcher.Space space, ResourceTypeRef resourceType) {
            this.type = type;
            this.space = space;
            this.resourceType = resourceType;
        }

        Type type() {
            return type;
        }

        /** The space {@link #type}'s own type indices belong to. */
        TypeMatcher.Space space() {
            return space;
        }

        /** The resource type this slot names, or {@code null} if it holds an ordinary type. */
        ResourceTypeRef resourceType() {
            return resourceType;
        }
    }
}
