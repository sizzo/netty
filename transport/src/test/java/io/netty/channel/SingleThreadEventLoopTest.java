package io.netty.channel;

import static org.junit.Assert.*;

import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SingleThreadEventLoopTest {

    private SingleThreadEventLoopImpl loop;

    @Before
    public void newEventLoop() {
        loop = new SingleThreadEventLoopImpl();
    }

    @After
    public void stopEventLoop() {
        if (!loop.isShutdown()) {
            loop.shutdown();
        }
        while (!loop.isTerminated()) {
            try {
                loop.awaitTermination(1, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
        assertEquals(1, loop.cleanedUp.get());
    }

    @Test
    public void shutdownBeforeStart() throws Exception {
        loop.shutdown();
    }

    @Test
    public void shutdownAfterStart() throws Exception {
        final AtomicBoolean interrupted = new AtomicBoolean();
        final CountDownLatch latch = new CountDownLatch(2);
        loop.execute(new Runnable() {
            @Override
            public void run() {
                latch.countDown();
                while (latch.getCount() > 0) {
                    try {
                        latch.await();
                    } catch (InterruptedException ignored) {
                        interrupted.set(true);
                    }
                }
            }
        });

        // Wait for the event loop thread to start.
        while (latch.getCount() >= 2) {
            Thread.yield();
        }

        // Request the event loop thread to stop - it will call wakeup(false) to interrupt the thread.
        loop.shutdown();

        // Make the task terminate by itself.
        latch.countDown();

        // Wait until the event loop is terminated.
        while (!loop.isTerminated()) {
            loop.awaitTermination(1, TimeUnit.DAYS);
        }

        // Make sure loop.shutdown() above triggered wakeup().
        assertTrue(interrupted.get());
    }

    @Test
    public void scheduleTask() throws Exception {
        long startTime = System.nanoTime();
        final AtomicLong endTime = new AtomicLong();
        loop.schedule(new Runnable() {
            @Override
            public void run() {
                endTime.set(System.nanoTime());
            }
        }, 500, TimeUnit.MILLISECONDS).get();
        assertTrue(endTime.get() - startTime >= TimeUnit.MILLISECONDS.toNanos(500));
    }

    @Test
    public void scheduleTaskAtFixedRate() throws Exception {
        final Queue<Long> timestamps = new LinkedBlockingQueue<Long>();
        ScheduledFuture<?> f = loop.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                timestamps.add(System.nanoTime());
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        Thread.sleep(550);
        assertTrue(f.cancel(true));
        assertEquals(5, timestamps.size());

        // Check if the task was run without a lag.
        Long previousTimestamp = null;
        for (Long t: timestamps) {
            if (previousTimestamp == null) {
                previousTimestamp = t;
                continue;
            }

            assertTrue(t.longValue() - previousTimestamp.longValue() >= TimeUnit.MILLISECONDS.toNanos(90));
            previousTimestamp = t;
        }
    }

    @Test
    public void scheduleLaggyTaskAtFixedRate() throws Exception {
        final Queue<Long> timestamps = new LinkedBlockingQueue<Long>();
        ScheduledFuture<?> f = loop.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                boolean empty = timestamps.isEmpty();
                timestamps.add(System.nanoTime());
                if (empty) {
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        Thread.sleep(550);
        assertTrue(f.cancel(true));
        assertEquals(5, timestamps.size());

        // Check if the task was run with lag.
        int i = 0;
        Long previousTimestamp = null;
        for (Long t: timestamps) {
            if (previousTimestamp == null) {
                previousTimestamp = t;
                continue;
            }

            long diff = t.longValue() - previousTimestamp.longValue();
            if (i == 0) {
                assertTrue(diff >= TimeUnit.MILLISECONDS.toNanos(400));
            } else {
                assertTrue(diff <= TimeUnit.MILLISECONDS.toNanos(10));
            }
            previousTimestamp = t;
            i ++;
        }
    }

    @Test
    public void scheduleTaskWithFixedDelay() throws Exception {
        final Queue<Long> timestamps = new LinkedBlockingQueue<Long>();
        ScheduledFuture<?> f = loop.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                timestamps.add(System.nanoTime());
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    // Ignore
                }
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
        Thread.sleep(500);
        assertTrue(f.cancel(true));
        assertEquals(3, timestamps.size());

        // Check if the task was run without a lag.
        Long previousTimestamp = null;
        for (Long t: timestamps) {
            if (previousTimestamp == null) {
                previousTimestamp = t;
                continue;
            }

            assertTrue(t.longValue() - previousTimestamp.longValue() >= TimeUnit.MILLISECONDS.toNanos(150));
            previousTimestamp = t;
        }
    }

    @Test
    public void shutdownWithPendingTasks() throws Exception {
        final int NUM_TASKS = 3;
        final AtomicInteger ranTasks = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        final Runnable task = new Runnable() {
            @Override
            public void run() {
                ranTasks.incrementAndGet();
                while (latch.getCount() > 0) {
                    try {
                        latch.await();
                    } catch (InterruptedException e) {
                        // Ignored
                    }
                }
            }
        };

        for (int i = 0; i < NUM_TASKS; i ++) {
            loop.execute(task);
        }

        // At this point, the first task should be running and stuck at latch.await().
        while (ranTasks.get() == 0) {
            Thread.yield();
        }
        assertEquals(1, ranTasks.get());

        // Shut down the event loop to test if the other tasks are run before termination.
        loop.shutdown();

        // Let the other tasks run.
        latch.countDown();

        // Wait until the event loop is terminated.
        while (!loop.isTerminated()) {
            loop.awaitTermination(1, TimeUnit.DAYS);
        }

        // Make sure loop.shutdown() above triggered wakeup().
        assertEquals(NUM_TASKS, ranTasks.get());
    }

    private static class SingleThreadEventLoopImpl extends SingleThreadEventLoop {

        final AtomicInteger cleanedUp = new AtomicInteger();

        @Override
        protected void run() {
            for (;;) {
                Runnable task;
                try {
                    task = takeTask();
                    task.run();
                } catch (InterruptedException e) {
                    // Waken up by interruptThread()
                }

                if (isShutdown() && peekTask() == null) {
                    break;
                }
            }
        }

        @Override
        protected void cleanup() {
            cleanedUp.incrementAndGet();
        }

        @Override
        protected void wakeup(boolean inEventLoop) {
            if (!inEventLoop) {
                interruptThread();
            }
        }

        @Override
        public ChannelFuture register(Channel channel, ChannelFuture future) {
            // Untested
            return future;
        }
    }
}
