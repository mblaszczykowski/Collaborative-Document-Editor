package dev.collabedit.app.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

final class DeterministicSimulation {
    private record ScheduledEvent(long at, long sequence, String label, Runnable action)
        implements Comparable<ScheduledEvent> {
        @Override
        public int compareTo(ScheduledEvent other) {
            int byTime = Long.compare(at, other.at);
            return byTime != 0 ? byTime : Long.compare(sequence, other.sequence);
        }
    }

    private final PriorityQueue<ScheduledEvent> queue = new PriorityQueue<>();
    private final Random random;
    private final long seed;

    private final List<String> history = new ArrayList<>();

    private long now;
    private long sequence;
    private long stepsExecuted;

    DeterministicSimulation(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    long seed() {
        return seed;
    }

    long now() {
        return now;
    }

    Random random() {
        return random;
    }

    List<String> history() {
        return List.copyOf(history);
    }

    long stepsExecuted() {
        return stepsExecuted;
    }

    void record(String event) {
        history.add(now + " " + event);
    }

    void schedule(long delayMillis, String label, Runnable action) {
        if (delayMillis < 0) {
            throw new IllegalArgumentException("cannot schedule into the past: " + delayMillis);
        }
        queue.add(new ScheduledEvent(now + delayMillis, sequence++, label, action));
    }

    long delayBetween(long minMillis, long maxMillis) {
        return minMillis + random.nextInt((int) (maxMillis - minMillis + 1));
    }

    private java.util.function.Supplier<String> invariant = () -> null;

    void checkAfterEachStep(java.util.function.Supplier<String> invariantCheck) {
        this.invariant = invariantCheck;
    }

    void runUntil(long deadlineMillis, long stepLimit) {
        while (!queue.isEmpty() && queue.peek().at() <= deadlineMillis) {
            if (stepsExecuted >= stepLimit) {
                throw new IllegalStateException(
                    "simulation exceeded " + stepLimit + " steps (seed " + seed + "); "
                        + "something is scheduling without end");
            }
            ScheduledEvent event = queue.poll();
            now = event.at();
            stepsExecuted++;
            event.action().run();

            String violation = invariant.get();
            if (violation != null) {
                throw new AssertionError("invariant broken after step " + stepsExecuted
                    + " (" + event.label() + ") at t=" + now + ", seed " + seed + ": " + violation
                    + System.lineSeparator() + "history:" + System.lineSeparator()
                    + String.join(System.lineSeparator(), history));
            }
        }
    }
}
