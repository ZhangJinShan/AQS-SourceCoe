# AQS 源码学习计划（任务单元制）

> **组织原则**：以任务单元（TU）为粒度推进，不按天划分。每个 TU 是一个可独立完成、可独立验收的闭环：
> **学习重点 → 源码阅读范围 → 需要验证的点 → 配套实操 → 复盘输出**。
> 完成一个 TU 才进入下一个，顺序固定：**基础抽象模型 → 核心排队逻辑 → 锁实现 → 并发工具落地**。
>
> **学习对象**：`java.util.concurrent.locks.AbstractQueuedSynchronizer`（JDK 8+ 源码，建议对照 JDK 8 与 JDK 17 两个版本阅读，核心结构一致，JDK 17 有注释与 `VarHandle` 化的小差异）。
> **实操载体**：当前工程 `AQS-SourceCode`（Maven），所有代码写在 `src/main/java/com/example/` 下。

---

## 阶段总览

| 阶段 | 目标 | 任务单元 |
|------|------|----------|
| 一、基础抽象模型 | 建立 AQS 的整体心智模型：state、模板方法、CLH 节点 | TU-1 ~ TU-3 |
| 二、核心排队逻辑 | 吃透入队、自旋/阻塞、取消、唤醒、共享传播的主干流程 | TU-4 ~ TU-7 |
| 三、锁实现 | 用 ReentrantLock / ReadWriteLock / Condition 验证 AQS 语义 | TU-8 ~ TU-10 |
| 四、并发工具落地 | Semaphore / CountDownLatch / 线程池 Worker / 自研同步器 | TU-11 ~ TU-13 |

---

## 阶段一：基础抽象模型

### TU-1　源码获取与整体架构定位

- **学习重点**
  - AQS 在 JUC 中的定位：`j.u.c.locks` 包的骨架类，为「依赖单一原子 int 状态（`state`）的同步器」提供阻塞等待/唤醒/队列框架。
  - 三大核心成员的宏观作用：`volatile int state`、CLH 变体等待队列（`Node` 链表）、`ConditionObject`。
  - 两种模式：独占（exclusive）与共享（shared）。
  - 使用者的两个扩展姿势：继承 AQS 实现模板方法（如 `ReentrantLock.Sync`），或直接持有内部 `Sync` 实例（如 `ReentrantReadWriteLock`）。
- **源码阅读范围**
  - `AbstractQueuedSynchronizer` 类注释（前 150 行，官方设计说明，信息密度极高，本 TU 只求通读不求全懂）。
  - 字段区：`head`、`tail`、`state` 及其 getter/setter（`getState/setState/compareAndSetState`）。
  - 构造方法与 `AbstractOwnableSynchronizer`（独占 owner 线程记录）。
  - 对照浏览 `ReentrantLock` 的类结构（只看结构，不看方法体）。
- **需要验证的点**
  - `state` 的读写全部走 `getState/setState/compareAndSetState` 三个方法，且后两者是 CAS 语义的入口。
  - AQS 中 `head`/`tail` 是 `volatile`，通过 `Unsafe`（JDK 8）或 `VarHandle`（JDK 9+）做 CAS 修改。
  - `AbstractOwnableSynchronizer` 只有一个字段 `exclusiveOwnerThread`，AQS 独占模式用它记录持有者。
- **配套实操**
  1. 确认本机 JDK 版本（`java -version`），在 IDE 中关联 `src.zip` 源码，能从 `AbstractQueuedSynchronizer` 一键跳转到 `ReentrantLock`。
  2. 在工程中创建 `com.example.overview.Overview01`，用 `ReentrantLock` 写 10 行加锁/解锁 demo 并运行，感受「使用者视角」的最短路径。
- **复盘输出**
  - `notes/TU-1-架构定位.md`：一张 AQS 三大成员（state / 队列 / ConditionObject）的职责图 + 一句话回答「为什么 AQS 只用一个 int 就能表达各种同步语义」。

### TU-2　模板方法与 state 语义

- **学习重点**
  - 五个使用者可重写方法的契约：`tryAcquire`、`tryRelease`、`tryAcquireShared`、`tryReleaseShared`、`isHeldExclusively`。
  - AQS 的设计哲学：**同步状态的语义由子类定义（state 怎么算占用），排队/阻塞/唤醒策略由 AQS 定义**。
  - `state` 如何被不同工具复用：ReentrantLock 用重入次数、Semaphore 用许可数、CountDownLatch 用剩余计数、ReadWriteLock 用高 16 位读/低 16 位写。
