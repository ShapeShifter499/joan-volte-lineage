package android.system;

import java.io.FileDescriptor;
import java.io.IOException;
import org.joan.ims.TestJoanTransport;

/**
 * Runtime-only, in-memory Os.write replacement for the transport suite.
 * Production is compiled against android.jar BEFORE this shim is compiled.
 * The shim's classpath is used only for TestJoanTransport, never packaging
 * or other suites. No real file descriptor or network endpoint is accessed.
 */
public final class Os {
    private Os() {}
    public static int write(FileDescriptor fd, byte[] bytes, int off, int count)
            throws IOException {
        return TestJoanTransport.PeerWrites.write(fd, bytes, off, count);
    }
}
