package com.droidenx.clouseau.core;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class Utf8BomTest {

    private static byte[] skip(int... values) throws IOException {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) bytes[i] = (byte) values[i];
        return Utf8Bom.skip(new ByteArrayInputStream(bytes)).readAllBytes();
    }

    @Test
    void removesLeadingBom() throws IOException {
        assertThat(skip(0xEF, 0xBB, 0xBF, 'a', 'b')).containsExactly('a', 'b');
    }

    @Test
    void leavesOtherStartsUntouched() throws IOException {
        assertThat(skip('a', 'b', 'c', 'd')).containsExactly('a', 'b', 'c', 'd');
    }

    @Test
    void leavesPartialBomUntouched() throws IOException {
        assertThat(skip(0xEF, 0xBB, 'x')).containsExactly(0xEF, 0xBB, 'x');
        assertThat(skip(0xEF, 0xBB)).containsExactly(0xEF, 0xBB);
    }

    @Test
    void handlesShortAndEmptyStreams() throws IOException {
        assertThat(skip()).isEmpty();
        assertThat(skip('a')).containsExactly('a');
    }

    @Test
    void bomOnlyStreamBecomesEmpty() throws IOException {
        assertThat(skip(0xEF, 0xBB, 0xBF)).isEmpty();
    }
}
