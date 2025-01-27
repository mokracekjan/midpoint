/*
 * Copyright (c) 2010-2018 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.model.impl.lens.projector;

import static com.evolveum.midpoint.model.impl.lens.LensUtil.getExportType;
import static com.evolveum.midpoint.schema.internals.InternalsConfig.consistencyChecks;
import static com.evolveum.midpoint.schema.result.OperationResult.DEFAULT;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import com.evolveum.midpoint.model.impl.lens.projector.util.ProcessorExecution;
import com.evolveum.midpoint.model.impl.lens.projector.util.ProcessorMethod;
import com.evolveum.midpoint.prism.*;
import com.evolveum.midpoint.prism.delta.*;
import com.evolveum.midpoint.schema.*;
import com.evolveum.midpoint.schema.constants.ObjectTypes;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.internals.InternalsConfig;
import com.evolveum.midpoint.schema.util.*;
import com.evolveum.midpoint.util.annotation.Experimental;
import com.evolveum.midpoint.util.logging.LoggingUtils;
import com.evolveum.midpoint.xml.ns._public.common.common_3.*;

import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.evolveum.midpoint.common.refinery.RefinedObjectClassDefinition;
import com.evolveum.midpoint.model.api.ModelExecuteOptions;
import com.evolveum.midpoint.model.api.context.SynchronizationPolicyDecision;
import com.evolveum.midpoint.model.common.ArchetypeManager;
import com.evolveum.midpoint.model.common.SystemObjectCache;
import com.evolveum.midpoint.model.impl.lens.ClockworkMedic;
import com.evolveum.midpoint.model.impl.lens.LensContext;
import com.evolveum.midpoint.model.impl.lens.LensElementContext;
import com.evolveum.midpoint.model.impl.lens.LensFocusContext;
import com.evolveum.midpoint.model.impl.lens.LensObjectDeltaOperation;
import com.evolveum.midpoint.model.impl.lens.LensProjectionContext;
import com.evolveum.midpoint.model.impl.lens.LensUtil;
import com.evolveum.midpoint.model.api.context.SynchronizationIntent;
import com.evolveum.midpoint.model.impl.security.SecurityHelper;
import com.evolveum.midpoint.provisioning.api.ProvisioningService;
import com.evolveum.midpoint.repo.api.RepositoryService;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.exception.CommunicationException;
import com.evolveum.midpoint.util.exception.ConfigurationException;
import com.evolveum.midpoint.util.exception.ExpressionEvaluationException;
import com.evolveum.midpoint.util.exception.ObjectAlreadyExistsException;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.PolicyViolationException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.exception.SecurityViolationException;
import com.evolveum.midpoint.util.exception.SystemException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;

import javax.xml.datatype.XMLGregorianCalendar;

/**
 * Context loader loads the missing parts of the context. The context enters the projector with just the minimum information.
 * Context loader gets missing data such as accounts. It gets them from the repository or provisioning as necessary. It follows
 * the account links in focus (linkRef) and focus deltas.
 *
 * @author Radovan Semancik
 *
 */
@Component
@ProcessorExecution()
public class ContextLoader implements ProjectorProcessor {

    @Autowired
    @Qualifier("cacheRepositoryService")
    private RepositoryService cacheRepositoryService;

    @Autowired private SystemObjectCache systemObjectCache;
    @Autowired private ArchetypeManager archetypeManager;
    @Autowired private ProvisioningService provisioningService;
    @Autowired private PrismContext prismContext;
    @Autowired private SecurityHelper securityHelper;
    @Autowired private ClockworkMedic medic;
    @Autowired private RelationRegistry relationRegistry;
    @Autowired private SchemaService schemaService;

    private static final Trace LOGGER = TraceManager.getTrace(ContextLoader.class);

    public static final String CLASS_DOT = ContextLoader.class.getName() + ".";
    private static final String OPERATION_LOAD = CLASS_DOT + "load";
    private static final String OPERATION_LOAD_PROJECTION = CLASS_DOT + "loadProjection";

    @ProcessorMethod
    <F extends ObjectType> void load(LensContext<F> context, String activityDescription,
            @SuppressWarnings("unused") XMLGregorianCalendar now, Task task, OperationResult parentResult)
            throws SchemaException, ObjectNotFoundException, CommunicationException, ConfigurationException,
            SecurityViolationException, PolicyViolationException, ExpressionEvaluationException {

        context.recompute();

        OperationResult result = parentResult.createMinorSubresult(OPERATION_LOAD);
        ProjectorComponentTraceType trace;
        if (result.isTracingAny(ProjectorComponentTraceType.class)) {
            trace = new ProjectorComponentTraceType(prismContext);
            if (result.isTracingNormal(ProjectorComponentTraceType.class)) {
                trace.setInputLensContextText(context.debugDump());
            }
            trace.setInputLensContext(context.toLensContextType(getExportType(trace, result)));
            result.addTrace(trace);
        } else {
            trace = null;
        }

        try {

            for (LensProjectionContext projectionContext: context.getProjectionContexts()) {
                preprocessProjectionContext(context, projectionContext, task, result);
            }

            context.checkConsistenceIfNeeded();

            determineFocusContext(context, task, result);

            LensFocusContext<F> focusContext = context.getFocusContext();
            if (focusContext != null) {

                context.recomputeFocus();

                loadFromSystemConfig(context, task, result);

                if (FocusType.class.isAssignableFrom(context.getFocusClass())) {
                    // this also removes the accountRef deltas
                    //noinspection unchecked
                    loadLinkRefs((LensContext<? extends FocusType>)context, task, result);
                    LOGGER.trace("loadLinkRefs done");
                }

                // Some cleanup
                ObjectDelta<F> primaryDelta = focusContext.getPrimaryDelta();
                if (ObjectDelta.isModify(primaryDelta) && primaryDelta.isEmpty()) {
                    focusContext.setPrimaryDelta(null);
                }

                for (LensProjectionContext projectionContext: context.getProjectionContexts()) {
                    if (projectionContext.getSynchronizationIntent() != null) {
                        // Accounts with explicitly set intent are never rotten. These are explicitly requested actions
                        // if they fail then they really should fail.
                        projectionContext.setFresh(true);
                    }
                }

                setPrimaryDeltaOldValue(focusContext);

            } else {
                // Projection contexts are not rotten in this case. There is no focus so there is no way to refresh them.
                for (LensProjectionContext projectionContext: context.getProjectionContexts()) {
                    projectionContext.setFresh(true);
                }
            }

            removeRottenContexts(context);

            context.checkConsistenceIfNeeded();

            for (LensProjectionContext projectionContext: context.getProjectionContexts()) {
                context.checkAbortRequested();
                // TODO: not perfect. Practically, we want loadProjection operation to contain all the projection
                //  results. But for that we would need code restructure.
                OperationResult projectionResult = result.createMinorSubresult(OPERATION_LOAD_PROJECTION);
                try {
                    finishLoadOfProjectionContext(context, projectionContext, task, projectionResult);
                } catch (Throwable e) {
                    projectionResult.recordFatalError(e);
                    throw e;
                } finally {
                    projectionResult.computeStatusIfUnknown();
                }
            }

            context.checkConsistenceIfNeeded();
            context.recompute();

            if (consistencyChecks) {
                fullCheckConsistence(context);
            }

            // Set the "fresh" mark now so following consistency check will be stricter
            context.setFresh(true);
            context.checkConsistenceIfNeeded();

            medic.traceContext(LOGGER, activityDescription, "after load", false, context, false);

            result.computeStatusComposite();

        } catch (Throwable e) {
            result.recordFatalError(e);
            throw e;
        } finally {
            if (trace != null) {
                if (result.isTracingNormal(ProjectorComponentTraceType.class)) {
                    trace.setOutputLensContextText(context.debugDump());
                }
                trace.setOutputLensContext(context.toLensContextType(getExportType(trace, result)));
            }
        }
    }


    /**
     * Removes projection contexts that are not fresh.
     * These are usually artifacts left after the context reload. E.g. an account that used to be linked to a user before
     * but was removed in the meantime.
     */
    private <F extends ObjectType> void removeRottenContexts(LensContext<F> context) {
        Iterator<LensProjectionContext> projectionIterator = context.getProjectionContextsIterator();
        while (projectionIterator.hasNext()) {
            LensProjectionContext projectionContext = projectionIterator.next();
            if (projectionContext.getPrimaryDelta() != null && !projectionContext.getPrimaryDelta().isEmpty()) {
                // We must never remove contexts with primary delta. Even though it fails later on.
                // What the user wishes should be done (or at least attempted) regardless of the consequences.
                // Vox populi vox dei
                continue;
            }
            if (projectionContext.getWave() >= context.getExecutionWave()) {
                // We must not remove context from this and later execution waves. These haven't had the
                // chance to be executed yet
                continue;
            }
            if (projectionContext.isHigherOrder()) {
                // HACK never rot higher-order context. TODO: check if lower-order context is rotten, the also rot this one
                continue;
            }
            if (!projectionContext.isFresh()) {
                LOGGER.trace("Removing rotten context {}", projectionContext.getHumanReadableName());

                if (projectionContext.isToBeArchived()) {
                    context.getHistoricResourceObjects().add(projectionContext.getResourceShadowDiscriminator());
                }

                List<LensObjectDeltaOperation<ShadowType>> executedDeltas = projectionContext.getExecutedDeltas();
                context.getRottenExecutedDeltas().addAll(executedDeltas);
                projectionIterator.remove();
            }
        }
    }


    /**
     * Make sure that the projection context is loaded as appropriate.
     */
    <F extends ObjectType> void makeSureProjectionIsLoaded(LensContext<F> context,
            LensProjectionContext projectionContext, Task task, OperationResult result) throws ObjectNotFoundException,
            CommunicationException, SchemaException, ConfigurationException, SecurityViolationException,
            ExpressionEvaluationException {
        preprocessProjectionContext(context, projectionContext, task, result);
        finishLoadOfProjectionContext(context, projectionContext, task, result);
    }

