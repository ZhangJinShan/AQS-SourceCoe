# AQS-SourceCode

AQS（`AbstractQueuedSynchronizer`）源码研读项目：**源码内置、可加日志、可写测试、可长期维护**。

配套学习计划：[AQS-源码学习计划.md](./AQS-源码学习计划.md)（13 个任务单元，从基础抽象模型 → 核心排队逻辑 → 锁实现 → 并发工具落地）。

## 源码来源与保真度

- **上游版本**：Oracle JDK **17.0.20.1**，取自 `$JAVA_HOME/lib/src.zip`
- **内置文件**（`src/main/java/com/example/aqs/locks/`，除下表所列改动外与上游逐字一致）：
  - `AbstractQueuedSynchronizer.java`（1856 行，含 `Node`、`ConditionObject`）
  - `AbstractOwnableSynchronizer.java`
  - `Condition.java`

### 与官方源码的全部差异

所有改动均带 `// [STUDY]` 标记，审计命令：`grep -rn "\[STUDY\]" src/`

| 文件 | 改动 | 原因 |
|------|------|------|
| 三个文件 | `package java.util.concurrent.locks` → `com.example.aqs.locks` | JDK 9+ 模块系统禁止向 `java.*` 包添加类 |
| `AbstractQueuedSynchronizer` | 新增 `import java.util.concurrent.locks.LockSupport;` | 换包后不再与 `LockSupport` 同包 |
| `AbstractQueuedSynchronizer` | `import jdk.internal.misc.Unsafe` → `import com.example.aqs.internal.Unsafe` | `jdk.internal.misc` 是 `java.base` 私有包 |

除以上 4 行外，**方法体、注释、Javadoc 未做任何修改**。上游 `jdk.internal.misc.Unsafe`
由垫片类 [`src/main/java/com/example/aqs/internal/Unsafe.java`](./src/main/java/com/example/aqs/internal/Unsafe.java) 适配（API 同名对齐，底层委托 `sun.misc.Unsafe`；弱 CAS→强 CAS、opaque→volatile 两处只会更强，详见垫片类注释）。

## 怎么用

- **读源码**：直接读 `src/main/java/com/example/aqs/locks/`，行号与官方源码一一对应（仅 import 区偏移 1~2 行）
- **加日志/埋点**：直接改拷贝版方法体，学习期不用顾虑污染 JDK；追踪并发流程建议用日志而非断点（断点会打乱线程时序）
- **写测试**：`src/test/java/com/example/aqs/` 下已有冒烟测试可作模板：
  - `MutexTest` — 最简互斥锁（模板方法 `tryAcquire/tryRelease/isHeldExclusively` 的最小程序）
  - `ConditionTest` — 基于 `ConditionObject` 的有界缓冲（生产者-消费者）
- **跑测试**：`mvn test`

## 与新版 JDK 同步

```bash
# 从新版 JDK 重新提取源码（对比差异用）
unzip -o $JAVA_HOME/lib/src.zip "java.base/java/util/concurrent/locks/*.java" -d /tmp/jdk-src-new
# 与内置拷贝对比（忽略 package/import 4 行改动即可）
diff /tmp/jdk-src-new/java.base/java/util/concurrent/locks/AbstractQueuedSynchronizer.java \
     src/main/java/com/example/aqs/locks/AbstractQueuedSynchronizer.java
```

## 许可证声明

`src/main/java/com/example/aqs/locks/` 与 `src/main/java/com/example/aqs/internal/` 中源自 JDK 的源码遵循
**GPLv2 + Classpath Exception**（各文件头部保留了原始版权与许可声明，务必随分发保留）。
项目自身新增的测试与文档可自由使用。
