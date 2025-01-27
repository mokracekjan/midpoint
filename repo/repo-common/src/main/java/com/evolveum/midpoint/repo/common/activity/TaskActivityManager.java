/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.repo.common.activity;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.repo.common.task.CommonTaskBeans;
import com.evolveum.midpoint.repo.common.task.work.workers.WorkersReconciliation;
import com.evolveum.midpoint.schema.GetOperationOptions;
import com.evolveum.midpoint.schema.SchemaService;
import com.evolveum.midpoint.schema.SelectorOptions;
import com.evolveum.midpoint.schema.util.task.*;
import com.evolveum.midpoint.schema.util.task.ActivityTreeUtil.ActivityStateInContext;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.task.api.TaskManager;
import com.evolveum.midpoint.util.TreeNode;
import com.evolveum.midpoint.util.exception.CommonException;
import com.evolveum.midpoint.util.exception.ObjectAlreadyExistsException;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;

import com.evolveum.midpoint.util.logging.LoggingUtils;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_3.*;

import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.util.annotation.Experimental;

import static com.evolveum.midpoint.xml.ns._public.common.common_3.TaskActivityStateType.F_TREE;

@Experimental
@Component
public class TaskActivityManager {

    private static final String OP_CLEAR_FAILED_ACTIVITY_STATE = TaskActivityManager.class.getName() + ".clearFailedActivityState";
    private static final String OP_RECONCILE_WORKERS = TaskActivityManager.class.getName() + ".reconcileWorkers";
    private static final String OP_RECONCILE_WORKERS_FOR_ACTIVITY = TaskActivityManager.class.getName() + ".reconcileWorkersForActivity";
    private static final String OP_DELETE_ACTIVITY_STATE_AND_WORKERS = TaskActivityManager.class.getName() + ".deleteActivityStateAndWorkers";

    private static final Trace LOGGER = TraceManager.getTrace(TaskActivityManager.class);

    @Autowired private PrismContext prismContext;
    @Autowired private SchemaService schemaService;
    @Autowired @Qualifier("repositoryService") private RepositoryService plainRepositoryService;
    @Autowired private TaskManager taskManager;
    @Autowired private CommonTaskBeans beans;

    // TODO reconsider this
    //  How should we clear the "not executed" flag in the tree overview when using e.g. the tests?
    //  In production the flag is updated automatically when the task/activities start.
    public void clearFailedActivityState(String taskOid, OperationResult parentResult)
            throws SchemaException, ObjectNotFoundException, ObjectAlreadyExistsException {
        OperationResult result = parentResult.subresult(OP_CLEAR_FAILED_ACTIVITY_STATE)
                .addParam("taskOid", taskOid)
                .build();
        try {
            plainRepositoryService.modifyObjectDynamically(TaskType.class, taskOid, null,
                    taskBean -> {
                        ActivityStateOverviewType stateOverview = ActivityStateOverviewUtil.getStateOverview(taskBean);
                        if (stateOverview != null) {
                            ActivityStateOverviewType updatedStateOverview = stateOverview.clone();
                            ActivityStateOverviewUtil.clearFailedState(updatedStateOverview);
                            return prismContext.deltaFor(TaskType.class)
                                    .item(TaskType.F_ACTIVITY_STATE, F_TREE, ActivityTreeStateType.F_ACTIVITY)
                                    .replace(updatedStateOverview)
                                    .asItemDeltas();
                        } else {
                            return List.of();
                        }
                    }, null, result);
        } catch (Throwable t) {
            result.recordFatalError(t);
            throw t;
        } finally {
            result.computeStatusIfUnknown();
        }
    }

    // TODO reconsider the concept of resolver (as it is useless now - we have to fetch the subtasks manually!)
    public ActivityProgressInformation getProgressInformation(String rootTaskOid, OperationResult result)
            throws SchemaException, ObjectNotFoundException {
        return ActivityProgressInformation.fromRootTask(
                getTaskWithSubtasks(rootTaskOid, result),
                createTaskResolver(result));
    }

    public TreeNode<ActivityPerformanceInformation> getPerformanceInformation(String rootTaskOid, OperationResult result)
            throws SchemaException, ObjectNotFoundException {
        return ActivityTreeUtil.transformStates(
                getTaskWithSubtasks(rootTaskOid, result),
                createTaskResolver(result),
                (path, state, workerStates, task) -> {
                    if (workerStates != null) {
                        return ActivityPerformanceInformation.forCoordinator(path, workerStates);
                    } else {
                        ActivityItemProcessingStatisticsType itemStats = getItemStats(state);
                        if (itemStats != null) {
                            return ActivityPerformanceInformation.forRegularActivity(path, itemStats, state.getProgress());
                        } else {
                            return ActivityPerformanceInformation.notApplicable(path);
                        }
                    }
                });
    }

    private ActivityItemProcessingStatisticsType getItemStats(ActivityStateType state) {
        return state != null && state.getStatistics() != null ?
                state.getStatistics().getItemProcessing() : null;
    }