    /**
     * Make sure that the context is OK and consistent. It means that is has a resource, it has correctly processed
     * discriminator, etc.
     */
    private <F extends ObjectType> void preprocessProjectionContext(LensContext<F> context,
                                                                    LensProjectionContext projectionContext, Task task, OperationResult result)
            throws ObjectNotFoundException, CommunicationException, SchemaException, ConfigurationException, SecurityViolationException, ExpressionEvaluationException {
        if (!ShadowType.class.isAssignableFrom(projectionContext.getObjectTypeClass())) {
            return;
        }
        String resourceOid = null;
        boolean isThombstone = false;
        ShadowKindType kind = ShadowKindType.ACCOUNT;
        String intent = null;
        String tag = null;
        int order = 0;
        ResourceShadowDiscriminator rsd = projectionContext.getResourceShadowDiscriminator();
        if (rsd != null) {
            resourceOid = rsd.getResourceOid();
            isThombstone = rsd.isTombstone();
            kind = rsd.getKind();
            intent = rsd.getIntent();
            tag = rsd.getTag();
            order = rsd.getOrder();
        }
        if (resourceOid == null && projectionContext.getObjectCurrent() != null) {
            resourceOid = ShadowUtil.getResourceOid(projectionContext.getObjectCurrent().asObjectable());
        }
        if (resourceOid == null && projectionContext.getObjectNew() != null) {
            resourceOid = ShadowUtil.getResourceOid(projectionContext.getObjectNew().asObjectable());
        }
        // We still may not have resource OID here. E.g. in case of the delete when the account is not loaded yet. It is
        // perhaps safe to skip this. It will be sorted out later.

        if (resourceOid != null) {
            if (intent == null && projectionContext.getObjectNew() != null) {
                ShadowType shadowNewType = projectionContext.getObjectNew().asObjectable();
                kind = ShadowUtil.getKind(shadowNewType);
                intent = ShadowUtil.getIntent(shadowNewType);
                tag = shadowNewType.getTag();
            }
            ResourceType resource = projectionContext.getResource();
            if (resource == null) {
                resource = LensUtil.getResourceReadOnly(context, resourceOid, provisioningService, task, result);
                projectionContext.setResource(resource);
            }
            String refinedIntent = LensUtil.refineProjectionIntent(kind, intent, resource, prismContext);
            rsd = new ResourceShadowDiscriminator(resourceOid, kind, refinedIntent, tag, isThombstone);
            rsd.setOrder(order);
            projectionContext.setResourceShadowDiscriminator(rsd);
        }
        if (projectionContext.getOid() == null && rsd != null && rsd.getOrder() != 0) {
            // Try to determine OID from lower-order contexts
            for (LensProjectionContext aProjCtx: context.getProjectionContexts()) {
                ResourceShadowDiscriminator aDiscr = aProjCtx.getResourceShadowDiscriminator();
                if (rsd.equivalent(aDiscr) && aProjCtx.getOid() != null) {
                    projectionContext.setOid(aProjCtx.getOid());
                    break;
                }
            }
        }
    }

    /**
     * try to load focus context from oid, delta, projections (e.g. by determining account owners)
     */
    public <O extends ObjectType> void determineFocusContext(LensContext<O> context, Task task, OperationResult parentResult)
            throws SchemaException, ObjectNotFoundException, CommunicationException, ConfigurationException,
            SecurityViolationException, ExpressionEvaluationException {

        OperationResult result = parentResult.subresult(CLASS_DOT + "determineFocusContext")
                .setMinor()
                .build();
        FocusLoadedTraceType trace;
        if (result.isTracingAny(FocusLoadedTraceType.class)) {
            trace = new FocusLoadedTraceType(prismContext);
            if (result.isTracingNormal(FocusLoadedTraceType.class)) {
                trace.setInputLensContextText(context.debugDump());
            }
            trace.setInputLensContext(context.toLensContextType(getExportType(trace, result)));
            result.addTrace(trace);
        } else {
            trace = null;
        }
        try {
            LensFocusContext<O> focusContext;
            if (context.getFocusContext() != null) {
                focusContext = context.getFocusContext();
            } else {
                focusContext = determineFocusContextFromProjections(context, result);
            }

            if (focusContext == null) {
                result.addReturnComment("Nothing to load");
                return;
            }

            // Make sure that we RELOAD the focus object if the context is not fresh
            // the focus may have changed in the meantime
            if (focusContext.getObjectCurrent() != null && focusContext.isFresh()) {
                result.addReturnComment("Already loaded");
                return;
            }

            ObjectDelta<O> objectDelta = focusContext.getSummaryDelta(); // TODO check this
            if (ObjectDelta.isAdd(objectDelta) && focusContext.getExecutedDeltas().isEmpty()) {
                //we're adding the focal object. No need to load it, it is in the delta
                focusContext.setFresh(true);
                result.addReturnComment("Obtained from delta");
                return;
            }

            if (focusContext.getObjectCurrent() != null && ObjectDelta.isDelete(objectDelta)) {
                // do not reload if the delta is delete. the reload will most likely fail anyway
                // but DO NOT set the fresh flag in this case, it may be misleading
                result.addReturnComment("Not loading as delta is DELETE");
                return;
            }

            String focusOid = focusContext.getOid();
            if (StringUtils.isBlank(focusOid)) {
                throw new IllegalArgumentException("No OID in primary focus delta");
            }

            PrismObject<O> object;
            if (ObjectTypes.isClassManagedByProvisioning(focusContext.getObjectTypeClass())) {
                object = provisioningService.getObject(focusContext.getObjectTypeClass(), focusOid,
                        SelectorOptions.createCollection(GetOperationOptions.createNoFetch()), task, result);
                setLoadedFocusInTrace(object, trace);
                result.addReturnComment("Loaded via provisioning");
            } else {

                // Always load a complete object here, including the not-returned-by-default properties.
                // This is temporary measure to make sure that the mappings will have all they need.
                // See MID-2635
                Collection<SelectorOptions<GetOperationOptions>> options =
                        SelectorOptions.createCollection(GetOperationOptions.createRetrieve(RetrieveOption.INCLUDE));
                object = cacheRepositoryService.getObject(focusContext.getObjectTypeClass(), focusOid, options, result);
                setLoadedFocusInTrace(object, trace);
                result.addReturnComment("Loaded from repository");
            }

            focusContext.setLoadedObject(object);
            focusContext.setFresh(true);
            LOGGER.trace("Focal object loaded: {}", object);
        } catch (Throwable t) {
            result.recordFatalError(t);
            throw t;
        } finally {
            if (trace != null) {
                if (result.isTracingNormal(FocusLoadedTraceType.class)) {
                    trace.setOutputLensContextText(context.debugDump());
                }
                trace.setOutputLensContext(context.toLensContextType(getExportType(trace, result)));
            }
            result.computeStatusIfUnknown();
        }
    }

    private <O extends ObjectType> void setLoadedFocusInTrace(PrismObject<O> object, FocusLoadedTraceType trace) {
        if (trace != null) {
            trace.setFocusLoadedRef(ObjectTypeUtil.createObjectRefWithFullObject(object, prismContext));
        }
    }

    private <O extends ObjectType> LensFocusContext<O> determineFocusContextFromProjections(LensContext<O> context, OperationResult result) {
        String focusOid = null;
        LensProjectionContext projectionContextThatYieldedFocusOid = null;
        PrismObject<O> focusOwner = null;
        for (LensProjectionContext projectionContext: context.getProjectionContexts()) {
            String projectionOid = projectionContext.getOid();
            if (projectionOid != null) {
                PrismObject<? extends FocusType> shadowOwner = cacheRepositoryService.searchShadowOwner(projectionOid,
                            SelectorOptions.createCollection(GetOperationOptions.createAllowNotFound()),
                            result);
                if (shadowOwner != null) {
                    if (focusOid == null || focusOid.equals(shadowOwner.getOid())) {
                        focusOid = shadowOwner.getOid();
                        //noinspection unchecked
                        focusOwner = (PrismObject<O>) shadowOwner;
                        projectionContextThatYieldedFocusOid = projectionContext;
                    } else {
                        throw new IllegalArgumentException("The context does not have explicit focus. Attempt to determine focus failed because two " +
                                "projections points to different foci: "+projectionContextThatYieldedFocusOid+"->"+focusOid+"; "+
                                projectionContext+"->"+shadowOwner);
                    }
                }
            }
        }

        if (focusOid != null) {
            LensFocusContext<O> focusCtx = context.getOrCreateFocusContext(focusOwner.getCompileTimeClass());
            focusCtx.setOid(focusOid);
            return focusCtx;
        } else {
            return null;
        }
    }

    private <O extends ObjectType> void setPrimaryDeltaOldValue(LensElementContext<O> ctx) {
        if (ctx.getPrimaryDelta() != null && ctx.getObjectOld() != null && ctx.isModify()) {
            boolean freezeAfterChange;
            if (ctx.getPrimaryDelta().isImmutable()) {
                ctx.setPrimaryDelta(ctx.getPrimaryDelta().clone());
                freezeAfterChange = true;
            } else {
                freezeAfterChange = false;
            }
            for (ItemDelta<?,?> itemDelta: ctx.getPrimaryDelta().getModifications()) {
                LensUtil.setDeltaOldValue(ctx, itemDelta);
            }
            if (freezeAfterChange) {
                ctx.getPrimaryDelta().freeze();
            }
        }
    }

    private <F extends ObjectType> void loadFromSystemConfig(LensContext<F> context, Task task, OperationResult result)
            throws ObjectNotFoundException, SchemaException, ConfigurationException, ExpressionEvaluationException, CommunicationException, SecurityViolationException {
        PrismObject<SystemConfigurationType> systemConfiguration = updateSystemConfigurationInContext(context, result);
        if (systemConfiguration == null) {
            // This happens in some tests. And also during first startup.
            return;
        }
        SystemConfigurationType systemConfigurationType = systemConfiguration.asObjectable();

        if (context.getFocusContext() != null) {
            if (context.getFocusContext().getArchetypePolicyType() == null) {
                ArchetypePolicyType archetypePolicy = determineArchetypePolicy(context, task, result);
                if (LOGGER.isTraceEnabled()) {
                    LOGGER.trace("Selected archetype policy:\n{}",
                            archetypePolicy==null?null:archetypePolicy.asPrismContainerValue().debugDump(1));
                }
                context.getFocusContext().setArchetypePolicyType(archetypePolicy);
            }
        }

        if (context.getFocusTemplate() == null) {
            // TODO is the nullity check needed here?
            updateFocusTemplate(context, result);
        }

        if (context.getAccountSynchronizationSettings() == null) {
            ProjectionPolicyType globalAccountSynchronizationSettings = systemConfigurationType.getGlobalAccountSynchronizationSettings();
            LOGGER.trace("Applying globalAccountSynchronizationSettings to context: {}", globalAccountSynchronizationSettings);
            context.setAccountSynchronizationSettings(globalAccountSynchronizationSettings);
        }

        loadSecurityPolicy(context, task, result);
    }

