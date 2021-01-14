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
import java.util.concurrent.locks.LockSupport;

/**
 * A cancellable asynchronous computation.  This class provides a base
 * implementation of {@link Future}, with methods to start and cancel
 * a computation, query to see if the computation is complete, and
 * retrieve the result of the computation.  The result can only be
 * retrieved when the computation has completed; the {@code get}
 * methods will block if the computation has not yet completed.  Once
 * the computation has completed, the computation cannot be restarted
 * or cancelled (unless the computation is invoked using
 * {@link #runAndReset}).
 *
 * <p>A {@code FutureTask} can be used to wrap a {@link Callable} or
 * {@link Runnable} object.  Because {@code FutureTask} implements
 * {@code Runnable}, a {@code FutureTask} can be submitted to an
 * {@link Executor} for execution.
 *
 * <p>In addition to serving as a standalone class, this class provides
 * {@code protected} functionality that may be useful when creating
 * customized task classes.
 *
 * @since 1.5
 * @author Doug Lea
 * @param <V> The result type returned by this FutureTask's {@code get} methods
 */
public class FutureTask<V> implements RunnableFuture<V> {
    /*
     * Revision notes: This differs from previous versions of this
     * class that relied on AbstractQueuedSynchronizer, mainly to
     * avoid surprising users about retaining interrupt status during
     * cancellation races. Sync control in the current design relies
     * on a "state" field updated via CAS to track completion, along
     * with a simple Treiber stack to hold waiting threads.
     *
     * Style note: As usual, we bypass overhead of using
     * AtomicXFieldUpdaters and instead directly use Unsafe intrinsics.
     */

    /**
     * The run state of this task, initially NEW.  The run state
     * transitions to a terminal state only in methods set,
     * setException, and cancel.  During completion, state may take on
     * transient values of COMPLETING (while outcome is being set) or
     * INTERRUPTING (only while interrupting the runner to satisfy a
     * cancel(true)). Transitions from these intermediate to final
     * states use cheaper ordered/lazy writes because values are unique
     * and cannot be further modified.
     *
     * 此任务的运行状态，初始为新建。
     * 运行状态仅在方法set、setException、cancel中转变为终止状态。
     * 在完成期间，状态可能会是“计算（输出被设置）”或“中断（仅当中断运行者以满足cancel(true)）”这两种短暂状态之一。
     * 从这些中间状态到最终状态的转变使用更平价的有序懒写方式，因为这些值是唯一的，并且不能在过后被修改。
     *
     * Possible state transitions:
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     *
     * 可能的状态转变：
     * NEW -> COMPLETING -> NORMAL
     * NEW -> COMPLETING -> EXCEPTIONAL
     * NEW -> CANCELLED
     * NEW -> INTERRUPTING -> INTERRUPTED
     */
    private volatile int state;
    private static final int NEW          = 0;
    private static final int COMPLETING   = 1;
    private static final int NORMAL       = 2;
    private static final int EXCEPTIONAL  = 3;
    private static final int CANCELLED    = 4;
    private static final int INTERRUPTING = 5;
    private static final int INTERRUPTED  = 6;

    /** The underlying callable; nulled out after running */
    // 底层可调用对象；在运行之后消失
    private Callable<V> callable;
    /** The result to return or exception to throw from get() */
    /*
        将要从get方法中返回的结果，或者抛出的异常。
        非易变的，受状态reads/writes保护。
     */
    private Object outcome; // non-volatile, protected by state reads/writes
    /** The thread running the callable; CASed during run() */
    private volatile Thread runner;
    /** Treiber stack of waiting threads */
    // Treiberg等待线程堆栈
    private volatile WaitNode waiters;

    /**
     * Returns result or throws exception for completed task.
     *
     * 对于完成的任务，返回结果或者抛出异常
     *
     * @param s completed state value
     */
    @SuppressWarnings("unchecked")
    private V report(int s) throws ExecutionException {
        // 获得结果
        Object x = outcome;

        // 如果任务状态正常，则返回结果
        if (s == NORMAL)
            return (V)x;
        // 如果任务被取消或者中断，则抛出取消异常
        if (s >= CANCELLED)
            throw new CancellationException();
        // 如果任务状态不正常，也未被取消或中断，则是任务执行异常，将此异常抛出
        throw new ExecutionException((Throwable)x);
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Callable}.
     *
     * 创建FutureTask实例，该实例将在运行时计算给定的Callable。
     *
     * @param  callable the callable task
     * @throws NullPointerException if the callable is null
     */
    public FutureTask(Callable<V> callable) {
        // 对入参可调用对象判空，如果入参可调用对象为空，则抛出空指针异常
        if (callable == null)
            throw new NullPointerException();

        // 设置当前FutureTask的可调用对象实例和当前任务状态
        this.callable = callable;
        // 确保可调用对象的可见性
        this.state = NEW;       // ensure visibility of callable
    }

