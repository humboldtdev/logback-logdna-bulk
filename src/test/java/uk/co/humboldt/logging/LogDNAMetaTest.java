package uk.co.humboldt.logging;

import org.junit.After;
import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class LogDNAMetaTest {

    @After
    public void clear() {
        LogDNAMeta.clear();
    }

    @Test
    public void testEmpty() {

        Map<String, Object> empty = LogDNAMeta.getAll();
        assertNotNull(empty);
        assertEquals(0, empty.size());
    }

    @Test
    public void testPutNumber() {

        LogDNAMeta.put("number", 1234L);

        Map<String, Object> all = LogDNAMeta.getAll();
        Object number = all.get("number");
        assertEquals(1234L, number);
    }

    @Test
    public void testPutString() {

        LogDNAMeta.put("string", "12345");

        Map<String, Object> all = LogDNAMeta.getAll();
        Object string = all.get("string");
        assertEquals("12345", string);
    }

    @Test
    public void testRemove() {

        LogDNAMeta.put("word", "xyzzy");
        LogDNAMeta.remove("word");

        testEmpty();
    }

    @Test
    public void testClose() {

        try(LogDNAMeta.CloseableMeta c =
                    LogDNAMeta.putCloseable("test", "value")) {

            Map<String, Object> all = LogDNAMeta.getAll();
            Object string = all.get("test");
            assertEquals("value", string);
        }

        testEmpty();
    }
}