    // TODO find a better home for this method
    @Nullable
    public <F extends ObjectType> PrismObject<SystemConfigurationType> updateSystemConfigurationInContext(LensContext<F> context,
            OperationResult result) throws SchemaException {
        PrismObject<SystemConfigurationType> systemConfiguration = systemObjectCache.getSystemConfiguration(result);
        if (systemConfiguration != null) {
            context.setSystemConfiguration(systemConfiguration);
        }
        return systemConfiguration;
    }

    private <F extends ObjectType> ArchetypePolicyType determineArchetypePolicy(LensContext<F> context, Task task, OperationResult result) throws SchemaException, ConfigurationException {
        if (!canProcessArchetype(context)) {
            return null;
        }
        PrismObject<F> object = context.getFocusContext().getObjectAny();
        String explicitArchetypeOid = LensUtil.determineExplicitArchetypeOid(object);
        return archetypeManager.determineArchetypePolicy(object, explicitArchetypeOid, result);
    }

    private <O extends ObjectType> boolean canProcessArchetype(LensContext<O> context) {
        PrismObject<SystemConfigurationType> systemConfiguration = context.getSystemConfiguration();
        if (systemConfiguration == null) {
            return false;
        }
        if (context.getFocusContext() == null) {
            return false;
        }
        return true;
    }

    public <F extends AssignmentHolderType> void updateArchetype(LensContext<F> context, Task task, OperationResult result) throws SchemaException, ConfigurationException {
        if (!canProcessArchetype(context)) {
            return;
        }

        PrismObject<F> object = context.getFocusContext().getObjectAny();

        String explicitArchetypeOid = LensUtil.determineExplicitArchetypeOid(object);
        PrismObject<ArchetypeType> archetype = archetypeManager.determineArchetype(object, explicitArchetypeOid, result);

        if (archetype == null) {
            return;
        }

        context.getFocusContext().setArchetype(archetype.asObjectable());
    }

    public <F extends ObjectType> void updateArchetypePolicy(LensContext<F> context, Task task, OperationResult result) throws SchemaException, ConfigurationException {
        if (context.getFocusContext() == null) {
            return;
        }
        ArchetypePolicyType newArchetypePolicy = determineArchetypePolicy(context, task, result);
        if (newArchetypePolicy != context.getFocusContext().getArchetypePolicyType()) {
            if (LOGGER.isTraceEnabled()) {
                LOGGER.trace("Changed policy configuration because of changed subtypes:\n{}",
                        newArchetypePolicy==null?null:newArchetypePolicy.asPrismContainerValue().debugDump(1));
            }
            context.getFocusContext().setArchetypePolicyType(newArchetypePolicy);
        }
    }

    // expects that object policy configuration is already set in focusContext
    public <F extends ObjectType> void updateFocusTemplate(LensContext<F> context, OperationResult result)
            throws ObjectNotFoundException, SchemaException {

        // 1. When this method is called after inbound processing, we might want to change the existing template
        //    (because e.g. subtype or archetype was determined and we want to move from generic to more specific template).
        // 2. On the other hand, if focus template is set up explicitly from the outside (e.g. in synchronization section)
        //    we probably do not want to change it here.
        if (context.getFocusTemplate() != null && context.isFocusTemplateExternallySet()) {
            return;
        }

        String currentOid = context.getFocusTemplate() != null ? context.getFocusTemplate().getOid() : null;
        String newOid;

        LensFocusContext<F> focusContext = context.getFocusContext();
        if (focusContext == null) {
            newOid = null;
        } else {
            ArchetypePolicyType archetypePolicy = focusContext.getArchetypePolicyType();
            if (archetypePolicy == null) {
                LOGGER.trace("No default object template (no policy)");
                newOid = null;
            } else {
                ObjectReferenceType templateRef = archetypePolicy.getObjectTemplateRef();
                if (templateRef == null) {
                    LOGGER.trace("No default object template (no templateRef)");
                    newOid = null;
                } else {
                    newOid = templateRef.getOid();
                }
            }
        }

        LOGGER.trace("current focus template OID = {}, new = {}", currentOid, newOid);
        if (!java.util.Objects.equals(currentOid, newOid)) {
            ObjectTemplateType template;
            if (newOid != null) {
                template = cacheRepositoryService.getObject(ObjectTemplateType.class, newOid, null, result).asObjectable();
            } else {
                template = null;
            }
            context.setFocusTemplate(template);
        }
    }

    private <F extends FocusType> void loadLinkRefs(LensContext<F> context, Task task, OperationResult result)
            throws ObjectNotFoundException, SchemaException, CommunicationException, ConfigurationException,
            SecurityViolationException, PolicyViolationException, ExpressionEvaluationException {

        LOGGER.trace("loadLinkRefs starting");

        LensFocusContext<F> focusContext = context.getFocusContext();
        PrismObject<F> focusCurrent = focusContext.getObjectCurrent();
        if (focusCurrent != null) {
            loadLinkRefsFromFocus(context, focusCurrent, task, result);
            LOGGER.trace("loadLinkRefsFromFocus done");
        }

        context.checkConsistenceIfNeeded();

        loadLinkRefsFromDelta(context, focusCurrent, focusContext, task, result);
        LOGGER.trace("loadLinkRefsFromDelta done");

        context.checkConsistenceIfNeeded();

        loadProjectionContextsSync(context, task, result);
        LOGGER.trace("loadProjectionContextsSync done");

        context.checkConsistenceIfNeeded();
    }

    /**
     * Does not overwrite existing account contexts, just adds new ones.
     */
    private <F extends FocusType> void loadLinkRefsFromFocus(LensContext<F> context, PrismObject<F> focus,
            Task task, OperationResult result) throws ObjectNotFoundException,
            CommunicationException, SchemaException, ConfigurationException, SecurityViolationException, PolicyViolationException, ExpressionEvaluationException {
        PrismReference linkRef = focus.findReference(FocusType.F_LINK_REF);
        if (linkRef == null) {
            return;
        }
        for (PrismReferenceValue linkRefVal : linkRef.getValues()) {
            String oid = linkRefVal.getOid();
            if (!relationRegistry.isMember(linkRefVal.getRelation())) {
                LOGGER.trace("Inactive linkRef: will only refresh it, no processing. Relation={}, ref={}",
                        linkRefVal.getRelation(), linkRefVal);
                refreshInactiveLinkedShadow(oid, context, task, result);
                continue;
            }
            if (StringUtils.isBlank(oid)) {
                LOGGER.trace("Null or empty OID in link reference {} in:\n{}", linkRef,
                        focus.debugDump(1));
                throw new SchemaException("Null or empty OID in link reference in " + focus);
            }
            LensProjectionContext existingAccountContext = findAccountContext(oid, context);
            if (existingAccountContext != null) {
                // TODO: do we need to reload the account inside here? yes we need
                existingAccountContext.setFresh(true);
                continue;
            }
            PrismObject<ShadowType> shadow;
            //noinspection unchecked
            PrismObject<ShadowType> shadowFromLink = linkRefVal.getObject();
            if (shadowFromLink == null) {
                var options = createStandardGetOptions();
                LOGGER.trace("Loading shadow {} from linkRef, options={}", oid, options);
                try {
                    shadow = provisioningService.getObject(ShadowType.class, oid, options, task, result);
                } catch (ObjectNotFoundException e) {
                    // Broken accountRef. We need to mark it for deletion
                    LensProjectionContext projectionContext = getOrCreateEmptyThombstoneProjectionContext(context, oid);
                    projectionContext.setFresh(true);
                    projectionContext.setExists(false);
                    projectionContext.setShadowExistsInRepo(false);
                    OperationResult getObjectSubresult = result.getLastSubresult();
                    getObjectSubresult.setErrorsHandled();
                    continue;
                }
            } else {
                shadow = shadowFromLink;
                // Make sure it has a proper definition. This may come from outside of the model.
                provisioningService.applyDefinition(shadow, task, result);
            }
            ContextAcquisitionResult acqResult = getOrCreateAccountContext(context, shadow, task, result);
            LensProjectionContext projectionContext = acqResult.context;
            if (acqResult.shadowSet) {
                projectionContext.setFresh(projectionContext.getObjectOld() != null); // TODO reconsider
            } else {
                projectionContext.setFresh(true);
                projectionContext.setExists(ShadowUtil.isExists(shadow.asObjectable()));
                if (ShadowUtil.isDead(shadow.asObjectable())) {
                    projectionContext.markTombstone();
                    LOGGER.trace("Loading dead shadow {} for projection {}.", shadow, projectionContext.getHumanReadableName());
                    continue;
                }
                if (projectionContext.isDoReconciliation()) {
                    // Do not load old account now. It will get loaded later in the reconciliation step.
                    continue;
                }
                projectionContext.setLoadedObject(shadow);
            }
        }
    }

    private <F extends FocusType> Collection<SelectorOptions<GetOperationOptions>> createStandardGetOptions() {
        // Using NO_FETCH so we avoid reading in a full account. This is more efficient as we don't need full account here.
        // We need to fetch from provisioning and not repository so the correct definition will be set.
        return schemaService.getOperationOptionsBuilder()
                .noFetch()
                .pointInTime(PointInTimeType.FUTURE)
                .build();
    }

    /**
     * We do this just to restore old behavior that ensures that linked shadows are quick-refreshed,
     * deleting e.g. expired pending operations (see TestMultiResource.test429).
     */
    private void refreshInactiveLinkedShadow(String oid, LensContext<?> context, Task task, OperationResult result) {
        Collection<SelectorOptions<GetOperationOptions>> options;
        if (context.isDoReconciliationForAllProjections()) {
            // Ensures an attempt to complete any pending operations.
            // TODO Shouldn't we include FUTURE option as well? E.g. to avoid failing on not-yet-created accounts?
            //  (Fortunately, we ignore any exceptions but anyway: FUTURE is used in other cases in this class.)
            options = schemaService.getOperationOptionsBuilder()
                    .forceRetry()
                    .build();
        } else {
            // This ensures only minimal processing, e.g. the quick shadow refresh is done.
            options = schemaService.getOperationOptionsBuilder()
                    .noFetch()
                    .pointInTime(PointInTimeType.FUTURE)
                    .build();
        }
        try {
            provisioningService.getObject(ShadowType.class, oid, options, task, result);
        } catch (Exception e) {
            result.muteLastSubresultError();
            LOGGER.debug("Couldn't refresh linked shadow {}. Continuing.", oid, e);
        }
    }

