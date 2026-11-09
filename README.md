# AQS-SourceCode

AQS（`AbstractQueuedSynchronizer`）源码研读项目：**源码内置、可加日志、可写测试、可长期维护**。

配套学习计划：[AQS-源码学习计划.md](./AQS-源码学习计划.md)（13 个任务单元，从基础抽象模型 → 核心排队逻辑 → 锁实现 → 并发工具落地）。

## 源码来源与保真度

- **上游版本**：Oracle JDK **17.0.20.1**，取自 `$JAVA_HOME/lib/src.zip`
- **内置文件**（除下表所列改动外与上游逐字一致）：

| 位置 | 文件 | 对应上游包 |
|------|------|-----------|
| `com.example.aqs.locks` | `AbstractQueuedSynchronizer.java`（1856 行，含 `Node`、`ConditionObject`） | `java.util.concurrent.locks` |
| | `AbstractOwnableSynchronizer.java` | |
| | `Condition.java`、`Lock.java`、`ReadWriteLock.java` | |
| | `ReentrantLock.java`、`ReentrantReadWriteLock.java` | |
| `com.example.aqs.concurrent` | `Semaphore.java`、`CountDownLatch.java` | `java.util.concurrent` |
| `com.example.aqs.internal` | `Unsafe` / `ReservedStackAccess` / `LockSupport` 三个垫片（见下） | — |

### 与官方源码的全部差异

所有改动均带 `// [STUDY]` 标记，审计命令：`grep -rn "\[STUDY\]" src/`

| 文件 | 改动 | 原因 |
|------|------|------|
| 全部 8 个拷贝文件 | `package` 声明改写 | JDK 9+ 模块系统禁止向 `java.*` 包添加类 |
| `AbstractQueuedSynchronizer` | `import jdk.internal.misc.Unsafe` → `com.example.aqs.internal.Unsafe` | `jdk.internal.misc` 是 `java.base` 私有包 |
| | 新增 `import ...locks.LockSupport` | 换包后不再与 `LockSupport` 同包 |
| `ReentrantLock`、`ReentrantReadWriteLock` | `import jdk.internal.vm.annotation.ReservedStackAccess` → `com.example.aqs.internal.ReservedStackAccess` | `java.base` 私有注解（HotSpot 栈预留标记，无学习行为影响） |
| `ReentrantReadWriteLock` | 新增 `import com.example.aqs.internal.LockSupport` | 裸调用同包的包私有 `LockSupport.getThreadId`，垫片以 `Thread.getId()` 等价实现 |
| `Semaphore`、`CountDownLatch` | AQS 的 import 重定向到 `com.example.aqs.locks` 拷贝 | 防止静默绑回真实 JDK 类 |
| | 新增 `import java.util.concurrent.TimeUnit` | 换包后不再与 `TimeUnit` 同包 |

除以上改动外，**方法体、注释、Javadoc 均未修改**。三个垫片（`com.example.aqs.internal`）：
- `Unsafe`：API 同名对齐 `jdk.internal.misc.Unsafe`（AQS 用到的 8 个方法），底层委托 `sun.misc.Unsafe`；弱 CAS→强 CAS、opaque→volatile 两处只会更强
- `ReservedStackAccess`：空标记注解，仅保源码本体逐字
- `LockSupport`：仅补包私有 `getThreadId(Thread)`，语义等价 `Thread.getId()`

**接线保障**：`ToolsSmokeTest#syncsAreWiredToCopiedAqs` 用反射断言四个工具的 `Sync` 父类
均为拷贝版 AQS——若将来同步新版本时漏改 import，测试会立刻失败。

## 怎么用

- **读源码**：直接读 `com.example.aqs.locks` / `com.example.aqs.concurrent`，行号与官方源码一一对应（仅 import 区有偏移）
- **加日志/埋点**：直接改拷贝版方法体，学习期不用顾虑污染 JDK；追踪并发流程建议用日志而非断点（断点会打乱线程时序）
- **写测试**：`src/test/java/com/example/aqs/` 下已有冒烟测试可作模板：
  - `MutexTest` — 最简互斥锁（模板方法 `tryAcquire/tryRelease/isHeldExclusively` 的最小程序）
  - `ConditionTest` — 基于 `ConditionObject` 的有界缓冲（生产者-消费者）
  - `ToolsSmokeTest` — ReentrantLock 重入、读写锁读写分离、CountDownLatch 归零放行、Semaphore 限流 + 接线审计
- **跑测试**：`mvn test`

## 尚未内置（按需再补）

- `ThreadPoolExecutor`（TU-12 的 `Worker`）：拷它会连带 `AbstractExecutorService`/`FutureTask` 一整条继承链，学到 TU-12 时再决定拷链还是直读 JDK 源
- `Phaser`/`StampedLock` 等拓展阅读项：IDE 关联 `src.zip` 直读即可

## 与新版 JDK 同步

```bash
# 从新版 JDK 重新提取源码（对比差异用）
unzip -o $JAVA_HOME/lib/src.zip "java.base/java/util/concurrent/locks/*.java" \
       "java.base/java/util/concurrent/Semaphore.java" "java.base/java/util/concurrent/CountDownLatch.java" -d /tmp/jdk-src-new
# 与内置拷贝对比（忽略带 [STUDY] 标记的行即可）
diff /tmp/jdk-src-new/java.base/java/util/concurrent/locks/ReentrantLock.java \
     src/main/java/com/example/aqs/locks/ReentrantLock.java
# 同步后务必重跑：mvn test（含接线审计）
```

## 许可证声明

`com.example.aqs.locks` 与 `com.example.aqs.concurrent` 中源自 JDK 的源码遵循
**GPLv2 + Classpath Exception**（各文件头部保留了原始版权与许可声明，务必随分发保留）。
项目自身新增的垫片、测试与文档可自由使用。