- **源码阅读范围**
  - `tryAcquire/tryRelease/tryAcquireShared/tryReleaseShared/isHeldExclusively` 五个方法的注释与默认实现（默认抛 `UnsupportedOperationException`）。
  - `acquire/acquireShared/release/releaseShared` 四个「不可重写」的模板入口方法的方法签名与注释。
  - 浏览 `Semaphore.NonfairSync#tryAcquireShared`、`CountDownLatch.Sync#tryAcquireShared/tryReleaseShared` 的简短实现，感受「只改 state 语义」。
- **需要验证的点**
  - 五个模板方法默认全部抛 `UnsupportedOperationException`，强制子类显式选择独占或共享路线。
  - `tryAcquireShared` 返回值语义是三态：负值=失败、0=成功但后续共享者应阻塞、正值=成功且可传播。
  - `CountDownLatch.Sync#tryReleaseShared` 只在 state 减到 0 时返回 true（唤醒临界点）。
- **配套实操**
  1. 写 `com.example.model.Mutex01 extends AQS`：实现最简单的互斥锁（state 0/1），只实现 `tryAcquire/tryRelease/isHeldExclusively`，借助 AQS 的 `acquire/release` 对外提供 `lock/unlock`。用两个线程对 `int` 计数 10 万次验证互斥正确。
  2. 故意让 `tryAcquire` 先 `Thread.sleep(1)` 再 CAS，观察大量线程下自旋浪费（为 TU-4 的阻塞策略埋伏笔）。
- **复盘输出**
  - `notes/TU-2-模板方法.md`：五个模板方法的「调用时机 / 参数含义 / 返回值契约」表格，附 `state` 语义对照表（四个工具各一行）。
  - `src/main/java/com/example/model/Mutex01.java` 可运行，注释标出哪些行为是 AQS 提供的、哪些是子类提供的。

### TU-3　Node 结构与等待队列模型

- **学习重点**
  - `Node` 节点字段：`thread`、`prev`、`next`、`waitStatus`、`nextWaiter`（双职责：共享模式标记 + Condition 链表后继）。
  - `waitStatus` 四常量语义：`CANCELLED=1`、`SIGNAL=-1`、`CONDITION=-2`、`PROPAGATE=-3`、`0` 初始态。
  - CLH 变体队列：虚拟 head（`head` 指向的节点 thread 为 null 或已获锁者），`tail` 用 CAS 入队。
  - 「节点自旋 + 前驱 waitStatus 决定是否 park」的核心思想（本 TU 只建立概念，执行细节留给 TU-4/5）。
- **源码阅读范围**
  - `Node` 静态内部类全部代码（含 `isShared/previousOf/predecessor` 等小方法）。
  - `addWaiter(Node)`、`enq(Node)` 两个方法的方法体。
  - `AbstractQueuedSynchronizer` 类注释中关于队列的部分（Figure 1/2/3 配图注释）。
- **需要验证的点**
  - `enq` 是「CAS 自旋入队」：失败则重试，成功时若 `tail == null` 先初始化 `head`（哨兵节点）。
  - `addWaiter` 快速路径失败后落到 `enq` 慢速路径，两者最终都保证 `prev` 链接与 `tail` CAS 的顺序正确。
  - 队列是**双向链表**（`prev` 用于取消时回退，`next` 用于唤醒），但不是强一致的——`next` 指针允许「短暂为空」，靠 `enq`/`setHead` 等时序保证可恢复。
  - `nextWaiter` 在共享模式下固定为 `Node.SHARED` 哨兵，在 Condition 链表中是单向后继——一个字段两种用途。
- **配套实操**
  1. 用 30 行代码写 `com.example.model.SimpleCLHDemo01`：模拟 CLH 队列（一个 `AtomicReference<Thread> tail` + 自旋），不调用 AQS，只为体会「自旋看前驱状态」的思想。
  2. 在 TU-2 的 `Mutex01` 上打断点（`addWaiter`、`enq` 入口），开 3 个线程竞争锁，观察 `head/tail` 的初始哨兵与排队形态。
- **复盘输出**
  - `notes/TU-3-Node与队列.md`：手绘一张 `Node` 字段表 + 一张「3 线程排队」的队列快照图（标注 head/tail/waitStatus）。
  - 能口头回答：为什么需要哨兵 head？为什么队列改指针用 CAS 而不是锁？

---

## 阶段二：核心排队逻辑

### TU-4　独占模式主干：acquire → acquireQueued → park