    /**
     * Creates a {@code FutureTask} that will, upon running, execute the
     * given {@code Runnable}, and arrange that {@code get} will return the
     * given result on successful completion.
     *
     * @param runnable the runnable task
     * @param result the result to return on successful completion. If
     * you don't need a particular result, consider using
     * constructions of the form:
     * {@code Future<?> f = new FutureTask<Void>(runnable, null)}
     * @throws NullPointerException if the runnable is null
     */
    public FutureTask(Runnable runnable, V result) {
        this.callable = Executors.callable(runnable, result);
        this.state = NEW;       // ensure visibility of callable
    }

    public boolean isCancelled() {
        return state >= CANCELLED;
    }

    public boolean isDone() {
        return state != NEW;
    }

    public boolean cancel(boolean mayInterruptIfRunning) {
        if (!(state == NEW &&
              UNSAFE.compareAndSwapInt(this, stateOffset, NEW,
                  mayInterruptIfRunning ? INTERRUPTING : CANCELLED)))
            return false;
        try {    // in case call to interrupt throws exception
            if (mayInterruptIfRunning) {
                try {
                    Thread t = runner;
                    if (t != null)
                        t.interrupt();
                } finally { // final state
                    UNSAFE.putOrderedInt(this, stateOffset, INTERRUPTED);
                }
            }
        } finally {
            finishCompletion();
        }
        return true;
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    public V get() throws InterruptedException, ExecutionException {
        // 获得当前任务运行状态
        int s = state;

        // 如果当前任务还未开始或者仍在进行中，则等待任务完成
        if (s <= COMPLETING)
            s = awaitDone(false, 0L);

        // 报告当前任务状态
        return report(s);
    }

    /**
     * @throws CancellationException {@inheritDoc}
     */
    public V get(long timeout, TimeUnit unit)
        throws InterruptedException, ExecutionException, TimeoutException {
        if (unit == null)
            throw new NullPointerException();
        int s = state;
        if (s <= COMPLETING &&
            (s = awaitDone(true, unit.toNanos(timeout))) <= COMPLETING)
            throw new TimeoutException();
        return report(s);
    }

    /**
     * Protected method invoked when this task transitions to state
     * {@code isDone} (whether normally or via cancellation). The
     * default implementation does nothing.  Subclasses may override
     * this method to invoke completion callbacks or perform
     * bookkeeping. Note that you can query status inside the
     * implementation of this method to determine whether this task
     * has been cancelled.
     */
    protected void done() { }

    /**
     * Sets the result of this future to the given value unless
     * this future has already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon successful completion of the computation.
     *
     * @param v the value
     */
    protected void set(V v) {
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            outcome = v;
            UNSAFE.putOrderedInt(this, stateOffset, NORMAL); // final state
            finishCompletion();
        }
    }

    /**
     * Causes this future to report an {@link ExecutionException}
     * with the given throwable as its cause, unless this future has
     * already been set or has been cancelled.
     *
     * <p>This method is invoked internally by the {@link #run} method
     * upon failure of the computation.
     *
     * @param t the cause of failure
     */
    protected void setException(Throwable t) {
        // cas地将任务状态变更为计算中
        if (UNSAFE.compareAndSwapInt(this, stateOffset, NEW, COMPLETING)) {
            // 将任务的输出结果设置为被抛出的异常
            outcome = t;
            UNSAFE.putOrderedInt(this, stateOffset, EXCEPTIONAL); // final state

            // 结束计算
            finishCompletion();
        }
    }

