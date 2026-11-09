package com.example.aqs.internal;

import java.lang.reflect.Field;

/**
 * [STUDY] 学习用 Unsafe 垫片。
 *
 * <p>目的：让 {@link com.example.aqs.locks.AbstractQueuedSynchronizer} 的源码本体
 * 除 import 一行外与官方 JDK 源码逐字一致。方法签名与 {@code jdk.internal.misc.Unsafe}
 * 中 AQS 实际用到的 8 个方法完全对齐，底层委托给公开可得的 {@code sun.misc.Unsafe}。
 *
 * <p>与真实 {@code jdk.internal.misc.Unsafe} 的语义差异（只会更强，不会更弱）：
 * <ul>
 *   <li>{@link #getUnsafe()}：原版校验调用者必须是 bootstrap 类加载器；本垫片直接返回单例
 *       （否则项目内类无法获取实例）。</li>
 *   <li>{@code weakCompareAndSetReference}：原版是弱 CAS（允许伪失败）；本垫片用强 CAS 实现，
 *       弱 CAS 的调用方本就必须容忍失败，强 CAS 语义完全兼容。</li>
 *   <li>{@code putIntOpaque}：原版是 opaque 写；{@code sun.misc.Unsafe} 无此模式，
 *       用 volatile 写（更强的可见性保证）代替。</li>
 * </ul>
 *
 * <p>方法逐一说明见方法注释；学习 AQS 逻辑时可放心认为垫片行为与原版一致。
 */
public final class Unsafe {

    private static final Unsafe INSTANCE = new Unsafe();

    /** 底层真实的 sun.misc.Unsafe，经反射从 theUnsafe 字段获取（jdk.unsupported 模块开放）。 */
    private final sun.misc.Unsafe u;

    private Unsafe() {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            u = (sun.misc.Unsafe) f.get(null);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** 对齐 jdk.internal.misc.Unsafe#getUnsafe()，但不做类加载器校验。 */
    public static Unsafe getUnsafe() {
        return INSTANCE;
    }

    /** 对齐 objectFieldOffset(Class, String)：按「类 + 字段名」取字段偏移量。 */
    public long objectFieldOffset(Class<?> c, String name) {
        try {
            return u.objectFieldOffset(c.getDeclaredField(name));
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    /** CAS 更新 int 字段（volatile 语义），成功返回 true。 */
    public boolean compareAndSetInt(Object o, long offset, int expected, int x) {
        return u.compareAndSwapInt(o, offset, expected, x);
    }

    /** CAS 更新引用字段（volatile 语义），成功返回 true。 */
    public boolean compareAndSetReference(Object o, long offset, Object expected, Object x) {
        return u.compareAndSwapObject(o, offset, expected, x);
    }

    /**
     * 弱 CAS 更新引用字段。[STUDY] 原版允许伪失败，这里用强 CAS 实现——
     * 弱 CAS 的调用方（AQS 链表 CAS）本就把失败当作"重查即可"，语义兼容。
     */
    public boolean weakCompareAndSetReference(Object o, long offset, Object expected, Object x) {
        return u.compareAndSwapObject(o, offset, expected, x);
    }

    /**
     * 原子地将 int 字段按位与 v，返回【修改前】的旧值。
     * [STUDY] sun.misc.Unsafe 无此方法，用 volatile 读 + CAS 自旋循环实现，原子性等价。
     */
    public int getAndBitwiseAndInt(Object o, long offset, int v) {
        int prev;
        do {
            prev = u.getIntVolatile(o, offset);
        } while (!u.compareAndSwapInt(o, offset, prev, prev & v));
        return prev;
    }

    /** 普通（plain，无 volatile）写引用字段，与 jdk.internal 的 putReference(plain) 对齐。 */
    public void putReference(Object o, long offset, Object x) {
        u.putObject(o, offset, x);
    }

    /** 普通（plain，无 volatile）写 int 字段。 */
    public void putInt(Object o, long offset, int x) {
        u.putInt(o, offset, x);
    }

    /**
     * opaque 写 int 字段。[STUDY] sun.misc.Unsafe 无 opaque 模式，
     * 以 volatile 写代替（可见性只会更强，不影响 AQS 正确性）。
     */
    public void putIntOpaque(Object o, long offset, int x) {
        u.putIntVolatile(o, offset, x);
    }
}