- **学习重点**
  - `acquire(int)` 的完整流程：`tryAcquire` 失败 → `addWaiter` 入队（独占模式 `Node.EXCLUSIVE`）→ `acquireQueued` 自旋 → `shouldParkAfterFailedAcquire` 修整前驱状态 → `parkAndCheckInterrupt` 阻塞。
  - 关键设计：**入队后不立刻 park**，先自旋一轮把前驱 `waitStatus` 置为 `SIGNAL`（「你释放时记得叫醒我」），之后才 park。
  - 中断策略：AQS 默认忽略中断直到获取成功（`acquireQueued` 中 park 被唤醒后只记录中断标志，成功后再 `selfInterrupt` 补偿），即 **interruptible 语义是可延迟的**。
- **源码阅读范围**
  - `acquire(int)`、`addWaiter`、`acquireQueued(Node, int)`、`shouldParkAfterFailedAcquire(Node, Node)`、`parkAndCheckInterrupt()`、`selfInterrupt()` 全部方法体。
  - 对照读 `acquireInterruptibly(int)`、`doAcquireInterruptibly(int)`，明确「响应中断」版本的差异点（失败时 `cancelAcquire` + 抛 `InterruptedException`）。
- **需要验证的点**
  - `shouldParkAfterFailedAcquire` 三种分支：前驱 `SIGNAL`→返回 true（可以 park）；`waitStatus > 0`（CANCELLED）→跳过取消节点并返回 false（再自旋）；其他（0/CONDITION/PROPAGATE）→ CAS 置 `SIGNAL` 并返回 false（再自旋一次）。
  - CAS 置 `SIGNAL` 失败会走「重新检查」路径——前驱可能已被并发取消。
  - `acquireQueued` 返回值是「过程中是否被中断过」，最终若为 true 则调用 `selfInterrupt()`，这解释了 `lock()` 不抛异常但会补发中断的语义。
  - 首个节点入队后其前驱是哨兵 head，哨兵的 `waitStatus` 会被后来者或自己置为 `SIGNAL`。
- **配套实操**
  1. `com.example.queue.AcquireTrace01`：在 `Mutex01` 基础上覆写/埋点（或直接在调试器里单线程走 `acquire` 全流程），打印每一步 `waitStatus` 变化，产出一张时序日志。
  2. 写两个线程：T1 持锁 sleep 3 秒，T2 调 `lock()` 后被中断（`t2.interrupt()`），验证 T2 既不立刻抛异常、也拿不到锁，直到 T1 释放后 T2 拿到锁并收到补发的中断（检查 `Thread.interrupted()`）。
  3. 对照 `lockInterruptibly()` 重复实验，验证中断立即生效并抛 `InterruptedException`。
- **复盘输出**
  - `notes/TU-4-独占主干.md`：一张 `acquire` 全流程泳道图（tryAcquire→addWaiter→acquireQueued→shouldPark→park），并单独写清「为什么不立刻 park 而要先自旋修 waitStatus」。

### TU-5　释放与唤醒：release → unparkSuccessor

- **学习重点**
  - `release(int)` 流程：`tryRelease` 成功 → `h = head` 判定 → `unparkSuccessor` 唤醒后继。
  - `unparkSuccessor` 的三个细节：head 的 `waitStatus` 先清零（允许传播失败时的重试）、找后继时**从 tail 向前回溯**找最近的未取消节点（`next` 指针可能滞后）、被唤醒节点走 `parkAndCheckInterrupt` 返回后继续 `acquireQueued` 自旋竞争。
  - 唤醒 ≠ 获锁：被 unpark 的线程还要重新 `tryAcquire`，可能被新来的线程插队（非公平的根源，TU-8 展开）。
- **源码阅读范围**
  - `release(int)`、`unparkSuccessor(Node)`、`setHead(Node)`。
  - 回看 `acquireQueued` 中「被唤醒后」的代码路径，串起完整闭环。
- **需要验证的点**
  - `unparkSuccessor` 中 `node.next` 为空或已取消时，用 `tail` 反向遍历找前驱链上的有效后继——验证队列在并发下 `next` 可能短暂不一致。
  - `waitStatus` 被 CAS 清 0 后，若 unpark 失败/目标已取消，队列依靠下一次入队或释放的重试保证最终被唤醒（无丢失唤醒）。
  - `setHead` 会把 head 的 `thread`、`prev` 置 null，但保留 `waitStatus` 给下一轮使用。
- **配套实操**
  1. `com.example.queue.ReleaseTrace01`：延续 TU-4 的埋点，T1 `unlock()` 时打印 `head/waitStatus` 与被 unpark 的线程名，验证「唤醒的线程 ≠ 立即持锁」。
  2. 构造 5 线程排队，持锁者释放后立刻再抢锁，观察被唤醒线程是否可能抢不过插队线程（对应非公平锁现象）。
