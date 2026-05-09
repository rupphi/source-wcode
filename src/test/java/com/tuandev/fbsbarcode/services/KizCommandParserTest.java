package com.tuandev.fbsbarcode.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KizCommandParserTest {
    @Test
    void parsesMultipleRanges() {
        List<KizCommandParser.KizRange> result = KizCommandParser.parse("12:1-3\n5:10-12");

        assertEquals(2, result.size());
        assertEquals(12, result.get(0).categoryId());
        assertEquals(1, result.get(0).from());
        assertEquals(3, result.get(0).to());
        assertEquals(3, result.get(0).count());
    }

    @Test
    void rejectsInvalidSyntax() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> KizCommandParser.parse("12-3"));

        assertEquals("Sai định dạng: 12-3 | Đúng: ID:FROM-TO", ex.getMessage());
    }
}
