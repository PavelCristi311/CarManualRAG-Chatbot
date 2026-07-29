package com.atlas.manualassistant;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class CpuBudgetTest {
    @Test
    public void usesAtMostEightyPercentOfLogicalProcessors() {
        assertEquals(1, CpuBudget.workerThreads(1));
        assertEquals(1, CpuBudget.workerThreads(2));
        assertEquals(3, CpuBudget.workerThreads(4));
        assertEquals(6, CpuBudget.workerThreads(8));
    }
}
