package run.endive.cm.wasi.p2.filesystem.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.ReadOnlyFileSystemException;
import org.junit.jupiter.api.Test;
import run.endive.cm.wasi.p2.bindings.wasi.filesystem.types.ErrorCode;

class ErrorCodesTest {

    @Test
    void eachFileSystemExceptionHasItsCode() {
        assertEquals(ErrorCode.NO_ENTRY, ErrorCodes.codeOf(new NoSuchFileException("a")));
        assertEquals(ErrorCode.EXIST, ErrorCodes.codeOf(new FileAlreadyExistsException("a")));
        assertEquals(ErrorCode.NOT_EMPTY, ErrorCodes.codeOf(new DirectoryNotEmptyException("a")));
        assertEquals(ErrorCode.NOT_DIRECTORY, ErrorCodes.codeOf(new NotDirectoryException("a")));
        assertEquals(ErrorCode.READ_ONLY, ErrorCodes.codeOf(new ReadOnlyFileSystemException()));
        assertEquals(ErrorCode.BAD_DESCRIPTOR, ErrorCodes.codeOf(new ClosedChannelException()));
    }

    @Test
    void anyOtherIoFailureIsIo() {
        assertEquals(ErrorCode.IO, ErrorCodes.codeOf(new IOException("disk on fire")));
    }

    @Test
    void aFailureOutsideTheFileSystemHasNoCode() {
        assertNull(ErrorCodes.codeOf(new IllegalStateException()));
    }

    @Test
    void failingWithoutACodeReportsIo() {
        assertEquals(ErrorCode.IO, ErrorCodes.failed(new IllegalStateException()).error());
        assertEquals(ErrorCode.NO_ENTRY, ErrorCodes.failed(new NoSuchFileException("a")).error());
    }
}