    private <F extends FocusType> void loadLinkRefsFromDelta(LensContext<F> context, PrismObject<F> focus,
            LensFocusContext<F> focusContext, Task task, OperationResult result) throws SchemaException,
            ObjectNotFoundException, CommunicationException, ConfigurationException,
            SecurityViolationException, PolicyViolationException, ExpressionEvaluationException {

        ObjectDelta<F> focusPrimaryDelta = focusContext.getPrimaryDelta();
        if (focusPrimaryDelta == null) {
            return;
        }

        ReferenceDelta linkRefDelta;
        if (focusPrimaryDelta.getChangeType() == ChangeType.ADD) {
            PrismReference linkRef = focusPrimaryDelta.getObjectToAdd().findReference(FocusType.F_LINK_REF);
            if (linkRef == null) {
                // Adding new focus with no linkRef -> nothing to do
                return;
            }
            linkRefDelta = linkRef.createDelta(FocusType.F_LINK_REF);
            linkRefDelta.addValuesToAdd(PrismValueCollectionsUtil.cloneValues(linkRef.getValues()));
        } else if (focusPrimaryDelta.getChangeType() == ChangeType.MODIFY) {
            linkRefDelta = focusPrimaryDelta.findReferenceModification(FocusType.F_LINK_REF);
            if (linkRefDelta == null) {
                return;
            }
        } else {
            // delete, all existing account are already marked for delete
            return;
        }

        if (linkRefDelta.isReplace()) {
            // process "replace" by distributing values to delete and add
            linkRefDelta = linkRefDelta.clone();
            PrismReference linkRef = focus.findReference(FocusType.F_LINK_REF);
            linkRefDelta.distributeReplace(linkRef == null ? null : linkRef.getValues());
        }

        if (linkRefDelta.getValuesToAdd() != null) {
            for (PrismReferenceValue refVal : linkRefDelta.getValuesToAdd()) {
                if (!relationRegistry.isMember(refVal.getRelation())) {
                    LOGGER.trace("loadLinkRefsFromDelta: Skipping inactive linkRef to add (relation={}): {}",
                            refVal.getRelation(), refVal);
                    continue;
                }
                LOGGER.trace("loadLinkRefsFromDelta: Processing value to add: {}", refVal);
                String oid = refVal.getOid();
                LensProjectionContext projectionContext;
                PrismObject<ShadowType> shadow;
                boolean isCombinedAdd = false;
                if (oid == null) {
                    // Adding new account
                    //noinspection unchecked
                    shadow = refVal.getObject();
                    if (shadow == null) {
                        throw new SchemaException("Null or empty OID in account reference " + refVal + " in " + focus);
                    }
                    provisioningService.applyDefinition(shadow, task, result);
                    if (consistencyChecks) ShadowUtil.checkConsistence(shadow, "account from "+linkRefDelta);
                    // Check for conflicting change
                    projectionContext = LensUtil.getProjectionContext(context, shadow, provisioningService, prismContext, task, result);
                    if (projectionContext != null) {
                        // There is already existing context for the same discriminator. Tolerate this only if
                        // the deltas match. It is an error otherwise.
                        ObjectDelta<ShadowType> primaryDelta = projectionContext.getPrimaryDelta();
                        if (primaryDelta == null) {
                            throw new SchemaException("Attempt to add "+shadow+" to a focus that already contains "+
                                    projectionContext.getHumanReadableKind()+" of type '"+
                                    projectionContext.getResourceShadowDiscriminator().getIntent()+"' on "+projectionContext.getResource());
                        }
                        if (!primaryDelta.isAdd()) {
                            throw new SchemaException("Conflicting changes in the context. " +
                                    "Add of linkRef in the focus delta with embedded object conflicts with explicit delta "+primaryDelta);
                        }
                        if (!shadow.equals(primaryDelta.getObjectToAdd())) {
                            throw new SchemaException("Conflicting changes in the context. " +
                                    "Add of linkRef in the focus delta with embedded object is not adding the same object as explicit delta "+primaryDelta);
                        }
                    } else {
                        // Create account context from embedded object
                        projectionContext = createProjectionContext(context, shadow, task, result);
                    }
                    // This is a new account that is to be added. So it should
                    // go to account primary delta
                    ObjectDelta<ShadowType> accountPrimaryDelta = shadow.createAddDelta();
                    projectionContext.setPrimaryDelta(accountPrimaryDelta);
                    projectionContext.setFullShadow(true);
                    projectionContext.setExists(false);
                    isCombinedAdd = true;
                } else {
                    // We have OID. This is either linking of existing account or
                    // add of new account
                    // therefore check for account existence to decide
                    try {
                        // Using NO_FETCH so we avoid reading in a full account. This is more efficient as we don't need full account here.
                        // We need to fetch from provisioning and not repository so the correct definition will be set.
                        GetOperationOptions rootOpts = GetOperationOptions.createNoFetch();
                        rootOpts.setPointInTimeType(PointInTimeType.FUTURE);
                        Collection<SelectorOptions<GetOperationOptions>> options = SelectorOptions.createCollection(rootOpts);
                        shadow = provisioningService.getObject(ShadowType.class, oid, options, task, result);
                        // Create account context from retrieved object
                        projectionContext = getOrCreateAccountContext(context, shadow, task, result).context; // TODO what about shadowSet etc?
                        projectionContext.setLoadedObject(shadow);
                        projectionContext.setExists(ShadowUtil.isExists(shadow.asObjectable()));
                    } catch (ObjectNotFoundException e) {
                        if (refVal.getObject() == null) {
                            // account does not exist, no composite account in
                            // ref -> this is really an error
                            throw e;
                        } else {
                            // New account (with OID)
                            result.muteLastSubresultError();
                            shadow = refVal.getObject();
                            if (!shadow.hasCompleteDefinition()) {
                                provisioningService.applyDefinition(shadow, task, result);
                            }
                            // Create account context from embedded object
                            projectionContext = createProjectionContext(context, shadow, task, result);
                            ObjectDelta<ShadowType> accountPrimaryDelta = shadow.createAddDelta();
                            projectionContext.setPrimaryDelta(accountPrimaryDelta);
                            projectionContext.setFullShadow(true);
                            projectionContext.setExists(false);
                            projectionContext.setShadowExistsInRepo(false);
                            isCombinedAdd = true;
                        }
                    }
                }
                if (context.isDoReconciliationForAllProjections() && !isCombinedAdd) {
                    projectionContext.setDoReconciliation(true);
                }
                projectionContext.setFresh(true);
            }
        }

        if (linkRefDelta.getValuesToDelete() != null) {
            for (PrismReferenceValue refVal : linkRefDelta.getValuesToDelete()) {
                if (!relationRegistry.isMember(refVal.getRelation())) {
                    LOGGER.trace("loadLinkRefsFromDelta: Skipping inactive linkRef to delete (relation={}): {}",
                            refVal.getRelation(), refVal);
                    continue;
                }
                LOGGER.trace("loadLinkRefsFromDelta: Processing value to delete: {}", refVal);
                String oid = refVal.getOid();
                LensProjectionContext projectionContext = null;
                PrismObject<ShadowType> shadow = null;
                if (oid == null) {
                    throw new SchemaException("Cannot delete account ref without an oid in " + focus);
                } else {
                    try {
                        // Using NO_FETCH so we avoid reading in a full account. This is more efficient as we don't need full account here.
                        // We need to fetch from provisioning and not repository so the correct definition will be set.
                        Collection<SelectorOptions<GetOperationOptions>> options = SelectorOptions.createCollection(GetOperationOptions.createNoFetch());
                        shadow = provisioningService.getObject(ShadowType.class, oid, options, task, result);
                        // Create account context from retrieved object
                        projectionContext = getOrCreateAccountContext(context, shadow, task, result).context; // TODO what about shadowSet etc?
                        projectionContext.setLoadedObject(shadow);
                        projectionContext.setExists(ShadowUtil.isExists(shadow.asObjectable()));
                        LOGGER.trace("Loaded projection context: {}", projectionContext);
                    } catch (ObjectNotFoundException e) {
                        try {
                            LOGGER.trace("Broken linkRef? We need to try again with raw options, because the error could be "
                                    + "thrown because of non-existent resource", e);
                            Collection<SelectorOptions<GetOperationOptions>> options = SelectorOptions.createCollection(GetOperationOptions.createRaw());
                            shadow = provisioningService.getObject(ShadowType.class, oid, options, task, result);
                            projectionContext = getOrCreateEmptyThombstoneProjectionContext(context, oid);
                            projectionContext.setFresh(true);
                            projectionContext.setExists(false);
                            projectionContext.setShadowExistsInRepo(false);
                            LOGGER.trace("Loaded projection context: {}", projectionContext);
                            OperationResult getObjectSubresult = result.getLastSubresult();
                            getObjectSubresult.setErrorsHandled();
                        } catch (ObjectNotFoundException ex) {
                            // This is still OK. It means deleting an accountRef
                            // that points to non-existing object
                            // just log a warning
                            LOGGER.warn("Deleting accountRef of " + focus + " that points to non-existing OID " + oid);
                        }
                    }
                }
                if (projectionContext != null) {
                    if (refVal.getObject() == null) {
                        projectionContext.setSynchronizationIntent(SynchronizationIntent.UNLINK);
                    } else {
                        // I.e. this is when we request to delete link containing full object.
                        projectionContext.setSynchronizationIntent(SynchronizationIntent.DELETE);
                        ObjectDelta<ShadowType> accountPrimaryDelta = shadow.createDeleteDelta();
                        projectionContext.setPrimaryDelta(accountPrimaryDelta);
                    }
                    projectionContext.setFresh(true);
                }
            }
        }

        // remove the accountRefs without oid. These will get into the way now.
        // The accounts
        // are in the context now and will be linked at the end of the process
        // (it they survive the policy)
        // We need to make sure this happens on the real primary focus delta

        ObjectDelta<F> primaryDeltaToUpdate;
        if (focusPrimaryDelta.isImmutable()) {
            primaryDeltaToUpdate = focusPrimaryDelta.clone();
            focusContext.setPrimaryDelta(primaryDeltaToUpdate);
        } else {
            primaryDeltaToUpdate = focusPrimaryDelta;
        }

        if (primaryDeltaToUpdate.getChangeType() == ChangeType.ADD) {
            primaryDeltaToUpdate.getObjectToAdd().removeReference(FocusType.F_LINK_REF);
        } else if (primaryDeltaToUpdate.getChangeType() == ChangeType.MODIFY) {
            primaryDeltaToUpdate.removeReferenceModification(FocusType.F_LINK_REF);
        }
        // It is little bit questionable whether we need to make primary delta immutable. It makes some sense, but I am not sure.
        // Note that (as a side effect) this can make "focus new" immutable as well, in the case of ADD delta.
        primaryDeltaToUpdate.freeze();
    }

