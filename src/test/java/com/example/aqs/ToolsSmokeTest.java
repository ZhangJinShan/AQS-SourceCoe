package com.example.aqs;

import com.example.aqs.concurrent.CountDownLatch;
import com.example.aqs.concurrent.Semaphore;
import com.example.aqs.locks.AbstractQueuedSynchronizer;
import com.example.aqs.locks.ReentrantLock;
import com.example.aqs.locks.ReentrantReadWriteLock;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 工具类拷贝的冒烟测试：
 * 1) 接线审计——各工具内部 Sync 的父类必须是【项目内拷贝】的 AQS，
 *    防止某处 import 漏改后静默绑回真实 JDK 类（那样埋点实验就白做了）；
 * 2) 基本行为——重入、读写分离、倒计时、信号量。
 */
public class ToolsSmokeTest {

    private static void assertWiredToCopiedAqs(String syncClassName) throws Exception {
        Class<?> sync = Class.forName(syncClassName);
        assertEquals("Sync 必须继承项目内拷贝的 AQS，而不是真实 JDK 类",
                AbstractQueuedSynchronizer.class, sync.getSuperclass());
    }

    @Test
    public void syncsAreWiredToCopiedAqs() throws Exception {
        assertWiredToCopiedAqs("com.example.aqs.locks.ReentrantLock$Sync");
        assertWiredToCopiedAqs("com.example.aqs.locks.ReentrantReadWriteLock$Sync");
        assertWiredToCopiedAqs("com.example.aqs.concurrent.Semaphore$Sync");
        assertWiredToCopiedAqs("com.example.aqs.concurrent.CountDownLatch$Sync");
    }

    @Test
    public void reentrantLockReentrancy() {
        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        lock.lock();
        assertEquals(2, lock.getHoldCount());
        lock.unlock();
        assertEquals(1, lock.getHoldCount());
        lock.unlock();
        assertEquals(0, lock.getHoldCount());
    }

    @Test
    public void readWriteLockSeparatesReadersAndWriter() throws Exception {
        ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
        rwl.readLock().lock();
        rwl.readLock().lock(); // 读锁可重入/共享
        assertEquals(2, rwl.getReadLockCount());
        assertTrue(rwl.getReadHoldCount() >= 1);

        // 写锁应被读者挡住：tryLock 立刻失败，超时 tryLock 也失败
        assertTrue(!rwl.writeLock().tryLock());
        assertTrue(!rwl.writeLock().tryLock(50, java.util.concurrent.TimeUnit.MILLISECONDS));

        rwl.readLock().unlock();
        rwl.readLock().unlock();
        assertTrue(rwl.writeLock().tryLock());
        assertTrue(rwl.getWriteHoldCount() == 1);
        rwl.writeLock().unlock();
    }

    @Test
    public void countDownLatchReleasesAllWaitersAtZero() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger released = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            new Thread(() -> {
                try {
                    latch.await();
                    released.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }).start();
        }
        Thread.sleep(50); // 让 3 个线程都进入等待（仅冒烟用途）
        latch.countDown();
        Thread.sleep(50);
        assertEquals(0, released.get()); // 未归零，一个都不放行
        latch.countDown(); // 归零
        Thread.sleep(100);
        assertEquals(3, released.get()); // 归零瞬间全部放行
    }

    @Test
    public void semaphoreLimitsConcurrency() throws Exception {
        Semaphore sem = new Semaphore(2);
        assertTrue(sem.tryAcquire());
        assertTrue(sem.tryAcquire());
        assertTrue(!sem.tryAcquire()); // 第 3 个必须失败
        assertEquals(0, sem.availablePermits());
        sem.release(2);
        assertEquals(2, sem.availablePermits());
    }
}
