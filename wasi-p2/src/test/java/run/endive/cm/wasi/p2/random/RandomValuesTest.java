package run.endive.cm.wasi.p2.random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;
import run.endive.runtime.TrapException;

class RandomValuesTest {

    @Test
    void bytesAreDrawnFromTheGenerator() {
        byte[] expected = new byte[16];
        new Random(7).nextBytes(expected);

        assertArrayEquals(expected, RandomValues.bytes(new Random(7), BigInteger.valueOf(16)));
    }

    @Test
    void noBytesAreAnEmptyList() {
        assertEquals(0, RandomValues.bytes(new Random(), BigInteger.ZERO).length);
    }

    @Test
    void moreBytesThanTheHostCanHoldTrap() {
        Random random = new Random();

        assertThrows(
                TrapException.class,
                () -> RandomValues.bytes(random, BigInteger.valueOf(RandomValues.MAX_LENGTH + 1L)));
        assertThrows(
                TrapException.class, () -> RandomValues.bytes(random, RandomValues.unsigned(-1L)));
    }

    @Test
    void aU64HoldsTheBitsOfALong() {
        assertEquals(new Random(7).nextLong(), RandomValues.u64(new Random(7)).longValue());
    }

    @Test
    void aNegativeLongIsALargeU64() {
        assertEquals(
                BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE), RandomValues.unsigned(-1L));
        assertEquals(BigInteger.ONE.shiftLeft(63), RandomValues.unsigned(Long.MIN_VALUE));
        assertEquals(BigInteger.valueOf(42), RandomValues.unsigned(42L));
    }
}