    private TaskResolver createTaskResolver(OperationResult result) {
        return oid -> getTaskWithSubtasks(oid, result);
    }

    @NotNull
    private TaskType getTaskWithSubtasks(String oid, OperationResult result) throws ObjectNotFoundException, SchemaException {
        Collection<SelectorOptions<GetOperationOptions>> withChildren = schemaService.getOperationOptionsBuilder()
                .item(TaskType.F_SUBTASK_REF).retrieve()
                .build();

        return taskManager.getTask(oid, withChildren, result)
                .getUpdatedTaskObject()
                .asObjectable();
    }

    public @NotNull Activity<?, ?> getActivity(Task rootTask, ActivityPath activityPath)
            throws SchemaException {
        return ActivityTree.create(rootTask, beans)
                .getActivity(activityPath);
    }

    /**
     * Note that we reconcile only workers for distributed activities that already have their state.
     */
    public void reconcileWorkers(@NotNull String rootTaskOid, @NotNull OperationResult parentResult)
            throws SchemaException, ObjectNotFoundException {
        OperationResult result = parentResult.subresult(OP_RECONCILE_WORKERS)
                .addParam("rootTaskOid", rootTaskOid)
                .build();
        try {
            Task rootTask = taskManager.getTaskTree(rootTaskOid, result);
            TaskType rootTaskBean = rootTask.getRawTaskObjectClonedIfNecessary().asObjectable();
            ActivityTreeUtil.processStates(rootTaskBean, TaskResolver.empty(), (path, state, workerStates, task) -> {
                if (workerStates != null) {
                    reconcileWorkersForActivity(rootTask, task, path, result);
                }
            });
        } catch (Throwable t) {
            result.recordFatalError(t);
            throw t;
        } finally {
            result.computeStatusIfUnknown();
        }
    }

    /**
     * Note: common exceptions are not propagated - these are reflected only in the operation result
     */
    private void reconcileWorkersForActivity(@NotNull Task rootTask, @NotNull TaskType coordinatorTaskBean,
            @NotNull ActivityPath path, OperationResult parentResult) {
        OperationResult result = parentResult.subresult(OP_RECONCILE_WORKERS_FOR_ACTIVITY)
                .addArbitraryObjectAsParam("rootTask", rootTask)
                .addParam("coordinatorTask", coordinatorTaskBean)
                .addArbitraryObjectAsParam("path", path)
                .build();
        try {
            Task coordinatorTask = taskManager.createTaskInstance(coordinatorTaskBean.asPrismObject(), result);
            new WorkersReconciliation(rootTask, coordinatorTask, path, null, beans)
                    .execute(result);
        } catch (CommonException e) {
            LoggingUtils.logUnexpectedException(LOGGER, "Couldn't reconcile workers for activity path '{}' in {}/{}", e, path,
                    coordinatorTaskBean, rootTask);
            result.recordFatalError(e);
        } catch (Throwable t) {
            result.recordFatalError(t);
            throw t;
        } finally {
            result.computeStatusIfUnknown();
        }
    }

    /**
     * TODO somehow unify with TaskStatePurger
     */
    public void deleteActivityStateAndWorkers(@NotNull String rootTaskOid, boolean deleteWorkers, long subtasksWaitTime,
            OperationResult parentResult)
            throws SchemaException, ObjectNotFoundException {
        OperationResult result = parentResult.subresult(OP_DELETE_ACTIVITY_STATE_AND_WORKERS)
                .addArbitraryObjectAsParam("rootTaskOid", rootTaskOid)
                .addParam("deleteWorkers", deleteWorkers)
                .addParam("subtasksWaitTime", subtasksWaitTime)
                .build();
        try {
            boolean suspended = taskManager.suspendTaskTree(rootTaskOid, subtasksWaitTime, result);
            if (!suspended) {
                // TODO less harsh handling
                throw new IllegalStateException("Not all tasks could be suspended. Please retry to operation.");
            }
            Task rootTask = taskManager.getTaskTree(rootTaskOid, result);
            TaskType rootTaskBean = rootTask.getRawTaskObjectClonedIfNecessary().asObjectable();
            TreeNode<ActivityStateInContext> stateTree = ActivityTreeUtil.toStateTree(rootTaskBean, TaskResolver.empty());

            deleteWorkersOrTheirState(stateTree, deleteWorkers, result);
            purgeOrDeleteActivityState(stateTree, result);

        } catch (Throwable t) {
            result.recordFatalError(t);
            throw t;
        } finally {
            result.computeStatusIfUnknown();
        }
    }

    private void deleteWorkersOrTheirState(TreeNode<ActivityStateInContext> root, boolean deleteWorkers, OperationResult result) {
        root.acceptDepthFirst(node -> {
            if (node.getUserObject().isCoordinator()) {
                deleteActivityWorkersOrTheirState(node.getUserObject(), deleteWorkers, result);
            }
        });
    }

