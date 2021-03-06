/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.bookkeeper.bookie;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.bookkeeper.bookie.CheckpointSource.Checkpoint;
import org.apache.bookkeeper.bookie.LedgerDirsManager.LedgerDirsListener;
import org.apache.bookkeeper.bookie.LedgerDirsManager.NoWritableLedgerDirException;
import org.apache.bookkeeper.conf.ServerConfiguration;
import org.apache.bookkeeper.util.MathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SyncThread is a background thread which help checkpointing ledger storage
 * when a checkpoint is requested. After a ledger storage is checkpointed,
 * the journal files added before checkpoint will be garbage collected.
 * <p>
 * After all data has been persisted to ledger index files and entry
 * loggers, it is safe to complete a checkpoint by persisting the log marker
 * to disk. If bookie failed after persist log mark, bookie is able to relay
 * journal entries started from last log mark without losing any entries.
 * </p>
 * <p>
 * Those journal files whose id are less than the log id in last log mark,
 * could be removed safely after persisting last log mark. We provide a
 * setting to let user keeping number of old journal files which may be used
 * for manual recovery in critical disaster.
 * </p>
 */
class SyncThread {
    private static final Logger LOG = LoggerFactory.getLogger(SyncThread.class);

    final ScheduledExecutorService executor;
    final int flushInterval;
    final LedgerStorage ledgerStorage;
    final LedgerDirsListener dirsListener;
    final CheckpointSource checkpointSource;

    private final Object suspensionLock = new Object();
    private boolean suspended = false;
    private boolean disableCheckpoint = false;

    public SyncThread(ServerConfiguration conf,
                      LedgerDirsListener dirsListener,
                      LedgerStorage ledgerStorage,
                      CheckpointSource checkpointSource) {
        this.dirsListener = dirsListener;
        this.ledgerStorage = ledgerStorage;
        this.checkpointSource = checkpointSource;
        ThreadFactoryBuilder tfb = new ThreadFactoryBuilder()
            .setNameFormat("SyncThread-" + conf.getBookiePort() + "-%d");
        this.executor = Executors.newSingleThreadScheduledExecutor(tfb.build());
        flushInterval = conf.getFlushInterval();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Flush Interval : {}", flushInterval);
        }
    }

    void start() {
        executor.scheduleAtFixedRate(new Runnable() {
            public void run() {
                try {
                    synchronized (suspensionLock) {
                        while (suspended) {
                            try {
                                suspensionLock.wait();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                continue;
                            }
                        }
                    }
                    if (!disableCheckpoint) {
                        checkpoint(checkpointSource.newCheckpoint());
                    }
                } catch (Throwable t) {
                    LOG.error("Exception in SyncThread", t);
                    dirsListener.fatalError();
                }
            }
        }, flushInterval, flushInterval, TimeUnit.MILLISECONDS);
    }

    public Future<Void> requestFlush() {
        return executor.submit(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                try {
                    flush();
                } catch (Throwable t) {
                    LOG.error("Exception flushing ledgers ", t);
                }
                return null;
            }
        });
    }

    private void flush() {
        Checkpoint checkpoint = checkpointSource.newCheckpoint();
        try {
            ledgerStorage.flush();
        } catch (NoWritableLedgerDirException e) {
            LOG.error("No writeable ledger directories", e);
            dirsListener.allDisksFull();
            return;
        } catch (IOException e) {
            LOG.error("Exception flushing ledgers", e);
            return;
        }

        if (disableCheckpoint) {
            return;
        }

        LOG.info("Flush ledger storage at checkpoint {}.", checkpoint);
        try {
            checkpointSource.checkpointComplete(checkpoint, false);
        } catch (IOException e) {
            LOG.error("Exception marking checkpoint as complete", e);
            dirsListener.allDisksFull();
        }
    }

    @VisibleForTesting
    public void checkpoint(Checkpoint checkpoint) {
        try {
            checkpoint = ledgerStorage.checkpoint(checkpoint);
        } catch (NoWritableLedgerDirException e) {
            LOG.error("No writeable ledger directories", e);
            dirsListener.allDisksFull();
            return;
        } catch (IOException e) {
            LOG.error("Exception flushing ledgers", e);
            return;
        }

        try {
            checkpointSource.checkpointComplete(checkpoint, true);
        } catch (IOException e) {
            LOG.error("Exception marking checkpoint as complete", e);
            dirsListener.allDisksFull();
        }
    }

    /**
     * Suspend sync thread. (for testing)
     */
    @VisibleForTesting
    public void suspendSync() {
        synchronized (suspensionLock) {
            suspended = true;
        }
    }

    /**
     * Resume sync thread. (for testing)
     */
    @VisibleForTesting
    public void resumeSync() {
        synchronized (suspensionLock) {
            suspended = false;
            suspensionLock.notify();
        }
    }

    @VisibleForTesting
    public void disableCheckpoint() {
        disableCheckpoint = true;
    }

    // shutdown sync thread
    void shutdown() throws InterruptedException {
        LOG.info("Shutting down SyncThread");
        requestFlush();
        executor.shutdown();
        long start = MathUtils.now();
        while (!executor.awaitTermination(5, TimeUnit.MINUTES)) {
            long now = MathUtils.now();
            LOG.info("SyncThread taking a long time to shutdown. Has taken {}"
                    + " seconds so far", now - start);
        }
    }
}
