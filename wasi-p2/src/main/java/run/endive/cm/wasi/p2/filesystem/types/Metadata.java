package run.endive.cm.wasi.p2.filesystem.types;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import run.endive.cm.wasi.p2.bindings.wasi.clocks.wallclock.Datetime;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DescriptorStat;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.DescriptorType;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.ErrorCode;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.MetadataHashValue;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.NewTimestamp;

/**
 * Reads and adjusts the metadata of a host path, never following a symbolic link it names.
 *
 * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types</a>
 */
final class Metadata {

    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};

    /** Mixed into every hash, so its inputs cannot be recovered from it. */
    private static final byte[] SECRET = secret();

    private Metadata() {}

    private static byte[] secret() {
        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    /** The attributes of {@code path}. */
    static DescriptorStat stat(Path path) {
        BasicFileAttributes attributes = attributes(path);
        return new DescriptorStat(
                type(attributes),
                BigInteger.valueOf(linkCount(path)),
                BigInteger.valueOf(attributes.size()),
                datetime(attributes.lastAccessTime()),
                datetime(attributes.lastModifiedTime()),
                datetime(statusChangeTime(path)));
    }

    /**
     * The type and size of an open file that no longer has the name it was opened under, which is
     * all the host can still tell about it.
     */
    static DescriptorStat detached(DescriptorType type, long size) {
        return new DescriptorStat(
                type, BigInteger.ZERO, BigInteger.valueOf(size), null, null, null);
    }

    /** What identifies the object {@code path} names, or {@code null} where the host has none. */
    static Object fileKey(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW).fileKey();
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    /** The type of the object {@code path} names. */
    static DescriptorType type(Path path) {
        return type(attributes(path));
    }

    static DescriptorType type(BasicFileAttributes attributes) {
        if (attributes.isDirectory()) {
            return DescriptorType.DIRECTORY;
        }
        if (attributes.isRegularFile()) {
            return DescriptorType.REGULAR_FILE;
        }
        if (attributes.isSymbolicLink()) {
            return DescriptorType.SYMBOLIC_LINK;
        }
        return DescriptorType.UNKNOWN;
    }

    /**
     * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types, set-times</a>
     */
    static void setTimes(Path path, NewTimestamp dataAccess, NewTimestamp dataModification) {
        BasicFileAttributeView view =
                Files.getFileAttributeView(path, BasicFileAttributeView.class, NOFOLLOW);
        try {
            view.setTimes(fileTime(dataModification), fileTime(dataAccess), null);
        } catch (IOException e) {
            throw ErrorCodes.failed(e);
        }
    }

    /**
     * A hash of what changes when {@code path} is modified or replaced.
     *
     * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types, metadata-hash</a>
     */
    static MetadataHashValue hash(Path path) {
        BasicFileAttributes attributes = attributes(path);
        Object fileKey = attributes.fileKey();
        return hash(
                fileKey == null ? path.toAbsolutePath() : fileKey,
                attributes.size(),
                attributes.lastModifiedTime());
    }

    /**
     * A hash of an object's identity, size and last modification, which is {@code null} when it
     * is not known.
     */
    static MetadataHashValue hash(Object identity, long size, FileTime modifiedTime) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides SHA-256", e);
        }
        digest.update(SECRET);
        digest.update(String.valueOf(identity).getBytes(StandardCharsets.UTF_8));
        Instant modified = modifiedTime == null ? Instant.EPOCH : modifiedTime.toInstant();
        digest.update(
                ByteBuffer.allocate(Long.BYTES * 3)
                        .putLong(size)
                        .putLong(modified.getEpochSecond())
                        .putLong(modified.getNano())
                        .array());
        byte[] hashed = digest.digest();
        return new MetadataHashValue(
                new BigInteger(1, Arrays.copyOfRange(hashed, 0, 8)),
                new BigInteger(1, Arrays.copyOfRange(hashed, 8, 16)));
    }

    private static BasicFileAttributes attributes(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW);
        } catch (IOException e) {
            throw ErrorCodes.failed(e);
        }
    }

    /** The hard links to {@code path}, which is one where the host does not count them. */
    private static long linkCount(Path path) {
        Object count = unixAttribute(path, "unix:nlink");
        return count instanceof Number ? ((Number) count).longValue() : 1;
    }

    private static FileTime statusChangeTime(Path path) {
        Object changed = unixAttribute(path, "unix:ctime");
        return changed instanceof FileTime ? (FileTime) changed : null;
    }

    /** A {@code unix} view attribute, or {@code null} where the host has no such view. */
    private static Object unixAttribute(Path path, String attribute) {
        try {
            return Files.getAttribute(path, attribute, NOFOLLOW);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            return null;
        } catch (IOException e) {
            throw ErrorCodes.failed(e);
        }
    }

    /** {@code time} as a {@code datetime}, which cannot represent a time before the epoch. */
    private static Datetime datetime(FileTime time) {
        if (time == null) {
            return null;
        }
        Instant instant = time.toInstant();
        if (instant.isBefore(Instant.EPOCH)) {
            return null;
        }
        return new Datetime(BigInteger.valueOf(instant.getEpochSecond()), (long) instant.getNano());
    }

    /** The time a {@code new-timestamp} sets, or {@code null} to leave it unchanged. */
    private static FileTime fileTime(NewTimestamp timestamp) {
        if (timestamp instanceof NewTimestamp.Now) {
            return FileTime.from(Instant.now());
        }
        if (timestamp instanceof NewTimestamp.Timestamp) {
            Datetime value = ((NewTimestamp.Timestamp) timestamp).value();
            if (value.seconds().bitLength() >= Long.SIZE) {
                throw ErrorCodes.error(ErrorCode.OVERFLOW);
            }
            if (value.nanoseconds() >= 1_000_000_000L) {
                throw ErrorCodes.error(ErrorCode.INVALID);
            }
            try {
                return FileTime.from(
                        Instant.ofEpochSecond(value.seconds().longValue(), value.nanoseconds()));
            } catch (DateTimeException e) {
                throw ErrorCodes.error(ErrorCode.OVERFLOW);
            }
        }
        return null;
    }
}
