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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@ApiStatus.Internal
@NotNullByDefault
public final class BoundedEventIntake implements EventIntake, AutoCloseable {

    private RetentionPolicySet retentionPolicies;
    private final ArrayBlockingQueue<AcceptedEvent> queue;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private final Object availabilityMonitor = new Object();
    private volatile State state = State.RUNNING;

    public BoundedEventIntake(int capacity, RetentionPolicySet retentionPolicies) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive.");
        }
        this.retentionPolicies = Objects.requireNonNull(retentionPolicies, "retentionPolicies");
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public Admission accept(EventSubmission submission) {
        Objects.requireNonNull(submission, "submission");

        var lock = this.lifecycleLock.readLock();
        lock.lock();
        try {
            if (this.state == State.FAILED) {
                return Admission.UNAVAILABLE;
            }
            if (this.state != State.RUNNING) {
                return Admission.CLOSED;
            }

            final AcceptedEvent acceptedEvent;
            try {
                acceptedEvent = this.retentionPolicies.resolve(submission);
            } catch (RetentionResolutionException e) {
                return Admission.UNAVAILABLE;
            }

            if (!this.queue.offer(acceptedEvent)) {
                return Admission.UNAVAILABLE;
            }
            this.signalAvailabilityChange();
            return Admission.ACCEPTED;
        } finally {
            lock.unlock();
        }
    }

    @Nullable
    public AcceptedEvent poll() {
        return this.queue.poll();
    }

    @Nullable
    public AcceptedEvent awaitNext() throws InterruptedException {
        synchronized (this.availabilityMonitor) {
            while (true) {
                var event = this.queue.poll();
                if (event != null) {
                    return event;
                }
                if (this.state != State.RUNNING) {
                    return null;
                }
                this.availabilityMonitor.wait();
            }
        }
    }

    @Nullable
    public AcceptedEvent awaitNext(long timeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit, "unit");
        if (timeout <= 0) {
            return this.queue.poll();
        }

        var remainingNanos = unit.toNanos(timeout);
        var deadline = System.nanoTime() + remainingNanos;

        synchronized (this.availabilityMonitor) {
            while (true) {
                var event = this.queue.poll();
                if (event != null) {
                    return event;
                }
                if (this.state != State.RUNNING) {
                    return null;
                }
                if (remainingNanos <= 0) {
                    return null;
                }

                TimeUnit.NANOSECONDS.timedWait(this.availabilityMonitor, remainingNanos);
                remainingNanos = deadline - System.nanoTime();
            }
        }
    }

    public int size() {
        return this.queue.size();
    }

    public State state() {
        return this.state;
    }

    public void replaceRetentionPolicies(RetentionPolicySet retentionPolicies) {
        Objects.requireNonNull(retentionPolicies, "retentionPolicies");

        var lock = this.lifecycleLock.writeLock();
        lock.lock();
        try {
            if (this.state != State.RUNNING) {
                throw new IllegalStateException(
                    "Retention policies can only be replaced while intake is running."
                );
            }
            this.retentionPolicies = retentionPolicies;
        } finally {
            lock.unlock();
        }
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
        this.signalAvailabilityChange();
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
        this.signalAvailabilityChange();
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
        this.signalAvailabilityChange();
    }

    private void signalAvailabilityChange() {
        synchronized (this.availabilityMonitor) {
            this.availabilityMonitor.notifyAll();
        }
    }

    public enum State {
        RUNNING,
        DRAINING,
        FAILED,
        CLOSED
    }
}
