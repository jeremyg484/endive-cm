package run.endive.cm.wasi.p2.filesystem.types;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import run.endive.cm.runtime.Tuple2;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.Advice;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DescriptorFlags;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DescriptorStat;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DescriptorType;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.ErrorCode;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.ErrorCodeException;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.MetadataHashValue;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.NewTimestamp;
import run.endive.cm.wasi.p2.bindings.wasi.io.streams.InputStream;
import run.endive.cm.wasi.p2.bindings.wasi.io.streams.OutputStream;
import run.endive.cm.wasi.p2.io.poll.HostPoll;

/**
 * A descriptor naming an open file, read and written at an offset given with each call.
 *
 * <p>Reading needs {@code read} and anything changing the file needs {@code write}. Either fails
 * with {@code bad-descriptor} without the flag it needs.
 *
 * <p>Reads and writes go through the open file. Once it no longer has the name it was opened
 * under, its metadata can only be described in part, and its times can no longer be set.
 *
 * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types</a>
 */
public final class HostFile extends HostDescriptor {

    /** The most one {@code read} returns, so a guest asking for more cannot exhaust the heap. */
    static final int MAX_READ = 1 << 20;

    private final SharedChannel shared;
    private boolean dropped;

    private HostFile(
            Path root,
            Path path,
            DescriptorFlags flags,
            HostPoll poll,
            OpenFiles files,
            FileChannel channel) {
        super(root, path, flags, poll, files);
        this.shared = new SharedChannel(channel, files);
    }

    /**
     * Opens the file {@code path} names, which is never a symbolic link.
     *
     * @param create whether a missing file is created
     * @param exclusive whether an existing file fails with {@code exist} instead
     * @param truncate whether an existing file is emptied
     */
    static HostFile open(
            Path path,
            DescriptorFlags flags,
            HostPoll poll,
            boolean create,
            boolean exclusive,
            boolean truncate) {
        return open(
                path.getParent(), path, flags, poll, new OpenFiles(), create, exclusive, truncate);
    }

    /**
     * Opens the file {@code path} names below the preopened directory {@code root}.
     *
     * @param files where the open file is tracked
     * @param create whether a missing file is created
     * @param exclusive whether an existing file fails with {@code exist} instead
     * @param truncate whether an existing file is emptied
     */
    static HostFile open(
            Path root,
            Path path,
            DescriptorFlags flags,
            HostPoll poll,
            OpenFiles files,
            boolean create,
            boolean exclusive,
            boolean truncate) {
        Set<OpenOption> options = new HashSet<>();
        options.add(LinkOption.NOFOLLOW_LINKS);
        if (flags.has(DescriptorFlags.Flag.READ)) {
            options.add(StandardOpenOption.READ);
        }
        // The host ignores creating and truncating on a channel it cannot write.
        if (flags.has(DescriptorFlags.Flag.WRITE) || create || truncate) {
            options.add(StandardOpenOption.WRITE);
        }
        if (!options.contains(StandardOpenOption.WRITE)) {
            options.add(StandardOpenOption.READ);
        }
        if (create) {
            options.add(exclusive ? StandardOpenOption.CREATE_NEW : StandardOpenOption.CREATE);
        }
        if (truncate) {
            options.add(StandardOpenOption.TRUNCATE_EXISTING);
        }
        try {
            return new HostFile(root, path, flags, poll, files, FileChannel.open(path, options));
        } catch (IOException | UnsupportedOperationException e) {
            throw ErrorCodes.failed(e);
        }
    }

    @Override
    public DescriptorType getType() {
        return DescriptorType.REGULAR_FILE;
    }

    /**
     * Up to {@code length} bytes from {@code offset}, and whether they reach the end of the file.
     * No more than {@value #MAX_READ} bytes are returned by one call.
     *
     * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types, read</a>
     */
    @Override
    public Tuple2<byte[], Boolean> read(BigInteger length, BigInteger offset) {
        require(DescriptorFlags.Flag.READ);
        try {
            long size = shared.channel().size();
            long position = clamp(offset);
            if (position >= size) {
                return Tuple2.of(new byte[0], true);
            }
            int count = (int) Math.min(Math.min(clamp(length), size - position), MAX_READ);
            ByteBuffer buffer = ByteBuffer.allocate(count);
            while (buffer.hasRemaining()) {
                if (shared.channel().read(buffer, position + buffer.position()) < 0) {
                    break;
                }
            }
            byte[] bytes =
                    buffer.position() == count
                            ? buffer.array()
                            : Arrays.copyOf(buffer.array(), buffer.position());
            return Tuple2.of(bytes, position + bytes.length >= size);
        } catch (IOException e) {
            throw ErrorCodes.failed(e);
        }
    }

