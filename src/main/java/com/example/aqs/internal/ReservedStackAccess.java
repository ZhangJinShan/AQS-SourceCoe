package com.example.aqs.internal;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * [STUDY] 标记注解垫片：对齐 {@code jdk.internal.vm.annotation.ReservedStackAccess}。
 *
 * <p>原注解是给 HotSpot 的标记（偏向锁撤销等场景为该方法预留栈空间），
 * 对纯 Java 学习场景无任何行为影响。保留注解声明的目的仅是让
 * {@code ReentrantLock}/{@code ReentrantReadWriteLock} 源码本体除 import 一行外
 * 与官方逐字一致（{@code @ReservedStackAccess} 标注处无需删改）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface ReservedStackAccess {
}
