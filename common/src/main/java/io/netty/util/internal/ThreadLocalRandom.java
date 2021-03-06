/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

/*
 * Written by Doug Lea with assistance from members of JCP JSR-166
 * Expert Group and released to the public domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package io.netty.util.internal;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.lang.Thread.UncaughtExceptionHandler;
import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A random number generator isolated to the current thread.  Like the
 * global {@link java.util.Random} generator used by the {@link
 * java.lang.Math} class, a {@code ThreadLocalRandom} is initialized
 * with an internally generated seed that may not otherwise be
 * modified. When applicable, use of {@code ThreadLocalRandom} rather
 * than shared {@code Random} objects in concurrent programs will
 * typically encounter much less overhead and contention.  Use of
 * {@code ThreadLocalRandom} is particularly appropriate when multiple
 * tasks (for example, each a {@link io.netty.util.internal.chmv8.ForkJoinTask}) use random numbers
 * in parallel in thread pools.
 *
 * <p>Usages of this class should typically be of the form:
 * {@code ThreadLocalRandom.current().nextX(...)} (where
 * {@code X} is {@code Int}, {@code Long}, etc).
 * When all usages are of this form, it is never possible to
 * accidently share a {@code ThreadLocalRandom} across multiple threads.
 *
 * <p>This class also provides additional commonly used bounded random
 * generation methods.
 *
 * //since 1.7
 * //author Doug Lea
 * 与当前线程隔离的随机数生成器。与数学类使用的全局随机生成器类似，ThreadLocalRandom是用内部生成的种子初始化的，否则可能无法修改。在适用的情况下，在并发程序中使用ThreadLocalRandom而不是共享随机对象通常会遇到更少的开销和争用。当多个任务(例如，每个任务都是io.net .util. intern. chmv8. forkjointask)在线程池中并行使用随机数时，使用ThreadLocalRandom特别合适。
 该类的用法通常为:ThreadLocalRandom.current(). nextx(…)(其中X是Int, Long，等等)。当所有的用法都是这种形式时，不可能在多个线程之间意外地共享ThreadLocalRandom。
 该类还提供了其他常用的有界随机生成方法。//从1.7开始，作者Doug Lea
 */
//
@SuppressWarnings("all")
public final class ThreadLocalRandom extends Random {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ThreadLocalRandom.class);

    private static final AtomicLong seedUniquifier = new AtomicLong();

    private static volatile long initialSeedUniquifier;

    private static final Thread seedGeneratorThread;
    private static final BlockingQueue<Long> seedQueue;
    private static final long seedGeneratorStartTime;
    private static volatile long seedGeneratorEndTime;

//
    static {
        initialSeedUniquifier = SystemPropertyUtil.getLong("io.netty.initialSeedUniquifier", 0);
        if (initialSeedUniquifier == 0) {
            boolean secureRandom = SystemPropertyUtil.getBoolean("java.util.secureRandomSeed", false);
            if (secureRandom) {
                seedQueue = new LinkedBlockingQueue<Long>();
                seedGeneratorStartTime = System.nanoTime();

                // Try to generate a real random number from /dev/random.
                // Get from a different thread to avoid blocking indefinitely on a machine without much entropy.//尝试从/dev/ randomd生成一个真正的随机数。
//从另一个线程获取，以避免在没有太多熵的机器上无限期阻塞。
                seedGeneratorThread = new Thread("initialSeedUniquifierGenerator") {
                    @Override
                    public void run() {
                        final SecureRandom random = new SecureRandom(); // Get the real random seed from /dev/random从/dev/random中获得真正的随机种子
                        final byte[] seed = random.generateSeed(8);
                        seedGeneratorEndTime = System.nanoTime();
                        long s = ((long) seed[0] & 0xff) << 56 |
                                 ((long) seed[1] & 0xff) << 48 |
                                 ((long) seed[2] & 0xff) << 40 |
                                 ((long) seed[3] & 0xff) << 32 |
                                 ((long) seed[4] & 0xff) << 24 |
                                 ((long) seed[5] & 0xff) << 16 |
                                 ((long) seed[6] & 0xff) <<  8 |
                                 (long) seed[7] & 0xff;
                        seedQueue.add(s);
                    }
                };
                seedGeneratorThread.setDaemon(true);
                seedGeneratorThread.setUncaughtExceptionHandler(new UncaughtExceptionHandler() {
                    @Override
                    public void uncaughtException(Thread t, Throwable e) {
                        logger.debug("An exception has been raised by {}", t.getName(), e);
                    }
                });
                seedGeneratorThread.start();
            } else {
                initialSeedUniquifier = mix64(System.currentTimeMillis()) ^ mix64(System.nanoTime());
                seedGeneratorThread = null;
                seedQueue = null;
                seedGeneratorStartTime = 0L;
            }
        } else {
            seedGeneratorThread = null;
            seedQueue = null;
            seedGeneratorStartTime = 0L;
        }
    }

    public static void setInitialSeedUniquifier(long initialSeedUniquifier) {
        ThreadLocalRandom.initialSeedUniquifier = initialSeedUniquifier;
    }