    /**
     * Writes all of {@code buffer} at {@code offset}, extending the file when that is past its
     * end.
     *
     * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types, write</a>
     */
    @Override
    public BigInteger write(byte[] buffer, BigInteger offset) {
        require(DescriptorFlags.Flag.WRITE);
        long position = position(offset);
        try {
            shared.writeFully(ByteBuffer.wrap(buffer), position);
        } catch (IOException e) {
            throw ErrorCodes.failed(e);
        }
        return BigInteger.valueOf(buffer.length);
    }

    /**
     * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types, set-size</a>
     */
    @Override
    public void setSize(BigInteger size) {
        require(DescriptorFlags.Flag.WRITE);
        long target = position(size);
        try {
            long current = shared.channel().size();
            if (target < current) {
                shared.channel().truncate(target);
            } else if (target > current) {
                shared.writeFully(ByteBuffer.allocate(1), target - 1);
            }
        } catch (IOException e) {
            throw ErrorCodes.failed(e);
        }
    }

    @Override
    public DescriptorStat stat() {
        Path current = currentPath();
        return current == null
                ? Metadata.detached(DescriptorType.REGULAR_FILE, size())
                : Metadata.stat(current);
    }

    @Override
    public MetadataHashValue metadataHash() {
        Path current = currentPath();
        if (current != null) {
            return Metadata.hash(current);
        }
        return Metadata.hash(identity(), size(), null);
    }

    /**
     * Sets the times through the name the file was opened under, which fails with {@code
     * no-entry} once the file no longer has it.
     */
    @Override
    public void setTimes(NewTimestamp dataAccessTimestamp, NewTimestamp dataModificationTimestamp) {
        require(DescriptorFlags.Flag.WRITE);
        Path current = currentPath();
        if (current == null) {
            throw ErrorCodes.error(ErrorCode.NO_ENTRY);
        }
        Metadata.setTimes(current, dataAccessTimestamp, dataModificationTimestamp);
    }

    /** Accepts any advice and acts on none, which the spec allows. */
    @Override
    public void advise(BigInteger offset, BigInteger length, Advice advice) {}

    @Override
    public void sync() {
        force(true);
    }

    @Override
    public void syncData() {
        force(false);
    }

    /**
     * A stream reading from {@code offset}, which keeps the file open after this descriptor is
     * dropped.
     *
     * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types, read-via-stream</a>
     */
    @Override
    public InputStream readViaStream(BigInteger offset) {
        require(DescriptorFlags.Flag.READ);
        return new HostFileInputStream(poll(), shared, clamp(offset));
    }

    /**
     * A stream writing from {@code offset}, which keeps the file open after this descriptor is
     * dropped.
     *
     * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types, write-via-stream</a>
     */
    @Override
    public OutputStream writeViaStream(BigInteger offset) {
        require(DescriptorFlags.Flag.WRITE);
        return HostFileOutputStream.at(poll(), shared, position(offset));
    }

    /**
     * A stream writing at the end of the file, which keeps the file open after this descriptor is
     * dropped.
     *
     * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types, append-via-stream</a>
     */
    @Override
    public OutputStream appendViaStream() {
        require(DescriptorFlags.Flag.WRITE);
        return HostFileOutputStream.appending(poll(), shared);
    }

    /** Lets go of the file, which closes once no stream opened from it is left either. */
    @Override
    public void drop() {
        if (!dropped) {
            dropped = true;
            shared.release();
        }
    }

    /** The name this file still has, or {@code null} once it was renamed, removed or replaced. */
    private Path currentPath() {
        Path current;
        try {
            current = path();
        } catch (ErrorCodeException e) {
            return null;
        }
        if (fileKey() == null) {
            return Files.exists(current, LinkOption.NOFOLLOW_LINKS) ? current : null;
        }
        return fileKey().equals(Metadata.fileKey(current)) ? current : null;
    }

    private long size() {
        try {
            return shared.channel().size();
        } catch (IOException e) {
            throw ErrorCodes.failed(e);
        }
    }

    private void force(boolean metadata) {
        if (!getFlags().has(DescriptorFlags.Flag.WRITE)) {
            return;
        }
        try {
            shared.channel().force(metadata);
        } catch (IOException e) {
            throw ErrorCodes.failed(e);
        }
    }

    private void require(DescriptorFlags.Flag flag) {
        if (!getFlags().has(flag)) {
            throw ErrorCodes.error(ErrorCode.BAD_DESCRIPTOR);
        }
    }

    /** {@code value} as a {@code long}, saturating where it does not fit. */
    private static long clamp(BigInteger value) {
        return value.bitLength() < Long.SIZE ? value.longValue() : Long.MAX_VALUE;
    }

    /** {@code offset} as a position the host can write at, or {@code file-too-large}. */
    private static long position(BigInteger offset) {
        if (offset.bitLength() >= Long.SIZE) {
            throw ErrorCodes.error(ErrorCode.FILE_TOO_LARGE);
        }
        return offset.longValue();
    }
}