- **复盘输出**
  - `notes/TU-5-释放唤醒.md`：release→unparkSuccessor→acquireQueued 续跑的完整时序图；附「为什么从 tail 反向找后继」的解释（next 指针滞后性）。

### TU-6　取消、超时与异常路径：cancelAcquire

- **学习重点**
  - `cancelAcquire(Node)`：中断/超时/异常时的节点退队。三步：置 `CANCELLED` → 修正 `prev` 跳过自己（CAS 改前驱的 `next`）→ 若自己是 tail 则 CAS 尾回退。
  - 退队不是物理删除：节点靠 `waitStatus=CANCELLED` 逻辑失效，由后继在 `shouldParkAfterFailedAcquire` 时清理。
  - `doAcquireNanos`（超时版）与 `doAcquireInterruptibly`（中断版）在失败分支统一调用 `cancelAcquire`。
- **源码阅读范围**
  - `cancelAcquire(Node)` 逐行精读。
  - `doAcquireNanos(int, long)`、`doAcquireInterruptibly(int)`、`doAcquireSharedInterruptibly/ doAcquireSharedNanos`（对比独占/共享失败处理）。
  - `tryAcquireNanos`、`tryAcquireSharedNanos` 入口包装。
- **需要验证的点**
  - `cancelAcquire` 中「改前驱 next」用的是 CAS，失败允许（靠遍历兜底），但「改 tail」必须 CAS 成功——理解哪些指针要求强一致。
  - 超时返回前一定执行 `cancelAcquire`，不会留下永远 CANCELLED-but-referenced 的悬挂问题。
  - 被取消节点的 thread 字段会被置 null（帮助 GC）。
- **配套实操**
  1. `com.example.queue.CancelDemo01`：用 `tryLock(timeout)` / `lockInterruptibly` 构造排队中被中断和超时的场景，jstack 或埋点观察队列中出现 `CANCELLED` 节点及其被后续节点跳过的过程。
  2. 压测：50 线程随机 `tryLock(随机超时)`，统计超时/成功比例，验证没有线程死等、没有 CPU 空转（`top`/`jstack` 观察）。
- **复盘输出**
  - `notes/TU-6-取消与超时.md`：`cancelAcquire` 的状态图（谁把 CANCELLED 节点从队列里「挤掉」：前驱跳转 + 后继清理），并列出「CAS 失败可容忍」与「必须成功」的操作清单。

### TU-7　共享模式与传播：acquireShared → doReleaseShared

- **学习重点**
  - 共享模式主干：`acquireShared` → `tryAcquireShared` 成功且返回 >0 直接返回；否则 `doAcquireShared` 入队（`Node.SHARED`）。
  - **传播唤醒（propagation）**：`doAcquireShared` 中拿到锁后若 `tryAcquireShared` 返回 >0 且 `head.next` 是共享节点，继续唤醒——`doReleaseShared` 中 `waitStatus` 置 `PROPAGATE` 的意义。
  - `setHeadAndPropagate` 的双条件判断：仅当「返回值 >0 或 next 是共享节点」才继续传播。
  - 共享模式的「惊群 → 逐个确认」问题：一串共享节点被逐个 unpark，每个再确认 `tryAcquireShared`。
- **源码阅读范围**
  - `acquireShared(int)`、`acquireSharedInterruptibly`、`tryAcquireSharedNanos`、`doAcquireShared(Node)`、`setHeadAndPropagate(Node, int)`、`doReleaseShared()`、`releaseShared(int)` 全部方法体。
  - 对照 `Semaphore.FairSync/NonfairSync#tryAcquireShared` 与 `CountDownLatch.Sync`，看共享语义的两种取向（公平排队 vs 一次放行）。
- **需要验证的点**
  - `setHeadAndPropagate` 中 `propagate` 字段与 `PROPAGATE` 状态共同防止「唤醒链断裂」：并发释放时可能有新共享节点入队，必须保证它也能被唤醒。
  - `doReleaseShared` 中 head 的 `waitStatus` 先被 CAS 为 0 或 `PROPAGATE`，失败则重试——自旋直到成功，保证唤醒不丢失。
  - 共享模式下 `releaseShared` 一次可能唤醒一串（`Semaphore.release(n)` / `CountDownLatch` 归零）。
- **配套实操**
  1. `com.example.queue.SharedPropagate01`：用 `Semaphore(1)` + 5 个 `acquire` 线程，一次 `release(1)` 只唤醒 1 个；换成 `CountDownLatch(1)` + 5 个 `await`，`countDown()` 一次全部唤醒——埋点观察两种传播路径。
  2. 断点单步 `setHeadAndPropagate`，人为制造「释放时新线程入队」的并发窗口（用 `CyclicBarrier` 对齐时序），验证传播链不丢人。