    private <F extends ObjectType> void loadProjectionContextsSync(LensContext<F> context, Task task, OperationResult result)
            throws SchemaException, ObjectNotFoundException, CommunicationException, ConfigurationException,
            SecurityViolationException, ExpressionEvaluationException {
        for (LensProjectionContext projCtx : context.getProjectionContexts()) {
            if (projCtx.isFresh() && projCtx.getObjectCurrent() != null) {
                // already loaded
                continue;
            }
            ObjectDelta<ShadowType> syncDelta = projCtx.getSyncDelta();
            if (syncDelta != null) {
                if (projCtx.isDoReconciliation()) {
                    // Do not load old account now. It will get loaded later in the reconciliation step. Just mark it as fresh.
                    projCtx.setFresh(true);
                    continue;
                }
                String oid = syncDelta.getOid();
                PrismObject<ShadowType> shadow = null;

                if (syncDelta.getChangeType() == ChangeType.ADD) {
                    shadow = syncDelta.getObjectToAdd().clone();
                    projCtx.setLoadedObject(shadow);
                    projCtx.setExists(ShadowUtil.isExists(shadow.asObjectable()));

                } else {

                    if (oid == null) {
                        throw new IllegalArgumentException("No OID in sync delta in " + projCtx);
                    }
                    // Using NO_FETCH so we avoid reading in a full account. This is more efficient as we don't need full account
                    // here. We need to fetch from provisioning and not repository so the correct definition will be set.
                    GetOperationOptions option = GetOperationOptions.createNoFetch();
                    option.setDoNotDiscovery(true);
                    option.setPointInTimeType(PointInTimeType.FUTURE);
                    Collection<SelectorOptions<GetOperationOptions>> options = SelectorOptions.createCollection(option);

                    try {

                        shadow = provisioningService.getObject(ShadowType.class, oid, options, task, result);

                    } catch (ObjectNotFoundException e) {
                        LOGGER.trace("Loading shadow {} from sync delta failed: not found", oid);
                        projCtx.setExists(false);
                        projCtx.setObjectCurrent(null);
                        projCtx.setShadowExistsInRepo(false);
                    }

                    // We will not set old account if the delta is delete. The account does not really exists now.
                    // (but the OID and resource will be set from the repo shadow)
                    if (syncDelta.getChangeType() == ChangeType.DELETE) {
                        projCtx.markTombstone();
                    } else if (shadow != null) {
                        syncDelta.applyTo(shadow);
                        projCtx.setLoadedObject(shadow);
                        projCtx.setExists(ShadowUtil.isExists(shadow.asObjectable()));
                    }
                }

                // Make sure OID is set correctly
                projCtx.setOid(oid);
                // Make sure that resource is also resolved
                if (projCtx.getResource() == null && shadow != null) {
                    String resourceOid = ShadowUtil.getResourceOid(shadow.asObjectable());
                    if (resourceOid == null) {
                        throw new IllegalArgumentException("No resource OID in " + shadow);
                    }
                    ResourceType resourceType = LensUtil.getResourceReadOnly(context, resourceOid, provisioningService, task, result);
                    projCtx.setResource(resourceType);
                }
                projCtx.setFresh(true);
            }
        }
    }

    @Experimental // This is a temporary solution: TODO factor out context acquisition into separate class.
    private static class ContextAcquisitionResult {
        private final LensProjectionContext context;
        private final boolean created;
        private final boolean shadowSet;

        private ContextAcquisitionResult(LensProjectionContext context, boolean created, boolean shadowSet) {
            this.context = context;
            this.created = created;
            this.shadowSet = shadowSet;
        }

        private static ContextAcquisitionResult existing(LensProjectionContext ctx) {
            return new ContextAcquisitionResult(ctx, false, false);
        }
    }

    private <F extends FocusType> ContextAcquisitionResult getOrCreateAccountContext(LensContext<F> context,
            PrismObject<ShadowType> projection, Task task, OperationResult result) throws ObjectNotFoundException,
            CommunicationException, SchemaException, ConfigurationException, SecurityViolationException, PolicyViolationException, ExpressionEvaluationException {
        ShadowType shadowType = projection.asObjectable();
        String resourceOid = ShadowUtil.getResourceOid(shadowType);
        if (resourceOid == null) {
            throw new SchemaException("The " + projection + " has null resource reference OID");
        }

        LensProjectionContext existingContext = context.findProjectionContextByOid(shadowType.getOid());
        LOGGER.trace("Projection context by shadow OID: {} yielded: {}", shadowType.getOid(), existingContext);
        if (existingContext != null) {
            return ContextAcquisitionResult.existing(existingContext);
        }

        String intent = ShadowUtil.getIntent(shadowType);
        ShadowKindType kind = ShadowUtil.getKind(shadowType);
        ResourceType resource = LensUtil.getResourceReadOnly(context, resourceOid, provisioningService, task, result);
        intent = LensUtil.refineProjectionIntent(kind, intent, resource, prismContext);
        boolean tombstone = ShadowUtil.isDead(shadowType);
        ResourceShadowDiscriminator rsd = new ResourceShadowDiscriminator(resourceOid, kind, intent, shadowType.getTag(), tombstone);
        LensUtil.GetOrCreateProjectionContextResult mainCtxResult = LensUtil.getOrCreateProjectionContext(context, rsd);
        LensProjectionContext mainCtx = mainCtxResult.context;
        LOGGER.trace("Projection context for {}: {} (created = {})", rsd, context, mainCtxResult.created);

        if (mainCtx.getOid() == null) {
            mainCtx.setOid(projection.getOid());
        } else if (projection.getOid() != null && !mainCtx.getOid().equals(projection.getOid())) {
            // Conflict. We have existing projection and another project that is added (with the same discriminator).
            // Chances are that the old object is already deleted (e.g. during rename). So let's be
            // slightly inefficient here and check for resource object existence.
            try {
                GetOperationOptions rootOpt = GetOperationOptions.createPointInTimeType(PointInTimeType.FUTURE);
                rootOpt.setDoNotDiscovery(true);
                Collection<SelectorOptions<GetOperationOptions>> opts = SelectorOptions.createCollection(rootOpt);
                LOGGER.trace("Projection conflict detected, existing: {}, new {}", mainCtx.getOid(), projection.getOid());
                PrismObject<ShadowType> existingShadow = provisioningService.getObject(ShadowType.class, mainCtx.getOid(), opts, task, result);
                // Maybe it is the other way around
                try {
                    PrismObject<ShadowType> newShadow = provisioningService.getObject(ShadowType.class, projection.getOid(), opts, task, result);
                    // Obviously, two projections with the same discriminator exist.
                    LOGGER.trace("Projection {} already exists in context\nExisting:\n{}\nNew:\n{}", rsd,
                            existingShadow.debugDumpLazily(1), newShadow.debugDumpLazily(1));
                    if (!ShadowUtil.isDead(newShadow.asObjectable())) {
                        throw new PolicyViolationException("Projection "+rsd+" already exists in context (existing "+existingShadow+", new "+projection);
                    }
                    // Dead shadow. This is somehow expected, fix it and we can go on.
                    rsd.setTombstone(true);
                    LensUtil.GetOrCreateProjectionContextResult newShadowCtxResult =
                            LensUtil.getOrCreateProjectionContext(context, rsd);
                    LensProjectionContext newShadowCtx = newShadowCtxResult.context;
                    newShadowCtx.setExists(ShadowUtil.isExists(newShadow.asObjectable()));
                    newShadowCtx.setFullShadow(false);
                    newShadowCtx.setLoadedObject(newShadow); // TODO ok even if we reused existing context?
                    newShadowCtx.setOid(newShadow.getOid());
                    return new ContextAcquisitionResult(newShadowCtx, newShadowCtxResult.created, true);
                } catch (ObjectNotFoundException e) {
                    // This is somehow expected, fix it and we can go on.
                    result.muteLastSubresultError();
                    // We have to create new context in this case, but it has to have tombstone set.
                    rsd.setTombstone(true);
                    LensUtil.GetOrCreateProjectionContextResult noNewShadowCtxResult =
                            LensUtil.getOrCreateProjectionContext(context, rsd);
                    LensProjectionContext noNewShadowCtx = noNewShadowCtxResult.context;
                    // We have to mark it as dead right now, otherwise the uniqueness check may fail.
                    markShadowDead(projection.getOid(), result); // But we know the shadow does not exist in repo! So how do we mark it as dead?
                    noNewShadowCtx.setShadowExistsInRepo(false);
                    return new ContextAcquisitionResult(noNewShadowCtx, noNewShadowCtxResult.created, true); // true = no need to set the shadow by caller
                }
            } catch (ObjectNotFoundException e) {
                // This is somehow expected, fix it and we can go on.
                result.muteLastSubresultError();
                String shadowOid = mainCtx.getOid();
                mainCtx.getResourceShadowDiscriminator().setTombstone(true);
                LensUtil.GetOrCreateProjectionContextResult newCtxResult = LensUtil.getOrCreateProjectionContext(context, rsd);
                LensProjectionContext newCtx = newCtxResult.context;
                newCtx.setShadowExistsInRepo(false);
                // We have to mark it as dead right now, otherwise the uniqueness check may fail.
                markShadowDead(shadowOid, result);
                return new ContextAcquisitionResult(newCtx, newCtxResult.created, true); // no need to set the object
            }
        }
        return new ContextAcquisitionResult(mainCtx, mainCtxResult.created, false);
    }