    private void deleteActivityWorkersOrTheirState(@NotNull ActivityStateInContext activityStateInContext,
            boolean deleteWorkers, OperationResult result) {
        TaskType coordinatorTaskBean = activityStateInContext.getTask();
        ActivityPath activityPath = activityStateInContext.getActivityPath();
        Set<String> workerOids =
                ActivityTreeUtil.getSubtasksForPath(coordinatorTaskBean, activityPath, TaskResolver.empty()).stream()
                        .map(ObjectType::getOid)
                        .collect(Collectors.toSet());
        if (deleteWorkers) {
            taskManager.suspendAndDeleteTasks(workerOids, TaskManager.DO_NOT_WAIT, true, result);
            LOGGER.info("Deleted workers: {}", workerOids);
        } else {
            for (String workerOid : workerOids) {
                deleteCompleteState(workerOid, result);
            }
        }
    }

    private void deleteCompleteState(String taskOid, OperationResult result) {
        try {
            List<ItemDelta<?, ?>> modifications = prismContext.deltaFor(TaskType.class)
                    .item(TaskType.F_ACTIVITY_STATE).replace()
                    .asItemDeltas();
            plainRepositoryService.modifyObject(TaskType.class, taskOid, modifications, result);
            LOGGER.info("Deleted complete task activity state in {}", taskOid);
        } catch (CommonException e) {
            LoggingUtils.logUnexpectedException(LOGGER, "Couldn't delete activity state in {}", e, taskOid);
        }
    }

    private void purgeOrDeleteActivityState(TreeNode<ActivityStateInContext> node, OperationResult result) {
        ActivityStateInContext stateInContext = node.getUserObject();
        if (stateInContext.getActivityState() == null) {
            return;
        }

        TaskType taskBean = stateInContext.getTask();
        if (ActivityTreeUtil.hasDelegatedActivity(node)) {
            purgeState(taskBean, stateInContext.getActivityPath(), stateInContext.getActivityState(), result);
            for (TreeNode<ActivityStateInContext> child : node.getChildren()) {
                purgeOrDeleteActivityState(child, result);
            }
        } else {
            deleteState(taskBean, stateInContext.getActivityPath(), result);
        }
    }

    private void deleteState(TaskType task, ActivityPath activityPath, OperationResult result) {
        try {
            TaskActivityStateType taskActivityState = task.getActivityState();
            ItemPath stateItemPath = ActivityStateUtil.getStateItemPath(taskActivityState, activityPath);
            List<ItemDelta<?, ?>> itemDeltas;
            if (activityPath.equals(ActivityStateUtil.getLocalRootPath(taskActivityState))) {
                // This is the [local] root activity. Delete everything!
                itemDeltas = PrismContext.get().deltaFor(TaskType.class)
                        .item(TaskType.F_ACTIVITY_STATE).replace()
                        .asItemDeltas();
            } else {
                Long id = ItemPath.toId(stateItemPath.last());
                assert id != null;
                itemDeltas = PrismContext.get().deltaFor(TaskType.class)
                        .item(stateItemPath.allExceptLast()).delete(new ActivityStateType().id(id))
                        .asItemDeltas();
            }
            plainRepositoryService.modifyObject(TaskType.class, task.getOid(), itemDeltas, result);
            LOGGER.info("Deleted activity state for '{}' in {}", activityPath, task);
        } catch (CommonException e) {
            LoggingUtils.logUnexpectedException(LOGGER, "Couldn't delete state for activity path '{}' in {}", e, activityPath, task);
        }
    }

    private void purgeState(TaskType task, ActivityPath activityPath, ActivityStateType activityState, OperationResult result) {
        try {
            TaskActivityStateType taskActivityState = task.getActivityState();
            ItemPath stateItemPath = ActivityStateUtil.getStateItemPath(taskActivityState, activityPath);
            List<ItemDelta<?, ?>> itemDeltas = PrismContext.get().deltaFor(TaskType.class)
                    .item(stateItemPath.append(ActivityStateType.F_RESULT_STATUS)).replace()
                    .item(stateItemPath.append(ActivityStateType.F_PROGRESS)).replace()
                    .item(stateItemPath.append(ActivityStateType.F_STATISTICS)).replace()
                    .item(stateItemPath.append(ActivityStateType.F_BUCKETING)).replace()
                    .item(stateItemPath.append(ActivityStateType.F_COUNTERS)).replace()
                    .asItemDeltas();
            if (!(activityState.getWorkState() instanceof DelegationWorkStateType)) {
                itemDeltas.addAll(
                        PrismContext.get().deltaFor(TaskType.class)
                                .item(stateItemPath.append(ActivityStateType.F_REALIZATION_STATE)).replace()
                                .item(stateItemPath.append(ActivityStateType.F_WORK_STATE)).replace()
                                .asItemDeltas());
            }
            plainRepositoryService.modifyObject(TaskType.class, task.getOid(), itemDeltas, result);
            LOGGER.info("Purged activity state for '{}' in {}", activityPath, task);
        } catch (CommonException e) {
            LoggingUtils.logUnexpectedException(LOGGER, "Couldn't delete state for activity path '{}' in {}", e, activityPath, task);
        }
    }
}