- **复盘输出**
  - `notes/TU-7-共享传播.md`：共享模式从「一个节点拿到」到「一串节点放行」的时序图；单独回答「`PROPAGATE` 状态解决的具体竞态是什么」（引用源码行号）。

---

## 阶段三：锁实现

### TU-8　ReentrantLock：公平 vs 非公平、重入

- **学习重点**
  - `NonfairSync#lock()` 的「 barging 」两段式：先直接 CAS 抢一次 state，失败才走 `acquire(1)` 完整排队。
  - `FairSync#tryAcquire` 中 `hasQueuedPredecessors()` 的公平闸门——排队有人则不许插队。
  - 重入实现：`tryAcquire` 中 `current == getExclusiveOwnerThread()` 时 state 计数 +1，`tryRelease` 减到 0 才真正释放。
  - `tryLock()` 无参版是纯粹的 barging（连队列都不看），与 `tryLock(timeout)` 语义差异。
- **源码阅读范围**
  - `ReentrantLock` 全类精读：`Sync#nonfairTryAcquire`、`NonfairSync`、`FairSync`、`lock/tryLock/unlock/lockInterruptibly/newCondition`、`getHoldCount/isHeldByCurrentThread/isLocked/getQueueLength/getQueuedThreads/hasQueuedThreads/hasQueuedPredecessors` 等监控方法。
  - 回读 AQS 的 `getFirstQueuedThread`、`apparentlyFirstQueuedIsExclusive`、`isQueued`（看 AQS 为锁提供的查询能力）。
- **需要验证的点**
  - `hasQueuedPredecessors` 的精确判定：`head != tail` 且「首节点不是自己」（自己重入排队时不能自锁）。
  - 非公平锁的吞吐优势来源：新到线程直接 CAS 抢 state，省一次入队 park/unpark；代价是队列尾部线程可能饥饿。
  - 重入 n 次必须 unlock n 次，`getHoldCount()` 与 state 数值一致。
  - `getQueuedThreads()` 读取链表是弱一致快照（可能读到已取消节点），验证监控 API 的非精确性。
- **配套实操**
  1. `com.example.locks.ReentrantDemo01`：同一线程重入 3 次打印 `getHoldCount`；配合 `unlock` 少于次数验证不解锁。
  2. `com.example.locks.FairVsNonfair01`：10 线程循环抢锁（每次持锁 1ms），分别在公平/非公平模式下统计每个线程的获取次数分布与平均等待时间，输出对比表——用数据回答「公平的代价」。
  3. `com.example.locks.BargingDemo01`：验证无参 `tryLock()` 在队列有人时仍能成功。
- **复盘输出**
  - `notes/TU-8-ReentrantLock.md`：公平/非公平两条 `tryAcquire` 流程的对照图 + 实测吞吐数据；回答「为什么 `lock()` 默认非公平」。

### TU-9　Condition：await / signal 与 ConditionObject

- **学习重点**
  - `ConditionObject` 内部的**条件队列**（`firstWaiter/lastWaiter` 单向链表，`nextWaiter` 串联）与 AQS **同步队列** 的关系：await = 从同步队列搬到条件队列 + park；signal = 从条件队列搬回同步队列。
  - `await()` 的关键步骤：`fullyRelease`（完全释放重入计数，返回保存的 state）→ 加入条件队列 → `park` → 被 signal 后 `acquireQueued` 重新竞争锁 → 成功后恢复 `savedState`（重入还原）。
  - `signal()` 的 `transferForSignal`：节点从条件队列转移到同步队列尾部（`enq`），`waitStatus` 从 `CONDITION` 改为 0 或 `SIGNAL`。
  - 虚假唤醒与标准写法：`while (!condition) await()`。
- **源码阅读范围**
  - `ConditionObject` 全部：`await()`、`awaitUninterruptibly`、`awaitNanos/awaitUntil`、`reportInterruptAfterWaiting`、`transferAfterCancelledWait`、`signal/signalAll`、`transferForSignal/transferForSignalAll`、`fullyRelease`、`isOnSyncQueue`、`checkInterruptWhileWaiting`。
  - `ReentrantLock#newCondition()` 与 `AbstractQueuedSynchronizer#ConditionObject` 的绑定关系。
  - 对照读 `ArrayBlockingQueue`（或 `LinkedBlockingQueue`）中 `notEmpty/notFull` 两个 Condition 的使用——await/signal 的真实用户。
