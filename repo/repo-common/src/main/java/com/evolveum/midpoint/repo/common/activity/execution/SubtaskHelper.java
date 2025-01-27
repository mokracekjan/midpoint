/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.repo.common.activity.execution;

import static com.evolveum.midpoint.schema.result.OperationResultStatus.FATAL_ERROR;
import static com.evolveum.midpoint.task.api.TaskRunResult.TaskRunResultStatus.PERMANENT_ERROR;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.jetbrains.annotations.NotNull;

import com.evolveum.midpoint.repo.common.activity.ActivityExecutionException;
import com.evolveum.midpoint.repo.common.task.CommonTaskBeans;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.task.ActivityPath;
import com.evolveum.midpoint.task.api.RunningTask;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.exception.ObjectAlreadyExistsException;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.TaskUnpauseActionType;

/**
 * Helps with the management of subtasks for {@link DelegatingActivityExecution} and {@link DistributingActivityExecution}.
 */
class SubtaskHelper {

    private static final Trace LOGGER = TraceManager.getTrace(SubtaskHelper.class);

    @NotNull private final AbstractActivityExecution<?, ?, ?> activityExecution;

    SubtaskHelper(@NotNull AbstractActivityExecution<?, ?, ?> activityExecution) {
        this.activityExecution = activityExecution;
    }

    void deleteRelevantSubtasksIfPossible(OperationResult result) throws ActivityExecutionException {
        LOGGER.debug("Going to delete subtasks, if there are any, and if they are closed");
        try {
            List<? extends Task> relevantChildren = getRelevantChildren(result);
            List<? extends Task> notClosed = relevantChildren.stream()
                    .filter(t -> !t.isClosed())
                    .collect(Collectors.toList());
            if (!notClosed.isEmpty()) {
                // The error may be permanent or transient. But reporting it as permanent is more safe, as it causes
                // the parent task to always suspend, catching the attention of the administrators.
                throw new ActivityExecutionException("Couldn't (re)create activity subtask(s) because there are existing one(s) "
                        + "that are not closed: " + notClosed, FATAL_ERROR, PERMANENT_ERROR);
            }
            for (Task worker : relevantChildren) {
                getBeans().taskManager.deleteTaskTree(worker.getOid(), result);
            }
        } catch (Exception e) {
            throw new ActivityExecutionException("Couldn't delete activity subtask(s)", FATAL_ERROR, PERMANENT_ERROR, e);
        }
    }

    // TODO deduplicate
    @NotNull List<? extends Task> getRelevantChildren(OperationResult result) throws SchemaException {
        List<? extends Task> allChildren = getRunningTask().listSubtasks(true, result);
        List<? extends Task> relevantChildren = allChildren.stream()
                .filter(this::isRelevantWorker)
                .collect(Collectors.toList());
        LOGGER.debug("Found {} relevant workers out of {} children: {}",
                relevantChildren.size(), allChildren.size(), relevantChildren);
        return relevantChildren;
    }

    void switchExecutionToChildren(Collection<Task> children, OperationResult result) throws ActivityExecutionException {
        try {
            RunningTask runningTask = getRunningTask();
            runningTask.makeWaitingForOtherTasks(TaskUnpauseActionType.EXECUTE_IMMEDIATELY);
            runningTask.flushPendingModifications(result);
            for (Task child : children) {
                if (child.isSuspended()) {
                    getBeans().taskManager.resumeTask(child.getOid(), result);
                    LOGGER.debug("Started prepared child {}", child);
                }
            }
        } catch (SchemaException | ObjectNotFoundException | ObjectAlreadyExistsException e) {
            throw new ActivityExecutionException("Couldn't switch execution to activity subtask",
                    FATAL_ERROR, PERMANENT_ERROR, e);
        }
    }

    private boolean isRelevantWorker(Task worker) {
        return getActivityPath().equalsBean(worker.getWorkState().getLocalRoot());
    }

    private @NotNull RunningTask getRunningTask() {
        return activityExecution.getRunningTask();
    }

    private @NotNull ActivityPath getActivityPath() {
        return activityExecution.getActivityPath();
    }

    private @NotNull CommonTaskBeans getBeans() {
        return activityExecution.getBeans();
    }
}
