package com.dameokja.backend.push.experiment;

import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class PushExperimentMeasurement implements AutoCloseable {
    static final int SAMPLE_INTERVAL_MS = 20;
    private final long beforeHeap = usedHeap();
    private final long beforeGcCount = gcCount();
    private final long beforeGcTime = gcTime();
    private final AtomicLong sampledPeak = new AtomicLong(beforeHeap);
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("experiment-heap-sampler").factory());

    private PushExperimentMeasurement() {
        sampler.scheduleAtFixedRate(this::sample, SAMPLE_INTERVAL_MS, SAMPLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    static <T> T measure(Map<String, Object> report, String stage, Operation<T> operation) throws Exception {
        report.put("activeStage", stage);
        System.out.println("실험 단계 시작: " + stage);
        try (PushExperimentMeasurement measurement = new PushExperimentMeasurement()) {
            long start = System.nanoTime();
            try {
                T result = operation.run();
                report.put("lastCompletedStage", stage);
                return result;
            } catch (Exception | Error failure) {
                report.put("failureStage", stage);
                throw failure;
            } finally {
                report.put(stage + "Ms", (System.nanoTime() - start) / 1_000_000.0);
                measurement.record(report, stage);
            }
        }
    }

    private void record(Map<String, Object> report, String stage) {
        long afterHeap = usedHeap();
        sampledPeak.accumulateAndGet(afterHeap, Math::max);
        report.put(stage + "HeapBeforeBytes", beforeHeap);
        report.put(stage + "HeapAfterBytes", afterHeap);
        report.put(stage + "SampledPeakHeapBytes", sampledPeak.get());
        report.put(stage + "HeapDeltaBytes", afterHeap - beforeHeap);
        report.put(stage + "GcCollections", gcCount() - beforeGcCount);
        report.put(stage + "GcTimeMs", gcTime() - beforeGcTime);
    }

    private void sample() { sampledPeak.accumulateAndGet(usedHeap(), Math::max); }
    private static long usedHeap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }

    private static long gcCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionCount())).sum();
    }

    private static long gcTime() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }

    @Override
    public void close() { sampler.shutdownNow(); }

    @FunctionalInterface
    interface Operation<T> {
        T run() throws Exception;
    }
}
