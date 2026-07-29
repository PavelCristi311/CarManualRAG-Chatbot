package com.atlas.manualassistant;

final class CpuBudget {
    static final double MAX_DEVICE_CPU_SHARE = 0.80;

    private CpuBudget() {}

    /** Derives the worker count from processors visible to the Android runtime. */
    static int workerThreads() {
        return workerThreads(Runtime.getRuntime().availableProcessors());
    }

    /** Caps model thread parallelism at 80 percent of available logical CPUs. */
    static int workerThreads(int availableProcessors) {
        return Math.max(
                1,
                (int) Math.floor(Math.max(1, availableProcessors)
                        * MAX_DEVICE_CPU_SHARE));
    }
}