- **需要验证的点**
  - `fullyRelease` 失败（state 归零异常）时节点被置 `CANCELLED` 并抛 `IllegalMonitorStateException`，不会滞留条件队列。
  - signal 时若 `transferForSignal` 失败（节点已被中断/超时取消，`waitStatus` 已非 CONDITION），方法静默返回 false，信号不丢失语义由「取消者自行转移」兜底（`transferAfterCancelledWait`）。
  - await 响应中断的时机：park 期间被中断不立即抛出，而是**先重新抢锁**，抢到后再抛 `InterruptedException`（保证锁状态一致）。
  - `signalAll` 是逐个 `transferForSignal`，被转移者之间在同步队列中仍有先后。
- **配套实操**
  1. `com.example.locks.BoundedBuffer01`：用 `ReentrantLock` + 两个 Condition 实现有界阻塞队列（生产者-消费者），跑 2 生产 2 消费 10 万条数据验证不丢不重、不死锁。
  2. `com.example.locks.ConditionInterrupt01`：await 中被中断，验证抛 `InterruptedException` 时锁仍被持有（finally 中 unlock），并用 `getHoldCount` 佐证。
  3. 对照版：用 `synchronized + wait/notify` 实现同一有界队列，写一段两者的差异总结。
- **复盘输出**
  - `notes/TU-9-Condition.md`：「条件队列 ⇄ 同步队列」节点迁移图（await/signal/超时/中断四条路径）；附虚假唤醒的标准防御写法与一次中断实验日志。

### TU-10　ReentrantReadWriteLock：读写状态编排

- **学习重点**
  - state 的位切分：**高 16 位 = 读锁持有计数，低 16 位 = 写锁重入计数**（`SHARED_SHIFT=16`、`SHARED_UNIT`、`EXCLUSIVE_MASK`）。
  - `Sync` 同时实现独占（写锁）与共享（读锁）两套模板方法，`tryAcquire`（写）与 `tryAcquireShared`（读）互相排斥。
  - 读锁计数：每个线程的重入次数记在 `ThreadLocalHoldCounter`，总计数在 state 高位。
  - 写锁降级为读锁的路径（`tryReleaseShared` 与锁降级用法）；读锁不能升级为写锁的死锁风险。
  - 公平版读锁的 `apparentlyFirstQueuedIsExclusive` 闸门（防写线程饥饿）。
- **源码阅读范围**
  - `ReentrantReadWriteLock` 重点：`Sync`（全部位运算方法：`fullTryAcquireShared`、`tryAcquireShared`、`tryAcquire`、`tryRelease`、`tryReleaseShared`、`readerShouldBlock/writerShouldBlock`）、`NonfairSync/FairSync`、`ReadLock/WriteLock`、`HoldCounter/ThreadLocalHoldCounter`。
  - 回看 AQS `apparentlyFirstQueuedIsExclusive`、`doAcquireShared` 中对独占 head.next 的处理（读锁排队遇到写节点的阻塞）。
- **需要验证的点**
  - state 位运算：`sharedCount(c) == c >>> 16`、`exclusiveCount(c) == c & 0xFFFF`；验证读计数增加是 `SHARED_UNIT`（65536）步进。
  - 写锁获取条件：`exclusiveCount == 0`（无写）且（`c == 0` 或持有者是自己）；读锁获取条件：无写锁 且（公平下无写线程排队）。
  - 锁降级：持有写锁 → `readLock().lock()` → `writeLock().unlock()` 是合法顺序；反向升级会死锁（写等读释放、读等写释放）。
  - 非公平读锁的 `readerShouldBlock` 用 `apparentlyFirstQueuedIsExclusive` 防止队首写线程饥饿——只看队首不看全队。
  - `getReadHoldCount` 与 `getReadLockCount` 的区别（当前线程 vs 全局）。
- **配套实操**
  1. `com.example.locks.ReadWriteDemo01`：多读少写压测，统计读-读并发时长 vs 互斥锁版本，验证读共享吞吐。
  2. `com.example.locks.DowngradeDemo01`：实现「先写后降级读」的缓存更新（check-then-act），并演示升级失败的死锁（用 `tryLock(timeout)` 救回）。
  3. `com.example.locks.StarveDemo01`：持续读压力 + 偶发写，对比公平/非公平下写线程的等待时间分布。
- **复盘输出**
  - `notes/TU-10-ReadWriteLock.md`：state 位布局图 + 读写四种获取/释放的判定条件表；写清「为什么读锁不能升级、写锁可以降级」（含死锁时序图）。

---

## 阶段四：并发工具落地

### TU-11　Semaphore 与 CountDownLatch：共享语义的两种取向

