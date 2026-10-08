package dev.durablejobs.runtime;

import dev.durablejobs.api.JobException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class WorkerLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private WorkerLock(FileChannel channel, FileLock lock) { this.channel = channel; this.lock = lock; }

    public static WorkerLock acquire(Path canonicalDatabase) {
        Path lockPath = canonicalDatabase.resolveSibling(canonicalDatabase.getFileName() + ".worker.lock");
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) throw new JobException("WORKER_ALREADY_RUNNING", "Only one worker may execute this database.");
            return new WorkerLock(channel, lock);
        } catch (IOException | OverlappingFileLockException | JobException e) {
            if (channel != null) {
                try { channel.close(); } catch (IOException close) { e.addSuppressed(close); }
            }
            if (e instanceof JobException job) throw job;
            if (e instanceof OverlappingFileLockException) {
                throw new JobException("WORKER_ALREADY_RUNNING", "Only one worker may execute this database.", e);
            }
            throw new JobException("WORKER_LOCK_ERROR", "Cannot acquire local worker lock.", e);
        }
    }

    @Override public void close() {
        try {
            try { lock.release(); } finally { channel.close(); }
        } catch (IOException e) { throw new JobException("WORKER_LOCK_ERROR", "Cannot release worker lock.", e); }
    }
}
