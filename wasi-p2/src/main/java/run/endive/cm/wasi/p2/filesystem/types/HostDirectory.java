package run.endive.cm.wasi.p2.filesystem.types;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.Descriptor;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DescriptorFlags;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DescriptorStat;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DescriptorType;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DirectoryEntry;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DirectoryEntryStream;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.ErrorCode;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.ErrorCodeException;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.MetadataHashValue;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.NewTimestamp;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.OpenFlags;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.PathFlags;
import run.endive.cm.wasi.p2.io.poll.HostPoll;

/**
 * A descriptor naming a directory, against which every path a guest passes is resolved without
 * reaching outside it.
 *
 * <p>Creating, removing, renaming or changing the metadata of anything through it needs {@code
 * mutate-directory}, and fails with {@code read-only} without it.
 *
 * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types</a>
 */
public final class HostDirectory extends HostDescriptor {

    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;

    /**
     * A preopened directory, the bound no path resolved through it or its descendants leaves.
     *
     * @param path the host directory this descriptor names
     * @param flags the access the guest is granted to it
     * @param poll the poll the streams of files opened through it subscribe through
     */
    public HostDirectory(Path path, DescriptorFlags flags, HostPoll poll) {
        this(path, path, flags, poll);
    }

    private HostDirectory(Path root, Path path, DescriptorFlags flags, HostPoll poll) {
        super(root, path, flags, poll);
    }

    @Override
    public DescriptorType getType() {
        return DescriptorType.DIRECTORY;
    }

    @Override
    public void setTimes(NewTimestamp dataAccessTimestamp, NewTimestamp dataModificationTimestamp) {
        requireMutate();
        Metadata.setTimes(path(), dataAccessTimestamp, dataModificationTimestamp);
    }

    /** The entries as they stand now, since a guest may read them long after asking. */
    @Override
    public DirectoryEntryStream readDirectory() {
        List<DirectoryEntry> entries = new ArrayList<>();
        try (DirectoryStream<Path> listed = Files.newDirectoryStream(path())) {
            for (Path entry : listed) {
                DescriptorType type;
                try {
                    type = Metadata.type(entry);
                } catch (ErrorCodeException e) {
                    if (e.error() == ErrorCode.NO_ENTRY) {
                        continue;
                    }
                    throw e;
                }
                entries.add(new DirectoryEntry(type, entry.getFileName().toString()));
            }
        } catch (IOException e) {
            throw ErrorCodes.failed(e);
        }
        return new HostDirectoryEntryStream(entries);
    }

    @Override
    public void createDirectoryAt(String path) {
        requireMutate();
        try {
            Files.createDirectory(resolve(false, path));
        } catch (IOException e) {
            throw ErrorCodes.failed(e);
        }
    }

    @Override
    public DescriptorStat statAt(PathFlags pathFlags, String path) {
        return Metadata.stat(resolve(pathFlags, path));
    }

    @Override
    public void setTimesAt(
            PathFlags pathFlags,
            String path,
            NewTimestamp dataAccessTimestamp,
            NewTimestamp dataModificationTimestamp) {
        requireMutate();
        Metadata.setTimes(resolve(pathFlags, path), dataAccessTimestamp, dataModificationTimestamp);
    }

    /**
     * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types, link-at</a>
     */
    @Override
    public void linkAt(
            PathFlags oldPathFlags, String oldPath, Descriptor newDescriptor, String newPath) {
        HostDirectory target = directory(newDescriptor);
        requireMutate();
        target.requireMutate();
        Path existing = resolve(oldPathFlags, oldPath);
        Path link = target.resolve(false, newPath);
        requireSameFileSystem(existing, link);
        if (!Files.exists(existing, NOFOLLOW)) {
            throw ErrorCodes.error(ErrorCode.NO_ENTRY);
        }
        if (Files.isDirectory(existing, NOFOLLOW)) {
            throw ErrorCodes.error(ErrorCode.NOT_PERMITTED);
        }
        // Some hosts link what a symbolic link points to, which could be outside.
        if (Files.isSymbolicLink(existing)) {
            throw ErrorCodes.error(ErrorCode.UNSUPPORTED);
        }
        try {
            Files.createLink(link, existing);
        } catch (IOException | UnsupportedOperationException e) {
            throw ErrorCodes.failed(e);
        }
    }

    /**
     * Opens a directory as another {@code HostDirectory} and anything else as a {@link HostFile}.
     * A symbolic link left unfollowed fails with {@code loop}, as {@code O_NOFOLLOW} does.
     *
     * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types, open-at</a>
     */
    @Override
    public Descriptor openAt(
            PathFlags pathFlags, String path, OpenFlags openFlags, DescriptorFlags flags) {
        boolean create = openFlags.has(OpenFlags.Flag.CREATE);
        boolean exclusive = openFlags.has(OpenFlags.Flag.EXCLUSIVE);
        boolean truncate = openFlags.has(OpenFlags.Flag.TRUNCATE);
        boolean directoryOnly = openFlags.has(OpenFlags.Flag.DIRECTORY);
        if (flags.has(DescriptorFlags.Flag.WRITE)
                || flags.has(DescriptorFlags.Flag.MUTATE_DIRECTORY)
                || create
                || truncate) {
            requireMutate();
        }
        if (create && directoryOnly) {
            throw ErrorCodes.error(ErrorCode.INVALID);
        }
        Path target = resolve(pathFlags, path);
        if (path.endsWith("/") && !Files.exists(target, NOFOLLOW)) {
            throw ErrorCodes.error(create ? ErrorCode.IS_DIRECTORY : ErrorCode.NO_ENTRY);
        }
        if (create && exclusive && Files.exists(target, NOFOLLOW)) {
            throw ErrorCodes.error(ErrorCode.EXIST);
        }
        if (Files.isSymbolicLink(target)) {
            throw ErrorCodes.error(ErrorCode.LOOP);
        }
        if (Files.isDirectory(target, NOFOLLOW)) {
            if (flags.has(DescriptorFlags.Flag.WRITE) || create || truncate) {
                throw ErrorCodes.error(ErrorCode.IS_DIRECTORY);
            }
            return new HostDirectory(root(), target, flags, poll());
        }
        if (directoryOnly) {
            throw ErrorCodes.error(
                    Files.exists(target, NOFOLLOW) ? ErrorCode.NOT_DIRECTORY : ErrorCode.NO_ENTRY);
        }
        return HostFile.open(root(), target, fileFlags(flags), poll(), create, exclusive, truncate);
    }

