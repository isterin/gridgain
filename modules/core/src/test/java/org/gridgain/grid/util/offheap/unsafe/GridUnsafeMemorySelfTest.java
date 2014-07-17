/* @java.file.header */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.util.offheap.unsafe;

import org.gridgain.grid.GridFuture;
import org.gridgain.grid.util.*;
import org.gridgain.testframework.junits.common.*;
import org.jdk8.backport.*;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.*;

/**
 * Tests unsafe memory.
 */
public class GridUnsafeMemorySelfTest extends GridCommonAbstractTest {
    /**
     * @throws Exception If failed.
     */
    public void testBytes() throws Exception {
        GridUnsafeMemory mem = new GridUnsafeMemory(64);

        String s = "123";

        byte[] bytes = s.getBytes();

        int size = bytes.length * 2;

        long addr = mem.allocate(size);

        try {
            mem.writeBytes(addr, bytes);

            byte[] read = mem.readBytes(addr, bytes.length);

            assert Arrays.equals(bytes, read);
        }
        finally {
            mem.release(addr, size);
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testByte() throws Exception {
        GridUnsafeMemory mem = new GridUnsafeMemory(64);

        int size = 32;

        long addr = mem.allocate(size);

        try {
            byte b1 = 123;

            mem.writeByte(addr, b1);

            byte b2 = mem.readByte(addr);

            assertEquals(b1, b2);

            byte b3 = 11;

            mem.writeByteVolatile(addr, b3);

            assertEquals(b3, mem.readByteVolatile(addr));
        }
        finally {
            mem.release(addr, size);
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testShort() throws Exception {
        GridUnsafeMemory mem = new GridUnsafeMemory(64);

        int size = 16;

        long addr = mem.allocate(size);

        try {
            short s1 = (short)56777;

            mem.writeShort(addr, s1);

            assertEquals(s1, mem.readShort(addr));
        }
        finally {
            mem.release(addr, size);
        }
    }

    /**
     *
     */
    public void testFloat() {
        GridUnsafeMemory mem = new GridUnsafeMemory(64);

        int size = 32;

        long addr = mem.allocate(size);

        try {
            float f1 = 0.23223f;

            mem.writeFloat(addr, f1);

            assertEquals(f1, mem.readFloat(addr));
        }
        finally {
            mem.release(addr, size);
        }
    }

    /**
     *
     */
    public void testDouble() {
        GridUnsafeMemory mem = new GridUnsafeMemory(64);

        int size = 32;

        long addr = mem.allocate(size);

        try {
            double d1 = 0.2323423;

            mem.writeDouble(addr, d1);

            assertEquals(d1, mem.readDouble(addr));
        }
        finally {
            mem.release(addr, size);
        }
    }


    /**
     * @throws Exception If failed.
     */
    public void testInt() throws Exception {
        GridUnsafeMemory mem = new GridUnsafeMemory(64);

        int size = 32;

        long addr = mem.allocate(size);

        try {
            int i1 = 123;

            mem.writeInt(addr, i1);

            int i2 = mem.readInt(addr);

            assertEquals(i1, i2);

            int i3 = 321;

            mem.writeIntVolatile(addr, i3);

            int i4 = 222;

            assertTrue(mem.casInt(addr, i3, i4));
            assertFalse(mem.casInt(addr, i3, 0));

            assertEquals(i4, mem.readIntVolatile(addr));

        }
        finally {
            mem.release(addr, size);
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testLong() throws Exception {
        GridUnsafeMemory mem = new GridUnsafeMemory(64);

        int size = 32;

        long addr = mem.allocate(size);

        try {
            long l1 = 123456;

            mem.writeLong(addr, l1);

            long l2 = mem.readLong(addr);

            assertEquals(l1, l2);

            long l3 = 654321;

            mem.writeLongVolatile(addr, l3);

            long l4 = 666666;

            assertTrue(mem.casLong(addr, l3, l4));
            assertFalse(mem.casLong(addr, l3, 0));

            assertEquals(l4, mem.readLongVolatile(addr));
        }
        finally {
            mem.release(addr, size);
        }
    }

    /**
     * @throws Exception if failed.
     */
    public void testGuardedOps() throws Exception {
        final AtomicReferenceArray<CmpMem> ptrs = new AtomicReferenceArray<>(4);

        final AtomicBoolean finished = new AtomicBoolean();

        final LongAdder cntr = new LongAdder();

        final GridUnsafeGuard guard = new GridUnsafeGuard();

        GridFuture<?> fut = multithreadedAsync(new Callable<Object>() {
            @Override public Object call() throws Exception {
                Random rnd = new GridRandom();

                while (!finished.get()) {
                    int idx = rnd.nextInt(ptrs.length());

                    guard.begin();

                    try {
                        final CmpMem old;

                        CmpMem ptr = null;

                        switch(rnd.nextInt(10)) {
                            case 0:
                                ptr = new CmpMem(cntr);

                                //noinspection fallthrough
                            case 1:
                                old = ptrs.getAndSet(idx, ptr);

                                if (old != null) {
                                    guard.finalizeLater(new Runnable() {
                                        @Override public void run() {
                                            old.deallocate();
                                        }
                                    });
                                }

                                break;

                            case 2:
                                if (rnd.nextBoolean())
                                    ptr = new CmpMem(cntr);

                                old = ptrs.getAndSet(idx, ptr);

                                if (old != null)
                                    guard.releaseLater(old);

                                break;

                            default:
                                old = ptrs.get(idx);

                                if (old != null)
                                    old.touch();
                        }
                    }
                    finally {
                        guard.end();
                    }
                }

                return null;
            }
        }, 37);

        Thread.sleep(60000);

        finished.set(true);

        fut.get();

        for (int i = 0; i < ptrs.length(); i++) {
            CmpMem ptr = ptrs.get(i);

            if (ptr != null) {
                ptr.touch();

                ptr.deallocate();
            }
        }

        assertEquals(0, cntr.sum());
    }

    private static class CmpMem extends AtomicInteger implements GridUnsafeCompoundMemory {
        /** */
        private AtomicBoolean deallocated = new AtomicBoolean();

        /** */
        private LongAdder cntr;

        /**
         * @param cntr Counter.
         */
        CmpMem(LongAdder cntr) {
            this.cntr = cntr;

            cntr.increment();
        }

        public void touch() {
            assert !deallocated.get();
        }

        @Override public void deallocate() {
            boolean res = deallocated.compareAndSet(false, true);

            assert res;

            cntr.add(-get() - 1); // Merged plus this instance.
        }

        @Override public void merge(GridUnsafeCompoundMemory compound) {
            touch();

            CmpMem c = (CmpMem)compound;

            c.touch();

            assert c.get() == 0;

            incrementAndGet();
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testCompare1() throws Exception {
        checkCompare("123");
    }

    /**
     * @throws Exception If failed.
     */
    public void testCompare2() throws Exception {
        checkCompare("1234567890");
    }

    /**
     * @throws Exception If failed.
     */
    public void testCompare3() throws Exception {
        checkCompare("12345678901234567890");
    }

    /**
     * @param s String.
     * @throws Exception If failed.
     */
    public void checkCompare(String s) throws Exception {
        byte[] bytes = s.getBytes();

        int size = bytes.length + 8;

        GridUnsafeMemory mem = new GridUnsafeMemory(size);

        for (int i = 0; i < 8; i++) {
            long addr = mem.allocate(size);

            long ptr = addr + i;

            try {
                mem.writeBytes(ptr, bytes);

                assert mem.compare(ptr, bytes);

                byte[] read = mem.readBytes(ptr, bytes.length);

                assert Arrays.equals(bytes, read);
            }
            finally {
                mem.release(addr, size);
            }
        }
    }

    /**
     * @throws Exception If failed.
     */
    public void testOutOfMemory() throws Exception {
        int cap = 64;
        int block = 9;

        int allowed = cap / block;

        Collection<Long> addrs = new ArrayList<>(allowed);

        GridUnsafeMemory mem = new GridUnsafeMemory(cap);

        try {
            boolean oom = false;

            for (int i = 0; i <= allowed; i++) {
                boolean reserved = mem.reserve(block);

                long addr = mem.allocate(block, true, true);

                addrs.add(addr);

                if (!reserved) {
                    assertEquals(i, allowed);

                    oom = true;

                    break;
                }
            }

            assertTrue(oom);
        }
        finally {
            for (Long addr : addrs)
                mem.release(addr, block);
        }

        assertEquals(mem.allocatedSize(), 0);
    }
}

