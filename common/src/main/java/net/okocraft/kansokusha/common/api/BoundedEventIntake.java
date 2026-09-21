package net.okocraft.kansokusha.common.api;

import net.okocraft.kansokusha.api.event.EventSubmission;
import net.okocraft.kansokusha.common.event.AcceptedEvent;
import net.okocraft.kansokusha.common.event.RetentionPolicySet;
import net.okocraft.kansokusha.common.event.RetentionResolutionException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@ApiStatus.Internal
@NotNullByDefault
public final class BoundedEventIntake implements EventIntake, AutoCloseable {

    private final int capacity;
    private final RetentionPolicySet retentionPolicies;
    private final ArrayBlockingQueue<AcceptedEvent> queue;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private volatile State state = State.RUNNING;

    public BoundedEventIntake(int capacity, RetentionPolicySet retentionPolicies) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive.");
        }
        this.capacity = capacity;
        this.retentionPolicies = Objects.requireNonNull(retentionPolicies, "retentionPolicies");
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public Admission accept(EventSubmission submission) {
        Objects.requireNonNull(submission, "submission");

        var lock = this.lifecycleLock.readLock();
        lock.lock();
        try {
            if (this.state == State.DRAINING || this.state == State.CLOSED) {
                return Admission.CLOSED;
            }
            if (this.state == State.FAILED) {
                return Admission.UNAVAILABLE;
            }

            final AcceptedEvent acceptedEvent;
            try {
                acceptedEvent = this.retentionPolicies.resolve(submission);
            } catch (RetentionResolutionException e) {
                return Admission.UNAVAILABLE;
            }

            return this.queue.offer(acceptedEvent)
                ? Admission.ACCEPTED
                : Admission.UNAVAILABLE;
        } finally {
            lock.unlock();
        }
    }

    @Nullable
    public AcceptedEvent poll() {
        return this.queue.poll();
    }

    public int size() {
        return this.queue.size();
    }

    public int capacity() {
        return this.capacity;
    }

    public State state() {
        return this.state;
    }

    public void beginDraining() {
        var lock = this.lifecycleLock.writeLock();
        lock.lock();
        try {
            if (this.state == State.RUNNING) {
                this.state = State.DRAINING;
            }
        } finally {
            lock.unlock();
        }
    }

    public void fail() {
        var lock = this.lifecycleLock.writeLock();
        lock.lock();
        try {
            if (this.state == State.RUNNING || this.state == State.DRAINING) {
                this.state = State.FAILED;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        var lock = this.lifecycleLock.writeLock();
        lock.lock();
        try {
            this.state = State.CLOSED;
        } finally {
            lock.unlock();
        }
    }

    public enum State {
        RUNNING,
        DRAINING,
        FAILED,
        CLOSED
    }
}