    private void markShadowDead(String oid, OperationResult result) {
        if (oid == null) {
            // nothing to mark
            return;
        }
        Collection<? extends ItemDelta<?, ?>> modifications = MiscSchemaUtil.createCollection(
                prismContext.deltaFactory().property().createReplaceDelta(prismContext.getSchemaRegistry().findObjectDefinitionByCompileTimeClass(ShadowType.class),
                ShadowType.F_DEAD, true));
        try {
            cacheRepositoryService.modifyObject(ShadowType.class, oid, modifications, result);
            // TODO report to task?
        } catch (ObjectNotFoundException e) {
            // Done already
            result.muteLastSubresultError();
        } catch (ObjectAlreadyExistsException | SchemaException e) {
            // Should not happen
            throw new SystemException(e.getMessage(), e);
        }
    }

    private <F extends FocusType> LensProjectionContext createProjectionContext(LensContext<F> context,
                                                                                PrismObject<ShadowType> account, Task task, OperationResult result) throws ObjectNotFoundException,
            CommunicationException, SchemaException, ConfigurationException, SecurityViolationException, ExpressionEvaluationException {
        ShadowType shadowType = account.asObjectable();
        String resourceOid = ShadowUtil.getResourceOid(shadowType);
        if (resourceOid == null) {
            throw new SchemaException("The " + account + " has null resource reference OID");
        }
        String intent = ShadowUtil.getIntent(shadowType);
        ShadowKindType kind = ShadowUtil.getKind(shadowType);
        ResourceType resource = LensUtil.getResourceReadOnly(context, resourceOid, provisioningService, task, result);
        String accountIntent = LensUtil.refineProjectionIntent(kind, intent, resource, prismContext);
        ResourceShadowDiscriminator rsd = new ResourceShadowDiscriminator(resourceOid, kind, accountIntent, shadowType.getTag(), false);
        LensProjectionContext existingProjectionContext = context.findProjectionContext(rsd);
        if (existingProjectionContext != null) {
            throw new SchemaException("Attempt to add "+account+" to a focus that already contains projection of type '"+accountIntent+"' on "+resource);
        }
        LensProjectionContext newProjectionContext = context.createProjectionContext(rsd);
        newProjectionContext.setResource(resource);
        newProjectionContext.setOid(account.getOid());
        return newProjectionContext;
    }

    private <F extends ObjectType> LensProjectionContext findAccountContext(String accountOid, LensContext<F> context) {
        for (LensProjectionContext accContext : context.getProjectionContexts()) {
            if (accountOid.equals(accContext.getOid())) {
                return accContext;
            }
        }

        return null;
    }

    private <F extends ObjectType> LensProjectionContext getOrCreateEmptyThombstoneProjectionContext(LensContext<F> context,
            String missingShadowOid) {
        LensProjectionContext projContext = context.findProjectionContextByOid(missingShadowOid);
        if (projContext == null) {
            projContext = context.createProjectionContext(null);
            projContext.setOid(missingShadowOid);
        }

        if (projContext.getResourceShadowDiscriminator() == null) {
            projContext.setResourceShadowDiscriminator(new ResourceShadowDiscriminator(null, null, null, null, true));
        } else {
            projContext.markTombstone();
        }

        projContext.setFullShadow(false);
        projContext.setObjectCurrent(null);

        return projContext;
    }

    /**
     * Check reconcile flag in account sync context and set accountOld
     * variable if it's not set (from provisioning), load resource (if not set already), etc.
     */
    private <F extends ObjectType> void finishLoadOfProjectionContext(LensContext<F> context,
            LensProjectionContext projContext, Task task, OperationResult result)
            throws ObjectNotFoundException, CommunicationException, SchemaException, ConfigurationException,
            SecurityViolationException, ExpressionEvaluationException {

        if (projContext.getSynchronizationPolicyDecision() == SynchronizationPolicyDecision.BROKEN) {
            LOGGER.trace("Skipping loading of broken context {}", projContext.getHumanReadableName());
            result.recordNotApplicable();
            return;
        }

        // We could skip loading if the projection is completed, but it would cause problems e.g. in wasProvisioned
        // method in dependency processor (it checks objectCurrent, among other things). So let's be conservative
        // and load also completed projections.

        // MID-2436 (volatile objects) - as a quick but effective hack, we set reconciliation:=TRUE for volatile accounts
        ResourceObjectTypeDefinitionType objectDefinition = projContext.getResourceObjectTypeDefinitionType();
        if (objectDefinition != null && objectDefinition.getVolatility() == ResourceObjectVolatilityType.UNPREDICTABLE && !projContext.isDoReconciliation()) {
            LOGGER.trace("Resource object volatility is UNPREDICTABLE => setting doReconciliation to TRUE for {}", projContext.getResourceShadowDiscriminator());
            projContext.setDoReconciliation(true);
        }

        // Remember OID before the object could be wiped
        String projectionObjectOid = projContext.getOid();
        if (projContext.isDoReconciliation() && !projContext.isFullShadow()) {
            // The current object is useless here. So lets just wipe it so it will get loaded
            projContext.setObjectCurrent(null);
        }

        // Load current object
        boolean tombstone = false;
        PrismObject<ShadowType> projectionObject = projContext.getObjectCurrent();
        if (projContext.getObjectCurrent() == null || needToReload(context, projContext)) {
            if (projContext.isAdd() && !projContext.isCompleted()) {
                // No need to load old object, there is none
                projContext.setExists(false);
                projContext.recompute();
                projectionObject = projContext.getObjectNew();
            } else {
                if (projectionObjectOid == null) {
                    projContext.setExists(false);
                    if (projContext.getResourceShadowDiscriminator() == null || projContext.getResourceShadowDiscriminator().getResourceOid() == null) {
                        throw new SystemException(
                                "Projection "+projContext.getHumanReadableName()+" with null OID, no representation and no resource OID in account sync context "+projContext);
                    }
                } else {
                    Collection<SelectorOptions<GetOperationOptions>> options = createProjectionLoadingOptions(projContext);
                    LOGGER.trace("Loading shadow {} for projection {}, options={}", projectionObjectOid,
                            projContext.getHumanReadableName(), options);

                    try {
                        PrismObject<ShadowType> objectOld = provisioningService.getObject(
                                projContext.getObjectTypeClass(), projectionObjectOid, options, task, result);
                        GetOperationOptions rootOptions = SelectorOptions.findRootOptions(options);
                        if (!GetOperationOptions.isNoFetch(rootOptions) && !GetOperationOptions.isRaw(rootOptions)) {
                            LOGGER.trace("Full shadow loaded for {}:\n{}", projContext.getHumanReadableName(), objectOld.debugDumpLazily(1));
                        }
                        Validate.notNull(objectOld.getOid());
                        if (InternalsConfig.consistencyChecks) {
                            String resourceOid = projContext.getResourceOid();
                            if (resourceOid != null && !resourceOid.equals(objectOld.asObjectable().getResourceRef().getOid())) {
                                throw new IllegalStateException("Loaded shadow with wrong resourceRef. Loading shadow "+projectionObjectOid+", got "+
                                        objectOld.getOid()+", expected resourceRef "+resourceOid+", but was "+objectOld.asObjectable().getResourceRef().getOid()+
                                        " for context "+projContext.getHumanReadableName());
                            }
                        }
                        projContext.setLoadedObject(objectOld);
                        if (projContext.isDoReconciliation()) {
                            projContext.determineFullShadowFlag(objectOld);
                        } else {
                            projContext.setFullShadow(false);
                        }
                        projectionObject = objectOld;
                        if (ShadowUtil.isExists(objectOld.asObjectable())) {
                            projContext.setExists(true);
                        } else {
                            projContext.setExists(false);
                            if (ShadowUtil.isDead(objectOld.asObjectable())) {
                                projContext.markTombstone();
                                LOGGER.debug("Found only dead {} for projection context {}.", objectOld,
                                        projContext.getHumanReadableName());
                                tombstone = true;
                            } else {
                                LOGGER.debug("Found only non-existing but non-dead {} for projection context {}.", objectOld,
                                        projContext.getHumanReadableName());
                                // TODO Should we somehow mark this in the projection context?
                            }
                        }

                    } catch (ObjectNotFoundException ex) {
                        LOGGER.debug("Could not find object with oid {} for projection context {}.", projectionObjectOid, projContext.getHumanReadableName());
                        // This does not mean BROKEN. The projection was there, but it gone now.
                        // Consistency mechanism might have kicked in and fixed the shadow.
                        // What we really want here is a tombstone projection or a refreshed projection.
                        result.muteLastSubresultError();
                        projContext.setShadowExistsInRepo(false);
                        refreshContextAfterShadowNotFound(context, projContext, options, task, result);

                    } catch (CommunicationException | SchemaException | ConfigurationException | SecurityViolationException
                            | RuntimeException | Error e) {

                        LOGGER.warn("Problem while getting object with oid {}. Projection context {} is marked as broken: {}: {}",
                                projectionObjectOid, projContext.getHumanReadableName(), e.getClass().getSimpleName(), e.getMessage());
                        projContext.setSynchronizationPolicyDecision(SynchronizationPolicyDecision.BROKEN);

                        ResourceType resourceType = projContext.getResource();
                        if (resourceType == null) {
                            throw e;
                        } else {
                            ErrorSelectorType errorSelector = ResourceTypeUtil.getConnectorErrorCriticality(resourceType);
                            if (errorSelector == null) {
                                if (e instanceof SchemaException) {
                                    // Just continue evaluation. The error is recorded in the result.
                                    // The consistency mechanism has (most likely) already done the best.
                                    // We cannot do any better.
                                    return;
                                } else {
                                    throw e;
                                }
                            } else {
                                CriticalityType criticality = ExceptionUtil.getCriticality(errorSelector, e, CriticalityType.FATAL);
                                if (criticality == CriticalityType.FATAL) {
                                    throw e;
                                } else {
                                    return;
                                }
                            }
                        }
                    }

                }
                projContext.setFresh(true);
            }
        } else {
            projectionObject = projContext.getObjectCurrent();
            if (projectionObjectOid != null) {
                projContext.setExists(ShadowUtil.isExists(projectionObject.asObjectable()));
            }
        }


        // Determine Resource
        ResourceType resourceType = projContext.getResource();
        String resourceOid = null;
        if (resourceType == null) {
            if (projectionObject != null) {
                ShadowType shadowType = projectionObject.asObjectable();
                resourceOid = ShadowUtil.getResourceOid(shadowType);
            } else if (projContext.getResourceShadowDiscriminator() != null) {
                resourceOid = projContext.getResourceShadowDiscriminator().getResourceOid();
            } else if (!tombstone) {
                throw new IllegalStateException("No shadow, no discriminator and not tombstone? That won't do. Projection "+projContext.getHumanReadableName());
            }
        } else {
            resourceOid = resourceType.getOid();
        }

        // Determine discriminator
        ResourceShadowDiscriminator discr = projContext.getResourceShadowDiscriminator();
        if (discr == null) {
            if (projectionObject != null) {
                ShadowType accountShadowType = projectionObject.asObjectable();
                String intent = ShadowUtil.getIntent(accountShadowType);
                ShadowKindType kind = ShadowUtil.getKind(accountShadowType);
                discr = new ResourceShadowDiscriminator(resourceOid, kind, intent, accountShadowType.getTag(), tombstone);
            } else {
                discr = new ResourceShadowDiscriminator(null, null, null, null, tombstone);
            }
            projContext.setResourceShadowDiscriminator(discr);
        } else {
            if (tombstone) {
                // We do not want to reset tombstone flag if it was set before
                projContext.markTombstone();
            }
        }

        // Load resource
        if (resourceType == null && resourceOid != null) {
            resourceType = LensUtil.getResourceReadOnly(context, resourceOid, provisioningService, task, result);
            projContext.setResource(resourceType);
        }

        //Determine refined schema and password policies for account type
        RefinedObjectClassDefinition structuralObjectClassDef = projContext.getStructuralObjectClassDefinition();
        if (structuralObjectClassDef != null) {
            LOGGER.trace("Finishing loading of projection context: security policy");
            SecurityPolicyType projectionSecurityPolicy = securityHelper.locateProjectionSecurityPolicy(projContext.getStructuralObjectClassDefinition(), task, result);
            LOGGER.trace("Located security policy for: {},\n {}", projContext, projectionSecurityPolicy);
            projContext.setProjectionSecurityPolicy(projectionSecurityPolicy);
        } else {
            LOGGER.trace("No structural object class definition, skipping determining security policy");
        }

        //set limitation, e.g. if this projection context should be recomputed and processed by projector
        if (ModelExecuteOptions.isLimitPropagation(context.getOptions())){
            if (context.getTriggeredResourceOid() != null){
                if (!context.getTriggeredResourceOid().equals(resourceOid)){
                    projContext.setCanProject(false);
                }
            }
        }

        setPrimaryDeltaOldValue(projContext);
    }

