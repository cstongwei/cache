/*
 * 作者：钟勋 (email:zhongxunking@163.com)
 */

/*
 * 修订记录:
 * @author 钟勋 2022-07-22 14:45 创建
 */
package org.antframework.cache.common;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * 懒汉
 */
public class Idler {
    // 目标标识与等待点的映射
    private final Map<Object, WaitPoint> idWaitPoints = new ConcurrentHashMap<>();

    /**
     * 获取目标
     *
     * @param id            目标标识
     * @param targetLoader  需获取的目标的加载器
     * @param idleConverter 为懒汉准备的目标转换器
     * @param <T>           目标类型
     * @return 目标
     */
    public <T> T acquire(Object id, Callable<T> targetLoader, Function<T, T> idleConverter) {
        WaitPoint waitPoint = idWaitPoints.compute(id, (k, v) -> {
            if (v == null) {
                v = new WaitPoint();
            }
            v.ready();
            return v;
        });
        AtomicReference<T> target = new AtomicReference<>(null);
        if (waitPoint.amIRunner()) {
            AtomicReference<Throwable> ex = new AtomicReference<>(null);
            try {
                target.set(targetLoader.call());
            } catch (Throwable e) {
                ex.set(e);
            } finally {
                idWaitPoints.computeIfPresent(id, (k, v) -> {
                    if (v.amIRunner()) {
                        if (ex.get() == null) {
                            v.awakeWaiters(target.get());
                        } else {
                            v.awakeWaitersExceptionally(ex.get());
                        }
                        v = null;
                    }
                    return v;
                });
            }
            if (ex.get() != null) {
                throw ex.get();
            }
        } else {
            T value = (T) waitPoint.waitTarget();
            value = idleConverter.apply(value);
            target.set(value);
        }
        return target.get();
    }

    // 等待点
    private static class WaitPoint {
        // 运行者（运行目标加载器的线程）
        private final long runner = Thread.currentThread().getId();
        // 运行者给等待者交付目标的场所
        private CompletableFuture<Object> completableFuture = null;

        // 是否是运行者
        boolean amIRunner() {
            return Thread.currentThread().getId() == runner;
        }

        // 准备好交付目标的场所
        void ready() {
            if (!amIRunner() && completableFuture == null) {
                completableFuture = new CompletableFuture<>();
            }
        }

        // 等待目标
        Object waitTarget() {
            try {
                return completableFuture.get();
            } catch (InterruptedException e) {
                throw e;
            } catch (ExecutionException e) {
                throw e.getCause();
            }
        }

        // 用目标唤醒所有等待者
        void awakeWaiters(Object target) {
            if (completableFuture != null) {
                completableFuture.complete(target);
            }
        }

        // 用异常唤醒所有等待者
        void awakeWaitersExceptionally(Throwable e) {
            if (completableFuture != null) {
                completableFuture.completeExceptionally(e);
            }
        }
    }
}