- **学习重点**
  - `Semaphore`：state = 可用许可。非公平 `tryAcquireShared` 直接 CAS 减许可（ barging ）；公平版 `hasQueuedPredecessors` 排队。`release` 加许可 + `doReleaseShared` 传播。
  - `reducePermits/drainPermits` 等进阶方法的 state 直接操纵。
  - `CountDownLatch`：state = 剩余计数。`countDown` 走 `releaseShared`（减到 0 才返回 true 触发唤醒）；`await` 走 `acquireSharedInterruptibly`（`tryAcquireShared` 在 state==0 时返回 1）。一次性、不可重置。
  - 两者对照：可重入许可 vs 一次性倒计时；`release(n)` 的传播 vs `countDown` 归零的一次性全放行。
- **源码阅读范围**
  - `Semaphore` 全类（`Sync/NonfairSync/FairSync`、`acquire/acquireUninterruptibly/tryAcquire/release` 各重载、`availablePermits`）。
  - `CountDownLatch` 全类（`Sync`、`await/countDown/getCount`）——本类很短，通读。
  - 回看 TU-7 的 `doReleaseShared/setHeadAndPropagate` 在这两个类中的实际触发时机。
- **需要验证的点**
  - `Semaphore.release()` 没有配对 acquire 也能增加许可（计数上界语义），与锁的「必须持有者释放」不同。
  - `CountDownLatch.countDown()` 在计数未归零时返回 false 但不唤醒；归零瞬间唤醒全部 `await` 线程。
  - 公平 Semaphore 的 `tryAcquire(0)` 与非公平版语义差异（0 许可只查不抢）。
  - 两个类都是 `Serializable`，验证序列化只保存 state。
- **配套实操**
  1. `com.example.tools.SemaphorePool01`：用 `Semaphore(3)` 实现简单对象池（借出/归还），10 线程压测验证并发上限恰为 3。
  2. `com.example.tools.LatchDemo01`：模拟「N 个子任务汇总」：主线程 `await`，N 个工作线程完成后 `countDown`；再用 `CyclicBarrier` 对比两者差异（可重用 vs 一次性）。
  3. 故意多调 `countDown`/多 `release`，打印 `getCount/availablePermits` 观察越界行为。
- **复盘输出**
  - `notes/TU-11-Semaphore与CountDownLatch.md`：两者「state 语义 / tryAcquireShared 判定 / 唤醒时机」三行对照表；附一次对象池压测的结果截图或数据。

### TU-12　AQS 在线程池中的身影：ThreadPoolExecutor.Worker

- **学习重点**
  - `Worker extends AbstractQueuedSynchronizer`（JDK 8；JDK 9+ 改为继承 AQS 的简化实现）：每个工作线程是一个 AQS，用 state 0/1 实现**任务执行期间不可被中断**（`lock/unlock` 包住 `runWorker` 执行任务的段）。
  - `tryLock/tryAcquire` 的特殊语义：非重入、只在 state==0 时获取——保证 `interruptIfIdle` 不会打断正在跑任务的线程。
  - `shutdown` 中 `interruptIdleWorkers`：对每个 Worker `tryLock`，成功（空闲）才 `interrupt`，失败（忙）跳过——AQS 的一次工程化妙用。
  - 对照 `FutureTask`（JDK 8 也是 AQS，状态机更复杂）与 `ForkJoinPool.ManagedBlocker` 了解 AQS 思想的延伸。
- **源码阅读范围**
  - `ThreadPoolExecutor.Worker` 内部类全读（`lock/unlock/isLocked/tryLock/tryAcquire/tryRelease`）。
  - `runWorker(Worker)`、`getTask()`、`interruptIdleWorkers(boolean)`、`shutdown()` 中相关段落。
  - （选读）`FutureTask` 的 `state` 状态机注释与 `awaitDone/release` 方法。
- **需要验证的点**
  - Worker 的 `tryAcquire` 用 `compareAndSetState(0, 1)` 且不支持重入（`tryRelease` 直接置 0）——与 ReentrantLock 的区别。
  - 执行任务前后 `w.lock()/w.unlock()` 的作用域恰是「任务运行期」，`interruptIdleWorkers` 的 `tryLock` 失败即证明线程忙。
  - `shutdown` 不会打断正在执行任务的线程，只会打断空转等 `getTask` 的线程——用实验复现。
- **配套实操**
  1. `com.example.tools.IdleInterrupt01`：构造单线程池，提交一个 sleep 10s 的任务，调用 `shutdown()`，用 jstack/日志验证任务不被打断；对照提交空任务让线程空闲后再 shutdown，验证被打断。
  2. 打印 `Worker` 的 `isLocked`/`state` 反射值（或加日志）在任务运行期与空闲期的变化。
