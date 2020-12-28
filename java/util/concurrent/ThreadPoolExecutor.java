/*
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

/*
 *
 *
 *
 *
 *
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package java.util.concurrent;

import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.concurrent.locks.AbstractQueuedSynchronizer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.*;

/**
 * An {@link ExecutorService} that executes each submitted task using
 * one of possibly several pooled threads, normally configured
 * using {@link Executors} factory methods.
 *
 * <p>Thread pools address two different problems: they usually
 * provide improved performance when executing large numbers of
 * asynchronous tasks, due to reduced per-task invocation overhead,
 * and they provide a means of bounding and managing the resources,
 * including threads, consumed when executing a collection of tasks.
 * Each {@code ThreadPoolExecutor} also maintains some basic
 * statistics, such as the number of completed tasks.
 *
 * <p>To be useful across a wide range of contexts, this class
 * provides many adjustable parameters and extensibility
 * hooks. However, programmers are urged to use the more convenient
 * {@link Executors} factory methods {@link
 * Executors#newCachedThreadPool} (unbounded thread pool, with
 * automatic thread reclamation), {@link Executors#newFixedThreadPool}
 * (fixed size thread pool) and {@link
 * Executors#newSingleThreadExecutor} (single background thread), that
 * preconfigure settings for the most common usage
 * scenarios. Otherwise, use the following guide when manually
 * configuring and tuning this class:
 *
 * <dl>
 *
 * <dt>Core and maximum pool sizes</dt>
 *
 * <dd>A {@code ThreadPoolExecutor} will automatically adjust the
 * pool size (see {@link #getPoolSize})
 * according to the bounds set by
 * corePoolSize (see {@link #getCorePoolSize}) and
 * maximumPoolSize (see {@link #getMaximumPoolSize}).
 *
 * When a new task is submitted in method {@link #execute(Runnable)},
 * and fewer than corePoolSize threads are running, a new thread is
 * created to handle the request, even if other worker threads are
 * idle.  If there are more than corePoolSize but less than
 * maximumPoolSize threads running, a new thread will be created only
 * if the queue is full.  By setting corePoolSize and maximumPoolSize
 * the same, you create a fixed-size thread pool. By setting
 * maximumPoolSize to an essentially unbounded value such as {@code
 * Integer.MAX_VALUE}, you allow the pool to accommodate an arbitrary
 * number of concurrent tasks. Most typically, core and maximum pool
 * sizes are set only upon construction, but they may also be changed
 * dynamically using {@link #setCorePoolSize} and {@link
 * #setMaximumPoolSize}. </dd>
 *
 * <dt>On-demand construction</dt>
 *
 * <dd>By default, even core threads are initially created and
 * started only when new tasks arrive, but this can be overridden
 * dynamically using method {@link #prestartCoreThread} or {@link
 * #prestartAllCoreThreads}.  You probably want to prestart threads if
 * you construct the pool with a non-empty queue. </dd>
 *
 * <dt>Creating new threads</dt>
 *
 * <dd>New threads are created using a {@link ThreadFactory}.  If not
 * otherwise specified, a {@link Executors#defaultThreadFactory} is
 * used, that creates threads to all be in the same {@link
 * ThreadGroup} and with the same {@code NORM_PRIORITY} priority and
 * non-daemon status. By supplying a different ThreadFactory, you can
 * alter the thread's name, thread group, priority, daemon status,
 * etc. If a {@code ThreadFactory} fails to create a thread when asked
 * by returning null from {@code newThread}, the executor will
 * continue, but might not be able to execute any tasks. Threads
 * should possess the "modifyThread" {@code RuntimePermission}. If
 * worker threads or other threads using the pool do not possess this
 * permission, service may be degraded: configuration changes may not
 * take effect in a timely manner, and a shutdown pool may remain in a
 * state in which termination is possible but not completed.</dd>
 *
 * <dt>Keep-alive times</dt>
 *
 * <dd>If the pool currently has more than corePoolSize threads,
 * excess threads will be terminated if they have been idle for more
 * than the keepAliveTime (see {@link #getKeepAliveTime(TimeUnit)}).
 * This provides a means of reducing resource consumption when the
 * pool is not being actively used. If the pool becomes more active
 * later, new threads will be constructed. This parameter can also be
 * changed dynamically using method {@link #setKeepAliveTime(long,
 * TimeUnit)}.  Using a value of {@code Long.MAX_VALUE} {@link
 * TimeUnit#NANOSECONDS} effectively disables idle threads from ever
 * terminating prior to shut down. By default, the keep-alive policy
 * applies only when there are more than corePoolSize threads. But
 * method {@link #allowCoreThreadTimeOut(boolean)} can be used to
 * apply this time-out policy to core threads as well, so long as the
 * keepAliveTime value is non-zero. </dd>
 *
 * <dt>Queuing</dt>
 *
 * <dd>Any {@link BlockingQueue} may be used to transfer and hold
 * submitted tasks.  The use of this queue interacts with pool sizing:
 *
 * <ul>
 *
 * <li> If fewer than corePoolSize threads are running, the Executor
 * always prefers adding a new thread
 * rather than queuing.</li>
 *
 * <li> If corePoolSize or more threads are running, the Executor
 * always prefers queuing a request rather than adding a new
 * thread.</li>
 *
 * <li> If a request cannot be queued, a new thread is created unless
 * this would exceed maximumPoolSize, in which case, the task will be
 * rejected.</li>
 *
 * </ul>
 *
 * There are three general strategies for queuing:
 * <ol>
 *
 * <li> <em> Direct handoffs.</em> A good default choice for a work
 * queue is a {@link SynchronousQueue} that hands off tasks to threads
 * without otherwise holding them. Here, an attempt to queue a task
 * will fail if no threads are immediately available to run it, so a
 * new thread will be constructed. This policy avoids lockups when
 * handling sets of requests that might have internal dependencies.
 * Direct handoffs generally require unbounded maximumPoolSizes to
 * avoid rejection of new submitted tasks. This in turn admits the
 * possibility of unbounded thread growth when commands continue to
 * arrive on average faster than they can be processed.  </li>
 *
 * <li><em> Unbounded queues.</em> Using an unbounded queue (for
 * example a {@link LinkedBlockingQueue} without a predefined
 * capacity) will cause new tasks to wait in the queue when all
 * corePoolSize threads are busy. Thus, no more than corePoolSize
 * threads will ever be created. (And the value of the maximumPoolSize
 * therefore doesn't have any effect.)  This may be appropriate when
 * each task is completely independent of others, so tasks cannot
 * affect each others execution; for example, in a web page server.
 * While this style of queuing can be useful in smoothing out
 * transient bursts of requests, it admits the possibility of
 * unbounded work queue growth when commands continue to arrive on
 * average faster than they can be processed.  </li>
 *
 * <li><em>Bounded queues.</em> A bounded queue (for example, an
 * {@link ArrayBlockingQueue}) helps prevent resource exhaustion when
 * used with finite maximumPoolSizes, but can be more difficult to
 * tune and control.  Queue sizes and maximum pool sizes may be traded
 * off for each other: Using large queues and small pools minimizes
 * CPU usage, OS resources, and context-switching overhead, but can
 * lead to artificially low throughput.  If tasks frequently block (for
 * example if they are I/O bound), a system may be able to schedule
 * time for more threads than you otherwise allow. Use of small queues
 * generally requires larger pool sizes, which keeps CPUs busier but
 * may encounter unacceptable scheduling overhead, which also
 * decreases throughput.  </li>
 *
 * </ol>
 *
 * </dd>
 *
 * <dt>Rejected tasks</dt>
 *
 * <dd>New tasks submitted in method {@link #execute(Runnable)} will be
 * <em>rejected</em> when the Executor has been shut down, and also when
 * the Executor uses finite bounds for both maximum threads and work queue
 * capacity, and is saturated.  In either case, the {@code execute} method
 * invokes the {@link
 * RejectedExecutionHandler#rejectedExecution(Runnable, ThreadPoolExecutor)}
 * method of its {@link RejectedExecutionHandler}.  Four predefined handler
 * policies are provided:
 *
 * <ol>
 *
 * <li> In the default {@link ThreadPoolExecutor.AbortPolicy}, the
 * handler throws a runtime {@link RejectedExecutionException} upon
 * rejection. </li>
 *
 * <li> In {@link ThreadPoolExecutor.CallerRunsPolicy}, the thread
 * that invokes {@code execute} itself runs the task. This provides a
 * simple feedback control mechanism that will slow down the rate that
 * new tasks are submitted. </li>
 *
 * <li> In {@link ThreadPoolExecutor.DiscardPolicy}, a task that
 * cannot be executed is simply dropped.  </li>
 *
 * <li>In {@link ThreadPoolExecutor.DiscardOldestPolicy}, if the
 * executor is not shut down, the task at the head of the work queue
 * is dropped, and then execution is retried (which can fail again,
 * causing this to be repeated.) </li>
 *
 * </ol>
 *
 * It is possible to define and use other kinds of {@link
 * RejectedExecutionHandler} classes. Doing so requires some care
 * especially when policies are designed to work only under particular
 * capacity or queuing policies. </dd>
 *
 * <dt>Hook methods</dt>
 *
 * <dd>This class provides {@code protected} overridable
 * {@link #beforeExecute(Thread, Runnable)} and
 * {@link #afterExecute(Runnable, Throwable)} methods that are called
 * before and after execution of each task.  These can be used to
 * manipulate the execution environment; for example, reinitializing
 * ThreadLocals, gathering statistics, or adding log entries.
 * Additionally, method {@link #terminated} can be overridden to perform
 * any special processing that needs to be done once the Executor has
 * fully terminated.
 *
 * <p>If hook or callback methods throw exceptions, internal worker
 * threads may in turn fail and abruptly terminate.</dd>
 *
 * <dt>Queue maintenance</dt>
 *
 * <dd>Method {@link #getQueue()} allows access to the work queue
 * for purposes of monitoring and debugging.  Use of this method for
 * any other purpose is strongly discouraged.  Two supplied methods,
 * {@link #remove(Runnable)} and {@link #purge} are available to
 * assist in storage reclamation when large numbers of queued tasks
 * become cancelled.</dd>
 *
 * <dt>Finalization</dt>
 *
 * <dd>A pool that is no longer referenced in a program <em>AND</em>
 * has no remaining threads will be {@code shutdown} automatically. If
 * you would like to ensure that unreferenced pools are reclaimed even
 * if users forget to call {@link #shutdown}, then you must arrange
 * that unused threads eventually die, by setting appropriate
 * keep-alive times, using a lower bound of zero core threads and/or
 * setting {@link #allowCoreThreadTimeOut(boolean)}.  </dd>
 *
 * </dl>
 *
 * <p><b>Extension example</b>. Most extensions of this class
 * override one or more of the protected hook methods. For example,
 * here is a subclass that adds a simple pause/resume feature:
 *
 *  <pre> {@code
 * class PausableThreadPoolExecutor extends ThreadPoolExecutor {
 *   private boolean isPaused;
 *   private ReentrantLock pauseLock = new ReentrantLock();
 *   private Condition unpaused = pauseLock.newCondition();
 *
 *   public PausableThreadPoolExecutor(...) { super(...); }
 *
 *   protected void beforeExecute(Thread t, Runnable r) {
 *     super.beforeExecute(t, r);
 *     pauseLock.lock();
 *     try {
 *       while (isPaused) unpaused.await();
 *     } catch (InterruptedException ie) {
 *       t.interrupt();
 *     } finally {
 *       pauseLock.unlock();
 *     }
 *   }
 *
 *   public void pause() {
 *     pauseLock.lock();
 *     try {
 *       isPaused = true;
 *     } finally {
 *       pauseLock.unlock();
 *     }
 *   }
 *
 *   public void resume() {
 *     pauseLock.lock();
 *     try {
 *       isPaused = false;
 *       unpaused.signalAll();
 *     } finally {
 *       pauseLock.unlock();
 *     }
 *   }
 * }}</pre>
 *
 * @since 1.5
 * @author Doug Lea
 */