    public void run() {
        // 判断任务状态（必须是新建状态），接着设置运行者
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return;

        // 主逻辑。此段代码一般会被线程池调用。
        try {
            Callable<V> c = callable;

            // 如果调用对象不为空并且任务状态新建，则执行下面逻辑
            if (c != null && state == NEW) {
                V result;
                boolean ran;
                try {
                    // 执行用户自定义逻辑，并获得返回结果，将任务执行标记置为真
                    result = c.call();
                    ran = true;
                } catch (Throwable ex) {
                    // 异常处理：置结果为空、任务执行标记为假、设置异常信息
                    result = null;
                    ran = false;
                    setException(ex);
                }

                // 如果任务正常完成，则设置结果
                if (ran)
                    set(result);
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            // 运行者必须不空，直至任务状态被设置，以阻止对run方法的并发调用
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            // 在清空运行者之后，状态必须可重复读取，以泄露中断
            int s = state;

            // 如果状态值在中断之后，则处理可能取消的中断
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
    }

    /**
     * Executes the computation without setting its result, and then
     * resets this future to initial state, failing to do so if the
     * computation encounters an exception or is cancelled.  This is
     * designed for use with tasks that intrinsically execute more
     * than once.
     *
     * @return {@code true} if successfully run and reset
     */
    protected boolean runAndReset() {
        if (state != NEW ||
            !UNSAFE.compareAndSwapObject(this, runnerOffset,
                                         null, Thread.currentThread()))
            return false;
        boolean ran = false;
        int s = state;
        try {
            Callable<V> c = callable;
            if (c != null && s == NEW) {
                try {
                    c.call(); // don't set result
                    ran = true;
                } catch (Throwable ex) {
                    setException(ex);
                }
            }
        } finally {
            // runner must be non-null until state is settled to
            // prevent concurrent calls to run()
            runner = null;
            // state must be re-read after nulling runner to prevent
            // leaked interrupts
            s = state;
            if (s >= INTERRUPTING)
                handlePossibleCancellationInterrupt(s);
        }
        return ran && s == NEW;
    }

    /**
     * Ensures that any interrupt from a possible cancel(true) is only
     * delivered to a task while in run or runAndReset.
     *
     * 确保在run或者runAndReset方法中，任何可能来自cancel(true)的中断，被传递到任务。
     */
    private void handlePossibleCancellationInterrupt(int s) {
        // It is possible for our interrupter to stall before getting a
        // chance to interrupt us.  Let's spin-wait patiently.
        if (s == INTERRUPTING)
            while (state == INTERRUPTING)
                Thread.yield(); // wait out pending interrupt

        // assert state == INTERRUPTED;

        // We want to clear any interrupt we may have received from
        // cancel(true).  However, it is permissible to use interrupts
        // as an independent mechanism for a task to communicate with
        // its caller, and there is no way to clear only the
        // cancellation interrupt.
        //
        // Thread.interrupted();
    }

    /**
     * Simple linked list nodes to record waiting threads in a Treiber
     * stack.  See other classes such as Phaser and SynchronousQueue
     * for more detailed explanation.
     *
     * 用以记录在Treiber栈里等待线程的简单链接列表。
     * 更多细节解释，请看其他的类，如：Phaser和SynchronousQueue。
     *
     * （该结点是用来包装线程的。）
     */
    static final class WaitNode {
        volatile Thread thread;
        volatile WaitNode next;
        WaitNode() { thread = Thread.currentThread(); }
    }

    /**
     * Removes and signals all waiting threads, invokes done(), and
     * nulls out callable.
     *
     * 移除并通知所有正在等待线程，调用done()方法，并且使可调用对象无效。
     */
    private void finishCompletion() {
        // assert state > COMPLETING;
        // 断言任务状态值为COMPLETING之后；
        for (WaitNode q; (q = waiters) != null;) {
            // 将当前等待结点置空
            if (UNSAFE.compareAndSwapObject(this, waitersOffset, q, null)) {
                // 循环操作
                for (;;) {
                    // 获得当前结点的线程
                    Thread t = q.thread;

                    // 如果当前结点的线程不为空，则置当前结点的线程为空，并且开车当前结点的线程
                    if (t != null) {
                        q.thread = null;
                        LockSupport.unpark(t);
                    }

                    // 此时当前结点的线程为空，则获得当前结点的下一个结点
                    WaitNode next = q.next;

                    // 如果下一个结点为空，则退出循环
                    if (next == null)
                        break;

                    // 如果下一个结点不为空，则将当前等待结点的下一个结点引用置空，并且跳转处理下一个结点
                    q.next = null; // unlink to help gc
                    q = next;
                }

                // 退出循环
                break;
            }
        }

        // 钩子方法，在本类中是空实现。子类可以实现此方法，以实现一些特别的自定义功能。
        done();

        // 使可调用对象失效
        callable = null;        // to reduce footprint
    }

    /**
     * Awaits completion or aborts on interrupt or timeout.
     *
     * @param timed true if use timed waits
     * @param nanos time to wait, if timed
     * @return state upon completion
     */
    private int awaitDone(boolean timed, long nanos)
        throws InterruptedException {
        // 根据是否计时等待，计算出任务终止时刻
        final long deadline = timed ? System.nanoTime() + nanos : 0L;
        WaitNode q = null;
        boolean queued = false;

        // 循环处理，直至以下情况发生，退出循环
        for (;;) {
            // 如果当前线程（执行任务的线程）被中断，则移除等待结点，并抛出被中断异常
            if (Thread.interrupted()) {
                removeWaiter(q);
                throw new InterruptedException();
            }

            /*
                当前线程未被中断
             */
            // 获得当前任务状态
            int s = state;

            // 如果当前任务状态既不是新建状态，也不是正在计算的状态（也就是，不管是计算成功还是失败，该任务都有一个结果。）
            if (s > COMPLETING) {
                // 如果当前等待结点不为空，则设置当前等待结点的线程为空，接着返回当前任务的状态
                if (q != null)
                    q.thread = null;
                return s;
            }
            // 如果当前任务正在计算中，还不能超时
            else if (s == COMPLETING) // cannot time out yet
                // 当前线程让出cpu
                Thread.yield();
            // 如果当前任务处于新建状态，并且等待结点为空，则新建一个等待结点
            else if (q == null)
                // 要什么要有一个WaitNode结点，是因为可能会有多个线程调用FutureTask的get方法
                q = new WaitNode();
            // 如果当前任务处于新建状态，且等待结点不为空，但是未排队，则排队
            else if (!queued)
                queued = UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                     q.next = waiters, q);
            // 如果以上条件都不满足，并且该任务有时间限制
            else if (timed) {
                // 超时，则移除等待结点，返回当前任务状态；若未超时，则在剩余的时间内陷入阻塞
                nanos = deadline - System.nanoTime();
                if (nanos <= 0L) {
                    removeWaiter(q);
                    return state;
                }
                LockSupport.parkNanos(this, nanos);
            }
            else
                // 否则，阻塞当前线程
                LockSupport.park(this);
        }
    }

    /**
     * Tries to unlink a timed-out or interrupted wait node to avoid
     * accumulating garbage.  Internal nodes are simply unspliced
     * without CAS since it is harmless if they are traversed anyway
     * by releasers.  To avoid effects of unsplicing from already
     * removed nodes, the list is retraversed in case of an apparent
     * race.  This is slow when there are a lot of nodes, but we don't
     * expect lists to be long enough to outweigh higher-overhead
     * schemes.
     */
    private void removeWaiter(WaitNode node) {
        // 必须保证结点不为空
        if (node != null) {
            // 将结点的线程引用置为空
            node.thread = null;

            retry:
            // 重新开始removeWaiter竞赛
            for (;;) {          // restart on removeWaiter race
                for (WaitNode pred = null, q = waiters, s; q != null; q = s) {
                    // 记录当前结点的下一个结点
                    s = q.next;

                    // 如果当前结点的线程不为空，则置上一个结点为当前结点，然后跳过当前结点
                    if (q.thread != null)
                        pred = q;
                    // 如果当前结点的线程为空，并且上一个结点也不为空
                    else if (pred != null) {
                        // 将上一个的结点的下一结点引用到当前结点的下一个结点
                        pred.next = s;

                        // 如果上一个结点的线程为空，则继续检查对比
                        if (pred.thread == null) // check for race
                            continue retry;
                    }
                    // 如果当前结点的线程为空，并且上一个结点也为空，则cas地移除当前结点，如果移除失败，则再次尝试移除结点
                    else if (!UNSAFE.compareAndSwapObject(this, waitersOffset,
                                                          q, s))
                        continue retry;
                }

                // 跳出循环
                break;
            }
        }
    }

    // Unsafe mechanics
    private static final sun.misc.Unsafe UNSAFE;
    private static final long stateOffset;
    private static final long runnerOffset;
    private static final long waitersOffset;
    static {
        try {
            UNSAFE = sun.misc.Unsafe.getUnsafe();
            Class<?> k = FutureTask.class;
            stateOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("state"));
            runnerOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("runner"));
            waitersOffset = UNSAFE.objectFieldOffset
                (k.getDeclaredField("waiters"));
        } catch (Exception e) {
            throw new Error(e);
        }
    }

}