//
    public static long getInitialSeedUniquifier() {
        // Use the value set via the setter.
        long initialSeedUniquifier = ThreadLocalRandom.initialSeedUniquifier;
        if (initialSeedUniquifier != 0) {
            return initialSeedUniquifier;
        }

        synchronized (ThreadLocalRandom.class) {
            initialSeedUniquifier = ThreadLocalRandom.initialSeedUniquifier;
            if (initialSeedUniquifier != 0) {
                return initialSeedUniquifier;
            }

            // Get the random seed from the generator thread with timeout.从带超时的生成器线程获取随机种子。
            final long timeoutSeconds = 3;
            final long deadLine = seedGeneratorStartTime + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            boolean interrupted = false;
            for (;;) {
                final long waitTime = deadLine - System.nanoTime();
                try {
                    final Long seed;
                    if (waitTime <= 0) {
                        seed = seedQueue.poll();
                    } else {
                        seed = seedQueue.poll(waitTime, TimeUnit.NANOSECONDS);
                    }

                    if (seed != null) {
                        initialSeedUniquifier = seed;
                        break;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                    logger.warn("Failed to generate a seed from SecureRandom due to an InterruptedException.");
                    break;
                }

                if (waitTime <= 0) {
                    seedGeneratorThread.interrupt();
                    logger.warn(
                            "Failed to generate a seed from SecureRandom within {} seconds. " +
                            "Not enough entropy?", timeoutSeconds
                    );
                    break;
                }
            }

            // Just in case the initialSeedUniquifier is zero or some other constant以防initialSeedUniquifier是0或者其他常数
            initialSeedUniquifier ^= 0x3255ecdc33bae119L; // just a meaningless random number
            initialSeedUniquifier ^= Long.reverse(System.nanoTime());

            ThreadLocalRandom.initialSeedUniquifier = initialSeedUniquifier;

            if (interrupted) {
                // Restore the interrupt status because we don't know how to/don't need to handle it here.恢复中断状态，因为我们不知道如何/不需要在这里处理它。
                Thread.currentThread().interrupt();

                // Interrupt the generator thread if it's still running,
                // in the hope that the SecureRandom provider raises an exception on interruption.如果生成器线程仍在运行，则中断它，
//希望SecureRandom提供程序在中断时抛出异常。
                seedGeneratorThread.interrupt();
            }

            if (seedGeneratorEndTime == 0) {
                seedGeneratorEndTime = System.nanoTime();
            }

            return initialSeedUniquifier;
        }
    }

//
    private static long newSeed() {
        for (;;) {
            final long current = seedUniquifier.get();
            final long actualCurrent = current != 0? current : getInitialSeedUniquifier();

            // L'Ecuyer, "Tables of Linear Congruential Generators of Different Sizes and Good Lattice Structure", 1999刘以中，“不同尺寸及良好晶格结构之线性同余生成元表”，民国九十三年
            final long next = actualCurrent * 181783497276652981L;

            if (seedUniquifier.compareAndSet(current, next)) {
                if (current == 0 && logger.isDebugEnabled()) {
                    if (seedGeneratorEndTime != 0) {
                        logger.debug(String.format(
                                "-Dio.netty.initialSeedUniquifier: 0x%016x (took %d ms)",
                                actualCurrent,
                                TimeUnit.NANOSECONDS.toMillis(seedGeneratorEndTime - seedGeneratorStartTime)));
                    } else {
                        logger.debug(String.format("-Dio.netty.initialSeedUniquifier: 0x%016x", actualCurrent));
                    }
                }
                return next ^ System.nanoTime();
            }
        }
    }

    // Borrowed from
    // http://gee.cs.oswego.edu/cgi-bin/viewcvs.cgi/jsr166/src/main/java/util/concurrent/ThreadLocalRandom.java
    private static long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    // same constants as Random, but must be redeclared because private
    private static final long multiplier = 0x5DEECE66DL;
    private static final long addend = 0xBL;
    private static final long mask = (1L << 48) - 1;

    /**
     * The random seed. We can't use super.seed.随机种子。我们不能用超级种子。
     */
    private long rnd;

    /**
     * Initialization flag to permit calls to setSeed to succeed only
     * while executing the Random constructor.  We can't allow others
     * since it would cause setting seed in one part of a program to
     * unintentionally impact other usages by the thread.初始化标志，允许对setSeed的调用只在执行随机构造函数时成功。我们不能允许其他的，因为它会导致在程序的一个部分设置种子，无意中影响线程的其他用法。
     */
    boolean initialized;

    // Padding to help avoid memory contention among seed updates in
    // different TLRs in the common case that they are located near
    // each other.//填充，以帮助避免种子更新之间的内存争用
//不同的tlr通常位于附近
//对方。
    private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    /**
     * Constructor called only by localRandom.initialValue.
     */
//
    ThreadLocalRandom() {
        super(newSeed());
        initialized = true;
    }

    /**
     * Returns the current thread's {@code ThreadLocalRandom}.
     *
     * @return the current thread's {@code ThreadLocalRandom}
     */
//
    public static ThreadLocalRandom current() {
        return InternalThreadLocalMap.get().random();
    }

    /**
     * Throws {@code UnsupportedOperationException}.  Setting seeds in
     * this generator is not supported.
     *
     * @throws UnsupportedOperationException always
     */
    public void setSeed(long seed) {
        if (initialized) {
            throw new UnsupportedOperationException();
        }
        rnd = (seed ^ multiplier) & mask;
    }

    protected int next(int bits) {
        rnd = (rnd * multiplier + addend) & mask;
        return (int) (rnd >>> (48 - bits));
    }

    /**
     * Returns a pseudorandom, uniformly distributed value between the
     * given least value (inclusive) and bound (exclusive).返回一个伪随机，在给定的最小值(包括)和边界(排除)之间均匀分布的值。
     *
     * @param least the least value returned
     * @param bound the upper bound (exclusive)
     * @throws IllegalArgumentException if least greater than or equal
     * to bound
     * @return the next value
     */
    public int nextInt(int least, int bound) {
        if (least >= bound) {
            throw new IllegalArgumentException();
        }
        return nextInt(bound - least) + least;
    }

    /**
     * Returns a pseudorandom, uniformly distributed value
     * between 0 (inclusive) and the specified value (exclusive).返回一个在0(包括)和指定值(不包括)之间均匀分布的伪随机值。
     *
     * @param n the bound on the random number to be returned.  Must be
     *        positive.
     * @return the next value
     * @throws IllegalArgumentException if n is not positive
     */
    public long nextLong(long n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive");
        }

        // Divide n by two until small enough for nextInt. On each
        // iteration (at most 31 of them but usually much less),
        // randomly choose both whether to include high bit in result
        // (offset) and whether to continue with the lower vs upper
        // half (which makes a difference only if odd).//将n除以2，直到足够小，得到nextInt。在每一个
//迭代(最多31次，但通常少很多)，
//在结果中随机选择是否包含高比特
//(偏移量)和是否继续与低vs高
//一半(只有奇数时才有区别)。
        long offset = 0;
        while (n >= Integer.MAX_VALUE) {
            int bits = next(2);
            long half = n >>> 1;
            long nextn = ((bits & 2) == 0) ? half : n - half;
            if ((bits & 1) == 0) {
                offset += n - nextn;
            }
            n = nextn;
        }
        return offset + nextInt((int) n);
    }

    /**
     * Returns a pseudorandom, uniformly distributed value between the
     * given least value (inclusive) and bound (exclusive).返回一个伪随机，在给定的最小值(包括)和边界(排除)之间均匀分布的值。
     *
     * @param least the least value returned
     * @param bound the upper bound (exclusive)
     * @return the next value
     * @throws IllegalArgumentException if least greater than or equal
     * to bound
     */
    public long nextLong(long least, long bound) {
        if (least >= bound) {
            throw new IllegalArgumentException();
        }
        return nextLong(bound - least) + least;
    }

    /**
     * Returns a pseudorandom, uniformly distributed {@code double} value
     * between 0 (inclusive) and the specified value (exclusive).
     *
     * @param n the bound on the random number to be returned.  Must be
     *        positive.
     * @return the next value
     * @throws IllegalArgumentException if n is not positive
     */
    public double nextDouble(double n) {
        if (n <= 0) {
            throw new IllegalArgumentException("n must be positive");
        }
        return nextDouble() * n;
    }

    /**
     * Returns a pseudorandom, uniformly distributed value between the
     * given least value (inclusive) and bound (exclusive).
     *
     * @param least the least value returned
     * @param bound the upper bound (exclusive)
     * @return the next value
     * @throws IllegalArgumentException if least greater than or equal
     * to bound
     */
    public double nextDouble(double least, double bound) {
        if (least >= bound) {
            throw new IllegalArgumentException();
        }
        return nextDouble() * (bound - least) + least;
    }

    private static final long serialVersionUID = -5851777807851030925L;
}
