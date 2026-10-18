package run.endive.cm.wasi.p2.random.random;

import java.math.BigInteger;
import java.security.SecureRandom;
import run.endive.cm.wasi.p2.bindings.wasi.random.random.Host;
import run.endive.cm.wasi.p2.random.RandomValues;

/**
 * The host side of {@code wasi:random/random}, drawing from a {@link SecureRandom}.
 *
 * @see <a href="https://github.com/WebAssembly/WASI/blob/v0.2.12/proposals/random/wit/random.wit">wasi:random/random</a>
 */
public class HostRandom implements Host {

    private final SecureRandom random;

    /** Draws from the platform's default {@link SecureRandom}. */
    public HostRandom() {
        this(new SecureRandom());
    }

    /** Draws from {@code random}, which must never block and must always return fresh data. */
    public HostRandom(SecureRandom random) {
        this.random = random;
    }

    /** Traps when the host cannot hold {@code len} bytes. */
    @Override
    public byte[] getRandomBytes(BigInteger len) {
        return RandomValues.bytes(random, len);
    }

    @Override
    public BigInteger getRandomU64() {
        return RandomValues.u64(random);
    }
}
