package run.endive.cm.abi;

/**
 * A component's exported {@code cabi_realloc} function, used by {@link CanonicalAbi} to
 * allocate destination memory when storing variable-length values (unbounded lists,
 * strings). Mirrors the Python reference's {@code cx.opts.realloc(old_ptr, old_size,
 * align, new_size) -> new_ptr}.
 */
@FunctionalInterface
public interface Realloc {

    int realloc(int oldPtr, int oldSize, int align, int newSize);
}
