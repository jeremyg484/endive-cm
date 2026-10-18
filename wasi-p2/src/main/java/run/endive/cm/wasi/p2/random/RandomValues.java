package run.endive.cm.wasi.p2.random;

import java.math.BigInteger;
import java.util.Random;
import run.endive.runtime.TrapException;

/** The values every {@code wasi:random} interface hands out, drawn from a {@link Random}. */
public final class RandomValues {

    /** The longest array the JVM is sure to allocate. */
    static final int MAX_LENGTH = Integer.MAX_VALUE - 8;

    private static final BigInteger U64_MASK =
            BigInteger.ONE.shiftLeft(Long.SIZE).subtract(BigInteger.ONE);

    private RandomValues() {}

    /** Draws {@code len} bytes, trapping when the host cannot hold that many. */
    public static byte[] bytes(Random random, BigInteger len) {
        if (len.compareTo(BigInteger.valueOf(MAX_LENGTH)) > 0) {
            throw new TrapException(len + " random bytes exceed what the host can hold");
        }
        byte[] bytes = new byte[len.intValueExact()];
        random.nextBytes(bytes);
        return bytes;
    }

    /** Draws a {@code u64}. */
    public static BigInteger u64(Random random) {
        return unsigned(random.nextLong());
    }

    /** The {@code u64} whose bits {@code value} holds. */
    public static BigInteger unsigned(long value) {
        return BigInteger.valueOf(value).and(U64_MASK);
    }
}
