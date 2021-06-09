/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.repo.common.tasks.handlers.composite;

import com.evolveum.midpoint.repo.common.activity.execution.ExecutionInstantiationContext;
import com.evolveum.midpoint.repo.common.activity.execution.ActivityExecutionResult;
import com.evolveum.midpoint.repo.common.activity.execution.LocalActivityExecution;
import com.evolveum.midpoint.repo.common.task.task.TaskExecution;
import com.evolveum.midpoint.repo.common.tasks.handlers.MockRecorder;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.RunningTask;

import com.evolveum.midpoint.util.DebugUtil;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;

import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractActivityWorkStateType;

import org.jetbrains.annotations.NotNull;

/**
 * Execution of mock opening or closing activity.
 */
public abstract class MockComponentActivityExecution
        extends LocalActivityExecution<CompositeMockWorkDefinition, CompositeMockActivityHandler, AbstractActivityWorkStateType> {

    public static final String NS_EXT = "http://midpoint.evolveum.com/xml/ns/repo-common-test/extension";

    private static final Trace LOGGER = TraceManager.getTrace(MockComponentActivityExecution.class);

    MockComponentActivityExecution(
            @NotNull ExecutionInstantiationContext<CompositeMockWorkDefinition, CompositeMockActivityHandler> context) {
        super(context);
    }

    @Override
    protected @NotNull ActivityExecutionResult executeLocal(OperationResult result) {

        CompositeMockWorkDefinition workDef = activity.getWorkDefinition();

        int steps = workDef.getSteps();
        long delay = workDef.getDelay();

        LOGGER.info("Mock activity starting: id={}, steps={}, delay={}, sub-activity={}:\n{}", workDef.getMessage(),
                steps, delay, getSubActivity(), debugDumpLazily());

        RunningTask task = taskExecution.getRunningTask();

        task.incrementProgressAndStoreStatisticsIfTimePassed(result);

        if (delay > 0) {
            sleep(task, delay);
        }

        result.recordSuccess();

        getRecorder().recordExecution(workDef.getMessage() + ":" + getSubActivity());

        LOGGER.info("Mock activity finished: id={}, sub-activity={}:\n{}", workDef.getMessage(), getSubActivity(),
                debugDumpLazily());

        return standardExitResult();
    }

    @NotNull
    private MockRecorder getRecorder() {
        return activity.getHandler().getRecorder();
    }

    private void sleep(RunningTask task, long delay) {
        LOGGER.trace("Sleeping for {} msecs", delay);
        long end = System.currentTimeMillis() + delay;
        while (task.canRun() && System.currentTimeMillis() < end) {
            try {
                //noinspection BusyWait
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
        }
    }

    @Override
    public @NotNull TaskExecution getTaskExecution() {
        return taskExecution;
    }

    abstract String getSubActivity();

    @Override
    public void debugDumpExtra(StringBuilder sb, int indent) {
        DebugUtil.debugDumpWithLabel(sb, "current recorder state", getRecorder(), indent+1);
    }
}
