/*
 * Copyright (c) 2020 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.model.impl.tasks;

import static com.evolveum.midpoint.model.api.ModelExecuteOptions.fromModelExecutionOptionsType;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.schema.util.task.work.ObjectSetUtil;

import com.evolveum.midpoint.xml.ns._public.common.common_3.*;

import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import com.evolveum.midpoint.model.api.ModelExecuteOptions;
import com.evolveum.midpoint.model.api.ModelPublicConstants;
import com.evolveum.midpoint.model.impl.lens.LensContext;
import com.evolveum.midpoint.model.impl.tasks.simple.ExecutionContext;
import com.evolveum.midpoint.model.impl.tasks.simple.SimpleActivityExecution;
import com.evolveum.midpoint.model.impl.tasks.simple.SimpleActivityHandler;
import com.evolveum.midpoint.model.impl.util.ModelImplUtils;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.repo.common.activity.definition.AbstractWorkDefinition;
import com.evolveum.midpoint.repo.common.activity.definition.ObjectSetSpecificationProvider;
import com.evolveum.midpoint.repo.common.activity.definition.WorkDefinitionFactory.WorkDefinitionSupplier;
import com.evolveum.midpoint.repo.common.task.ActivityReportingOptions;
import com.evolveum.midpoint.repo.common.task.ItemProcessingRequest;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.task.work.LegacyWorkDefinitionSource;
import com.evolveum.midpoint.schema.util.task.work.WorkDefinitionSource;
import com.evolveum.midpoint.schema.util.task.work.WorkDefinitionWrapper;
import com.evolveum.midpoint.task.api.RunningTask;
import com.evolveum.midpoint.util.DebugUtil;
import com.evolveum.midpoint.util.exception.CommonException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;

/**
 * Executes specified deltas on specified set of objects.
 */
@Component
public class RecomputationActivityHandler
        extends SimpleActivityHandler<ObjectType, RecomputationActivityHandler.MyWorkDefinition, ExecutionContext> {

    private static final String LEGACY_HANDLER_URI = ModelPublicConstants.RECOMPUTE_HANDLER_URI;
    private static final Trace LOGGER = TraceManager.getTrace(RecomputationActivityHandler.class);

    private static final QName DEFAULT_OBJECT_TYPE_FOR_LEGACY_SPEC = UserType.COMPLEX_TYPE;  // This is pre-4.4 behavior
    private static final QName DEFAULT_OBJECT_TYPE_FOR_NEW_SPEC = AssignmentHolderType.COMPLEX_TYPE; // This is more reasonable

    @Override
    protected @NotNull QName getWorkDefinitionTypeName() {
        return RecomputationWorkDefinitionType.COMPLEX_TYPE;
    }

    @Override
    protected @NotNull Class<MyWorkDefinition> getWorkDefinitionClass() {
        return MyWorkDefinition.class;
    }

    @Override
    protected @NotNull WorkDefinitionSupplier getWorkDefinitionSupplier() {
        return MyWorkDefinition::new;
    }

    @Override
    protected @NotNull String getLegacyHandlerUri() {
        return LEGACY_HANDLER_URI;
    }

    @Override
    public String getDefaultArchetypeOid() {
        return SystemObjectsType.ARCHETYPE_RECOMPUTATION_TASK.value();
    }

    @Override
    protected @NotNull String getShortName() {
        return "Recomputation";
    }

    @Override
    public @NotNull ActivityReportingOptions getDefaultReportingOptions() {
        return new ActivityReportingOptions()
                .enableActionsExecutedStatistics(true);
    }

    @Override
    public boolean processItem(PrismObject<ObjectType> object, ItemProcessingRequest<PrismObject<ObjectType>> request,
            SimpleActivityExecution<ObjectType, MyWorkDefinition, ExecutionContext> activityExecution,
            RunningTask workerTask, OperationResult result) throws CommonException {
        boolean simulate = activityExecution.isSimulate();
        String action = simulate ? "Simulated recomputation" : "Recomputation";

        LOGGER.trace("{} of object {}", action, object);

        LensContext<FocusType> syncContext = contextFactory.createRecomputeContext(object,
                activityExecution.getWorkDefinition().getExecutionOptions(), workerTask, result);
        LOGGER.trace("{} of object {}: context:\n{}", action, object, syncContext.debugDumpLazily());

        if (simulate) {
            clockwork.previewChanges(syncContext, null, workerTask, result);
        } else {
            clockwork.run(syncContext, workerTask, result);
        }
        LOGGER.trace("{} of object {}: {}", action, object, result.getStatus());
        return true;
    }

    public static class MyWorkDefinition extends AbstractWorkDefinition implements ObjectSetSpecificationProvider {

        private final ObjectSetType objects;
        private final ModelExecuteOptions executionOptions;

        MyWorkDefinition(WorkDefinitionSource source) {
            if (source instanceof LegacyWorkDefinitionSource) {
                LegacyWorkDefinitionSource legacy = (LegacyWorkDefinitionSource) source;
                objects = ObjectSetUtil.fromLegacySource(legacy);
                executionOptions = ModelImplUtils.getModelExecuteOptions(legacy.getTaskExtension());
                ObjectSetUtil.applyDefaultObjectType(objects, DEFAULT_OBJECT_TYPE_FOR_LEGACY_SPEC);
            } else {
                RecomputationWorkDefinitionType typedDefinition = (RecomputationWorkDefinitionType)
                        ((WorkDefinitionWrapper.TypedWorkDefinitionWrapper) source).getTypedDefinition();
                objects = typedDefinition.getObjects();
                executionOptions = fromModelExecutionOptionsType(typedDefinition.getExecutionOptions());
                ObjectSetUtil.applyDefaultObjectType(objects, DEFAULT_OBJECT_TYPE_FOR_NEW_SPEC);
            }
        }

        @Override
        public ObjectSetType getObjectSetSpecification() {
            return objects;
        }

        public ModelExecuteOptions getExecutionOptions() {
            return executionOptions;
        }

        @Override
        protected void debugDumpContent(StringBuilder sb, int indent) {
            DebugUtil.debugDumpWithLabelLn(sb, "objects", objects, indent+1);
            DebugUtil.debugDumpWithLabelLn(sb, "executionOptions", String.valueOf(executionOptions), indent+1);
        }
    }
}
