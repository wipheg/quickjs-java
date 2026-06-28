package com.bfo.quickjs;

import static com.dylibso.chicory.runtime.ConstantEvaluators.computeConstantValue;

import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import com.dylibso.chicory.runtime.*;
import com.dylibso.chicory.wasm.ChicoryException;
import com.dylibso.chicory.wasm.UninstantiableException;
import com.dylibso.chicory.wasm.types.*;

final class JSRuntimeMemory implements com.dylibso.chicory.runtime.Memory {

    private final MemoryLimits limits;
    private final Object growLock = new Object();
    private final Map<Integer,Object> locks;
    private byte[] data;
    private DataSegment[] dataSegments;
    private volatile int pages;
    private volatile boolean pinned;

    JSRuntimeMemory(MemoryLimits limits) {
        this.limits = limits;
        this.data = new byte[bytes(limits.initialPages())];
        this.pages = limits.initialPages();
        this.locks = limits.shared() ? new ConcurrentHashMap<>() : null;
    }

    ByteBuffer buffer(int ptr, int len) {
        checkBounds(ptr, len, sizeInBytes(), IllegalArgumentException::new);
        pinned = true;
        return ByteBuffer.wrap(data, ptr, len).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    long ptrLen(ByteBuffer buffer) {
        if (buffer == null) {
            throw new NullPointerException();
        } else if (!buffer.hasArray() || buffer.array() != data) {
            throw new IllegalArgumentException("ByteBuffer is not backed by WebAssembly memory");
        }
        int ptr = Math.addExact(buffer.arrayOffset(), buffer.position());
        int len = buffer.remaining();
        checkBounds(ptr, len, sizeInBytes(), IllegalArgumentException::new);
        return ((long)ptr << 32) | (len & 0xffffffffL);
    }

    @Override public int pages() {
        return pages;
    }

    @Override public int grow(int size) {
        if (size < 0) {
            return -1;
        }
        synchronized (growLock) {
            int previous = pages;
            int next = previous + size;
            if (next < previous || next > maximumPages()) {
                return -1;
            }
            int bytes = bytes(next);
            if (bytes > data.length) {
                if (pinned) {
                    return -1;
                }
                data = Arrays.copyOf(data, bytes);
            }
            pages = next;
            return previous;
        }
    }

    @Override public int initialPages() {
        return limits.initialPages();
    }

    @Override public int maximumPages() {
        return Math.min(limits.maximumPages(), com.dylibso.chicory.runtime.Memory.RUNTIME_MAX_PAGES);
    }

    @Override public boolean shared() {
        return limits.shared();
    }

    @Override public Object lock(int address) {
        if (!shared()) {
            return new Object();
        }
        return locks.computeIfAbsent(address, k -> new Object());
    }

    @Override public int waitOn(int address, int expected, long timeout) {
        return waitOn(address, timeout, () -> readInt(address) == expected);
    }

    @Override public int waitOn(int address, long expected, long timeout) {
        return waitOn(address, timeout, () -> readLong(address) == expected);
    }

    private int waitOn(int address, long timeout, BooleanSupplier condition) {
        if (!shared()) {
            throw new ChicoryException("Attempt to wait on a non-shared memory, not supported.");
        }
        Object lock = lock(address);
        long deadline = timeout < 0 ? Long.MAX_VALUE : System.nanoTime() + timeout;
        synchronized (lock) {
            if (!condition.getAsBoolean()) {
                return 1;
            }
            while (condition.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return 2;
                }
                try {
                    lock.wait(Math.max(remaining / 1_000_000L, 0), Math.max((int)(remaining % 1_000_000L), 0));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }
            return 0;
        }
    }

    @Override public int notify(int address, int maxThreads) {
        if (!shared()) {
            return 0;
        }
        Object lock = locks.get(address);
        if (lock == null) {
            return 0;
        }
        synchronized (lock) {
            lock.notifyAll();
        }
        return maxThreads < 0 ? 1 : Math.min(maxThreads, 1);
    }

    @Override public void initialize(Instance instance, DataSegment[] dataSegments) {
        initialize(instance, dataSegments, 0);
    }

    @Override public void initialize(Instance instance, DataSegment[] dataSegments, int memoryIndex) {
        this.dataSegments = dataSegments;
        if (dataSegments == null) {
            return;
        }
        for (DataSegment dataSegment : dataSegments) {
            if (dataSegment instanceof ActiveDataSegment) {
                ActiveDataSegment segment = (ActiveDataSegment)dataSegment;
                if (segment.index() != memoryIndex) {
                    continue;
                }
                byte[] segmentData = segment.data();
                int offset = (int)computeConstantValue(instance, segment.offsetInstructions())[0];
                checkBounds(offset, segmentData.length, sizeInBytes(), UninstantiableException::new);
                write(offset, segmentData);
            }
        }
    }

    @Override public void initPassiveSegment(int segmentId, int dest, int offset, int size) {
        byte[] segmentData = dataSegments[segmentId].data();
        checkBounds(offset, size, segmentData.length, WasmRuntimeException::new);
        write(dest, segmentData, offset, size);
    }

    @Override public void write(int addr, byte[] data) {
        write(addr, data, 0, data.length);
    }

    @Override public void write(int addr, byte[] data, int offset, int size) {
        checkBounds(offset, size, data.length, WasmRuntimeException::new);
        checkBounds(addr, size, sizeInBytes(), WasmRuntimeException::new);
        System.arraycopy(data, offset, this.data, addr, size);
    }

    @Override public byte read(int addr) {
        checkBounds(addr, 1, sizeInBytes(), WasmRuntimeException::new);
        return data[addr];
    }

    @Override public byte[] readBytes(int addr, int len) {
        checkBounds(addr, len, sizeInBytes(), WasmRuntimeException::new);
        return Arrays.copyOfRange(data, addr, addr + len);
    }

    @Override public void writeI32(int addr, int value) {
        checkBounds(addr, 4, sizeInBytes(), WasmRuntimeException::new);
        data[addr] = (byte)value;
        data[addr + 1] = (byte)(value >>> 8);
        data[addr + 2] = (byte)(value >>> 16);
        data[addr + 3] = (byte)(value >>> 24);
    }

    @Override public int readInt(int addr) {
        checkBounds(addr, 4, sizeInBytes(), WasmRuntimeException::new);
        return (data[addr] & 0xff) | ((data[addr + 1] & 0xff) << 8) | ((data[addr + 2] & 0xff) << 16) | ((data[addr + 3] & 0xff) << 24);
    }

    @Override public void writeLong(int addr, long value) {
        checkBounds(addr, 8, sizeInBytes(), WasmRuntimeException::new);
        for (int i=0;i<8;i++) {
            data[addr + i] = (byte)(value >>> (i * 8));
        }
    }

    @Override public long readLong(int addr) {
        checkBounds(addr, 8, sizeInBytes(), WasmRuntimeException::new);
        long value = 0;
        for (int i=0;i<8;i++) {
            value |= (data[addr + i] & 0xffL) << (i * 8);
        }
        return value;
    }

    @Override public void writeShort(int addr, short value) {
        checkBounds(addr, 2, sizeInBytes(), WasmRuntimeException::new);
        data[addr] = (byte)value;
        data[addr + 1] = (byte)(value >>> 8);
    }

    @Override public short readShort(int addr) {
        checkBounds(addr, 2, sizeInBytes(), WasmRuntimeException::new);
        return (short)((data[addr] & 0xff) | ((data[addr + 1] & 0xff) << 8));
    }

    @Override public long readU16(int addr) {
        return readShort(addr) & 0xffffL;
    }

    @Override public void writeByte(int addr, byte value) {
        checkBounds(addr, 1, sizeInBytes(), WasmRuntimeException::new);
        data[addr] = value;
    }

    @Override public void writeF32(int addr, float value) {
        writeI32(addr, Float.floatToRawIntBits(value));
    }

    @Override public long readF32(int addr) {
        return readInt(addr);
    }

    @Override public float readFloat(int addr) {
        return Float.intBitsToFloat(readInt(addr));
    }

    @Override public void writeF64(int addr, double value) {
        writeLong(addr, Double.doubleToRawLongBits(value));
    }

    @Override public double readDouble(int addr) {
        return Double.longBitsToDouble(readLong(addr));
    }

    @Override public long readF64(int addr) {
        return readLong(addr);
    }

    @Override public void zero() {
        Arrays.fill(data, 0, sizeInBytes(), (byte)0);
    }

    @Override public void fill(byte value, int fromIndex, int toIndex) {
        checkBounds(fromIndex, toIndex - fromIndex, sizeInBytes(), WasmRuntimeException::new);
        Arrays.fill(data, fromIndex, toIndex, value);
    }

    @Override public void copy(int dest, int src, int size) {
        checkBounds(dest, size, sizeInBytes(), WasmRuntimeException::new);
        checkBounds(src, size, sizeInBytes(), WasmRuntimeException::new);
        System.arraycopy(data, src, data, dest, size);
    }

    @Override public void drop(int segment) {
        dataSegments[segment] = PassiveDataSegment.EMPTY;
    }

    private int sizeInBytes() {
        return bytes(pages);
    }

    private static int bytes(int pages) {
        return com.dylibso.chicory.runtime.Memory.PAGE_SIZE * Math.min(pages, com.dylibso.chicory.runtime.Memory.RUNTIME_MAX_PAGES);
    }

    private static void checkBounds(int addr, int size, int limit, Function<String,? extends RuntimeException> factory) {
        if (addr < 0 || size < 0 || addr > limit || size > limit - addr) {
            throw factory.apply("out of bounds memory access: attempted to access address: " + addr + " but limit is: " + limit + " and size: " + size);
        }
    }

    private interface BooleanSupplier {
        boolean getAsBoolean();
    }
}