- **复盘输出**
  - `notes/TU-12-线程池Worker.md`：「Worker 为何用 AQS 而不是普通锁」的论证（非重入 + tryLock 探测忙闲 + 不可打断任务）；附 idle/busy 两态实验记录。

### TU-13　自研同步器 + 全局复盘

- **学习重点**
  - 综合运用：独立设计并实现一个完整同步器，把 state 语义、独占/共享、公平策略、Condition 全部走一遍。
  - 建立「AQS 解锁地图」：遇到一个同步需求时，如何判断用独占还是共享、state 怎么定、要不要公平、要不要 Condition。
  - AQS 的工程取舍总结：自旋+park 的折中、CAS 弱一致链表、逻辑删除、传播唤醒。
- **源码阅读范围**
  - 回读 `AbstractQueuedSynchronizer` 类注释中的使用范例（`Mutex`、`BooleanLatch` 两个官方示例）。
  - 自查前 12 个 TU 的笔记，标注仍有疑问的源码段，集中二次精读。
  - （拓展）`j.u.c.Phaser` 或 `StampedLock`：不是 AQS 子类，但思想同源，用一篇对比文收尾。
- **需要验证的点**
  - 自研同步器在多线程压测下：无死锁、无丢失唤醒、无饥饿（或明确文档化饥饿策略）、中断/超时行为符合契约。
  - 「解锁地图」决策树能覆盖 ReentrantLock/Semaphore/CountDownLatch/ReadWriteLock 四个案例。
- **配套实操**
  1. `com.example.final_.CyclicLatch01`：自研「可重置栅栏/可重用倒计时」（如 `ReusableLatch`，支持 `await` + `reset`），实现独占+共享混合语义，写 JUnit 并发测试（`CountDownLatch` 对齐、`ExecutorService` 压测）。
  2. `com.example.final_.CustomSemaphoreFair01`：自研公平信号量，与 `java.util.concurrent.Semaphore(true)` 行为对拍（随机压测比对 `availablePermits` 与放行顺序）。
  3. 在本工程 README 补一节「AQS 解锁地图」决策树。
- **复盘输出**
  - `notes/TU-13-自研与总复盘.md`：包含（a）自研同步器的设计文档与测试报告；（b）整个学习路径的「知识地图」一页图（state 语义 → 队列 → 三种唤醒 → 四类工具）；（c）10 个面试级问题自答（如：waitStatus 每个值何时出现？PROPAGATE 解决什么竞态？为什么 unpark 后还要自旋？公平锁如何防插队？Condition 为何不能用裸队列？……）。

---

## 贯穿性约定（所有 TU 通用）

1. **源码版本**：以 JDK 8 的 AQS 为主线（`Unsafe` CAS），完成后用 30 分钟 diff JDK 17 版（`VarHandle`、`Node` 精简），写一行结论即可。
   **注**：项目已内置 JDK 17.0.20.1 的 AQS 及锁/工具类拷贝（`com.example.aqs.locks` / `com.example.aqs.concurrent`，与官方逐字一致、仅少量 import 适配，见 README），本计划各 TU「源码阅读范围」默认读这份拷贝；加日志/埋点直接改它，改动请带 `// [STUDY]` 标记。TU-12 的 `ThreadPoolExecutor` 未内置，届时直读 JDK 源或按 README 说明补拷。
2. **阅读方法**：先通读方法注释 → 手画流程 → 再逐行读实现 → 最后用调试器验证。**每个 TU 至少断点一次**，日志/断点结论要落到复盘输出里。
3. **实操代码规范**：每个 demo 一个独立类，类名带 TU 编号；并发实验必须有可重复的对齐手段（`CyclicBarrier`/`CountDownLatch`），禁止裸 `sleep` 碰运气。
4. **验收门槛**：复盘输出里的「需要验证的点」逐条给出证据（源码行号 / 实验日志 / 断点截图），缺证据不算完成。
5. **遇到卡点**：优先回到「官方类注释 + 自己的流程图」，其次对照官方示例 `Mutex`/`BooleanLatch`，最后才查资料；卡住超过一个 TU 的正常精力投入就记录到复盘的「遗留问题」清单。

## 目录结构约定

```
AQS-SourceCode/
├── AQS-源码学习计划.md          # 本文件
├── notes/                       # 每个 TU 的复盘输出
│   ├── TU-1-架构定位.md
│   ├── ...
│   └── TU-13-自研与总复盘.md
└── src/main/java/com/example/
    ├── overview/    # TU-1
    ├── model/       # TU-2、TU-3
    ├── queue/       # TU-4 ~ TU-7
    ├── locks/       # TU-8 ~ TU-10
    ├── tools/       # TU-11、TU-12
    └── final_/      # TU-13
```
