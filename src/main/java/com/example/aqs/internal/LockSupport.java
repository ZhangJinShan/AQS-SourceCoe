package com.example.aqs.internal;

/**
 * [STUDY] 最小垫片：补出 {@code java.util.concurrent.locks.LockSupport} 的包私有辅助方法
 * {@code getThreadId(Thread)}（外部包无法访问）。
 *
 * <p>原实现用 Unsafe 直读 {@code Thread.tid} 字段，而 {@code Thread.getId()} 就是返回
 * {@code tid}，两者语义完全一致，这里直接委托 {@link Thread#getId()}。
 *
 * <p>仅供 {@code ReentrantReadWriteLock} 拷贝（读锁 hold count 用线程 id 匹配）使用；
 * park/unpark 仍走真实的 {@code java.util.concurrent.locks.LockSupport}（见 AQS 拷贝）。
 */
public final class LockSupport {

    private LockSupport() {
    }

    /** 对齐 LockSupport#getThreadId(Thread)：取线程 id（等价 Thread#getId()）。 */
    public static long getThreadId(Thread thread) {
        return thread.getId();
    }
}
