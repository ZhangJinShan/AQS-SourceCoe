package com.example.aqs;

import com.example.aqs.locks.AbstractQueuedSynchronizer;
import com.example.aqs.locks.Condition;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;

/**
 * 冒烟测试：验证项目内拷贝版 AQS（com.example.aqs.locks）在高竞争下互斥正确。
 * 同时也是「模板方法」最小程序的参考实现（对照学习计划 TU-2）。
 */
public class MutexTest {

    /** 基于拷贝版 AQS 的最简互斥锁：state 0=空闲 1=持有。 */
    static final class Mutex {
        private final Sync sync = new Sync();

        static final class Sync extends AbstractQueuedSynchronizer {
            @Override
            protected boolean tryAcquire(int acquires) {
                return compareAndSetState(0, 1);
            }

            @Override
            protected boolean tryRelease(int releases) {
                setState(0);
                return true;
            }

            @Override
            protected boolean isHeldExclusively() {
                return getState() == 1;
            }

            // 对照 ReentrantLock.Sync：newCondition 由子类提供，AQS 本身没有该方法
            Condition newCondition() {
                return new ConditionObject();
            }
        }

        public void lock() {
            sync.acquire(1);
        }

        public void unlock() {
            sync.release(1);
        }

        public Condition newCondition() {
            return sync.newCondition();
        }
    }

    @Test
    public void mutualExclusionUnderContention() throws Exception {
        final Mutex mutex = new Mutex();
        final int[] counter = {0}; // 普通 int，靠互斥保证可见性与原子性
        final int threads = 8;
        final int loops = 100_000;

        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < loops; j++) {
                        mutex.lock();
                        try {
                            counter[0]++;
                        } finally {
                            mutex.unlock();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();
        assertEquals(threads * loops, counter[0]);
    }
}
