/*
 * Copyright (c) 2020 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.repo.common.task;

import static com.evolveum.midpoint.repo.common.task.AnnotationSupportUtil.createFromAnnotation;
import static com.evolveum.midpoint.schema.result.OperationResultStatus.*;
import static com.evolveum.midpoint.task.api.TaskRunResult.TaskRunResultStatus.PERMANENT_ERROR;

import java.util.Locale;
import java.util.Objects;

import com.evolveum.midpoint.repo.common.activity.ActivityExecutionException;
import com.evolveum.midpoint.repo.common.activity.definition.WorkDefinition;
import com.evolveum.midpoint.repo.common.activity.execution.ExecutionInstantiationContext;
import com.evolveum.midpoint.repo.common.activity.execution.ActivityExecutionResult;
import com.evolveum.midpoint.repo.common.activity.execution.LocalActivityExecution;
import com.evolveum.midpoint.repo.common.activity.handlers.ActivityHandler;
import com.evolveum.midpoint.schema.util.task.ActivityItemProcessingStatisticsUtil;
import com.evolveum.midpoint.task.api.ExecutionSupport;
import com.evolveum.midpoint.task.api.RunningTask;
import com.evolveum.midpoint.util.logging.TraceManager;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import com.evolveum.midpoint.prism.xml.XmlTypeConverter;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.result.OperationResultStatus;
import com.evolveum.midpoint.schema.util.task.ActivityPerformanceInformation;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.annotation.Experimental;
import com.evolveum.midpoint.util.exception.*;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.xml.ns._public.common.common_3.*;

/**
 * Represents an execution of a generic iterative activity.
 *
 * Responsibilities:
 *
 * 1. Prepares _item gathering_ operation, like the iterative search, live sync, or async update listening.
 *    For example, for the search, it determines the object type, the query, and the options.
 *    See {@link #prepareItemSource(OperationResult)}.
 *
 * 2. Initiates item gathering operation. See {@link #processItems(OperationResult)} method.
 *
 * 3. Funnels all items received into the {@link ProcessingCoordinator}.
 *
 * 4. Holds all relevant state (including e.g. statistics) of the execution.
 *
 * *TODO finish the cleanup*
 */
