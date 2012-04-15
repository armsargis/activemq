/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.thread;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the thread pool for long running tasks. Long running tasks are not
 * always active but when they are active, they may need a few iterations of
 * processing for them to become idle. The manager ensures that each task is
 * processes but that no one task overtakes the system. This is kinda like
 * cooperative multitasking.
 *
 * @org.apache.xbean.XBean
 */
public class TaskRunnerFactory implements Executor {

    private ExecutorService executor;
    private int maxIterationsPerRun;
    private String name;
    private int priority;
    private boolean daemon;
    private AtomicLong id = new AtomicLong(0);
    private boolean dedicatedTaskRunner;
    private AtomicBoolean initDone = new AtomicBoolean(false);

    public TaskRunnerFactory() {
        this("ActiveMQ Task", Thread.NORM_PRIORITY, true, 1000);
    }

    private TaskRunnerFactory(String name, int priority, boolean daemon, int maxIterationsPerRun) {
        this(name,priority,daemon,maxIterationsPerRun,false);
    }

    public TaskRunnerFactory(String name, int priority, boolean daemon, int maxIterationsPerRun, boolean dedicatedTaskRunner) {
        this.name = name;
        this.priority = priority;
        this.daemon = daemon;
        this.maxIterationsPerRun = maxIterationsPerRun;
        this.dedicatedTaskRunner = dedicatedTaskRunner;
    }

    public void init() {
        if (initDone.compareAndSet(false, true)) {
            // If your OS/JVM combination has a good thread model, you may want to
            // avoid using a thread pool to run tasks and use a DedicatedTaskRunner instead.
            if (dedicatedTaskRunner || "true".equalsIgnoreCase(System.getProperty("org.apache.activemq.UseDedicatedTaskRunner"))) {
                executor = null;
            } else if (executor == null) {
                executor = createDefaultExecutor();
            }
        }
    }

    public void shutdown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        initDone.set(false);
    }

    public TaskRunner createTaskRunner(Task task, String name) {
        init();
        if (executor != null) {
            return new PooledTaskRunner(executor, task, maxIterationsPerRun);
        } else {
            return new DedicatedTaskRunner(task, name, priority, daemon);
        }
    }

    public void execute(Runnable runnable) {
        execute(runnable, "ActiveMQ Task");
    }

    public void execute(Runnable runnable, String name) {
        init();
        if (executor != null) {
            executor.execute(runnable);
        } else {
            new Thread(runnable, name + "-" + id.incrementAndGet()).start();
        }
    }

    protected ExecutorService createDefaultExecutor() {
        ThreadPoolExecutor rc = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 30, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name + "-" + id.incrementAndGet());
                thread.setDaemon(daemon);
                thread.setPriority(priority);
                return thread;
            }
        });
        return rc;
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public void setExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    public int getMaxIterationsPerRun() {
        return maxIterationsPerRun;
    }

    public void setMaxIterationsPerRun(int maxIterationsPerRun) {
        this.maxIterationsPerRun = maxIterationsPerRun;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public boolean isDaemon() {
        return daemon;
    }

    public void setDaemon(boolean daemon) {
        this.daemon = daemon;
    }

    public boolean isDedicatedTaskRunner() {
        return dedicatedTaskRunner;
    }

    public void setDedicatedTaskRunner(boolean dedicatedTaskRunner) {
        this.dedicatedTaskRunner = dedicatedTaskRunner;
    }
}
