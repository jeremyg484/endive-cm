package run.endive.cm.wasi.p2.filesystem.types;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import run.endive.cm.runtime.Tuple2;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.Advice;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.Descriptor;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DescriptorFlags;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DescriptorStat;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DirectoryEntryStream;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.ErrorCode;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.ErrorCodeException;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.MetadataHashValue;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.NewTimestamp;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.OpenFlags;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.PathFlags;
import run.endive.cm.wasi.p2.bindings.wasi.io.streams.InputStream;
import run.endive.cm.wasi.p2.bindings.wasi.io.streams.OutputStream;
import run.endive.cm.wasi.p2.io.poll.HostPoll;

/**
 * The host side of the {@code wasi:filesystem} {@code descriptor} resource, naming an object on a
 * host {@link java.nio.file.FileSystem}.
 *
 * <p>A {@link HostDirectory} refuses what only a file supports with {@code bad-descriptor}, and a
 * {@link HostFile} refuses what only a directory supports with {@code not-directory}.
 *
 * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types</a>
 */
public abstract class HostDescriptor implements Descriptor {

    private final Path path;
    private final DescriptorFlags flags;
    private final HostPoll poll;

    /**
     * @param path the host object this descriptor names
     * @param flags the access the guest is granted to it
     * @param poll the poll the streams this opens subscribe through
     */
    protected HostDescriptor(Path path, DescriptorFlags flags, HostPoll poll) {
        this.path = Objects.requireNonNull(path, "path");
        this.flags = Objects.requireNonNull(flags, "flags");
        this.poll = Objects.requireNonNull(poll, "poll");
    }

    HostPoll poll() {
        return poll;
    }

    /** The host object this descriptor names. */
    public Path path() {
        return path;
    }

    @Override
    public DescriptorFlags getFlags() {
        return flags;
    }

    @Override
    public DescriptorStat stat() {
        return Metadata.stat(path);
    }

    @Override
    public MetadataHashValue metadataHash() {
        return Metadata.hash(path);
    }

    @Override
    public Boolean isSameObject(Descriptor other) {
        if (!(other instanceof HostDescriptor)) {
            return false;
        }
        try {
            return Files.isSameFile(path, ((HostDescriptor) other).path);
        } catch (IOException e) {
            throw ErrorCodes.failed(e);
        }
    }

    /** Succeeds with no effect, as syncing a descriptor not open for writing does. */
    @Override
    public void sync() {}

    /** Succeeds with no effect, as syncing a descriptor not open for writing does. */
    @Override
    public void syncData() {}

    @Override
    public InputStream readViaStream(BigInteger offset) {
        throw notAFile();
    }

    @Override
    public OutputStream writeViaStream(BigInteger offset) {
        throw notAFile();
    }

    @Override
    public OutputStream appendViaStream() {
        throw notAFile();
    }

    @Override
    public void advise(BigInteger offset, BigInteger length, Advice advice) {
        throw notAFile();
    }

    @Override
    public void setSize(BigInteger size) {
        throw notAFile();
    }

    @Override
    public Tuple2<byte[], Boolean> read(BigInteger length, BigInteger offset) {
        throw notAFile();
    }

    @Override
    public BigInteger write(byte[] buffer, BigInteger offset) {
        throw notAFile();
    }

    @Override
    public DirectoryEntryStream readDirectory() {
        throw notADirectory();
    }

    @Override
    public void createDirectoryAt(String path) {
        throw notADirectory();
    }

    @Override
    public DescriptorStat statAt(PathFlags pathFlags, String path) {
        throw notADirectory();
    }

    @Override
    public void setTimesAt(
            PathFlags pathFlags,
            String path,
            NewTimestamp dataAccessTimestamp,
            NewTimestamp dataModificationTimestamp) {
        throw notADirectory();
    }

    @Override
    public void linkAt(
            PathFlags oldPathFlags, String oldPath, Descriptor newDescriptor, String newPath) {
        throw notADirectory();
    }

    @Override
    public Descriptor openAt(
            PathFlags pathFlags, String path, OpenFlags openFlags, DescriptorFlags flags) {
        throw notADirectory();
    }

    @Override
    public String readlinkAt(String path) {
        throw notADirectory();
    }

    @Override
    public void removeDirectoryAt(String path) {
        throw notADirectory();
    }

    @Override
    public void renameAt(String oldPath, Descriptor newDescriptor, String newPath) {
        throw notADirectory();
    }

    @Override
    public void symlinkAt(String oldPath, String newPath) {
        throw notADirectory();
    }

    @Override
    public void unlinkFileAt(String path) {
        throw notADirectory();
    }

    @Override
    public MetadataHashValue metadataHashAt(PathFlags pathFlags, String path) {
        throw notADirectory();
    }

    private static ErrorCodeException notAFile() {
        return ErrorCodes.error(ErrorCode.BAD_DESCRIPTOR);
    }

    private static ErrorCodeException notADirectory() {
        return ErrorCodes.error(ErrorCode.NOT_DIRECTORY);
    }
}