public class ThreadPoolExecutor extends AbstractExecutorService {
    /**
     * The main pool control state, ctl, is an atomic integer packing
     * two conceptual fields
     *   workerCount, indicating the effective number of threads
     *   runState,    indicating whether running, shutting down etc
     *
     * 线程池控制状态，ctl，这是一个将两个概念性字段打包的原子整型值
     *  工作者数量，指示有效线程数量
     *  运行状态，指示是否线程池处在运行状态或者关闭状态等等
     *
     * In order to pack them into one int, we limit workerCount to
     * (2^29)-1 (about 500 million) threads rather than (2^31)-1 (2
     * billion) otherwise representable. If this is ever an issue in
     * the future, the variable can be changed to be an AtomicLong,
     * and the shift/mask constants below adjusted. But until the need
     * arises, this code is a bit faster and simpler using an int.
     *
     * 为了将工作者数量和运行状态打包进一个整型值里，我们限制工作者数量为(2^29)-1（大概5亿）线程，而不是(2^31)-1（2亿）。
     * 如果在未来有问题，此变量能更改为原子长整型，并调整以下移位掩码常量。
     * 但是直到需求出现，此代码使用整型将更快且更简洁。
     *
     * The workerCount is the number of workers that have been
     * permitted to start and not permitted to stop.  The value may be
     * transiently different from the actual number of live threads,
     * for example when a ThreadFactory fails to create a thread when
     * asked, and when exiting threads are still performing
     * bookkeeping before terminating. The user-visible pool size is
     * reported as the current size of the workers set.
     *
     * 工作者数量是已被允许去开启并且不允许去关闭的工作者数量。
     * 这个值可能暂时地不同于存活线程的实际值，比如当线程池被要求创建线程却失败时，和在线程池关闭之前退出线程仍然在执行簿记操作。
     * 用户可见的线程池大小被报告为工作者集合的当前大小。
     *
     * The runState provides the main lifecycle control, taking on values:
     *
     * 运行状态提供了主要的生命周期控制，有以下值：
     *
     *   RUNNING:  Accept new tasks and process queued tasks
     *
     *   运行中：接受一个新的任务和处理排队任务
     *
     *   SHUTDOWN: Don't accept new tasks, but process queued tasks
     *
     *   关闭： 不接受新的任务，但是处理排队任务
     *
     *   STOP:     Don't accept new tasks, don't process queued tasks,
     *             and interrupt in-progress tasks
     *
     *   停止： 不接受新的任务，不处理排队任务，并且中断进程中的任务
     *
     *   TIDYING:  All tasks have terminated, workerCount is zero,
     *             the thread transitioning to state TIDYING
     *             will run the terminated() hook method
     *
     *   整理： 所有的任务已经终止，工作者数量为0，转换为状态TIDYING的线程将运行终止钩子方法
     *
     *   TERMINATED: terminated() has completed
     *
     *   终止： 终止方法已经完成
     *
     * The numerical order among these values matters, to allow
     * ordered comparisons. The runState monotonically increases over
     * time, but need not hit each state. The transitions are:
     *
     * 在这些值的数字顺序意味着允许顺序比较。
     * 运行状态随时间单调增加，但不必到达每个状态。
     * 转换为：
     *
     * RUNNING -> SHUTDOWN
     *    On invocation of shutdown(), perhaps implicitly in finalize()
     *
     * 运行中 -> 关闭
     *  在调用shutdown（）时，或者隐式地在finalize（）中
     *
     * (RUNNING or SHUTDOWN) -> STOP
     *    On invocation of shutdownNow()
     *
     * （运行中或者关闭） -> 关闭
     *  在调用shutdownNow()时
     *
     * SHUTDOWN -> TIDYING
     *    When both queue and pool are empty
     *
     * 关闭 -> 整理
     *  当队列和线程池都为空时
     *
     * STOP -> TIDYING
     *    When pool is empty
     *
     * 停止 -> 整理
     *  当线程池为空时
     *
     * TIDYING -> TERMINATED
     *    When the terminated() hook method has completed
     *
     * 整理 -> 关闭
     *  当关闭钩子方法完成时
     *
     * Threads waiting in awaitTermination() will return when the
     * state reaches TERMINATED.
     *
     * 当状态为终止状态，在awaitTermination方法里等待的线程将会返回。
     *
     * Detecting the transition from SHUTDOWN to TIDYING is less
     * straightforward than you'd like because the queue may become
     * empty after non-empty and vice versa during SHUTDOWN state, but
     * we can only terminate if, after seeing that it is empty, we see
     * that workerCount is 0 (which sometimes entails a recheck -- see
     * below).
     *
     * 检测从关闭到整理的状态转换比你想的简单，因为队列可能在关闭状态期间从非空状态变空，反之亦然，
     * 但是我们只有在看到队列为空以后，才能看到工作者数量为0，有时需要重新检查 -- 请看下面）
     *
     * Running = 29
     * ctlOf(29, 0)
     * 29 | 0 = 0
     *
     * 因此ctl初始值为0
     */
    private final AtomicInteger ctl = new AtomicInteger(ctlOf(RUNNING, 0));

    /**
     * Integer.SIZE为32位，因此COUNT_BITS为29位
     *
     * Integer = 32
     * COUNT_BITS = 32 - 3 = 29（这里说明32位中，用后29位来表示线程数量）
     */
    private static final int COUNT_BITS = Integer.SIZE - 3;

    /**
     * COUNT_BITS = 29
     * CAPACITY = (1 << 29) - 1 = xxx（536870911，反正是一个很大的数，也就是说线程池执行器最多允许这么多的线程）
     * CAPACITY = 0001 1111 1111 1111 1111 1111 1111 1111
     */
    private static final int CAPACITY   = (1 << COUNT_BITS) - 1;

    // runState is stored in the high-order bits
    // 运行状态被存储在高位里
    /**
     * RUNNING
     * -1 = 1111 1111 1111 1111 1111 1111 1111 1111
     * COUNT_BITS = 29
     * -1 << 29 = 1110 0000 0000 0000 0000 0000 0000 0000
     * 即32位中的前3位为111（-1）
     */
    private static final int RUNNING    = -1 << COUNT_BITS;

    /**
     * SHUTDOWN
     * 0
     * 即32位中的前3位为000（0）
     */
    private static final int SHUTDOWN   =  0 << COUNT_BITS;

    /**
     * STOP
     * 1 = 0000 0000 0000 0000 0000 0000 0000 0001
     * 1 << COUNT_BITS = 1 << 29 = 0010 0000 0000 0000 0000 0000 0000 0000
     * 即32位中的前3位为001（1）
     */
    private static final int STOP       =  1 << COUNT_BITS;

    /**
     * TIDYING
     * 2 = 0000 0000 0000 0000 0000 0000 0000 0010
     * 2 << COUNT_BITS = 2 << 29 = 0100 0000 0000 0000 0000 0000 0000 0000
     * 即32位中的前3位为010（2）
     */
    private static final int TIDYING    =  2 << COUNT_BITS;

    /**
     * TERMINATED
     * 3 = 0000 0000 0000 0000 0000 0000 0000 0011
     * 3 << COUNT_BITS = 3 << 29 = 0110 0000 0000 0000 0000 0000 0000 0000
     * 即32位中的前3位为011（3）
     */
    private static final int TERMINATED =  3 << COUNT_BITS;

    // Packing and unpacking ctl
    // 对ctl包箱和开箱
    private static int runStateOf(int c)     { return c & ~CAPACITY; }
    private static int workerCountOf(int c)  { return c & CAPACITY; }
    private static int ctlOf(int rs, int wc) { return rs | wc; }

    /*
     * Bit field accessors that don't require unpacking ctl.
     * These depend on the bit layout and on workerCount being never negative.
     */

    private static boolean runStateLessThan(int c, int s) {
        return c < s;
    }

    private static boolean runStateAtLeast(int c, int s) {
        return c >= s;
    }

    private static boolean isRunning(int c) {
        return c < SHUTDOWN;
    }