    /** What a guest asked for, apart from {@code mutate-directory}, which only a directory has. */
    private static DescriptorFlags fileFlags(DescriptorFlags flags) {
        return DescriptorFlags.of(
                flags.flags().stream()
                        .filter(flag -> flag != DescriptorFlags.Flag.MUTATE_DIRECTORY)
                        .toArray(DescriptorFlags.Flag[]::new));
    }

    @Override
    public String readlinkAt(String path) {
        try {
            return PathResolver.guestPath(Files.readSymbolicLink(resolve(false, path)));
        } catch (IOException | UnsupportedOperationException e) {
            throw ErrorCodes.failed(e);
        }
    }

    @Override
    public void removeDirectoryAt(String path) {
        requireMutate();
        Path target = resolve(false, path);
        if (target.equals(path())) {
            throw ErrorCodes.error(ErrorCode.INVALID);
        }
        if (Files.exists(target, NOFOLLOW) && !Files.isDirectory(target, NOFOLLOW)) {
            throw ErrorCodes.error(ErrorCode.NOT_DIRECTORY);
        }
        delete(target);
    }

    /**
     * Replaces whatever the new path names, as POSIX {@code rename} does, provided both name a
     * directory or neither does.
     *
     * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types, rename-at</a>
     */
    @Override
    public void renameAt(String oldPath, Descriptor newDescriptor, String newPath) {
        HostDirectory target = directory(newDescriptor);
        requireMutate();
        target.requireMutate();
        Path from = resolve(false, oldPath);
        Path to = target.resolve(false, newPath);
        requireSameFileSystem(from, to);
        if (from.equals(path()) || to.equals(target.path())) {
            throw ErrorCodes.error(ErrorCode.INVALID);
        }
        if (!Files.exists(from, NOFOLLOW)) {
            throw ErrorCodes.error(ErrorCode.NO_ENTRY);
        }
        boolean fromDirectory = Files.isDirectory(from, NOFOLLOW);
        if (Files.exists(to, NOFOLLOW)) {
            boolean toDirectory = Files.isDirectory(to, NOFOLLOW);
            if (fromDirectory && !toDirectory) {
                throw ErrorCodes.error(ErrorCode.NOT_DIRECTORY);
            }
            if (!fromDirectory && toDirectory) {
                throw ErrorCodes.error(ErrorCode.IS_DIRECTORY);
            }
        }
        if (from.equals(to)) {
            return;
        }
        if (fromDirectory && to.startsWith(from)) {
            throw ErrorCodes.error(ErrorCode.INVALID);
        }
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw ErrorCodes.failed(e);
        }
    }

    /**
     * The link is created as written, so a target reaching outside this directory is refused
     * only when something resolves through the link.
     *
     * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types, symlink-at</a>
     */
    @Override
    public void symlinkAt(String oldPath, String newPath) {
        requireMutate();
        Path target = PathResolver.relative(path().getFileSystem(), oldPath);
        try {
            Files.createSymbolicLink(resolve(false, newPath), target);
        } catch (IOException | UnsupportedOperationException e) {
            throw ErrorCodes.failed(e);
        }
    }

    @Override
    public void unlinkFileAt(String path) {
        requireMutate();
        Path target = resolve(false, path);
        if (Files.isDirectory(target, NOFOLLOW)) {
            throw ErrorCodes.error(ErrorCode.IS_DIRECTORY);
        }
        delete(target);
    }

    @Override
    public MetadataHashValue metadataHashAt(PathFlags pathFlags, String path) {
        return Metadata.hash(resolve(pathFlags, path));
    }

    private Path resolve(PathFlags pathFlags, String path) {
        return resolve(pathFlags.has(PathFlags.Flag.SYMLINK_FOLLOW), path);
    }

    private Path resolve(boolean followFinal, String path) {
        return PathResolver.resolve(path(), path, followFinal);
    }

    private void requireMutate() {
        if (!getFlags().has(DescriptorFlags.Flag.MUTATE_DIRECTORY)) {
            throw ErrorCodes.error(ErrorCode.READ_ONLY);
        }
    }

    /** The directory another descriptor argument names. */
    private static HostDirectory directory(Descriptor descriptor) {
        if (descriptor instanceof HostDirectory) {
            return (HostDirectory) descriptor;
        }
        throw ErrorCodes.error(
                descriptor instanceof HostDescriptor
                        ? ErrorCode.NOT_DIRECTORY
                        : ErrorCode.BAD_DESCRIPTOR);
    }

    private static void requireSameFileSystem(Path first, Path second) {
        if (!first.getFileSystem().equals(second.getFileSystem())) {
            throw ErrorCodes.error(ErrorCode.CROSS_DEVICE);
        }
    }

    private static void delete(Path path) {
        try {
            Files.delete(path);
        } catch (IOException e) {
            throw ErrorCodes.failed(e);
        }
    }
}