    private Collection<SelectorOptions<GetOperationOptions>> createProjectionLoadingOptions(LensProjectionContext projContext) {
        GetOperationOptionsBuilder builder = schemaService.getOperationOptionsBuilder()
                .pointInTime(PointInTimeType.FUTURE)
                .allowNotFound();

        LensContext<?> context = projContext.getLensContext();

        // Most probably reconciliation for all projections implies reconciliation for projContext
        // but we include both conditions just to be sure.
        if (projContext.isDoReconciliation() || context.isDoReconciliationForAllProjections()) {
            builder = builder.forceRefresh();

            // We force operation retry "in hard way" only if we do full-scale reconciliation AND we are starting the clockwork.
            // This is to avoid useless repetition of retries (pushing attempt number quickly too high).
            if (context.isDoReconciliationForAllProjections() && context.getProjectionWave() == 0) {
                builder = builder.forceRetry();
            }

            if (SchemaConstants.CHANNEL_DISCOVERY_URI.equals(context.getChannel())) {
                // Avoid discovery loops
                builder = builder.doNotDiscovery();
            }
        } else {
            builder = builder.noFetch();
        }

        return builder.build();
    }

    private <F extends ObjectType> boolean needToReload(LensContext<F> context,
            LensProjectionContext projContext) {
        ResourceShadowDiscriminator discr = projContext.getResourceShadowDiscriminator();
        if (discr == null) {
            return false;
        }
        // This is kind of brutal. But effective. We are reloading all higher-order dependencies
        // before they are processed. This makes sure we have fresh state when they are re-computed.
        // Because higher-order dependencies may have more than one projection context and the
        // changes applied to one of them are not automatically reflected on on other. therefore we need to reload.
        if (discr.getOrder() == 0) {
            return false;
        }
        int executionWave = context.getExecutionWave();
        int projCtxWave = projContext.getWave();
        if (executionWave == projCtxWave - 1) {
            // Reload right before its execution wave
            return true;
        }
        return false;
    }

    private <F extends ObjectType> void fullCheckConsistence(LensContext<F> context) {
        context.checkConsistence();
        for (LensProjectionContext projectionContext: context.getProjectionContexts()) {
            if (projectionContext.getSynchronizationPolicyDecision() == SynchronizationPolicyDecision.BROKEN) {
                continue;
            }
            if (projectionContext.getResourceShadowDiscriminator() == null) {
                throw new IllegalStateException("No discriminator in "+projectionContext);
            }
        }
    }

    public <F extends ObjectType> void loadFullShadow(LensContext<F> context, LensProjectionContext projCtx, String reason, Task task, OperationResult parentResult)
            throws ObjectNotFoundException, CommunicationException, SchemaException, ConfigurationException, SecurityViolationException, ExpressionEvaluationException {
        if (projCtx.isFullShadow()) {
            // already loaded
            return;
        }
        if (projCtx.isTombstone()) {
            // loading is futile
            return;
        }
        String oid = projCtx.getOid();
        if (oid == null) {
            if (projCtx.isAdd()) {
                // nothing to load yet
                return;
            }
            if (projCtx.getWave() > context.getExecutionWave()) {
                // will be dealt with later
                return;
            }
            if (projCtx.getWave() == context.getExecutionWave() && projCtx.getSynchronizationPolicyDecision() == null) {
                // probably will be created (activation was not run yet)
                return;
            }
        }
        OperationResult result = parentResult.subresult(CLASS_DOT + "loadFullShadow")
                .setMinor()
                .addParam("reason", reason)
                .build();
        FullShadowLoadedTraceType trace;
        if (result.isTracingAny(FullShadowLoadedTraceType.class)) {
            trace = new FullShadowLoadedTraceType(prismContext);
            if (result.isTracingNormal(FullShadowLoadedTraceType.class)) {
                trace.setInputLensContextText(context.debugDump());
                ResourceType resource = projCtx.getResource();
                PolyStringType name = resource != null ? resource.getName() : null;
                trace.setResourceName(name != null ? name : PolyStringType.fromOrig(projCtx.getResourceOid()));
            }
            trace.setInputLensContext(context.toLensContextType(getExportType(trace, result)));
            trace.setReason(reason);
            result.addTrace(trace);
        } else {
            trace = null;
        }
        try {
            if (projCtx.isHigherOrder()) {
                // It may be just too early to load the projection
                if (LensUtil.hasLowerOrderContext(context, projCtx) && context.getExecutionWave() < projCtx.getWave()) {
                    // We cannot reliably load the context now
                    result.addReturn(DEFAULT, "too early");
                    return;
                }
            }

            GetOperationOptions getOptions = GetOperationOptions.createAllowNotFound();
            getOptions.setPointInTimeType(PointInTimeType.FUTURE);
            if (projCtx.isDoReconciliation()) {
                getOptions.setForceRefresh(true);
            }
            if (SchemaConstants.CHANNEL_DISCOVERY_URI.equals(context.getChannel())) {
                LOGGER.trace("Loading full resource object {} from provisioning - with doNotDiscover to avoid loops; reason: {}",
                        projCtx, reason);
                // Avoid discovery loops
                getOptions.setDoNotDiscovery(true);
            } else {
                LOGGER.trace("Loading full resource object {} from provisioning (discovery enabled), reason: {}, channel: {}",
                        projCtx, reason, context.getChannel());
            }
            Collection<SelectorOptions<GetOperationOptions>> options = SelectorOptions.createCollection(getOptions);
            applyAttributesToGet(projCtx, options);
            try {
                if (oid == null) {
                    throw new IllegalStateException("Trying to load shadow with null OID (reason for load: " + reason + ") for "
                            + projCtx.getHumanReadableName());
                }
                PrismObject<ShadowType> objectCurrent = provisioningService
                        .getObject(ShadowType.class, oid, options, task, result);
                Validate.notNull(objectCurrent.getOid());
                if (trace != null) {
                    trace.setShadowLoadedRef(ObjectTypeUtil.createObjectRefWithFullObject(objectCurrent, prismContext));
                }
                // TODO: use setLoadedObject() instead?
                projCtx.setObjectCurrent(objectCurrent);
                projCtx.determineFullShadowFlag(objectCurrent);
                AdministrativeAvailabilityStatusType resourceAdministrativeAvailabilityStatus = ResourceTypeUtil.getAdministrativeAvailabilityStatus(projCtx.getResource());
                if (ShadowUtil.isExists(objectCurrent.asObjectable()) || AdministrativeAvailabilityStatusType.MAINTENANCE == resourceAdministrativeAvailabilityStatus) {
                    result.addReturn(DEFAULT, "found");
                } else {
                    LOGGER.debug("Load of full resource object {} ended with non-existent shadow (options={})", projCtx,
                            getOptions);
                    projCtx.setExists(false);
                    refreshContextAfterShadowNotFound(context, projCtx, options, task, result);
                    result.addReturn(DEFAULT, "not found");
                }

            } catch (ObjectNotFoundException ex) {
                LOGGER.debug("Load of full resource object {} ended with ObjectNotFoundException (options={})", projCtx,
                        getOptions);
                result.muteLastSubresultError();
                projCtx.setShadowExistsInRepo(false);
                refreshContextAfterShadowNotFound(context, projCtx, options, task, result);
                result.addReturn(DEFAULT, "not found");
            }

            projCtx.recompute();

            LOGGER.trace("Loaded full resource object:\n{}", projCtx.debugDumpLazily(1));
        } catch (Throwable t) {
            result.recordFatalError(t);
            throw t;
        } finally {
            if (trace != null) {
                if (result.isTracingNormal(FullShadowLoadedTraceType.class)) {
                    trace.setOutputLensContextText(context.debugDump());
                }
                trace.setOutputLensContext(context.toLensContextType(getExportType(trace, result)));
            }
            result.computeStatusIfUnknown();
        }
    }