    /**
     * Attempts to CAS-increment the workerCount field of ctl.
     *
     * 尝试cas地更新ctl字段的工作者数量
     */
    private boolean compareAndIncrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect + 1);
    }

    /**
     * Attempts to CAS-decrement the workerCount field of ctl.
     */
    private boolean compareAndDecrementWorkerCount(int expect) {
        return ctl.compareAndSet(expect, expect - 1);
    }

    /**
     * Decrements the workerCount field of ctl. This is called only on
     * abrupt termination of a thread (see processWorkerExit). Other
     * decrements are performed within getTask.
     */
    private void decrementWorkerCount() {
        do {} while (! compareAndDecrementWorkerCount(ctl.get()));
    }

    /**
     * The queue used for holding tasks and handing off to worker
     * threads.  We do not require that workQueue.poll() returning
     * null necessarily means that workQueue.isEmpty(), so rely
     * solely on isEmpty to see if the queue is empty (which we must
     * do for example when deciding whether to transition from
     * SHUTDOWN to TIDYING).  This accommodates special-purpose
     * queues such as DelayQueues for which poll() is allowed to
     * return null even if it may later return non-null when delays
     * expire.
     */
    private final BlockingQueue<Runnable> workQueue;

    /**
     * Lock held on access to workers set and related bookkeeping.
     * While we could use a concurrent set of some sort, it turns out
     * to be generally preferable to use a lock. Among the reasons is
     * that this serializes interruptIdleWorkers, which avoids
     * unnecessary interrupt storms, especially during shutdown.
     * Otherwise exiting threads would concurrently interrupt those
     * that have not yet interrupted. It also simplifies some of the
     * associated statistics bookkeeping of largestPoolSize etc. We
     * also hold mainLock on shutdown and shutdownNow, for the sake of
     * ensuring workers set is stable while separately checking
     * permission to interrupt and actually interrupting.
     */
    private final ReentrantLock mainLock = new ReentrantLock();

    /**
     * Set containing all worker threads in pool. Accessed only when
     * holding mainLock.
     *
     * 包含池内所有工作者线程的集合。
     * 仅当拥有主锁时才能被访问。
     */
    private final HashSet<Worker> workers = new HashSet<Worker>();

    /**
     * Wait condition to support awaitTermination
     *
     * 支持awaitTermination的等待条件
     */
    private final Condition termination = mainLock.newCondition();

    /**
     * Tracks largest attained pool size. Accessed only under
     * mainLock.
     *
     * 跟踪达到的最大池大小。
     * 仅当在拥有主锁的情况下来能被访问。
     */
    private int largestPoolSize;

    /**
     * Counter for completed tasks. Updated only on termination of
     * worker threads. Accessed only under mainLock.
     *
     * 计数完成的任务。
     * 仅当工作者线程结束时更新。
     * 仅当拥有主锁时访问。
     */
    private long completedTaskCount;

    /*
     * All user control parameters are declared as volatiles so that
     * ongoing actions are based on freshest values, but without need
     * for locking, since no internal invariants depend on them
     * changing synchronously with respect to other actions.
     */

    /**
     * Factory for new threads. All threads are created using this
     * factory (via method addWorker).  All callers must be prepared
     * for addWorker to fail, which may reflect a system or user's
     * policy limiting the number of threads.  Even though it is not
     * treated as an error, failure to create threads may result in
     * new tasks being rejected or existing ones remaining stuck in
     * the queue.
     *
     * We go further and preserve pool invariants even in the face of
     * errors such as OutOfMemoryError, that might be thrown while
     * trying to create threads.  Such errors are rather common due to
     * the need to allocate a native stack in Thread.start, and users
     * will want to perform clean pool shutdown to clean up.  There
     * will likely be enough memory available for the cleanup code to
     * complete without encountering yet another OutOfMemoryError.
     */
    private volatile ThreadFactory threadFactory;

    /**
     * Handler called when saturated or shutdown in execute.
     */
    private volatile RejectedExecutionHandler handler;

    /**
     * Timeout in nanoseconds for idle threads waiting for work.
     * Threads use this timeout when there are more than corePoolSize
     * present or if allowCoreThreadTimeOut. Otherwise they wait
     * forever for new work.
     */
    private volatile long keepAliveTime;

    /**
     * If false (default), core threads stay alive even when idle.
     * If true, core threads use keepAliveTime to time out waiting
     * for work.
     */
    private volatile boolean allowCoreThreadTimeOut;

    /**
     * Core pool size is the minimum number of workers to keep alive
     * (and not allow to time out etc) unless allowCoreThreadTimeOut
     * is set, in which case the minimum is zero.
     *
     * 核心线程数大小是保证活跃的工作者的最小数量（不允许超时等等），除非allowCoreThreadTimeout被设置，在这种情况下最小值为0。
     */
    private volatile int corePoolSize;

    /**
     * Maximum pool size. Note that the actual maximum is internally
     * bounded by CAPACITY.
     *
     * 允许的最大线程数。
     * 注意：实际的最大值内部由CAPACITY限制。
     */
    private volatile int maximumPoolSize;

    /**
     * The default rejected execution handler
     *
     * 默认拒绝执行处理者
     */
    private static final RejectedExecutionHandler defaultHandler =
        new AbortPolicy();

    /**
     * Permission required for callers of shutdown and shutdownNow.
     * We additionally require (see checkShutdownAccess) that callers
     * have permission to actually interrupt threads in the worker set
     * (as governed by Thread.interrupt, which relies on
     * ThreadGroup.checkAccess, which in turn relies on
     * SecurityManager.checkAccess). Shutdowns are attempted only if
     * these checks pass.
     *
     * All actual invocations of Thread.interrupt (see
     * interruptIdleWorkers and interruptWorkers) ignore
     * SecurityExceptions, meaning that the attempted interrupts
     * silently fail. In the case of shutdown, they should not fail
     * unless the SecurityManager has inconsistent policies, sometimes
     * allowing access to a thread and sometimes not. In such cases,
     * failure to actually interrupt threads may disable or delay full
     * termination. Other uses of interruptIdleWorkers are advisory,
     * and failure to actually interrupt will merely delay response to
     * configuration changes so is not handled exceptionally.
     */
    private static final RuntimePermission shutdownPerm =
        new RuntimePermission("modifyThread");

    /* The context to be used when executing the finalizer, or null. */
    // 当执行finalizer时将被使用的上下文，或者为空
    private final AccessControlContext acc;

    /**
     * Class Worker mainly maintains interrupt control state for
     * threads running tasks, along with other minor bookkeeping.
     * This class opportunistically extends AbstractQueuedSynchronizer
     * to simplify acquiring and releasing a lock surrounding each
     * task execution.  This protects against interrupts that are
     * intended to wake up a worker thread waiting for a task from
     * instead interrupting a task being run.  We implement a simple
     * non-reentrant mutual exclusion lock rather than use
     * ReentrantLock because we do not want worker tasks to be able to
     * reacquire the lock when they invoke pool control methods like
     * setCorePoolSize.  Additionally, to suppress interrupts until
     * the thread actually starts running tasks, we initialize lock
     * state to a negative value, and clear it upon start (in
     * runWorker).
     */
    private final class Worker
        extends AbstractQueuedSynchronizer
        implements Runnable
    {
        /**
         * This class will never be serialized, but we provide a
         * serialVersionUID to suppress a javac warning.
         */
        private static final long serialVersionUID = 6138294804551838833L;

        /** Thread this worker is running in.  Null if factory fails. */
        // 此工作者在此线程中运行，如果工厂失败则为空
        final Thread thread;
        /** Initial task to run.  Possibly null. */
        Runnable firstTask;
        /** Per-thread task counter */
        // 每个线程的任务完成计数器
        volatile long completedTasks;

        /**
         * Creates with given first task and thread from ThreadFactory.
         *
         * 用给定的第一个任务和来自线程工厂里的线程来进行创建。
         *
         * @param firstTask the first task (null if none)
         */
        Worker(Runnable firstTask) {
            // 禁止中断，直至运行工作者
            setState(-1); // inhibit interrupts until runWorker

            // 设置当前工作者的第一个任务和线程
            this.firstTask = firstTask;
            this.thread = getThreadFactory().newThread(this);
        }

        /** Delegates main run loop to outer runWorker  */
        // 将主运行循环委托给外部runWorker
        public void run() {
            runWorker(this);
        }

        // Lock methods
        //
        // The value 0 represents the unlocked state.
        // The value 1 represents the locked state.

        protected boolean isHeldExclusively() {
            return getState() != 0;
        }

        protected boolean tryAcquire(int unused) {
            if (compareAndSetState(0, 1)) {
                setExclusiveOwnerThread(Thread.currentThread());
                return true;
            }
            return false;
        }

        protected boolean tryRelease(int unused) {
            setExclusiveOwnerThread(null);
            setState(0);
            return true;
        }

        public void lock()        { acquire(1); }
        public boolean tryLock()  { return tryAcquire(1); }
        public void unlock()      { release(1); }
        public boolean isLocked() { return isHeldExclusively(); }

        void interruptIfStarted() {
            Thread t;

            // 如果满足以下条件，则打断此工作者线程
            if (getState() >= 0 && (t = thread) != null && !t.isInterrupted()) {
                try {
                    t.interrupt();
                } catch (SecurityException ignore) {
                }
            }
        }
    }

    /*
     * Methods for setting control state
     */

    /**
     * Transitions runState to given target, or leaves it alone if
     * already at least the given target.
     *
     * @param targetState the desired state, either SHUTDOWN or STOP
     *        (but not TIDYING or TERMINATED -- use tryTerminate for that)
     */
    private void advanceRunState(int targetState) {
        for (;;) {
            // 获得当前线程池状态
            int c = ctl.get();

            // 如果当前线程池状态值大于等于目标状态值，或者cas改变当前线程池状态成功，则退出循环，否则此方法一直循环下去
            if (runStateAtLeast(c, targetState) ||
                ctl.compareAndSet(c, ctlOf(targetState, workerCountOf(c))))
                break;
        }
    }

    /**
     * Transitions to TERMINATED state if either (SHUTDOWN and pool
     * and queue empty) or (STOP and pool empty).  If otherwise
     * eligible to terminate but workerCount is nonzero, interrupts an
     * idle worker to ensure that shutdown signals propagate. This
     * method must be called following any action that might make
     * termination possible -- reducing worker count or removing tasks
     * from the queue during shutdown. The method is non-private to
     * allow access from ScheduledThreadPoolExecutor.
     */
    final void tryTerminate() {
        for (;;) {
            // 获得当前线程池执状态
            int c = ctl.get();

            // 如果线程池正在运行
            // 如果当前线程池状态值大于等于TIDYING（2）
            // 如果当前线程池处于关闭状态并且工作队列为空
            // 则返回
            if (isRunning(c) ||
                runStateAtLeast(c, TIDYING) ||
                (runStateOf(c) == SHUTDOWN && ! workQueue.isEmpty()))
                return;

            // 如果当前线程池的工作者数量不为0，则有资格进行终止操作
            if (workerCountOf(c) != 0) { // Eligible to terminate
                // 中断空闲的工作者，然后退出调用
                interruptIdleWorkers(ONLY_ONE);
                return;
            }

            // 获得主锁并上锁
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                // ctlOf(TIDYING, 0)
                // ctlOf(0100 0000 0000 0000 0000 0000 0000 0000, 0)
                // 0100 0000 0000 0000 0000 0000 0000 0000 | 0100 0000 0000 0000 0000 0000 0000 0000
                // 0100 0000 0000 0000 0000 0000 0000 0000
                // 以上说明ctlOf(TIDYING, 0)的结果总是TIDYING，即将当前ctl的值改变（将线程池状态改变）
                if (ctl.compareAndSet(c, ctlOf(TIDYING, 0))) {
                    try {
                        // 结束方法默认为空实现，可作为钩子方法由子类继承实现
                        terminated();
                    } finally {
                        // 最后设置当前线程池的状态为终止
                        ctl.set(ctlOf(TERMINATED, 0));
                        termination.signalAll();
                    }
                    return;
                }
            } finally {
                // 解锁主锁
                mainLock.unlock();
            }
            // else retry on failed CAS
        }
    }

    /*
     * Methods for controlling interrupts to worker threads.
     */

    /**
     * If there is a security manager, makes sure caller has
     * permission to shut down threads in general (see shutdownPerm).
     * If this passes, additionally makes sure the caller is allowed
     * to interrupt each worker thread. This might not be true even if
     * first check passed, if the SecurityManager treats some threads
     * specially.
     */
    private void checkShutdownAccess() {
        // 获得安全管理器
        SecurityManager security = System.getSecurityManager();

        // 如果安全管理器不为空
        if (security != null) {
            security.checkPermission(shutdownPerm);
            final ReentrantLock mainLock = this.mainLock;
            mainLock.lock();
            try {
                // 这里似乎是用安全管理器检查每个工作者的线程
                for (Worker w : workers)
                    security.checkAccess(w.thread);
            } finally {
                mainLock.unlock();
            }
        }
    }

    /**
     * Interrupts all threads, even if active. Ignores SecurityExceptions
     * (in which case some threads may remain uninterrupted).
     */
    private void interruptWorkers() {
        // 拿到主锁，并上锁
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 中断所有启动了的工作者
            for (Worker w : workers)
                w.interruptIfStarted();
        } finally {
            // 解锁主锁
            mainLock.unlock();
        }
    }

    /**
     * Interrupts threads that might be waiting for tasks (as
     * indicated by not being locked) so they can check for
     * termination or configuration changes. Ignores
     * SecurityExceptions (in which case some threads may remain
     * uninterrupted).
     *
     * 中断可能正在等待任务的线程（如没有被锁定），以便他们能检查终止或配置的改变。
     * 忽略安全异常（在这种情况下，一些线程可能保持不被中断）
     *
     * @param onlyOne If true, interrupt at most one worker. This is
     * called only from tryTerminate when termination is otherwise
     * enabled but there are still other workers.  In this case, at
     * most one waiting worker is interrupted to propagate shutdown
     * signals in case all threads are currently waiting.
     * Interrupting any arbitrary thread ensures that newly arriving
     * workers since shutdown began will also eventually exit.
     * To guarantee eventual termination, it suffices to always
     * interrupt only one idle worker, but shutdown() interrupts all
     * idle workers so that redundant workers exit promptly, not
     * waiting for a straggler task to finish.
     */
    private void interruptIdleWorkers(boolean onlyOne) {
        // 获得主锁，并上锁
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 对工作者集合进行处理
            for (Worker w : workers) {
                // 获得当前工作者的线程
                Thread t = w.thread;

                // 如果当前工作者线程未被中断，并且工作者获锁成功（即，当前线程可以独占此工作者，也说明此工作者之前未被其他线程独占，即此工作者之前并未处理任务）
                if (!t.isInterrupted() && w.tryLock()) {
                    try {
                        // 中断当前工作者线程
                        t.interrupt();
                    } catch (SecurityException ignore) {
                    } finally {
                        // 解锁当前工作者
                        w.unlock();
                    }
                }

                // onlyOne为true，代表此段循环只执行一趟
                if (onlyOne)
                    break;
            }
        } finally {
            // 解锁
            mainLock.unlock();
        }
    }

    /**
     * Common form of interruptIdleWorkers, to avoid having to
     * remember what the boolean argument means.
     */
    private void interruptIdleWorkers() {
        // onlyOne为假，说明中断所有的空闲工作者
        interruptIdleWorkers(false);
    }

    private static final boolean ONLY_ONE = true;

    /*
     * Misc utilities, most of which are also exported to
     * ScheduledThreadPoolExecutor
     */

    /**
     * Invokes the rejected execution handler for the given command.
     * Package-protected for use by ScheduledThreadPoolExecutor.
     */
    final void reject(Runnable command) {
        // 拒绝处理者执行拒绝执行策略
        handler.rejectedExecution(command, this);
    }

    /**
     * Performs any further cleanup following run state transition on
     * invocation of shutdown.  A no-op here, but used by
     * ScheduledThreadPoolExecutor to cancel delayed tasks.
     */
    void onShutdown() {
    }

    /**
     * State check needed by ScheduledThreadPoolExecutor to
     * enable running tasks during shutdown.
     *
     * @param shutdownOK true if should return true if SHUTDOWN
     */
    final boolean isRunningOrShutdown(boolean shutdownOK) {
        int rs = runStateOf(ctl.get());
        return rs == RUNNING || (rs == SHUTDOWN && shutdownOK);
    }

    /**
     * Drains the task queue into a new list, normally using
     * drainTo. But if the queue is a DelayQueue or any other kind of
     * queue for which poll or drainTo may fail to remove some
     * elements, it deletes them one by one.
     */
    private List<Runnable> drainQueue() {
        // 将工作队列workQueue里的任务排出到taskList中
        BlockingQueue<Runnable> q = workQueue;
        ArrayList<Runnable> taskList = new ArrayList<Runnable>();
        q.drainTo(taskList);

        // 对非空的阻塞工作队列做处理
        if (!q.isEmpty()) {
            // 得到一份该阻塞队列转换为Runnable类型的数组
            for (Runnable r : q.toArray(new Runnable[0])) {
                // 任务队列移除该任务，并将该任务添加到未完成任务集中
                if (q.remove(r))
                    taskList.add(r);
            }
        }

        // 返回未完成任务集
        return taskList;
    }

    /*
     * Methods for creating, running and cleaning up after workers
     */

    /**
     * Checks if a new worker can be added with respect to current
     * pool state and the given bound (either core or maximum). If so,
     * the worker count is adjusted accordingly, and, if possible, a
     * new worker is created and started, running firstTask as its
     * first task. This method returns false if the pool is stopped or
     * eligible to shut down. It also returns false if the thread
     * factory fails to create a thread when asked.  If the thread
     * creation fails, either due to the thread factory returning
     * null, or due to an exception (typically OutOfMemoryError in
     * Thread.start()), we roll back cleanly.
     *
     * @param firstTask the task the new thread should run first (or
     * null if none). Workers are created with an initial first task
     * (in method execute()) to bypass queuing when there are fewer
     * than corePoolSize threads (in which case we always start one),
     * or when the queue is full (in which case we must bypass queue).
     * Initially idle threads are usually created via
     * prestartCoreThread or to replace other dying workers.
     *
     * @param core if true use corePoolSize as bound, else
     * maximumPoolSize. (A boolean indicator is used here rather than a
     * value to ensure reads of fresh values after checking other pool
     * state).
     * @return true if successful
     */
    private boolean addWorker(Runnable firstTask, boolean core) {
        // 设置标记，并循环
        retry:
        for (;;) {
            // 获得当前线程池状态
            int c = ctl.get();

            // 从当前线程池状态中获得当前线程运行状态
            // runStateOf(c)
            // c & ~CAPACITY
            // c & 1110 0000 0000 0000 0000 0000 0000 0000
            // 此按位与的结果就是后面29位全为0，前3位为c的32位中的前3位
            int rs = runStateOf(c);

            // Check if queue empty only if necessary.
            // 仅当有必要的时候检查队列是否为空
            // 当线程池关闭、入参任务为空、工作队列为空这些情况发生，则添加工作者失败，直接返回
            if (rs >= SHUTDOWN &&
                ! (rs == SHUTDOWN &&
                   firstTask == null &&
                   ! workQueue.isEmpty()))
                return false;

            // 循环
            for (;;) {
                // 获得当前线程池中的工作者数量
                int wc = workerCountOf(c);

                // 如果工作者数量超过线程池线程的容量，则添加失败，直接返回
                // 如果要添加的工作者是核心工作者，则检查工作者数量是否超过核心线程数，如果超过核心线程数，则返回失败
                // 如果要添加的工作者不是核心工作者，则检查工作者数量是否超过最大线程数，如果超过最大线程数，则返回失败
                if (wc >= CAPACITY ||
                    wc >= (core ? corePoolSize : maximumPoolSize))
                    return false;

                // 如果更新工作者数量成功（即更新线程池状态），则退出标记块
                if (compareAndIncrementWorkerCount(c))
                    break retry;

                // 重新读取线程当前状态，取出线程状态，如果当前线程池运行状态和上一次的线程池运行状态不相同，则重新执行标记块
                c = ctl.get();  // Re-read ctl
                if (runStateOf(c) != rs)
                    continue retry;
                // else CAS failed due to workerCount change; retry inner loop
                // cas失败，可能是由于工作者数量改变；重新尝试内部循环
            }
        }

        // 当代码执行到这，说明更新线程池状态成功，成功地增加了一个工作者数量，接下来要实际地增加一个工作者
        boolean workerStarted = false;
        boolean workerAdded = false;
        Worker w = null;
        try {
            // 生成一个工作者
            w = new Worker(firstTask);
            // 拿到工作者的线程
            final Thread t = w.thread;

            // 如果工作者线程不空
            if (t != null) {
                // 拿到线程池主锁，并上锁
                final ReentrantLock mainLock = this.mainLock;
                mainLock.lock();
                try {
                    // Recheck while holding lock.
                    // Back out on ThreadFactory failure or if
                    // shut down before lock acquired.
                    /*
                        当拥有锁时重新检查。
                        如果线程工厂失败或者在获取锁之前线程池关闭，则回退。
                     */
                    // 获得当前线程池的运行状态
                    int rs = runStateOf(ctl.get());

                    // 如果线程池处于运行中状态
                    // 如果线程池关闭且第一个任务为空
                    if (rs < SHUTDOWN ||
                        (rs == SHUTDOWN && firstTask == null)) {
                        // 预检测工作者线程是否是已启动的，如果线程已启动，则抛出违规线程状态异常
                        if (t.isAlive()) // precheck that t is startable
                            throw new IllegalThreadStateException();

                        // 将新生的工作者对象加入到工作者对象集合中
                        workers.add(w);

                        // 获取当前工作者的数量，如果当前工作者的数量超过最大池大小，则更新最大池大小
                        int s = workers.size();
                        if (s > largestPoolSize)
                            largestPoolSize = s;

                        // 设置工作者添加成功标记
                        workerAdded = true;
                    }
                } finally {
                    // 解锁
                    mainLock.unlock();
                }

                // 如果工作者添加成功，则启动工作者线程，并置工作者启动标记为真
                if (workerAdded) {
                    t.start();
                    workerStarted = true;
                }
            }
        } finally {
            // 如果工作者启动失败，则需要做一些处理
            if (! workerStarted)
                addWorkerFailed(w);
        }

        // 返回工作者启动标记
        return workerStarted;
    }

    /**
     * Rolls back the worker thread creation.
     * - removes worker from workers, if present
     * - decrements worker count
     * - rechecks for termination, in case the existence of this
     *   worker was holding up termination
     */
    private void addWorkerFailed(Worker w) {
        // 拿到主锁并上锁
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 如果工作者对象不为空，则将该工作者从工作者集合中移除
            if (w != null)
                workers.remove(w);

            // cas地更新ctl，即当前线程池状态
            decrementWorkerCount();

            // 这里说明，如果当前线程池添加工作者失败，会尝试终止线程池，当然线程池不一定会终止
            tryTerminate();
        } finally {
            // 解锁主锁
            mainLock.unlock();
        }
    }

    /**
     * Performs cleanup and bookkeeping for a dying worker. Called
     * only from worker threads. Unless completedAbruptly is set,
     * assumes that workerCount has already been adjusted to account
     * for exit.  This method removes thread from worker set, and
     * possibly terminates the pool or replaces the worker if either
     * it exited due to user task exception or if fewer than
     * corePoolSize workers are running or queue is non-empty but
     * there are no workers.
     *
     * @param w the worker
     * @param completedAbruptly if the worker died due to user exception
     */
    private void processWorkerExit(Worker w, boolean completedAbruptly) {
        // 如果线程池是突然完成的，则不调整工作者数量（但好像实际上还是将工作者的数量减1了）
        if (completedAbruptly) // If abrupt, then workerCount wasn't adjusted
            decrementWorkerCount();

        // 线程池不是突然地完成，正常地完成了所有任务
        // 拿到主锁，并上锁
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 更新线程池完成的任务数
            completedTaskCount += w.completedTasks;
            // 将当前工作者从工作者集合中移除
            workers.remove(w);
        } finally {
            // 解锁主锁
            mainLock.unlock();
        }

        // 尝试关闭线程池
        tryTerminate();

        // 获得当前线程池状态
        int c = ctl.get();

        // 如果当前线程池的运行状态值大于等于STOP（1）
        if (runStateLessThan(c, STOP)) {
            // 如果线程池是正常地完成了所有的任务
            if (!completedAbruptly) {
                // 根据是否允许核心线程超时淘汰，设定最小线程数
                int min = allowCoreThreadTimeOut ? 0 : corePoolSize;
                // 如果最小线程数为0，并且任务队列不空，则设最小线程数为1
                if (min == 0 && ! workQueue.isEmpty())
                    min = 1;

                // 如果当前线程池的工作者数量等于或超过最小线程数，则返回此方法调用
                if (workerCountOf(c) >= min)
                    return; // replacement not needed
            }

            // 代码执行到这，说明当前线程池的工作者数量小于计算出来的最小线程数
            addWorker(null, false);
        }
    }

    /**
     * Performs blocking or timed wait for a task, depending on
     * current configuration settings, or returns null if this worker
     * must exit because of any of:
     * 1. There are more than maximumPoolSize workers (due to
     *    a call to setMaximumPoolSize).
     * 2. The pool is stopped.
     * 3. The pool is shutdown and the queue is empty.
     * 4. This worker timed out waiting for a task, and timed-out
     *    workers are subject to termination (that is,
     *    {@code allowCoreThreadTimeOut || workerCount > corePoolSize})
     *    both before and after the timed wait, and if the queue is
     *    non-empty, this worker is not the last thread in the pool.
     *
     * @return task, or null if the worker must exit, in which case
     *         workerCount is decremented
     */
    private Runnable getTask() {
        // 最后的poll是否超时？
        boolean timedOut = false; // Did the last poll() time out?

        // 循环
        for (;;) {
            // 获得当前线程池的状态、运行状态
            int c = ctl.get();
            int rs = runStateOf(c);

            // Check if queue empty only if necessary.
            // 仅当有必要的时候检查队列是否为空
            // 如果当前线程池关闭，并且线程状态值>=STOP（1）或者工作队列为空，则减少工作者数量减一，并返回空
            if (rs >= SHUTDOWN && (rs >= STOP || workQueue.isEmpty())) {
                decrementWorkerCount();
                return null;
            }

            // 获得当前线程池的工作者数量
            int wc = workerCountOf(c);

            // Are workers subject to culling?
            // 有工作者会被淘汰吗？
            // 如果允许核心线程超时或者当前线程池的工作者数量大于核心线程数，则允许工作者超时淘汰
            boolean timed = allowCoreThreadTimeOut || wc > corePoolSize;

            // 如果当前线程池的工作者数量大于最大线程数
            // 如果工作者超时淘汰，并且存在工作者超时的情况
            // 上述两种条件，发生任一一种情况，再加上下面的情况
            // 工作者数量大于1或者工作队列为空
            if ((wc > maximumPoolSize || (timed && timedOut))
                && (wc > 1 || workQueue.isEmpty())) {
                // 如果更新当前线程池的工作者数量成功，则返回空
                if (compareAndDecrementWorkerCount(c))
                    return null;
                // 否则，退出本趟循环
                continue;
            }

            // 如果上述代码的所有条件判断通过到达此处，则从任务队列中取出任务返回
            try {
                // 根据是否允许超时，调用不同的队列方法取出任务并返回
                Runnable r = timed ?
                    workQueue.poll(keepAliveTime, TimeUnit.NANOSECONDS) :
                    workQueue.take();

                // 如果取出的任务不为空，则直接返回
                if (r != null)
                    return r;

                // 代码到达此处，说明取出的任务为空，即取任务发生了超时现象，置超时标记为真
                timedOut = true;
            } catch (InterruptedException retry) {
                // 如果当前线程被中断，则将超时标记为假
                timedOut = false;
            }
        }
    }

    /**
     * Main worker run loop.  Repeatedly gets tasks from queue and
     * executes them, while coping with a number of issues:
     *
     * 1. We may start out with an initial task, in which case we
     * don't need to get the first one. Otherwise, as long as pool is
     * running, we get tasks from getTask. If it returns null then the
     * worker exits due to changed pool state or configuration
     * parameters.  Other exits result from exception throws in
     * external code, in which case completedAbruptly holds, which
     * usually leads processWorkerExit to replace this thread.
     *
     * 2. Before running any task, the lock is acquired to prevent
     * other pool interrupts while the task is executing, and then we
     * ensure that unless pool is stopping, this thread does not have
     * its interrupt set.
     *
     * 3. Each task run is preceded by a call to beforeExecute, which
     * might throw an exception, in which case we cause thread to die
     * (breaking loop with completedAbruptly true) without processing
     * the task.
     *
     * 4. Assuming beforeExecute completes normally, we run the task,
     * gathering any of its thrown exceptions to send to afterExecute.
     * We separately handle RuntimeException, Error (both of which the
     * specs guarantee that we trap) and arbitrary Throwables.
     * Because we cannot rethrow Throwables within Runnable.run, we
     * wrap them within Errors on the way out (to the thread's
     * UncaughtExceptionHandler).  Any thrown exception also
     * conservatively causes thread to die.
     *
     * 5. After task.run completes, we call afterExecute, which may
     * also throw an exception, which will also cause thread to
     * die. According to JLS Sec 14.20, this exception is the one that
     * will be in effect even if task.run throws.
     *
     * The net effect of the exception mechanics is that afterExecute
     * and the thread's UncaughtExceptionHandler have as accurate
     * information as we can provide about any problems encountered by
     * user code.
     *
     * @param w the worker
     */
    final void runWorker(Worker w) {
        // 取得当前工作者线程、工作者的第一个任务
        Thread wt = Thread.currentThread();
        Runnable task = w.firstTask;

        // 置工作者的第一个任务为空
        w.firstTask = null;
        // 解锁，允许中断
        w.unlock(); // allow interrupts

        // 线程池突然完成标记，初始值为真
        boolean completedAbruptly = true;
        try {
            // 当任务不为空时，执行循环
            while (task != null || (task = getTask()) != null) {
                // 工作者加锁
                w.lock();

                // If pool is stopping, ensure thread is interrupted;
                // if not, ensure thread is not interrupted.  This
                // requires a recheck in second case to deal with
                // shutdownNow race while clearing interrupt

                /*
                    如果线程池停止，确保线程被中断；
                    如果线程池没有停止，则确保线程不被中断。
                    这需要在第二种情况下重新检测以处理shutdownNow竞争，并且清除中断标记
                 */
                // 在满足下列条件表达式的情况下，将中断当前工作者线程
                if ((runStateAtLeast(ctl.get(), STOP) ||
                     (Thread.interrupted() &&
                      runStateAtLeast(ctl.get(), STOP))) &&
                    !wt.isInterrupted())
                    wt.interrupt();

                // 真正的执行流程
                try {
                    // beforeExecute为钩子方法，用于子类继承实现，在此类中为默认的空实现
                    beforeExecute(wt, task);

                    // 执行任务，记录处理异常
                    Throwable thrown = null;
                    try {
                        task.run();
                    } catch (RuntimeException x) {
                        thrown = x; throw x;
                    } catch (Error x) {
                        thrown = x; throw x;
                    } catch (Throwable x) {
                        thrown = x; throw new Error(x);
                    } finally {
                        // 在线程执行之后，afterExecute也是钩子方法，用于子类继承实现
                        afterExecute(task, thrown);
                    }
                } finally {
                    // 清除任务、 工作者完成的任务数加1、工作者解锁
                    task = null;
                    w.completedTasks++;
                    w.unlock();
                }
            }

            // 代码执行到这里，说明没有了任务了，退出了循环，因此线程池不是突然地完成了，将completedAbruptly变量标记为假
            completedAbruptly = false;
        } finally {
            // 处理工作者退出
            processWorkerExit(w, completedAbruptly);
        }
    }

    // Public constructors and methods

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters and default thread factory and rejected execution handler.
     * It may be more convenient to use one of the {@link Executors} factory
     * methods instead of this general purpose constructor.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             Executors.defaultThreadFactory(), defaultHandler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters and default rejected execution handler.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code threadFactory} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             threadFactory, defaultHandler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters and default thread factory.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code handler} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              RejectedExecutionHandler handler) {
        this(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue,
             Executors.defaultThreadFactory(), handler);
    }

    /**
     * Creates a new {@code ThreadPoolExecutor} with the given initial
     * parameters.
     *
     * @param corePoolSize the number of threads to keep in the pool, even
     *        if they are idle, unless {@code allowCoreThreadTimeOut} is set
     * @param maximumPoolSize the maximum number of threads to allow in the
     *        pool
     * @param keepAliveTime when the number of threads is greater than
     *        the core, this is the maximum time that excess idle threads
     *        will wait for new tasks before terminating.
     * @param unit the time unit for the {@code keepAliveTime} argument
     * @param workQueue the queue to use for holding tasks before they are
     *        executed.  This queue will hold only the {@code Runnable}
     *        tasks submitted by the {@code execute} method.
     * @param threadFactory the factory to use when the executor
     *        creates a new thread
     * @param handler the handler to use when execution is blocked
     *        because the thread bounds and queue capacities are reached
     * @throws IllegalArgumentException if one of the following holds:<br>
     *         {@code corePoolSize < 0}<br>
     *         {@code keepAliveTime < 0}<br>
     *         {@code maximumPoolSize <= 0}<br>
     *         {@code maximumPoolSize < corePoolSize}
     * @throws NullPointerException if {@code workQueue}
     *         or {@code threadFactory} or {@code handler} is null
     */
    public ThreadPoolExecutor(int corePoolSize,
                              int maximumPoolSize,
                              long keepAliveTime,
                              TimeUnit unit,
                              BlockingQueue<Runnable> workQueue,
                              ThreadFactory threadFactory,
                              RejectedExecutionHandler handler) {
        // 校验参数
        if (corePoolSize < 0 ||
            maximumPoolSize <= 0 ||
            maximumPoolSize < corePoolSize ||
            keepAliveTime < 0)
            throw new IllegalArgumentException();
        if (workQueue == null || threadFactory == null || handler == null)
            throw new NullPointerException();

        // 设置相关成员变量
        this.acc = System.getSecurityManager() == null ?
                null :
                AccessController.getContext();
        // 核心线程数
        this.corePoolSize = corePoolSize;
        // 最大线程数
        this.maximumPoolSize = maximumPoolSize;
        // 工作队列（也称为任务队列）
        this.workQueue = workQueue;
        // 超过核心线程数的线程的空闲存活时间（单位为毫微秒）
        this.keepAliveTime = unit.toNanos(keepAliveTime);
        // 线程工厂，用于创建线程
        this.threadFactory = threadFactory;
        // 当任务数量超过最大线程数时，线程池将执行的拒绝策略，此handler为拒绝策略执行者
        this.handler = handler;

        // 从此方法可以看出，此类在构造方法里并未实际地创建线程，只是先存储了一些用于创建线程池的数据
    }

    /**
     * Executes the given task sometime in the future.  The task
     * may execute in a new thread or in an existing pooled thread.
     *
     * 在未来的某个时间执行给定的任务。
     * 任务可能在一个新线程里或者在一个存在的池内线程里执行。
     *
     * If the task cannot be submitted for execution, either because this
     * executor has been shutdown or because its capacity has been reached,
     * the task is handled by the current {@code RejectedExecutionHandler}.
     *
     * 如果任务不能被提交执行，要么是因为此执行器已经关闭，要么是因为执行器的容量已经被达到，
     * 那么任务将由当前拒绝执行处理器处理。
     *
     * @param command the task to execute
     * @throws RejectedExecutionException at discretion of
     *         {@code RejectedExecutionHandler}, if the task
     *         cannot be accepted for execution
     * @throws NullPointerException if {@code command} is null
     */
    public void execute(Runnable command) {
        // 如果执行任务为空，则抛出空指针异常
        if (command == null)
            throw new NullPointerException();
        /*
         * Proceed in 3 steps:
         *
         * 在３个步骤里处理：
         *
         * 1. If fewer than corePoolSize threads are running, try to
         * start a new thread with the given command as its first
         * task.  The call to addWorker atomically checks runState and
         * workerCount, and so prevents false alarms that would add
         * threads when it shouldn't, by returning false.
         *
         * 1. 如果当前运行的线程数少于核心线程数，尝试开启一个新线程，并将该给定任务作为该新线程的第一个任务。
         * 对添加工作者方法的调用将自动地检查运行状态和工作者计数，并且在不应添加线程时，通过返回假，阻止添加线程的假警告。
         *
         * 2. If a task can be successfully queued, then we still need
         * to double-check whether we should have added a thread
         * (because existing ones died since last checking) or that
         * the pool shut down since entry into this method. So we
         * recheck state and if necessary roll back the enqueuing if
         * stopped, or start a new thread if there are none.
         *
         * 2. 如果任务能被成功地排队，我们仍然需要二次检查是否我们应该添加一个线程（因为有自上次检查之后存在线程死亡的情况），
         * 或者当条目进入到此方法后，线程池已经停止。
         * 所以我们得重新检查状态，在线程池停止的情况下回滚任务入队，或者是开启一个新线程（因为有自上次检查之后存在线程死亡的情况）。
         *
         * 3. If we cannot queue task, then we try to add a new
         * thread.  If it fails, we know we are shut down or saturated
         * and so reject the task.
         *
         * 3. 如果我们不能入队任务，那么我们将尝试添加一个新线程。
         * 如果添加新线程失败，则我们知道线程池处于关闭状态或者线程池已经饱和，那么我们需要拒绝任务。
         */

        // 拿到当前的线程池状态
        int c = ctl.get();

        // CAPACITY = 0001 1111 1111 1111 1111 1111 1111 1111
        // workerCountOf(c) = c & CAPACITY
        // c & 0001 1111 1111 1111 1111 1111 1111 1111
        // 按位与的结果就是32位中的前3位一定是0，后面位就是原本c的后29位（后29位代表线程池的线程数），即当前线程池的线程数
        // 如果当前线程池中的线程数小于核心线程数，则添加工作者，并且将入参任务作为此工作者的第一个任务，且该工作者被设置为核心工作者，即核心线程
        if (workerCountOf(c) < corePoolSize) {
            // 添加核心工作者，并且将入参任务作为它的第一个任务，然后返回
            if (addWorker(command, true))
                return;

            // 如果添加核心工作者失败，则获取最新的线程池状态
            c = ctl.get();
        }

        // 如果代码执行到这，说明当前线程池中的工作者数量大于或等于核心线程数，那么需要将任务入队
        // 如果当前线程池正在运行，并且往工作队列中添加入参任务成功
        if (isRunning(c) && workQueue.offer(command)) {
            // 获取最新的线程池状态
            int recheck = ctl.get();

            // 如果当前线程池不是正在运行的状态，并且任务队列移除入参任务成功，则拒绝当前任务
            if (! isRunning(recheck) && remove(command))
                reject(command);
            // 如果当前线程池是正在运行的状态，并且当前线程池的工作者数量为0，则新增新的工作者
            else if (workerCountOf(recheck) == 0)
                addWorker(null, false);
        }
        // 如果当前线程池没有运行或者任务入队失败（有可能队列已满），则尝试添加新的工作者，如果添加新的工作者失败，则拒绝任务
        else if (!addWorker(command, false))
            reject(command);
    }

    /**
     * Initiates an orderly shutdown in which previously submitted
     * tasks are executed, but no new tasks will be accepted.
     * Invocation has no additional effect if already shut down.
     *
     * <p>This method does not wait for previously submitted tasks to
     * complete execution.  Use {@link #awaitTermination awaitTermination}
     * to do that.
     *
     * @throws SecurityException {@inheritDoc}
     */
    public void shutdown() {
        // 获得主锁，并上锁
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 检查关闭访问
            checkShutdownAccess();
            // 提前设置运行状态
            advanceRunState(SHUTDOWN);
            // 中断空闲的工作者
            interruptIdleWorkers();

            // 用于ScheduledThreadPoolExecutor的钩子方法
            onShutdown(); // hook for ScheduledThreadPoolExecutor
        } finally {
            // 解锁主锁
            mainLock.unlock();
        }

        // 尝试终止线程池
        tryTerminate();
    }

    /**
     * Attempts to stop all actively executing tasks, halts the
     * processing of waiting tasks, and returns a list of the tasks
     * that were awaiting execution. These tasks are drained (removed)
     * from the task queue upon return from this method.
     *
     * <p>This method does not wait for actively executing tasks to
     * terminate.  Use {@link #awaitTermination awaitTermination} to
     * do that.
     *
     * <p>There are no guarantees beyond best-effort attempts to stop
     * processing actively executing tasks.  This implementation
     * cancels tasks via {@link Thread#interrupt}, so any task that
     * fails to respond to interrupts may never terminate.
     *
     * @throws SecurityException {@inheritDoc}
     */
    public List<Runnable> shutdownNow() {
        List<Runnable> tasks;

        // 拿到主锁，并上锁
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 检查关闭访问
            checkShutdownAccess();
            // 提前设置线程运行状态
            advanceRunState(STOP);
            // 中断所有工作者
            interruptWorkers();
            // 将工作队列的未被处理的任务派出
            tasks = drainQueue();
        } finally {
            // 解锁主锁
            mainLock.unlock();
        }

        // 尝试终止线程池，返回未完成任务集
        tryTerminate();
        return tasks;
    }

    public boolean isShutdown() {
        return ! isRunning(ctl.get());
    }

    /**
     * Returns true if this executor is in the process of terminating
     * after {@link #shutdown} or {@link #shutdownNow} but has not
     * completely terminated.  This method may be useful for
     * debugging. A return of {@code true} reported a sufficient
     * period after shutdown may indicate that submitted tasks have
     * ignored or suppressed interruption, causing this executor not
     * to properly terminate.
     *
     * @return {@code true} if terminating but not yet terminated
     */
    public boolean isTerminating() {
        int c = ctl.get();
        return ! isRunning(c) && runStateLessThan(c, TERMINATED);
    }

    public boolean isTerminated() {
        return runStateAtLeast(ctl.get(), TERMINATED);
    }

    public boolean awaitTermination(long timeout, TimeUnit unit)
        throws InterruptedException {
        // 将时间转换为纳米单位
        long nanos = unit.toNanos(timeout);

        // 拿到主锁，并上锁
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            for (;;) {
                // 如果线程池已经终止，则返回真
                if (runStateAtLeast(ctl.get(), TERMINATED))
                    return true;
                // 如果线程池仍有任务在处理，并且入参等待时间是负数，则返回假
                if (nanos <= 0)
                    return false;

                // 如果线程池仍有任务在处理，并且调用可以等待一段时间，则等待nanos纳秒
                nanos = termination.awaitNanos(nanos);
            }
        } finally {
            // 解锁主锁
            mainLock.unlock();
        }
    }

    /**
     * Invokes {@code shutdown} when this executor is no longer
     * referenced and it has no threads.
     */
    protected void finalize() {
        SecurityManager sm = System.getSecurityManager();
        if (sm == null || acc == null) {
            shutdown();
        } else {
            PrivilegedAction<Void> pa = () -> { shutdown(); return null; };
            AccessController.doPrivileged(pa, acc);
        }
    }

    /**
     * Sets the thread factory used to create new threads.
     *
     * @param threadFactory the new thread factory
     * @throws NullPointerException if threadFactory is null
     * @see #getThreadFactory
     */
    public void setThreadFactory(ThreadFactory threadFactory) {
        if (threadFactory == null)
            throw new NullPointerException();
        this.threadFactory = threadFactory;
    }

    /**
     * Returns the thread factory used to create new threads.
     *
     * 返回用来创建新线程的线程工厂。
     *
     * @return the current thread factory
     * @see #setThreadFactory(ThreadFactory)
     */
    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    /**
     * Sets a new handler for unexecutable tasks.
     *
     * @param handler the new handler
     * @throws NullPointerException if handler is null
     * @see #getRejectedExecutionHandler
     */
    public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
        if (handler == null)
            throw new NullPointerException();
        this.handler = handler;
    }

    /**
     * Returns the current handler for unexecutable tasks.
     *
     * @return the current handler
     * @see #setRejectedExecutionHandler(RejectedExecutionHandler)
     */
    public RejectedExecutionHandler getRejectedExecutionHandler() {
        return handler;
    }

    /**
     * Sets the core number of threads.  This overrides any value set
     * in the constructor.  If the new value is smaller than the
     * current value, excess existing threads will be terminated when
     * they next become idle.  If larger, new threads will, if needed,
     * be started to execute any queued tasks.
     *
     * @param corePoolSize the new core size
     * @throws IllegalArgumentException if {@code corePoolSize < 0}
     * @see #getCorePoolSize
     */
    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize < 0)
            throw new IllegalArgumentException();
        int delta = corePoolSize - this.corePoolSize;
        this.corePoolSize = corePoolSize;
        if (workerCountOf(ctl.get()) > corePoolSize)
            interruptIdleWorkers();
        else if (delta > 0) {
            // We don't really know how many new threads are "needed".
            // As a heuristic, prestart enough new workers (up to new
            // core size) to handle the current number of tasks in
            // queue, but stop if queue becomes empty while doing so.
            int k = Math.min(delta, workQueue.size());
            while (k-- > 0 && addWorker(null, true)) {
                if (workQueue.isEmpty())
                    break;
            }
        }
    }

    /**
     * Returns the core number of threads.
     *
     * @return the core number of threads
     * @see #setCorePoolSize
     */
    public int getCorePoolSize() {
        return corePoolSize;
    }

    /**
     * Starts a core thread, causing it to idly wait for work. This
     * overrides the default policy of starting core threads only when
     * new tasks are executed. This method will return {@code false}
     * if all core threads have already been started.
     *
     * @return {@code true} if a thread was started
     */
    public boolean prestartCoreThread() {
        return workerCountOf(ctl.get()) < corePoolSize &&
            addWorker(null, true);
    }

    /**
     * Same as prestartCoreThread except arranges that at least one
     * thread is started even if corePoolSize is 0.
     */
    void ensurePrestart() {
        int wc = workerCountOf(ctl.get());
        if (wc < corePoolSize)
            addWorker(null, true);
        else if (wc == 0)
            addWorker(null, false);
    }

    /**
     * Starts all core threads, causing them to idly wait for work. This
     * overrides the default policy of starting core threads only when
     * new tasks are executed.
     *
     * @return the number of threads started
     */
    public int prestartAllCoreThreads() {
        int n = 0;
        while (addWorker(null, true))
            ++n;
        return n;
    }

    /**
     * Returns true if this pool allows core threads to time out and
     * terminate if no tasks arrive within the keepAlive time, being
     * replaced if needed when new tasks arrive. When true, the same
     * keep-alive policy applying to non-core threads applies also to
     * core threads. When false (the default), core threads are never
     * terminated due to lack of incoming tasks.
     *
     * @return {@code true} if core threads are allowed to time out,
     *         else {@code false}
     *
     * @since 1.6
     */
    public boolean allowsCoreThreadTimeOut() {
        return allowCoreThreadTimeOut;
    }

    /**
     * Sets the policy governing whether core threads may time out and
     * terminate if no tasks arrive within the keep-alive time, being
     * replaced if needed when new tasks arrive. When false, core
     * threads are never terminated due to lack of incoming
     * tasks. When true, the same keep-alive policy applying to
     * non-core threads applies also to core threads. To avoid
     * continual thread replacement, the keep-alive time must be
     * greater than zero when setting {@code true}. This method
     * should in general be called before the pool is actively used.
     *
     * @param value {@code true} if should time out, else {@code false}
     * @throws IllegalArgumentException if value is {@code true}
     *         and the current keep-alive time is not greater than zero
     *
     * @since 1.6
     */
    public void allowCoreThreadTimeOut(boolean value) {
        if (value && keepAliveTime <= 0)
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        if (value != allowCoreThreadTimeOut) {
            allowCoreThreadTimeOut = value;
            if (value)
                interruptIdleWorkers();
        }
    }

    /**
     * Sets the maximum allowed number of threads. This overrides any
     * value set in the constructor. If the new value is smaller than
     * the current value, excess existing threads will be
     * terminated when they next become idle.
     *
     * @param maximumPoolSize the new maximum
     * @throws IllegalArgumentException if the new maximum is
     *         less than or equal to zero, or
     *         less than the {@linkplain #getCorePoolSize core pool size}
     * @see #getMaximumPoolSize
     */
    public void setMaximumPoolSize(int maximumPoolSize) {
        if (maximumPoolSize <= 0 || maximumPoolSize < corePoolSize)
            throw new IllegalArgumentException();
        this.maximumPoolSize = maximumPoolSize;
        if (workerCountOf(ctl.get()) > maximumPoolSize)
            interruptIdleWorkers();
    }

    /**
     * Returns the maximum allowed number of threads.
     *
     * @return the maximum allowed number of threads
     * @see #setMaximumPoolSize
     */
    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    /**
     * Sets the time limit for which threads may remain idle before
     * being terminated.  If there are more than the core number of
     * threads currently in the pool, after waiting this amount of
     * time without processing a task, excess threads will be
     * terminated.  This overrides any value set in the constructor.
     *
     * @param time the time to wait.  A time value of zero will cause
     *        excess threads to terminate immediately after executing tasks.
     * @param unit the time unit of the {@code time} argument
     * @throws IllegalArgumentException if {@code time} less than zero or
     *         if {@code time} is zero and {@code allowsCoreThreadTimeOut}
     * @see #getKeepAliveTime(TimeUnit)
     */
    public void setKeepAliveTime(long time, TimeUnit unit) {
        if (time < 0)
            throw new IllegalArgumentException();
        if (time == 0 && allowsCoreThreadTimeOut())
            throw new IllegalArgumentException("Core threads must have nonzero keep alive times");
        long keepAliveTime = unit.toNanos(time);
        long delta = keepAliveTime - this.keepAliveTime;
        this.keepAliveTime = keepAliveTime;
        if (delta < 0)
            interruptIdleWorkers();
    }

    /**
     * Returns the thread keep-alive time, which is the amount of time
     * that threads in excess of the core pool size may remain
     * idle before being terminated.
     *
     * @param unit the desired time unit of the result
     * @return the time limit
     * @see #setKeepAliveTime(long, TimeUnit)
     */
    public long getKeepAliveTime(TimeUnit unit) {
        return unit.convert(keepAliveTime, TimeUnit.NANOSECONDS);
    }

    /* User-level queue utilities */

    /**
     * Returns the task queue used by this executor. Access to the
     * task queue is intended primarily for debugging and monitoring.
     * This queue may be in active use.  Retrieving the task queue
     * does not prevent queued tasks from executing.
     *
     * @return the task queue
     */
    public BlockingQueue<Runnable> getQueue() {
        return workQueue;
    }

    /**
     * Removes this task from the executor's internal queue if it is
     * present, thus causing it not to be run if it has not already
     * started.
     *
     * <p>This method may be useful as one part of a cancellation
     * scheme.  It may fail to remove tasks that have been converted
     * into other forms before being placed on the internal queue. For
     * example, a task entered using {@code submit} might be
     * converted into a form that maintains {@code Future} status.
     * However, in such cases, method {@link #purge} may be used to
     * remove those Futures that have been cancelled.
     *
     * @param task the task to remove
     * @return {@code true} if the task was removed
     */
    public boolean remove(Runnable task) {
        // 从工作队列中移除一个任务
        boolean removed = workQueue.remove(task);
        // 尝试终止（因为有可能线程池状态为关闭，并且任务队列不空）
        tryTerminate(); // In case SHUTDOWN and now empty

        // 返回任务移除成功与否的标记
        return removed;
    }

    /**
     * Tries to remove from the work queue all {@link Future}
     * tasks that have been cancelled. This method can be useful as a
     * storage reclamation operation, that has no other impact on
     * functionality. Cancelled tasks are never executed, but may
     * accumulate in work queues until worker threads can actively
     * remove them. Invoking this method instead tries to remove them now.
     * However, this method may fail to remove tasks in
     * the presence of interference by other threads.
     */
    public void purge() {
        final BlockingQueue<Runnable> q = workQueue;
        try {
            Iterator<Runnable> it = q.iterator();
            while (it.hasNext()) {
                Runnable r = it.next();
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    it.remove();
            }
        } catch (ConcurrentModificationException fallThrough) {
            // Take slow path if we encounter interference during traversal.
            // Make copy for traversal and call remove for cancelled entries.
            // The slow path is more likely to be O(N*N).
            for (Object r : q.toArray())
                if (r instanceof Future<?> && ((Future<?>)r).isCancelled())
                    q.remove(r);
        }

        tryTerminate(); // In case SHUTDOWN and now empty
    }

    /* Statistics */

    /**
     * Returns the current number of threads in the pool.
     *
     * @return the number of threads
     */
    public int getPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // Remove rare and surprising possibility of
            // isTerminated() && getPoolSize() > 0
            return runStateAtLeast(ctl.get(), TIDYING) ? 0
                : workers.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the approximate number of threads that are actively
     * executing tasks.
     *
     * 返回正在活跃地执行任务的大概线程数量。
     *
     * @return the number of threads
     */
    public int getActiveCount() {
        // 拿到主锁，并上锁
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            // 计数
            int n = 0;
            for (Worker w : workers)
                // 如果工作者被锁住，说明当前工作者正在执行任务，则计数加1
                if (w.isLocked())
                    ++n;
            return n;
        } finally {
            // 解锁主锁
            mainLock.unlock();
        }
    }

    /**
     * Returns the largest number of threads that have ever
     * simultaneously been in the pool.
     *
     * @return the number of threads
     */
    public int getLargestPoolSize() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            return largestPoolSize;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the approximate total number of tasks that have ever been
     * scheduled for execution. Because the states of tasks and
     * threads may change dynamically during computation, the returned
     * value is only an approximation.
     *
     * @return the number of tasks
     */
    public long getTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers) {
                n += w.completedTasks;
                if (w.isLocked())
                    ++n;
            }
            return n + workQueue.size();
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns the approximate total number of tasks that have
     * completed execution. Because the states of tasks and threads
     * may change dynamically during computation, the returned value
     * is only an approximation, but one that does not ever decrease
     * across successive calls.
     *
     * @return the number of tasks
     */
    public long getCompletedTaskCount() {
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            long n = completedTaskCount;
            for (Worker w : workers)
                n += w.completedTasks;
            return n;
        } finally {
            mainLock.unlock();
        }
    }

    /**
     * Returns a string identifying this pool, as well as its state,
     * including indications of run state and estimated worker and
     * task counts.
     *
     * @return a string identifying this pool, as well as its state
     */
    public String toString() {
        long ncompleted;
        int nworkers, nactive;
        final ReentrantLock mainLock = this.mainLock;
        mainLock.lock();
        try {
            ncompleted = completedTaskCount;
            nactive = 0;
            nworkers = workers.size();
            for (Worker w : workers) {
                ncompleted += w.completedTasks;
                if (w.isLocked())
                    ++nactive;
            }
        } finally {
            mainLock.unlock();
        }
        int c = ctl.get();
        String rs = (runStateLessThan(c, SHUTDOWN) ? "Running" :
                     (runStateAtLeast(c, TERMINATED) ? "Terminated" :
                      "Shutting down"));
        return super.toString() +
            "[" + rs +
            ", pool size = " + nworkers +
            ", active threads = " + nactive +
            ", queued tasks = " + workQueue.size() +
            ", completed tasks = " + ncompleted +
            "]";
    }

    /* Extension hooks */

    /**
     * Method invoked prior to executing the given Runnable in the
     * given thread.  This method is invoked by thread {@code t} that
     * will execute task {@code r}, and may be used to re-initialize
     * ThreadLocals, or to perform logging.
     *
     * <p>This implementation does nothing, but may be customized in
     * subclasses. Note: To properly nest multiple overridings, subclasses
     * should generally invoke {@code super.beforeExecute} at the end of
     * this method.
     *
     * @param t the thread that will run task {@code r}
     * @param r the task that will be executed
     */
    protected void beforeExecute(Thread t, Runnable r) { }

    /**
     * Method invoked upon completion of execution of the given Runnable.
     * This method is invoked by the thread that executed the task. If
     * non-null, the Throwable is the uncaught {@code RuntimeException}
     * or {@code Error} that caused execution to terminate abruptly.
     *
     * <p>This implementation does nothing, but may be customized in
     * subclasses. Note: To properly nest multiple overridings, subclasses
     * should generally invoke {@code super.afterExecute} at the
     * beginning of this method.
     *
     * <p><b>Note:</b> When actions are enclosed in tasks (such as
     * {@link FutureTask}) either explicitly or via methods such as
     * {@code submit}, these task objects catch and maintain
     * computational exceptions, and so they do not cause abrupt
     * termination, and the internal exceptions are <em>not</em>
     * passed to this method. If you would like to trap both kinds of
     * failures in this method, you can further probe for such cases,
     * as in this sample subclass that prints either the direct cause
     * or the underlying exception if a task has been aborted:
     *
     *  <pre> {@code
     * class ExtendedExecutor extends ThreadPoolExecutor {
     *   // ...
     *   protected void afterExecute(Runnable r, Throwable t) {
     *     super.afterExecute(r, t);
     *     if (t == null && r instanceof Future<?>) {
     *       try {
     *         Object result = ((Future<?>) r).get();
     *       } catch (CancellationException ce) {
     *           t = ce;
     *       } catch (ExecutionException ee) {
     *           t = ee.getCause();
     *       } catch (InterruptedException ie) {
     *           Thread.currentThread().interrupt(); // ignore/reset
     *       }
     *     }
     *     if (t != null)
     *       System.out.println(t);
     *   }
     * }}</pre>
     *
     * @param r the runnable that has completed
     * @param t the exception that caused termination, or null if
     * execution completed normally
     */
    protected void afterExecute(Runnable r, Throwable t) { }

    /**
     * Method invoked when the Executor has terminated.  Default
     * implementation does nothing. Note: To properly nest multiple
     * overridings, subclasses should generally invoke
     * {@code super.terminated} within this method.
     */
    protected void terminated() { }

    /* Predefined RejectedExecutionHandlers */

    /**
     * A handler for rejected tasks that runs the rejected task
     * directly in the calling thread of the {@code execute} method,
     * unless the executor has been shut down, in which case the task
     * is discarded.
     */
    public static class CallerRunsPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code CallerRunsPolicy}.
         */
        public CallerRunsPolicy() { }

        /**
         * Executes task r in the caller's thread, unless the executor
         * has been shut down, in which case the task is discarded.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                r.run();
            }
        }
    }

    /**
     * A handler for rejected tasks that throws a
     * {@code RejectedExecutionException}.
     */
    public static class AbortPolicy implements RejectedExecutionHandler {
        /**
         * Creates an {@code AbortPolicy}.
         *
         * 创建一个拒绝策略
         */
        public AbortPolicy() { }

        /**
         * Always throws RejectedExecutionException.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         * @throws RejectedExecutionException always
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            // 直接抛出一个拒绝执行异常
            throw new RejectedExecutionException("Task " + r.toString() +
                                                 " rejected from " +
                                                 e.toString());
        }
    }

    /**
     * A handler for rejected tasks that silently discards the
     * rejected task.
     */
    public static class DiscardPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code DiscardPolicy}.
         */
        public DiscardPolicy() { }

        /**
         * Does nothing, which has the effect of discarding task r.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        }
    }

    /**
     * A handler for rejected tasks that discards the oldest unhandled
     * request and then retries {@code execute}, unless the executor
     * is shut down, in which case the task is discarded.
     */
    public static class DiscardOldestPolicy implements RejectedExecutionHandler {
        /**
         * Creates a {@code DiscardOldestPolicy} for the given executor.
         */
        public DiscardOldestPolicy() { }

        /**
         * Obtains and ignores the next task that the executor
         * would otherwise execute, if one is immediately available,
         * and then retries execution of task r, unless the executor
         * is shut down, in which case task r is instead discarded.
         *
         * @param r the runnable task requested to be executed
         * @param e the executor attempting to execute this task
         */
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            if (!e.isShutdown()) {
                e.getQueue().poll();
                e.execute(r);
            }
        }
    }
}
