package run.endive.cm.wasi.p2.random.random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import run.endive.runtime.TrapException;

class HostRandomTest {

    private static final BigInteger U64_LIMIT = BigInteger.ONE.shiftLeft(64);

    @Test
    void bytesComeFromTheGivenGenerator() throws NoSuchAlgorithmException {
        byte[] expected = new byte[32];
        seeded().nextBytes(expected);

        assertArrayEquals(
                expected, new HostRandom(seeded()).getRandomBytes(BigInteger.valueOf(32)));
    }

    @Test
    void aU64ComesFromTheGivenGenerator() throws NoSuchAlgorithmException {
        long expected = seeded().nextLong();

        assertEquals(expected, new HostRandom(seeded()).getRandomU64().longValue());
    }

    @Test
    void theDefaultGeneratorReturnsFreshData() {
        HostRandom random = new HostRandom();

        byte[] first = random.getRandomBytes(BigInteger.valueOf(32));
        byte[] second = random.getRandomBytes(BigInteger.valueOf(32));

        assertEquals(32, first.length);
        assertFalse(Arrays.equals(first, second));
        assertTrue(random.getRandomU64().signum() >= 0);
        assertTrue(random.getRandomU64().compareTo(U64_LIMIT) < 0);
    }

    @Test
    void anImpossibleLengthTraps() {
        assertThrows(
                TrapException.class,
                () -> new HostRandom().getRandomBytes(U64_LIMIT.subtract(BigInteger.ONE)));
    }

    /** A generator seeded before its first use, which makes its output repeatable. */
    private static SecureRandom seeded() throws NoSuchAlgorithmException {
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(42L);
        return random;
    }
}
