package com.bfo.quickjs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;


public class ArrayBufferTest {

    @Test
    public void testArrayBufferSharing() throws Exception {
        try (JSRuntime runtime = new JSRuntime().setStderr(System.err).setStdout(System.out);
                JSContext context = runtime.newContext()) {
            ByteBuffer buffer = context.newBuffer(1024);
            context.put("abuf", buffer);
            assertEquals(1024, context.evalNow("abuf.byteLength"));

            context.evalNow("var ubuf = new Uint8Array(abuf); ubuf[2] = 2");
            assertEquals((byte)2, buffer.get(2));
            buffer.put(3, (byte)3);
            assertEquals(3, context.evalNow("ubuf[3]"));

            ByteBuffer jsBuffer = (ByteBuffer)context.evalNow("var jsabuf = new ArrayBuffer(1024); jsabuf");
            jsBuffer.put(4, (byte)4);
            assertEquals(4, context.evalNow("var jsubuf = new Uint8Array(jsabuf); jsubuf[4]"));
            context.evalNow("jsubuf[5] = 5");
            assertEquals((byte)5, jsBuffer.get(5));

            ByteBuffer large = context.newBuffer(70000);
            context.put("large", large);
            context.evalNow("var largeView = new Uint8Array(large); largeView[69999] = 9");
            assertEquals((byte)9, large.get(69999));
            large.put(65536, (byte)7);
            assertEquals(7, context.evalNow("largeView[65536]"));
        }
    }

}
