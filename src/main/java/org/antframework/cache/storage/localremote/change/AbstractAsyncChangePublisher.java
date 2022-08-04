/*
 * 作者：钟勋 (email:zhongxunking@163.com)
 */

/*
 * 修订记录:
 * @author 钟勋 2022-08-04 21:55 创建
 */
package org.antframework.cache.storage.localremote.change;

import org.antframework.cache.storage.localremote.ChangePublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 抽象异步修改发布器
 */
public abstract class AbstractAsyncChangePublisher implements ChangePublisher {
    // 队列
    private final BlockingQueue<Change> queue;
    // 异步任务
    private final AsyncTask asyncTask;

    public AbstractAsyncChangePublisher(int queueSize, int maxBatchSize, int publishThreads) {
        queue = new ArrayBlockingQueue<>(queueSize);
        asyncTask = new AsyncTask(maxBatchSize, publishThreads);
        asyncTask.start();
    }

    @Override
    public void publish(String name, String key) {
        Change change = new Change();
        change.setName(name);
        change.setKey(key);
        try {
            queue.put(change);
        } catch (InterruptedException e) {
            // 忽略
        }
    }

    /**
     * 执行发布
     *
     * @param batch 一批修改
     */
    protected abstract void doPublish(ChangeBatch batch);

    // 异步任务
    private class AsyncTask extends Thread {
        // 一批修改的最大容量
        private final int maxBatchSize;
        // 线程池
        private final Executor executor;

        AsyncTask(int maxBatchSize, int publishThreads) {
            setDaemon(true);
            this.maxBatchSize = maxBatchSize;
            this.executor = new ThreadPoolExecutor(
                    publishThreads,
                    publishThreads,
                    5,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(publishThreads * 10),
                    new ThreadPoolExecutor.CallerRunsPolicy());
        }

        @Override
        public void run() {
            while (true) {  // daemon线程，程序关闭时自动结束
                try {
                    List<Change> changes = new ArrayList<>();
                    changes.add(queue.take());
                    for (int i = 0; i < maxBatchSize - 1; i++) {
                        Change change = queue.poll();
                        if (change == null) {
                            break;
                        }
                        changes.add(change);
                    }
                    ChangeBatch batch = new ChangeBatch();
                    batch.setChanges(changes);
                    executor.execute(() -> {
                        try {
                            doPublish(batch);
                        } catch (Throwable e) {
                            // 忽略
                        }
                    });
                } catch (Throwable e) {
                    // 忽略
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException ex) {
                        // 忽略
                    }
                }
            }
        }
    }
}
