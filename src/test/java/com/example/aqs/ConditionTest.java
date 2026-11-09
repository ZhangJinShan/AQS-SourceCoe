package com.example.aqs;

import com.example.aqs.locks.Condition;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

/**
 * 冒烟测试：验证拷贝版 AQS 的 ConditionObject（条件队列 ⇄ 同步队列迁移）工作正常。
 * 有界缓冲 = 学习计划 TU-9 的标准练习形态。
 */
public class ConditionTest {

    /** 用 Mutex + 两个 Condition 实现容量为 1 的有界缓冲。 */
    static final class BoundedBuffer {
        private final MutexTest.Mutex mutex = new MutexTest.Mutex();
        private final Condition notFull = mutex.newCondition();
        private final Condition notEmpty = mutex.newCondition();
        private final Object[] items = new Object[1];
        private int count, put, take;

        void put(Object x) throws InterruptedException {
            mutex.lock();
            try {
                while (count == items.length) {
                    notFull.await();
                }
                items[put] = x;
                if (++put == items.length) {
                    put = 0;
                }
                count++;
                notEmpty.signal();
            } finally {
                mutex.unlock();
            }
        }

        Object take() throws InterruptedException {
            mutex.lock();
            try {
                while (count == 0) {
                    notEmpty.await();
                }
                Object x = items[take];
                if (++take == items.length) {
                    take = 0;
                }
                count--;
                notFull.signal();
                return x;
            } finally {
                mutex.unlock();
            }
        }
    }

    @Test
    public void producerConsumer() throws Exception {
        final BoundedBuffer buffer = new BoundedBuffer();
        final int n = 10_000;
        final AtomicInteger consumed = new AtomicInteger();
        final long[] sum = {0};

        Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < n; i++) {
                    buffer.put(i);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread consumer = new Thread(() -> {
            try {
                for (int i = 0; i < n; i++) {
                    sum[0] += (Integer) buffer.take();
                    consumed.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        producer.start();
        consumer.start();
        producer.join();
        consumer.join();

        assertEquals(n, consumed.get());
        assertEquals((long) n * (n - 1) / 2, sum[0]); // 不丢不重：0..n-1 求和
    }
}