public abstract class AbstractIterativeActivityExecution<
        I,
        WD extends WorkDefinition,
        AH extends ActivityHandler<WD, AH>,
        WS extends AbstractActivityWorkStateType>
        extends LocalActivityExecution<WD, AH, WS> implements ExecutionSupport {

    private static final Trace LOGGER = TraceManager.getTrace(AbstractIterativeActivityExecution.class);

    private static final String OP_PREFIX = AbstractIterativeActivityExecution.class.getName();

    /**
     * Processes individual items - in cooperation with {@link ProcessingCoordinator} and {@link ItemProcessingGatekeeper}.
     */
    protected ItemProcessor<I> itemProcessor;

    /**
     * Schedules individual items for processing by worker tasks (if running in multiple threads).
     */
    protected ProcessingCoordinator<I> coordinator;

    /** TODO */
    @NotNull protected final ErrorState errorState = new ErrorState();

    /**
     * Maintains selected statistical information related to processing items during this activity execution.
     */
    @NotNull protected final ActivityExecutionStatistics executionStatistics;

    /**
     * Things like "Import", "Reconciliation (on resource)", and so on. Used e.g. in log messages like:
     * "Import of UserType:jack (Jack Sparrow, c0c010c0-d34d-b33f-f00d-111111111111) from Crew Management has been started"
     */
    @NotNull String activityShortNameCapitalized;

    /**
     * Information that augments the process short name. Used e.g. in log messages.
     * An example: "from [resource]".
     */
    @NotNull private String contextDescription;

    /**
     * Determines and executes error handling strategy for this activity.
     */
    @NotNull private final ErrorHandlingStrategyExecutor errorHandlingStrategyExecutor;

    /**
     * Reporting options specific for this task part. They are copied from the main options
     * present in the task handler - by default. Note this will change when we allow configuring
     * them for individual tasks.
     */
    @NotNull protected final ActivityReportingOptions reportingOptions;

    /**
     * TODO Identifier of the activity. Must be unique in the current list of activities.
     */
    @Experimental
    String activityIdentifier;

    /**
     * TODO Relative number of the activity in the current list of activities. Starting at 1.
     */
    @Experimental
    private int activityNumber;

    /**
     * TODO Expected number of activities in the current list of activities.
     */
    @Experimental
    private int expectedActivities;

    @NotNull protected final CommonTaskBeans beans;

    protected AbstractIterativeActivityExecution(@NotNull ExecutionInstantiationContext<WD, AH> context,
            @NotNull String shortNameCapitalized) {
        super(context);
        this.executionStatistics = new ActivityExecutionStatistics();
        this.activityShortNameCapitalized = shortNameCapitalized;
        this.contextDescription = "";
        this.beans = taskExecution.getBeans();
        this.errorHandlingStrategyExecutor = new ErrorHandlingStrategyExecutor(getActivity(), getRunningTask(),
                getDefaultErrorAction(), beans);
        this.reportingOptions = getDefaultReportingOptions()
                .cloneWithConfiguration(
                        getRunningTask().getExtensionContainerRealValueOrClone(
                                SchemaConstants.MODEL_EXTENSION_REPORTING_OPTIONS)); // TODO move into activity definition
    }

    @NotNull
    public ActivityReportingOptions getDefaultReportingOptions() {
        return new ActivityReportingOptions();
    }

    protected @NotNull ActivityExecutionResult executeLocal(OperationResult opResult)
            throws ActivityExecutionException, CommonException {

        LOGGER.trace("{}: Starting with local coordinator task {}", activityShortNameCapitalized, getRunningTask());

        checkTaskPersistence();

        updateStatisticsOnStart();

        initializeExecution(opResult);

        executeInitialized(opResult);

        finishExecution(opResult);

        ActivityExecutionResult executionResult = createExecutionResult();

        LOGGER.trace("{} run finished (task {}, run result {})", activityShortNameCapitalized, getRunningTask(), executionResult);
        return executionResult;
    }

    private ActivityExecutionResult createExecutionResult() {
        if (!canRun()) {
            return ActivityExecutionResult.interrupted();
        }

        Throwable stoppingException = errorState.getStoppingException();
        if (stoppingException != null) {
            // TODO In the future we should distinguish between permanent and temporary errors here.
            return ActivityExecutionResult.exception(FATAL_ERROR, PERMANENT_ERROR, stoppingException);
        } else if (executionStatistics.getErrors() > 0) {
            return ActivityExecutionResult.finished(PARTIAL_ERROR);
        } else {
            return ActivityExecutionResult.success();
        }
    }

    /**
     * Execute initialized activity for a single or multiple buckets.
     *
     * TODO better name
     */
    protected void executeInitialized(OperationResult opResult) throws ActivityExecutionException, CommonException {
        executeSingleBucket(opResult);
    }

    /**
     * Executes the activity for a single bucket (if buckets are supported).
     *
     * TODO better name, independent of the bucketing
     */
    protected void executeSingleBucket(OperationResult opResult) throws ActivityExecutionException, CommonException {
        prepareItemSource(opResult);
        onPreparedItemSource(opResult);

        setExpectedTotal(opResult);

        itemProcessor = setupItemProcessor(opResult);
        coordinator = setupCoordinator();

        try {
            coordinator.createWorkerTasks(getReportingOptions());
            processItems(opResult);
        } finally {
            // This is redundant in the case of live sync event handling (because the handler gets a notification when all
            // items are submitted, and must stop the threads in order to allow provisioning to update the token).
            //
            // But overall, it is necessary to do this here in order to avoid endless waiting if any exception occurs.
            coordinator.finishProcessing(opResult);
        }

        updateOperationResultStatus(opResult);

        updateStatisticsOnFinish(opResult);
        logFinishInfo(opResult);
    }

    // TODO reconsider this method
    private void updateOperationResultStatus(OperationResult opResult) {
        if (errorState.wasStoppingExceptionEncountered()) {
            // We assume the error occurred within this part (otherwise that part would not even start).
            opResult.setStatus(FATAL_ERROR);
        } else if (executionStatistics.getErrors() > 0) {
            opResult.setStatus(PARTIAL_ERROR);
        } else if (!opResult.isError()) {
            opResult.setStatus(SUCCESS);
        }
    }

    /**
     * Initializes iterative execution.
     *
     * TODO better name to distinguish from abstract activity execution initialization.
     */
    protected void initializeExecution(OperationResult opResult) throws CommonException, ActivityExecutionException {
    }

    /**
     * Prepares the item source. E.g. for search-iterative tasks we prepare object type, query, and options here.
     */
    protected void prepareItemSource(OperationResult opResult) throws ActivityExecutionException, CommonException {
    }

    /** TODO */
    protected void onPreparedItemSource(OperationResult opResult) throws ActivityExecutionException, CommonException {
    }

    /**
     * Computes expected total and sets the value in the task. E.g. for search-iterative tasks we count the objects here.
     * TODO reconsider
     */
    protected void setExpectedTotal(OperationResult opResult) throws CommonException {
    }

    /**
     * Sets up the item processor. This is just delegated to the subclass (or default method is used).
     */
    private ItemProcessor<I> setupItemProcessor(OperationResult opResult) throws CommunicationException, ObjectNotFoundException,
            SchemaException, SecurityViolationException, ConfigurationException, ExpressionEvaluationException {
        return createItemProcessor(opResult);
    }

    /**
     * Creates the item processor. This method should not do anything more. For initialization there are other methods.
     */
    protected @NotNull ItemProcessor<I> createItemProcessor(OperationResult opResult) throws SchemaException, SecurityViolationException, ObjectNotFoundException,
            ExpressionEvaluationException, CommunicationException, ConfigurationException {
        return createItemProcessorFromAnnotation();
    }

    private @NotNull ItemProcessor<I> createItemProcessorFromAnnotation() {
        //noinspection unchecked
        return (ItemProcessor<I>) createFromAnnotation(this, this, this,
                ItemProcessorClass.class, ItemProcessorClass::value, "item processor");
    }

    /**
     * Creates the processing coordinator. Usually no customization is needed here.
     */
    private ProcessingCoordinator<I> setupCoordinator() {
        return new ProcessingCoordinator<>(getWorkerThreadsCount(), getRunningTask(), beans.taskManager);
    }

    /**
     * Starts the item source (e.g. searchObjectsIterative call or synchronize call) and begins processing items
     * generated by it. Returns when the source finishes.
     *
     * For example:
     *
     * - for search-based tasks, this call returns immediately after the search is over ... TODO ok?
     * - for live sync task, this returns after all changes were fetched and acknowledged, and the resulting token was written
     * - for async update task, this returns also after all changes were fetched and acknowledged and confirmed to the source
     */
    protected abstract void processItems(OperationResult opResult) throws CommonException;

    /**
     * Ends the processing. TODO better name
     */
    protected void finishExecution(OperationResult opResult) throws CommonException, ActivityExecutionException {
    }

    private void checkTaskPersistence() {
        if (getRunningTask().getOid() == null) {
            throw new IllegalArgumentException(
                    "Transient tasks cannot be run by " + getClass() + ": " + getRunningTask());
        }
    }

    /**
     * @return True if this task execution should periodically write traces, according to relevant task extension items.
     * It is also used to drive dynamic profiling (which is going to be deprecated).
     */
    public abstract boolean providesTracingAndDynamicProfiling();

    public long getStartTimeMillis() {
        return executionStatistics.startTimeMillis;
    }

    public boolean isMultithreaded() {
        return coordinator.isMultithreaded();
    }

    public Long heartbeat() {
        // If we exist then we run. So just return the progress count.
        return getRunningTask().getProgress();
    }

    private void updateStatisticsOnStart() {
        executionStatistics.recordStart();
    }

    private void updateStatisticsOnFinish(OperationResult result) {
        RunningTask task = getRunningTask();

        executionStatistics.recordEnd();
        task.updateStatisticsInTaskPrism(true);

        // TODO eventually remove
        TaskHandlerUtil.appendLastFailuresInformation(OP_PREFIX, task, result);
    }

    // TODO deduplicate with statistics output in ItemProcessingGatekeeper
    // TODO decide on the final form of these messages
    private void logFinishInfo(OperationResult result) {

        RunningTask task = getRunningTask();

        long endTime = Objects.requireNonNull(executionStatistics.endTimeMillis, "No end timestamp?");
        // Note: part statistics should be consistent with this end time.

        ActivityPerformanceInformation perfInfo =
                ActivityPerformanceInformation.forRegularActivity(
                        getActivityPath(),
                        getActivityState().getLiveItemProcessingStatistics().getValueCopy(),
                        getActivityState().getLiveProgress().getValueCopy());

        String shortMessage =
                String.format("Finished bucket for %s (%s). Resulting status: %s.%s",
                        getProcessShortName(), task, result.getStatus(),
                        task.canRun() ? "" : " Task was interrupted during processing.");

        String bucketStatMsg = String.format(Locale.US, "Current bucket: processed %,d objects in %.1f seconds, got %,d errors.",
                executionStatistics.getItemsProcessed(), executionStatistics.getWallClockTime(endTime) / 1000.0,
                executionStatistics.getErrors());
        if (executionStatistics.getItemsProcessed() > 0) {
            bucketStatMsg += String.format(Locale.US, " Average processing time for one object: %,.1f milliseconds. "
                            + "Wall clock average: %,.1f milliseconds, throughput: %,.1f items per minute.",
                    executionStatistics.getAverageTime(), executionStatistics.getAverageWallClockTime(endTime),
                    executionStatistics.getThroughput(endTime));
        }

        String partStatMsg = String.format(Locale.US, "The whole part: processed %,d objects in %.1f seconds, got %,d errors. Real progress: %,d.",
                perfInfo.getItemsProcessed(), ActivityItemProcessingStatisticsUtil.toSeconds(perfInfo.getWallClockTime()),
                perfInfo.getErrors(), perfInfo.getProgress());
        if (perfInfo.getItemsProcessed() > 0) {
            partStatMsg += String.format(Locale.US, " Average processing time for one object: %,.1f milliseconds. "
                            + "Wall clock average: %,.1f milliseconds, throughput: %,.1f items per minute.",
                    perfInfo.getAverageTime(), perfInfo.getAverageWallClockTime(),
                    perfInfo.getThroughput());
        }

        String fullStatMessage = String.format(Locale.US,
                "%s of a bucket done. Current activity: '%s'\n\n"
                        + "Items processed: %,d in current bucket and %,d in current part.\n"
                        + "Errors: %,d in current bucket and %,d in current part.\n"
                        + "Real progress is %,d.\n\n"
                        + "Average duration is %,.1f ms (in current bucket) and %,.1f ms (in current part).\n"
                        + "Wall clock average is %,.1f ms (in current bucket) and %,.1f ms (in current part).\n"
                        + "Average throughput is %,.1f items per minute (in current bucket) and %,.1f items per minute (in current part).\n\n"
                        + "Processing time is %,.1f ms (for current bucket) and %,.1f ms (for current part)\n"
                        + "Wall-clock time is %,d ms (for current bucket) and %,d ms (for current part)\n"
                        + "Start time was:\n"
                        + " - for current bucket: %s\n"
                        + " - for current part:   %s\n",
                getActivityShortNameCapitalized(), getActivityPath(),
                executionStatistics.getItemsProcessed(), perfInfo.getItemsProcessed(),
                executionStatistics.getErrors(), perfInfo.getErrors(),
                perfInfo.getProgress(),
                executionStatistics.getAverageTime(), perfInfo.getAverageTime(),
                executionStatistics.getAverageWallClockTime(endTime), perfInfo.getAverageWallClockTime(),
                executionStatistics.getThroughput(endTime), perfInfo.getThroughput(),
                executionStatistics.getProcessingTime(), perfInfo.getProcessingTime(),
                executionStatistics.getWallClockTime(endTime), perfInfo.getWallClockTime(),
                XmlTypeConverter.createXMLGregorianCalendar(executionStatistics.getStartTimeMillis()),
                perfInfo.getEarliestStartTime());

        result.createSubresult(OP_PREFIX + ".statistics")
                .recordStatus(SUCCESS, bucketStatMsg);
        result.createSubresult(OP_PREFIX + ".statistics")
                .recordStatus(SUCCESS, partStatMsg);

        TaskLoggingOptionType logging = reportingOptions.getBucketCompletionLogging();
        if (logging == TaskLoggingOptionType.FULL) {
            LOGGER.info("{}", fullStatMessage);
        } else if (logging == TaskLoggingOptionType.BRIEF) {
            LOGGER.info("{}\n{}\n{}", shortMessage, bucketStatMsg, partStatMsg);
            LOGGER.debug("{}", fullStatMessage);
        } else {
            LOGGER.debug("{}", fullStatMessage);
        }
    }

    private Integer getWorkerThreadsCount() {
        return getActivity().getDistributionDefinition().getWorkerThreads();
    }

    public void ensureNoWorkerThreads() {
        int threads = getWorkerThreadsCount();
        if (threads != 0) {
            throw new UnsupportedOperationException("Unsupported number of worker threads: " + threads +
                    ". This task cannot be run with worker threads. Please remove workerThreads task "
                    + "extension property and/or workerThreads distribution definition item or set its value to 0.");
        }
    }

    public @NotNull String getActivityShortNameCapitalized() {
        return activityShortNameCapitalized;
    }

    public @NotNull String getProcessShortName() {
        return StringUtils.uncapitalize(activityShortNameCapitalized);
    }

    public void setActivityShortNameCapitalized(String value) {
        this.activityShortNameCapitalized = ObjectUtils.defaultIfNull(value, "");
    }

    public @NotNull String getContextDescription() {
        return contextDescription;
    }

    public void setContextDescription(String value) {
        this.contextDescription = ObjectUtils.defaultIfNull(value, "");
    }

    ErrorHandlingStrategyExecutor.FollowUpAction handleError(@NotNull OperationResultStatus status,
            @NotNull Throwable exception, ItemProcessingRequest<?> request, OperationResult result) {
        return errorHandlingStrategyExecutor.handleError(status, exception, request.getObjectOidToRecordRetryTrigger(), result);
    }

    /**
     * @return Default error action if no policy is specified or if no policy entry matches.
     */
    protected @NotNull abstract ErrorHandlingStrategyExecutor.FollowUpAction getDefaultErrorAction();

    public @NotNull ActivityReportingOptions getReportingOptions() {
        return reportingOptions;
    }

    public boolean isSimulate() {
        return getExecutionMode() == ExecutionModeType.SIMULATE;
    }

    public boolean isDryRun() {
        return getExecutionMode() == ExecutionModeType.DRY_RUN;
    }

    public @NotNull String getRootTaskOid() {
        return getRunningTask().getRootTaskOid();
    }

    protected @NotNull Task getRootTask(OperationResult result) throws SchemaException {
        String rootTaskOid = getRootTaskOid();
        RunningTask task = getRunningTask();
        if (task.getOid().equals(rootTaskOid)) {
            return task;
        } else {
            try {
                return beans.taskManager.getTaskPlain(rootTaskOid, result);
            } catch (ObjectNotFoundException e) {
                // This is quite unexpected so it can be rethrown as SystemException
                throw new SystemException("The root task was not found", e);
            }
        }
    }

    public String getActivityIdentifier() {
        return activityIdentifier;
    }

    public void setActivityIdentifier(String activityIdentifier) {
        this.activityIdentifier = activityIdentifier;
    }

    public int getActivityNumber() {
        return activityNumber;
    }

    public void setActivityNumber(int activityNumber) {
        this.activityNumber = activityNumber;
    }


    public int getExpectedActivities() {
        return expectedActivities;
    }

    public void setExpectedActivities(int expectedActivities) {
        this.expectedActivities = expectedActivities;
    }

    /**
     * When did current part start?
     *
     * We need this for execution record creation that is needed for wall clock average and throughput computation.
     *
     * However, answering this question is a bit tricky because of bucketed runs. By recording part start when the bucket
     * starts is precise, but
     *
     * 1. does not take bucket management overhead into account,
     * 2. generates a lot of execution records - more than we can reasonably handle.
     *
     * So, for all except multi-part runs (that are never bucketed by definition) we use regular task start information.
     *
     * All of this will change when bucketing will be (eventually) rewritten.
     */
    public long getPartStartTimestamp() {
        return executionStatistics.getStartTimeMillis(); // FIXME

//        if (taskExecution.isInternallyMultipart()) {
//            return executionStatistics.getStartTimeMillis();
//        } else {
//            return Objects.requireNonNull(localCoordinatorTask.getLastRunStartTimestamp(),
//                    () -> "No last run start timestamp in " + localCoordinatorTask);
//        }
    }

    @Override
    public boolean supportsStatistics() {
        return true;
    }

    @Override
    public boolean supportsSynchronizationStatistics() {
        return reportingOptions.isEnableSynchronizationStatistics();
    }

    @Override
    public boolean supportsActionsExecuted() {
        return reportingOptions.isEnableActionsExecutedStatistics();
    }
}