    private <F extends ObjectType> void refreshContextAfterShadowNotFound(LensContext<F> context, LensProjectionContext projCtx,
            Collection<SelectorOptions<GetOperationOptions>> options, Task task, OperationResult result)
            throws ObjectNotFoundException, SchemaException, CommunicationException, ConfigurationException,
            ExpressionEvaluationException {
        if (projCtx.isDelete()) {
            // This is OK: shadow was deleted, but we will continue in processing with the old shadow.
            // And we set it as full to prevent from other full loading.
            projCtx.setFullShadow(true);
            return;
        }

        LOGGER.trace("Trying to compensate for ObjectNotFoundException.");
        boolean compensated;
        if (!GetOperationOptions.isDoNotDiscovery(SelectorOptions.findRootOptions(options))) {
            // The account might have been re-created by the discovery.
            // Reload focus, try to find out if there is a new matching link (and the old is gone)
            LOGGER.trace(" -> reloading the focus with the goal of finding matching link");
            compensated = reloadFocusAndFindMatchingLink(context, projCtx, task, result);
        } else {
            LOGGER.trace(" -> discovery is disabled, no point in reloading the focus");
            compensated = false;
        }

        if (compensated) {
            LOGGER.trace("ObjectNotFound error is compensated, continuing");
        } else {
            LOGGER.trace("ObjectNotFound error is not compensated, setting context to tombstone");
            projCtx.markTombstone();
        }
    }

    private <F extends ObjectType> boolean reloadFocusAndFindMatchingLink(LensContext<F> context, LensProjectionContext projCtx,
            Task task, OperationResult result) throws SchemaException, ObjectNotFoundException, CommunicationException,
            ConfigurationException, ExpressionEvaluationException {
        PrismObject<F> reloadedFocusObject = reloadFocus(context, task, result);
        if (reloadedFocusObject == null) {
            return false; // reason already logged
        }
        FocusType reloadedFocus = (FocusType) reloadedFocusObject.asObjectable();
        // TODO shouldn't we put the reloaded focus back into the focus context?

        LensFocusContext<F> focusContext = context.getFocusContextRequired();
        for (ObjectReferenceType linkRef: reloadedFocus.getLinkRef()) {
            if (linkRef.getOid().equals(projCtx.getOid())) {
                // The deleted shadow is still in the linkRef. This should not happen, but it obviously happens sometimes.
                // Maybe some strange race condition? Anyway, we want a robust behavior and this linkRef should NOT be there.
                // So simple remove it.
                LOGGER.warn("The OID "+ projCtx.getOid()+" of deleted shadow still exists in the linkRef after discovery ("+reloadedFocusObject+"), removing it");
                ReferenceDelta unlinkDelta = prismContext.deltaFactory().reference().createModificationDelete(
                        FocusType.F_LINK_REF, focusContext.getObjectDefinition(), linkRef.asReferenceValue().clone());
                focusContext.swallowToSecondaryDelta(unlinkDelta); // TODO will it work even with not-updated focus current?
                continue;
            }
            boolean found = context.getProjectionContexts().stream()
                    .anyMatch(pCtx -> linkRef.getOid().equals(pCtx.getOid()));
            if (found) {
                // This linkRef corresponds to an existing projection context.
                // Continue searching.
                continue;
            }

            // This linkRef is new, it is not in the existing lens context. Is it matching?
            PrismObject<ShadowType> newLinkRepoShadow;
            try {
                newLinkRepoShadow = cacheRepositoryService.getObject(ShadowType.class, linkRef.getOid(), null, result);
            } catch (ObjectNotFoundException e) {
                LoggingUtils.logExceptionAsWarning(LOGGER, "Couldn't resolve {}, unlinking it from the focus {}",
                        e, linkRef.getOid(), reloadedFocus);
                ReferenceDelta unlinkDelta = prismContext.deltaFactory().reference().createModificationDelete(
                        FocusType.F_LINK_REF, focusContext.getObjectDefinition(), linkRef.asReferenceValue().clone());
                focusContext.swallowToSecondaryDelta(unlinkDelta); // TODO will it work even with not-updated focus current?
                continue;
            }

            if (ShadowUtil.matches(newLinkRepoShadow, projCtx.getResourceShadowDiscriminator())) {
                LOGGER.trace("Found new matching link: {}, updating projection context", newLinkRepoShadow);
                LOGGER.trace("Applying definition from provisioning first."); // MID-3317
                provisioningService.applyDefinition(newLinkRepoShadow, task, result);
                projCtx.setObjectCurrent(newLinkRepoShadow);
                projCtx.setOid(newLinkRepoShadow.getOid());
                // The "exists" information in the projection context can be obsolete - reflecting the fact that
                // resource object couldn't be found.
                projCtx.setExists(ShadowUtil.isExists(newLinkRepoShadow));
                projCtx.recompute();
                return true;
            }

            LOGGER.trace("Found new link: {}, but skipping it because it does not match the projection context",
                    newLinkRepoShadow);
        }
        LOGGER.trace(" -> no suitable link found");
        return false;
    }

    private <F extends ObjectType> PrismObject<F> reloadFocus(LensContext<F> context, Task task, OperationResult result)
            throws SchemaException, ObjectNotFoundException {
        LensFocusContext<F> focusContext = context.getFocusContext();
        if (focusContext == null) {
            LOGGER.trace(" -> no focus context");
            return null;
        }
        Class<F> focusClass = focusContext.getObjectTypeClass();
        if (!FocusType.class.isAssignableFrom(focusClass)) {
            LOGGER.trace(" -> no focus context of FocusType");
            return null;
        }
        try {
            return cacheRepositoryService.getObject(focusClass, focusContext.getOid(), null, result);
        } catch (ObjectNotFoundException e) {
            if (focusContext.isDelete()) {
                // This may be OK. This may be later wave and the focus may be already deleted.
                // Therefore let's just keep what we have. We have no way how to refresh context
                // in that situation.
                result.muteLastSubresultError();
                LOGGER.trace(" -> focus does not exist any more");
                return null;
            } else {
                throw e;
            }
        }
    }

    private void applyAttributesToGet(LensProjectionContext projCtx, Collection<SelectorOptions<GetOperationOptions>> options) throws SchemaException {
        if ( !LensUtil.isPasswordReturnedByDefault(projCtx)
                && LensUtil.needsFullShadowForCredentialProcessing(projCtx)) {
            options.add(SelectorOptions.create(prismContext.toUniformPath(SchemaConstants.PATH_PASSWORD_VALUE), GetOperationOptions.createRetrieve()));
        }
    }

    public <F extends FocusType> void reloadSecurityPolicyIfNeeded(@NotNull LensContext<F> context,
            @NotNull LensFocusContext<F> focusContext, Task task, OperationResult result)
            throws ExpressionEvaluationException, SchemaException,
            CommunicationException, ConfigurationException, SecurityViolationException {
        if (focusContext.hasOrganizationalChange()) {
            loadSecurityPolicy(context, true, task, result);
        }
    }

    private <F extends ObjectType> void loadSecurityPolicy(LensContext<F> context,
            Task task, OperationResult result) throws ExpressionEvaluationException,
            SchemaException, CommunicationException, ConfigurationException, SecurityViolationException {
        loadSecurityPolicy(context, false, task, result);
    }

    @SuppressWarnings("unchecked")
    private <O extends ObjectType> void loadSecurityPolicy(LensContext<O> context, boolean forceReload,
            Task task, OperationResult result) throws ExpressionEvaluationException,
            SchemaException, CommunicationException, ConfigurationException, SecurityViolationException {
        LensFocusContext<O> genericFocusContext = context.getFocusContext();
        if (genericFocusContext == null || !genericFocusContext.represents(FocusType.class)) {
            LOGGER.trace("Skipping load of security policy because focus is not of FocusType");
            return;
        }
        LensFocusContext<FocusType> focusContext = (LensFocusContext<FocusType>) genericFocusContext;
        PrismObject<FocusType> focus = focusContext.getObjectAny();
        SecurityPolicyType globalSecurityPolicy = determineAndSetGlobalSecurityPolicy(context, focus, task, result);
        SecurityPolicyType focusSecurityPolicy = determineAndSetFocusSecurityPolicy(focusContext, focus, globalSecurityPolicy,
                forceReload, task, result);
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Security policy:\n  Global:\n{}\n  Focus:\n{}",
                    globalSecurityPolicy.asPrismObject().debugDump(2),
                    focusSecurityPolicy==null?null:focusSecurityPolicy.asPrismObject().debugDump(2));
        } else {
            LOGGER.debug("Security policy: global: {}, focus: {}", globalSecurityPolicy, focusSecurityPolicy);
        }
    }

    @NotNull
    private <O extends ObjectType> SecurityPolicyType determineAndSetGlobalSecurityPolicy(LensContext<O> context,
            PrismObject<FocusType> focus, Task task, OperationResult result)
            throws CommunicationException, ConfigurationException, SecurityViolationException, ExpressionEvaluationException {
        SecurityPolicyType existingPolicy = context.getGlobalSecurityPolicy();
        if (existingPolicy != null) {
            return existingPolicy;
        } else {
            SecurityPolicyType loadedPolicy = securityHelper.locateGlobalSecurityPolicy(focus, context.getSystemConfiguration(),
                    task, result);
            SecurityPolicyType resultingPolicy;
            if (loadedPolicy != null) {
                resultingPolicy = loadedPolicy;
            } else {
                // use empty policy to avoid repeated lookups
                resultingPolicy = new SecurityPolicyType();
            }
            context.setGlobalSecurityPolicy(resultingPolicy);
            return resultingPolicy;
        }
    }

    private SecurityPolicyType determineAndSetFocusSecurityPolicy(LensFocusContext<FocusType> focusContext,
            PrismObject<FocusType> focus, SecurityPolicyType globalSecurityPolicy, boolean forceReload, Task task,
            OperationResult result) throws SchemaException {
        SecurityPolicyType existingPolicy = focusContext.getSecurityPolicy();
        if (existingPolicy != null && !forceReload) {
            return existingPolicy;
        } else {
            SecurityPolicyType loadedPolicy = securityHelper.locateFocusSecurityPolicy(focus, task, result);
            SecurityPolicyType resultingPolicy;
            if (loadedPolicy != null) {
                resultingPolicy = loadedPolicy;
            } else {
                // Not very clean. In fact we should store focus security policy separate from global
                // policy to avoid confusion. But need to do this to fix MID-4793 and backport the fix.
                // Therefore avoiding big changes. TODO: fix properly later
                resultingPolicy = globalSecurityPolicy;
            }
            focusContext.setSecurityPolicy(resultingPolicy);
            return resultingPolicy;
        }
    }
}
