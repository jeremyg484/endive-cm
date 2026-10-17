package run.endive.cm.wasi.p2.filesystem.types;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.NotLinkException;
import java.nio.file.ReadOnlyFileSystemException;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.ErrorCode;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.ErrorCodeException;

/**
 * The ways a filesystem operation fails, as the exception the bindings turn into an {@code
 * error-code}.
 *
 * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/filesystem/wit/types.wit">wasi:filesystem/types</a>
 */
public final class ErrorCodes {

    private ErrorCodes() {}

    public static ErrorCodeException error(ErrorCode code) {
        return new ErrorCodeException(code);
    }

    /** The operation failed because of {@code cause}, reported as {@code io} when no code fits. */
    public static ErrorCodeException failed(Throwable cause) {
        ErrorCode code = codeOf(cause);
        return error(code == null ? ErrorCode.IO : code);
    }

    /** The code {@code cause} stands for, or {@code null} when it is not a filesystem failure. */
    public static ErrorCode codeOf(Throwable cause) {
        if (cause instanceof NoSuchFileException) {
            return ErrorCode.NO_ENTRY;
        }
        if (cause instanceof FileAlreadyExistsException) {
            return ErrorCode.EXIST;
        }
        if (cause instanceof DirectoryNotEmptyException) {
            return ErrorCode.NOT_EMPTY;
        }
        if (cause instanceof NotDirectoryException) {
            return ErrorCode.NOT_DIRECTORY;
        }
        if (cause instanceof AccessDeniedException) {
            return ErrorCode.ACCESS;
        }
        if (cause instanceof NotLinkException) {
            return ErrorCode.INVALID;
        }
        if (cause instanceof FileSystemLoopException) {
            return ErrorCode.LOOP;
        }
        if (cause instanceof ReadOnlyFileSystemException) {
            return ErrorCode.READ_ONLY;
        }
        if (cause instanceof UnsupportedOperationException) {
            return ErrorCode.UNSUPPORTED;
        }
        if (cause instanceof ClosedChannelException
                || cause instanceof NonReadableChannelException
                || cause instanceof NonWritableChannelException) {
            return ErrorCode.BAD_DESCRIPTOR;
        }
        if (cause instanceof IOException) {
            return ErrorCode.IO;
        }
        return null;
    }
}
