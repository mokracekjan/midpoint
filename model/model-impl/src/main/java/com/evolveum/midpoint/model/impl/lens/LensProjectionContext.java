/*
 * Copyright (c) 2010-2020 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.model.impl.lens;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Consumer;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.common.refinery.*;
import com.evolveum.midpoint.model.impl.lens.construction.EvaluatedAssignedResourceObjectConstructionImpl;
import com.evolveum.midpoint.model.impl.lens.construction.PlainResourceObjectConstruction;
import com.evolveum.midpoint.prism.*;
import com.evolveum.midpoint.prism.delta.*;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.schema.DeltaConvertor;
import com.evolveum.midpoint.schema.ResourceShadowDiscriminator;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.util.annotation.Experimental;
import com.evolveum.midpoint.util.exception.*;
import com.evolveum.midpoint.util.logging.LoggingUtils;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_3.*;
import com.evolveum.prism.xml.ns._public.types_3.ObjectDeltaType;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.jetbrains.annotations.NotNull;
import org.jvnet.jaxb2_commons.lang.Validate;

import com.evolveum.midpoint.common.crypto.CryptoUtil;
import com.evolveum.midpoint.model.api.context.ModelProjectionContext;
import com.evolveum.midpoint.model.api.context.SynchronizationPolicyDecision;
import com.evolveum.midpoint.prism.util.ObjectDeltaObject;
import com.evolveum.midpoint.schema.processor.ResourceAttribute;
import com.evolveum.midpoint.schema.processor.ResourceAttributeContainer;
import com.evolveum.midpoint.schema.processor.ResourceSchema;
import com.evolveum.midpoint.schema.util.MiscSchemaUtil;
import com.evolveum.midpoint.schema.util.ShadowUtil;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.schema.util.ResourceTypeUtil;
import com.evolveum.midpoint.schema.util.SchemaDebugUtil;
import com.evolveum.midpoint.util.DebugUtil;

/**
 * @author semancik
 *
 */
public class LensProjectionContext extends LensElementContext<ShadowType> implements ModelProjectionContext {

    private static final Trace LOGGER = TraceManager.getTrace(LensProjectionContext.class);

    private ObjectDelta<ShadowType> syncDelta;

    /**
     * Is this projection the source of the synchronization? (The syncDelta attribute could be used for this but in
     * reality it is not always present.) We need this information e.g. when it's not possible to record a clockwork
     * exception to focus (e.g. as in MID-5801). The alternate way is to record it into shadow representing the synchronization
     * source, e.g. the object being imported, reconciled, or live-synced.
     */
    private boolean synchronizationSource;

    /**
     * If set to true: absolute state of this projection was detected by the synchronization.
     * This is mostly for debugging and visibility. It is not used by projection logic.
     */
    private boolean syncAbsoluteTrigger = false;

    /**
     * The wave in which this resource should be processed. Initial value of -1 means "undetermined".
     */
    private int wave = -1;

    /**
     * Indicates that the wave computation is still in progress.
     */
    private transient boolean waveIncomplete = false;

    /**
     * Was the processing of this projection (in its execution wave) complete?
     * We use this flag to avoid re-processing projections when wave is repeated.
     */
    @Experimental
    private boolean completed;

    /**
     * Definition of account type.
     */
    private ResourceShadowDiscriminator resourceShadowDiscriminator;

    private boolean fullShadow = false;

    /**
     * True if the account is assigned to the user by a valid assignment. It may be false for accounts that are either
     * found to be illegal by live sync, were unassigned from user, etc.
     * If set to null the situation is not yet known. Null is a typical value when the context is constructed.
     */
    private Boolean isAssigned;
    private Boolean isAssignedOld;

    /**
     * True if the account should be part of the synchronization. E.g. outbound expression should be applied to it.
     * TODO It looks like this is currently not used. Consider removing.
     */
    private boolean isActive;

    /**
     * True if there is a valid assignment for this projection and/or the policy allows such projection to exist.
     */
    private Boolean isLegal = null;
    private Boolean isLegalOld = null;

    /**
     * True if the projection exists (or will exist) on resource. False if it does not exist.
     * NOTE: entire projection is loaded with pointInTime=future. Therefore this does NOT
     * reflect actual situation. If there is a pending operation to create the object then
     * isExists will in fact be true.
     */
    private boolean isExists;

    /**
     * True if shadow exists in the repo. It is set to false after projector discovers that a shadow is gone.
     * This is a corner case, but it may happen: if shadow is unintentionally deleted, if the shadow is
     * cleaned up by another thread and so on.
     */
    private transient boolean shadowExistsInRepo = true;

    /**
     * Decision regarding the account. It indicated what the engine has DECIDED TO DO with the context.
     * If set to null no decision was made yet. Null is also a typical value when the context is created.
     */
    private SynchronizationPolicyDecision synchronizationPolicyDecision;

    /**
     * True if we want to reconcile account in this context.
     */
    private boolean doReconciliation;

    /**
     * false if the context should be not taken into the account while synchronizing changes from other resource
     */
    private boolean canProject = true;

    /**
     * Synchronization situation as it was originally detected by the synchronization code (SynchronizationService).
     * This is mostly for debug purposes. Projector and clockwork do not need to care about this.
     * The synchronization intent is used instead.
     */
    private SynchronizationSituationType synchronizationSituationDetected = null;
    /**
     * Synchronization situation which was the result of synchronization reaction (projector and clockwork run).
     * This is mostly for debug purposes. Projector and clockwork do not care about this (except for setting it).
     * The synchronization decision is used instead.
     */
    private SynchronizationSituationType synchronizationSituationResolved = null;

    /**
     * Delta set triple for constructions obtained via assignments. Specifies which constructions (projections e.g. accounts)
     * should be added, removed or stay as they are.
     *
     * It tells almost nothing about attributes directly although the information about attributes are inside
     * each construction.
     *
     * Intermediary computation result. It is stored to allow re-computing of constructions during
     * iterative computations.
     *
     * Source: AssignmentProcessor
     * Target: ConsolidationProcessor / ReconciliationProcessor (via squeezed structures)
     *
     * Note that relativity is taken to focus OLD state, not to the current state.
     */
    private transient DeltaSetTriple<EvaluatedAssignedResourceObjectConstructionImpl<?>> evaluatedAssignedConstructionDeltaSetTriple;

    /**
     * Evaluated "plain" resource object construction obtained from the schema handling configuration for given resource.
     * TODO better name
     *
     * Source: OutboundProcessor
     * Target: ConsolidationProcessor / ReconciliationProcessor (via squeezed structures)
     */
    private transient PlainResourceObjectConstruction<?> evaluatedPlainConstruction;

    /**
     * Post-processed triples from the above two properties.
     * Source: ConsolidationProcessor
     * Target: ReconciliationProcessor
     */
    private transient Map<QName, DeltaSetTriple<ItemValueWithOrigin<PrismPropertyValue<?>,PrismPropertyDefinition<?>>>> squeezedAttributes;
    private transient Map<QName, DeltaSetTriple<ItemValueWithOrigin<PrismContainerValue<ShadowAssociationType>,PrismContainerDefinition<ShadowAssociationType>>>> squeezedAssociations;
    private transient Map<QName, DeltaSetTriple<ItemValueWithOrigin<PrismPropertyValue<QName>,PrismPropertyDefinition<QName>>>> squeezedAuxiliaryObjectClasses;

    private transient Collection<ResourceObjectTypeDependencyType> dependencies = null;

    // Cached copy, to avoid constructing it over and over again
    private transient PrismObjectDefinition<ShadowType> shadowDefinition = null;

    private transient RefinedObjectClassDefinition structuralObjectClassDefinition;
    private transient Collection<RefinedObjectClassDefinition> auxiliaryObjectClassDefinitions;
    private transient CompositeRefinedObjectClassDefinition compositeObjectClassDefinition;

    private SecurityPolicyType projectionSecurityPolicy;

    /**
     * Resource that hosts this projection.
     */
    private transient ResourceType resource;

    /**
     * EXPERIMENTAL. A flag that this projection context has to be put into 'history archive'.
     * Necessary to evaluate old state of hasLinkedAccount.
     *
     * TODO implement as non-transient.
     */
    private transient boolean toBeArchived;

    private transient String humanReadableName;

    private Map<String, PrismObject<ShadowType>> entitlementMap = new HashMap<>();

    private transient String humanReadableString;

    /**
     * Cached value metadata to be used for resource object values processed by inbound mappings.
     */
    private transient ValueMetadataType cachedValueMetadata;

    LensProjectionContext(LensContext<? extends ObjectType> lensContext, ResourceShadowDiscriminator resourceAccountType) {
        super(ShadowType.class, lensContext);
        this.resourceShadowDiscriminator = resourceAccountType;
    }

    public ObjectDelta<ShadowType> getSyncDelta() {
        return syncDelta;
    }

    public void setSyncDelta(ObjectDelta<ShadowType> syncDelta) {
        this.syncDelta = syncDelta;
    }

    public ObjectDeltaObject<ShadowType> getObjectDeltaObject() throws SchemaException {
        ObjectDelta<ShadowType> currentDelta = getCurrentDelta();
        PrismObject<ShadowType> base;
        if (shouldCreateObjectNew(currentDelta)) {
            RefinedObjectClassDefinition rOCD = getCompositeObjectClassDefinition();
            if (rOCD != null) {
                base = rOCD.createBlankShadow(resourceShadowDiscriminator.getTag());
            } else {
                base = null;
            }
        } else {
            base = objectCurrent;
        }
        return new ObjectDeltaObject<>(base, currentDelta, objectNew, getObjectDefinition());
    }

    private boolean shouldCreateObjectNew(ObjectDelta<ShadowType> currentDelta) {
        return objectCurrent == null && (ObjectDelta.isModify(currentDelta) || currentDelta == null && decisionIsAdd());
    }

    @Override
    public ObjectDelta<ShadowType> getSummaryDelta() {
        return getCurrentDelta();
    }

    @Override
    public ObjectDelta<ShadowType> getSummarySecondaryDelta() {
        return secondaryDelta;
    }

    public boolean hasSecondaryDelta() {
        return secondaryDelta != null && !secondaryDelta.isEmpty();
    }

    public boolean isSyncAbsoluteTrigger() {
        return syncAbsoluteTrigger;
    }

    public void setSyncAbsoluteTrigger(boolean syncAbsoluteTrigger) {
        this.syncAbsoluteTrigger = syncAbsoluteTrigger;
    }

    public int getWave() {
        return wave;
    }

    public void setWave(int wave) {
        this.wave = wave;
    }

    public boolean isWaveIncomplete() {
        return waveIncomplete;
    }

    public void setWaveIncomplete(boolean waveIncomplete) {
        this.waveIncomplete = waveIncomplete;
    }

    public boolean isDoReconciliation() {
        return doReconciliation;
    }

    public void setDoReconciliation(boolean doReconciliation) {
        this.doReconciliation = doReconciliation;
    }

    @Override
    public ResourceShadowDiscriminator getResourceShadowDiscriminator() {
        return resourceShadowDiscriminator;
    }

    public void markTombstone() {
        if (resourceShadowDiscriminator != null) {
            resourceShadowDiscriminator.setTombstone(true);
        }
        setExists(false);
        setFullShadow(false);
        humanReadableName = null;
    }

    public void setResourceShadowDiscriminator(ResourceShadowDiscriminator resourceShadowDiscriminator) {
        this.resourceShadowDiscriminator = resourceShadowDiscriminator;
    }

    public boolean compareResourceShadowDiscriminator(ResourceShadowDiscriminator rsd, boolean compareOrder) {
        Validate.notNull(rsd.getResourceOid());
        if (resourceShadowDiscriminator == null) {
            // This may be valid case e.g. in case of broken contexts or if a context is just loading
            return false;
        }
        if (!rsd.getResourceOid().equals(resourceShadowDiscriminator.getResourceOid())) {
            return false;
        }
        if (!rsd.getKind().equals(resourceShadowDiscriminator.getKind())) {
            return false;
        }
        if (rsd.isTombstone() != resourceShadowDiscriminator.isTombstone()) {
            return false;
        }
        if (rsd.getIntent() == null) {
            try {
                if (!getStructuralObjectClassDefinition().isDefaultInAKind()) {
                    return false;
                }
            } catch (SchemaException e) {
                throw new SystemException("Internal error: "+e.getMessage(), e);
            }
        } else if (!rsd.getIntent().equals(resourceShadowDiscriminator.getIntent())) {
            return false;
        }
        if (!Objects.equals(rsd.getTag(), resourceShadowDiscriminator.getTag())) {
            return false;
        }

        if (compareOrder && rsd.getOrder() != resourceShadowDiscriminator.getOrder()) {
            return false;
        }

        return true;
    }

    public boolean isTombstone() {
        return resourceShadowDiscriminator != null && resourceShadowDiscriminator.isTombstone();
    }

    public void addAccountSyncDelta(ObjectDelta<ShadowType> delta) throws SchemaException {
        if (syncDelta == null) {
            syncDelta = delta;
        } else {
            syncDelta.merge(delta);
        }
    }

    public boolean isAdd() {
        if (synchronizationPolicyDecision == SynchronizationPolicyDecision.ADD) {
            return true;
        } else if (synchronizationPolicyDecision != null) {
            return false;
        } else {
            return ObjectDelta.isAdd(primaryDelta);
        }
    }

    public boolean isModify() {
        if (synchronizationPolicyDecision == SynchronizationPolicyDecision.KEEP) {
            return true;
        } else if (synchronizationPolicyDecision != null) {
            return false;
        } else {
            return super.isModify();
        }
    }

    public boolean isDelete() {
        // Note that there are situations where decision is UNLINK with primary delta being DELETE. (Why?)
        return synchronizationPolicyDecision == SynchronizationPolicyDecision.DELETE ||
                ObjectDelta.isDelete(syncDelta) || ObjectDelta.isDelete(primaryDelta);
    }

    @Override
    public ArchetypeType getArchetype() {
        throw new UnsupportedOperationException("Archetypes are not supported for projections.");
    }

    public ResourceType getResource() {
        return resource;
    }

    public void setResource(ResourceType resource) {
        this.resource = resource;
    }

    public Map<String, PrismObject<ShadowType>> getEntitlementMap() {
        return entitlementMap;
    }

    public void setEntitlementMap(Map<String, PrismObject<ShadowType>> entitlementMap) {
        this.entitlementMap = entitlementMap;
    }

    @Override
    public PrismObjectDefinition<ShadowType> getObjectDefinition() {
        if (shadowDefinition == null) {
            try {
                shadowDefinition = ShadowUtil.applyObjectClass(super.getObjectDefinition(), getCompositeObjectClassDefinition());
                shadowDefinition.freeze();
            } catch (SchemaException e) {
                // This should not happen
                throw new SystemException(e.getMessage(), e);
            }
        }
        return shadowDefinition;
    }

    public Boolean isAssigned() {
        return isAssigned;
    }

    public void setAssigned(boolean isAssigned) {
        this.isAssigned = isAssigned;
    }

    public Boolean isAssignedOld() {
        return isAssignedOld;
    }

    public void setAssignedOld(Boolean isAssignedOld) {
        this.isAssignedOld = isAssignedOld;
    }

    /**
     * We want to set "assigned in old state" only once - in projection wave 0 where real "old"
     * values are known.
     *
     * TODO: what should we do with projections that appear later?
     */
    public void setAssignedOldIfUnknown(Boolean value) {
        if (isAssignedOld == null) {
            setAssignedOld(value);
        }
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean isActive) {
        this.isActive = isActive;
    }

    public Boolean isLegal() {
        return isLegal;
    }

    public void setLegal(Boolean isLegal) {
        this.isLegal = isLegal;
    }

    public Boolean isLegalOld() {
        return isLegalOld;
    }

    public void setLegalOld(Boolean isLegalOld) {
        this.isLegalOld = isLegalOld;
    }

    /**
     * We want to set "legal in old state" only once - in projection wave 0 where real "old"
     * values are known.
     *
     * TODO: what should we do with projections that appear later?
     */
    public void setLegalOldIfUnknown(Boolean value) {
        if (isLegalOld == null) {
            setLegalOld(value);
        }
    }

    public boolean isExists() {
        return isExists;
    }

    public void setExists(boolean exists) {
        this.isExists = exists;
    }

    public boolean isShadowExistsInRepo() {
        return shadowExistsInRepo;
    }

    public void setShadowExistsInRepo(boolean shadowExistsInRepo) {
        this.shadowExistsInRepo = shadowExistsInRepo;
    }

    public SynchronizationPolicyDecision getSynchronizationPolicyDecision() {
        return synchronizationPolicyDecision;
    }

    public void setSynchronizationPolicyDecision(SynchronizationPolicyDecision policyDecision) {
        this.synchronizationPolicyDecision = policyDecision;
    }

    public SynchronizationSituationType getSynchronizationSituationDetected() {
        return synchronizationSituationDetected;
    }

    public void setSynchronizationSituationDetected(
            SynchronizationSituationType synchronizationSituationDetected) {
        this.synchronizationSituationDetected = synchronizationSituationDetected;
    }

    public SynchronizationSituationType getSynchronizationSituationResolved() {
        return synchronizationSituationResolved;
    }

    public void setSynchronizationSituationResolved(SynchronizationSituationType synchronizationSituationResolved) {
        this.synchronizationSituationResolved = synchronizationSituationResolved;
    }

    public boolean isFullShadow() {
        return fullShadow;
    }

    /**
     * Returns true if full shadow is available, either loaded or in a create delta.
     */
    public boolean hasFullShadow() {
        if (synchronizationPolicyDecision == SynchronizationPolicyDecision.ADD) {
            return true;
        }
        return isFullShadow();
    }

    public void setFullShadow(boolean fullShadow) {
        this.fullShadow = fullShadow;
    }

    public ShadowKindType getKind() {
        ResourceShadowDiscriminator discr = getResourceShadowDiscriminator();
        if (discr != null) {
            return discr.getKind();
        }
        if (getObjectOld()!=null) {
            return getObjectOld().asObjectable().getKind();
        }
        if (getObjectCurrent()!=null) {
            return getObjectCurrent().asObjectable().getKind();
        }
        if (getObjectNew()!=null) {
            return getObjectNew().asObjectable().getKind();
        }
        return ShadowKindType.ACCOUNT;
    }

    public <AH extends AssignmentHolderType> DeltaSetTriple<EvaluatedAssignedResourceObjectConstructionImpl<AH>> getEvaluatedAssignedConstructionDeltaSetTriple() {
        //noinspection unchecked
        return (DeltaSetTriple) evaluatedAssignedConstructionDeltaSetTriple;
    }

    public <AH extends AssignmentHolderType> void setEvaluatedAssignedConstructionDeltaSetTriple(DeltaSetTriple<EvaluatedAssignedResourceObjectConstructionImpl<AH>> evaluatedAssignedConstructionDeltaSetTriple) {
        this.evaluatedAssignedConstructionDeltaSetTriple = (DeltaSetTriple) evaluatedAssignedConstructionDeltaSetTriple;
    }

    public <AH extends AssignmentHolderType> PlainResourceObjectConstruction<AH> getEvaluatedPlainConstruction() {
        //noinspection unchecked
        return (PlainResourceObjectConstruction<AH>) evaluatedPlainConstruction;
    }

    public void setEvaluatedPlainConstruction(PlainResourceObjectConstruction<?> evaluatedPlainConstruction) {
        this.evaluatedPlainConstruction = evaluatedPlainConstruction;
    }

    public Map<QName, DeltaSetTriple<ItemValueWithOrigin<PrismPropertyValue<?>,PrismPropertyDefinition<?>>>> getSqueezedAttributes() {
        return squeezedAttributes;
    }

    public void setSqueezedAttributes(Map<QName, DeltaSetTriple<ItemValueWithOrigin<PrismPropertyValue<?>,PrismPropertyDefinition<?>>>> squeezedAttributes) {
        this.squeezedAttributes = squeezedAttributes;
    }

    public Map<QName, DeltaSetTriple<ItemValueWithOrigin<PrismContainerValue<ShadowAssociationType>,PrismContainerDefinition<ShadowAssociationType>>>> getSqueezedAssociations() {
        return squeezedAssociations;
    }

    public void setSqueezedAssociations(
            Map<QName, DeltaSetTriple<ItemValueWithOrigin<PrismContainerValue<ShadowAssociationType>,PrismContainerDefinition<ShadowAssociationType>>>> squeezedAssociations) {
        this.squeezedAssociations = squeezedAssociations;
    }

    public Map<QName, DeltaSetTriple<ItemValueWithOrigin<PrismPropertyValue<QName>, PrismPropertyDefinition<QName>>>> getSqueezedAuxiliaryObjectClasses() {
        return squeezedAuxiliaryObjectClasses;
    }

    public void setSqueezedAuxiliaryObjectClasses(
            Map<QName, DeltaSetTriple<ItemValueWithOrigin<PrismPropertyValue<QName>, PrismPropertyDefinition<QName>>>> squeezedAuxiliaryObjectClasses) {
        this.squeezedAuxiliaryObjectClasses = squeezedAuxiliaryObjectClasses;
    }

    public ResourceObjectTypeDefinitionType getResourceObjectTypeDefinitionType() {
        if (synchronizationPolicyDecision == SynchronizationPolicyDecision.BROKEN) {
            return null;
        }
        ResourceShadowDiscriminator discr = getResourceShadowDiscriminator();
        if (discr == null) {
            return null;            // maybe when an account is deleted
        }
        if (resource == null) {
            return null;
        }
        return ResourceTypeUtil.getResourceObjectTypeDefinitionType(resource, discr.getKind(), discr.getIntent());
    }

    private ResourceSchema getResourceSchema() throws SchemaException {
        return RefinedResourceSchemaImpl.getResourceSchema(resource, getNotNullPrismContext());
    }

    public RefinedResourceSchema getRefinedResourceSchema() throws SchemaException {
        if (resource == null) {
            return null;
        }
        return RefinedResourceSchemaImpl.getRefinedSchema(resource, LayerType.MODEL, getNotNullPrismContext());
    }

    public RefinedObjectClassDefinition getStructuralObjectClassDefinition() throws SchemaException {
        if (structuralObjectClassDefinition == null) {
            RefinedResourceSchema refinedSchema = getRefinedResourceSchema();
            if (refinedSchema == null) {
                return null;
            }
            structuralObjectClassDefinition = refinedSchema.getRefinedDefinition(getResourceShadowDiscriminator().getKind(), getResourceShadowDiscriminator().getIntent());
            if (structuralObjectClassDefinition != null) {
                structuralObjectClassDefinition.freeze();
            }
        }
        return structuralObjectClassDefinition;
    }

    public Collection<RefinedObjectClassDefinition> getAuxiliaryObjectClassDefinitions() throws SchemaException {
        if (auxiliaryObjectClassDefinitions == null) {
            refreshAuxiliaryObjectClassDefinitions();
        }
        return auxiliaryObjectClassDefinitions;
    }

    public void refreshAuxiliaryObjectClassDefinitions() throws SchemaException {
        RefinedResourceSchema refinedSchema = getRefinedResourceSchema();
        if (refinedSchema == null) {
            return;
        }
        List<QName> auxiliaryObjectClassQNames = new ArrayList<>();
        addAuxiliaryObjectClassNames(auxiliaryObjectClassQNames, getObjectOld());
        addAuxiliaryObjectClassNames(auxiliaryObjectClassQNames, getObjectNew());
        auxiliaryObjectClassDefinitions = new ArrayList<>(auxiliaryObjectClassQNames.size());
        for (QName auxiliaryObjectClassQName: auxiliaryObjectClassQNames) {
            RefinedObjectClassDefinition auxiliaryObjectClassDef = refinedSchema.getRefinedDefinition(auxiliaryObjectClassQName);
            if (auxiliaryObjectClassDef == null) {
                throw new SchemaException("Auxiliary object class "+auxiliaryObjectClassQName+" specified in "+this+" does not exist");
            }
            auxiliaryObjectClassDefinitions.add(auxiliaryObjectClassDef);
        }
        compositeObjectClassDefinition = null;
    }

    public CompositeRefinedObjectClassDefinition getCompositeObjectClassDefinition() throws SchemaException {
        if (compositeObjectClassDefinition == null) {
            RefinedObjectClassDefinition structuralObjectClassDefinition = getStructuralObjectClassDefinition();
            if (structuralObjectClassDefinition != null) {
                compositeObjectClassDefinition = new CompositeRefinedObjectClassDefinitionImpl(
                        structuralObjectClassDefinition, getAuxiliaryObjectClassDefinitions());
                compositeObjectClassDefinition.freeze();
            }
        }
        return compositeObjectClassDefinition;
    }

    private void addAuxiliaryObjectClassNames(List<QName> auxiliaryObjectClassQNames,
            PrismObject<ShadowType> shadow) {
        if (shadow == null) {
            return;
        }
        for (QName aux: shadow.asObjectable().getAuxiliaryObjectClass()) {
            if (!auxiliaryObjectClassQNames.contains(aux)) {
                auxiliaryObjectClassQNames.add(aux);
            }
        }
    }

    public <T> RefinedAttributeDefinition<T> findAttributeDefinition(QName attrName) throws SchemaException {
        RefinedAttributeDefinition<T> attrDef = getStructuralObjectClassDefinition().findAttributeDefinition(attrName);
        if (attrDef != null) {
            return attrDef;
        }
        for (RefinedObjectClassDefinition auxOcDef: getAuxiliaryObjectClassDefinitions()) {
            attrDef = auxOcDef.findAttributeDefinition(attrName);
            if (attrDef != null) {
                return attrDef;
            }
        }
        return null;
    }

    public Collection<ResourceObjectTypeDependencyType> getDependencies() {
        if (dependencies == null) {
            ResourceObjectTypeDefinitionType resourceAccountTypeDefinitionType = getResourceObjectTypeDefinitionType();
            if (resourceAccountTypeDefinitionType == null) {
                // No dependencies. But we cannot set null as that means "unknown". So let's set empty collection instead.
                dependencies = new ArrayList<>();
            } else {
                dependencies = resourceAccountTypeDefinitionType.getDependency();
            }
        }
        return dependencies;
    }

    public SecurityPolicyType getProjectionSecurityPolicy() {
        return projectionSecurityPolicy;
    }

    public void setProjectionSecurityPolicy(SecurityPolicyType projectionSecurityPolicy) {
        this.projectionSecurityPolicy = projectionSecurityPolicy;
    }

    public void setCanProject(boolean canProject) {
        this.canProject = canProject;
    }

    public boolean isCanProject() {
        return canProject;
    }

    public AssignmentPolicyEnforcementType getAssignmentPolicyEnforcementType() throws SchemaException {
        // TODO: per-resource assignment enforcement
        ResourceType resource = getResource();
        ProjectionPolicyType objectClassProjectionPolicy = determineObjectClassProjectionPolicy();

        if (objectClassProjectionPolicy != null && objectClassProjectionPolicy.getAssignmentPolicyEnforcement() != null) {
            return MiscSchemaUtil.getAssignmentPolicyEnforcementType(objectClassProjectionPolicy);
        }

        ProjectionPolicyType globalAccountSynchronizationSettings = null;
        if (resource != null){
            globalAccountSynchronizationSettings = resource.getProjection();
        }

        if (globalAccountSynchronizationSettings == null) {
            globalAccountSynchronizationSettings = getLensContext().getAccountSynchronizationSettings();
        }
        AssignmentPolicyEnforcementType globalAssignmentPolicyEnforcement = MiscSchemaUtil.getAssignmentPolicyEnforcementType(globalAccountSynchronizationSettings);
        return globalAssignmentPolicyEnforcement;
    }

    public boolean isLegalize() throws SchemaException {
        ResourceType resource = getResource();

        ProjectionPolicyType objectClassProjectionPolicy = determineObjectClassProjectionPolicy();
        if (objectClassProjectionPolicy != null) {
            return BooleanUtils.isTrue(objectClassProjectionPolicy.isLegalize());
        }
        ProjectionPolicyType globalAccountSynchronizationSettings = null;
        if (resource != null){
            globalAccountSynchronizationSettings = resource.getProjection();
        }

        if (globalAccountSynchronizationSettings == null) {
            globalAccountSynchronizationSettings = getLensContext().getAccountSynchronizationSettings();
        }

        if (globalAccountSynchronizationSettings == null){
            return false;
        }

        return BooleanUtils.isTrue(globalAccountSynchronizationSettings.isLegalize());
    }

    private ProjectionPolicyType determineObjectClassProjectionPolicy() throws SchemaException {
        RefinedResourceSchema refinedSchema = getRefinedResourceSchema();
        if (refinedSchema == null) {
            return null;
        }

        RefinedObjectClassDefinition objectClassDef = refinedSchema.getRefinedDefinition(resourceShadowDiscriminator.getKind(),
                resourceShadowDiscriminator.getIntent());

        if (objectClassDef == null) {
            return null;
        }
        return objectClassDef.getProjection();
    }

    /**
     * Recomputes the new state of account (accountNew). It is computed by applying deltas to the old state (accountOld).
     * Assuming that oldAccount is already set (or is null if it does not exist)
     */
    public void recompute() throws SchemaException {
        ObjectDelta<ShadowType> currentDelta = getCurrentDelta();
        PrismObject<ShadowType> base;
        if (objectCurrent == null && ObjectDelta.isAdd(syncDelta)) {
            base = syncDelta.getObjectToAdd();
        } else if (shouldCreateObjectNew(currentDelta)) {
            RefinedObjectClassDefinition rOCD = getCompositeObjectClassDefinition();
            if (rOCD != null) {
                base = rOCD.createBlankShadow(resourceShadowDiscriminator.getTag());
            } else {
                base = null;
            }
        } else {
            base = objectCurrent;
        }

        if (currentDelta == null) {
            // No change
            setObjectNew(base);
        } else {
            setObjectNew(currentDelta.computeChangedObject(base));
        }
    }

    /**
     * We sometimes need the 'object new' to exist before any real modifications are computed.
     * An example is when outbound mappings reference $projection/tag (see MID-6899).
     */
    private boolean decisionIsAdd() {
        return synchronizationPolicyDecision == SynchronizationPolicyDecision.ADD;
    }

    public void clearIntermediateResults() {
        //constructionDeltaSetTriple = null;
        evaluatedPlainConstruction = null;
        squeezedAttributes = null;
    }

    /**
     * Returns delta suitable for execution. The primary and secondary deltas may not make complete sense all by themselves.
     * E.g. they may both be MODIFY deltas even in case that the account should be created. The deltas begin to make sense
     * only if combined with sync decision. This method provides the deltas all combined and ready for execution.
     */
    @Override
    public ObjectDelta<ShadowType> getExecutableDelta() throws SchemaException {
        SynchronizationPolicyDecision policyDecision = getSynchronizationPolicyDecision();
        ObjectDelta<ShadowType> origDelta = getCurrentDelta();
        if (policyDecision == SynchronizationPolicyDecision.ADD) {
            // let's try to retrieve original (non-fixed) delta. Maybe it's ADD delta so we spare fixing it.
            origDelta = getSummaryDelta(); // TODO check this
            if (origDelta == null || origDelta.isModify()) {
                // We need to convert modify delta to ADD
                ObjectDelta<ShadowType> addDelta = getPrismContext().deltaFactory().object().create(getObjectTypeClass(),
                    ChangeType.ADD);
                RefinedObjectClassDefinition rObjectClassDef = getCompositeObjectClassDefinition();

                if (rObjectClassDef == null) {
                    throw new IllegalStateException("Definition for account type " + getResourceShadowDiscriminator()
                            + " not found in the context, but it should be there");
                }
                PrismObject<ShadowType> newAccount = rObjectClassDef.createBlankShadow(resourceShadowDiscriminator.getTag());
                addDelta.setObjectToAdd(newAccount);

                if (origDelta != null) {
                    addDelta.merge(origDelta);
                }
                return addDelta;
            }
        } else if (policyDecision == SynchronizationPolicyDecision.KEEP) {
            // (Almost) any delta is OK
            if (isExists && ObjectDelta.isAdd(origDelta)) {
                LOGGER.trace("Projection exists and we try to create it anew. This probably means that the primary ADD delta"
                        + " should be ignored. Using secondary delta only. Current delta is:\n{}\nSecondary delta that will"
                        + " be used instead is:\n{}", origDelta.debugDumpLazily(), DebugUtil.debugDumpLazily(getSecondaryDelta()));
                origDelta = getSecondaryDelta();
            }
        } else if (policyDecision == SynchronizationPolicyDecision.DELETE) {
            ObjectDelta<ShadowType> deleteDelta = getPrismContext().deltaFactory().object().create(getObjectTypeClass(),
                ChangeType.DELETE);
            String oid = getOid();
            if (oid == null) {
                throw new IllegalStateException(
                        "Internal error: account context OID is null during attempt to create delete secondary delta; context="
                                +this);
            }
            deleteDelta.setOid(oid);
            return deleteDelta;
        } else {
            // This is either UNLINK or null, both are in fact the same as KEEP
            // Any delta is OK
        }
        if (origDelta != null && origDelta.isImmutable()) {
            // E.g. locked primary delta.
            // We need modifiable delta for execution, e.g. to set metadata, oid and so on.
            return origDelta.clone();
        } else {
            return origDelta;
        }
    }

    /**
     * As getPrimaryDelta() but caters for the possibility that an object already exists.
     * So, if the primary delta is ADD and object already exists, it should be changed somehow,
     * e.g. to MODIFY delta or to null.
     *
     * Actually, the question is what to do with the attribute values if changed to MODIFY.
     * (a) Should they become REPLACE item deltas? (b) ADD ones?
     * (c) Or should we compute a difference from objectCurrent to objectToAdd, hoping that
     * secondary deltas will re-add everything that might be unknowingly removed by this step?
     * (d) Or should we simply ignore ADD delta altogether, hoping that it was executed
     * so it need not be repeated?
     *
     * And, should not we report AlreadyExistingException instead?
     *
     * It seems that (c) i.e. reverting back to objectToAdd is not a good idea at all. For example, this
     * may erase linkRefs for good.
     *
     * For the time being let us proceed with (d), i.e. ignoring such a delta.
     *
     * TODO is this OK???? [med]
     */
    private ObjectDelta<ShadowType> getFixedPrimaryDelta() {
        if (ObjectDelta.isAdd(getPrimaryDelta()) && getObjectCurrent() != null) {
            // Object does exist. Let's ignore the delta - see description above.
            return null;
        } else {
            return getPrimaryDelta(); // nothing to do
        }
    }

    public void checkConsistence() {
        checkConsistence(null, true, false);
    }

    @Override
    public void checkConsistence(String contextDesc) {
        super.checkConsistence(contextDesc);
        if (secondaryDelta != null) {
            boolean requireOid = isRequireSecondaryDeltaOid();
            // Secondary delta may not have OID yet (as it may relate to ADD primary delta that doesn't have OID yet)
            checkConsistence(secondaryDelta, requireOid, getElementDesc() + " secondary delta in " + this + (contextDesc == null ? "" : " in " + contextDesc));
        }
    }

    public void checkConsistence(String contextDesc, boolean fresh, boolean force) {
        if (synchronizationPolicyDecision == SynchronizationPolicyDecision.IGNORE) {
            // No not check these. they may be quite wild.
            return;
        }
        super.checkConsistence(contextDesc);
        if (synchronizationPolicyDecision == SynchronizationPolicyDecision.BROKEN) {
            return;
        }
        if (fresh && !force && resourceShadowDiscriminator != null && !resourceShadowDiscriminator.isTombstone()) {
            if (resource == null) {
                throw new IllegalStateException("Null resource in "+this + (contextDesc == null ? "" : " in " +contextDesc));
            }
            if (resourceShadowDiscriminator == null) {
                throw new IllegalStateException("Null resource account type in "+this + (contextDesc == null ? "" : " in " +contextDesc));
            }
        }
        if (syncDelta != null) {
            try {
                syncDelta.checkConsistence(true, true, true, ConsistencyCheckScope.THOROUGH);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(e.getMessage()+"; in "+getElementDesc()+" sync delta in "+this + (contextDesc == null ? "" : " in " +contextDesc), e);
            } catch (IllegalStateException e) {
                throw new IllegalStateException(e.getMessage()+"; in "+getElementDesc()+" sync delta in "+this + (contextDesc == null ? "" : " in " +contextDesc), e);
            }
        }
    }

    @Override
    protected void checkConsistence(PrismObject<ShadowType> object, String elementDesc, String contextDesc) {
        super.checkConsistence(object, elementDesc, contextDesc);
        ResourceAttributeContainer attributesContainer = ShadowUtil.getAttributesContainer(object);
        if (attributesContainer != null) {
            ResourceType resource = getResource();
            if (resource != null) {
                String resourceNamespace = ResourceTypeUtil.getResourceNamespace(resource);
                for(ResourceAttribute<?> attribute: attributesContainer.getAttributes()) {
                    QName attrName = attribute.getElementName();
                    if (SchemaConstants.NS_ICF_SCHEMA.equals(attrName.getNamespaceURI())) {
                        continue;
                    }
                    if (resourceNamespace.equals(attrName.getNamespaceURI())) {
                        continue;
                    }
                    String desc = elementDesc+" in "+this + (contextDesc == null ? "" : " in " +contextDesc);
                    throw new IllegalStateException("Invalid namespace for attribute "+attrName+" in "+desc);
                }
            }
        }
    }

    protected boolean isRequireSecondaryDeltaOid() {
        if (synchronizationPolicyDecision == SynchronizationPolicyDecision.ADD ||
                synchronizationPolicyDecision == SynchronizationPolicyDecision.BROKEN ||
                synchronizationPolicyDecision == SynchronizationPolicyDecision.IGNORE) {
            return false;
        }
        if (isHigherOrder()) {
            // These may not have the OID yet
            return false;
        }
        return super.isRequireSecondaryDeltaOid();
    }

    @Override
    public void cleanup() {
        checkIfShouldArchive();

        // We will clean up this projection context fully only if there's a chance we will touch it again.
        if (!completed) {
            synchronizationPolicyDecision = null;
            isAssigned = null;
            isActive = false;
        }

        // However, selected items are still cleaned up, in order to preserve existing behavior.
        // This might be important e.g. for inbound mappings that take previous deltas into account.
        secondaryDelta = null;

//      isLegal = null;
//      isLegalOld = null;
//      isAssignedOld = false;  // ??? [med]
    }

    @Override
    public void normalize() {
        super.normalize();
        if (syncDelta != null) {
            syncDelta.normalize();
        }
    }

    private void checkIfShouldArchive() {
        if (synchronizationPolicyDecision == SynchronizationPolicyDecision.DELETE ||
                synchronizationPolicyDecision == SynchronizationPolicyDecision.UNLINK) {
            toBeArchived = true;
        } else if (synchronizationPolicyDecision != null) {
            toBeArchived = false;
        }
    }

    @Override
    public void adopt(PrismContext prismContext) throws SchemaException {
        super.adopt(prismContext);
        if (syncDelta != null) {
            prismContext.adopt(syncDelta);
        }
    }

    @Override
    public LensProjectionContext clone(LensContext<? extends ObjectType> lensContext) {
        LensProjectionContext clone = new LensProjectionContext(lensContext, resourceShadowDiscriminator);
        copyValues(clone);
        return clone;
    }

    private void copyValues(LensProjectionContext clone) {
        super.copyValues(clone);
        // do NOT clone transient values such as accountConstructionDeltaSetTriple
        // these are not meant to be cloned and they are also not directly cloneable
        clone.dependencies = this.dependencies;
        clone.doReconciliation = this.doReconciliation;
        clone.fullShadow = this.fullShadow;
        clone.isAssigned = this.isAssigned;
        clone.isAssignedOld = this.isAssignedOld;
        clone.evaluatedPlainConstruction = this.evaluatedPlainConstruction;
        clone.synchronizationPolicyDecision = this.synchronizationPolicyDecision;
        clone.resource = this.resource;
        clone.resourceShadowDiscriminator = this.resourceShadowDiscriminator;
        clone.squeezedAttributes = cloneSqueezedAttributes();
        if (this.syncDelta != null) {
            clone.syncDelta = this.syncDelta.clone();
        }
        clone.secondaryDelta = cloneDelta(this.secondaryDelta);
        clone.wave = this.wave;
        clone.synchronizationSource = this.synchronizationSource;
    }

    private Map<QName, DeltaSetTriple<ItemValueWithOrigin<PrismPropertyValue<?>,PrismPropertyDefinition<?>>>> cloneSqueezedAttributes() {
        if (squeezedAttributes == null) {
            return null;
        }
        Map<QName, DeltaSetTriple<ItemValueWithOrigin<PrismPropertyValue<?>,PrismPropertyDefinition<?>>>> clonedMap = new HashMap<>();
        for (Entry<QName, DeltaSetTriple<ItemValueWithOrigin<PrismPropertyValue<?>,PrismPropertyDefinition<?>>>> entry: squeezedAttributes.entrySet()) {
            clonedMap.put(entry.getKey(), entry.getValue().clone(ItemValueWithOrigin::clone));
        }
        return clonedMap;
    }

    /**
     * Returns true if the projection has any value for specified attribute.
     */
    public boolean hasValueForAttribute(QName attributeName) {
        ItemPath attrPath = ItemPath.create(ShadowType.F_ATTRIBUTES, attributeName);
        if (getObjectNew() != null) {
            PrismProperty<?> attrNew = getObjectNew().findProperty(attrPath);
            if (attrNew != null && !attrNew.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void checkEncrypted() {
        super.checkEncrypted();
        if (syncDelta != null) {
            CryptoUtil.checkEncrypted(syncDelta);
        }
        if (secondaryDelta != null) {
            CryptoUtil.checkEncrypted(secondaryDelta);
        }
    }

    @Override
    public String getHumanReadableName() {
        if (humanReadableName == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("account(");
            String humanReadableAccountIdentifier = getHumanReadableIdentifier();
            if (StringUtils.isEmpty(humanReadableAccountIdentifier)) {
                sb.append("no ID");
            } else {
                sb.append("ID ");
                sb.append(humanReadableAccountIdentifier);
            }
            ResourceShadowDiscriminator discr = getResourceShadowDiscriminator();
            if (discr != null) {
                sb.append(", type '");
                sb.append(discr.getIntent());
                sb.append("', ");
                if (discr.getOrder() != 0) {
                    sb.append("order ").append(discr.getOrder()).append(", ");
                }
            } else {
                sb.append(" (no discriminator) ");
            }
            sb.append(getResource());
            sb.append(")");
            humanReadableName = sb.toString();
        }
        return humanReadableName;
    }

    private String getHumanReadableIdentifier() {
        PrismObject<ShadowType> object = getObjectNew();
        if (object == null) {
            object = getObjectOld();
        }
        if (object == null) {
            object = getObjectCurrent();
        }
        if (object == null) {
            return null;
        }
        if (object.canRepresent(ShadowType.class)) { // probably always the case
            Collection<ResourceAttribute<?>> identifiers = ShadowUtil.getPrimaryIdentifiers(object);
            if (identifiers == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            Iterator<ResourceAttribute<?>> iterator = identifiers.iterator();
            while (iterator.hasNext()) {
                ResourceAttribute<?> id = iterator.next();
                sb.append(id.toHumanReadableString());
                if (iterator.hasNext()) {
                    sb.append(",");
                }
            }
            return sb.toString();
        } else {
            return object.toString();
        }
    }

    @Override
    public String debugDump(int indent) {
        return debugDump(indent, true);
    }

    public String debugDump(int indent, boolean showTriples) {
        StringBuilder sb = new StringBuilder();
        SchemaDebugUtil.indentDebugDump(sb, indent);
        sb.append("PROJECTION ");
        sb.append(getObjectTypeClass() == null ? "null" : getObjectTypeClass().getSimpleName());
        sb.append(" ");
        sb.append(getResourceShadowDiscriminator());
        if (resource != null) {
            sb.append(" : ");
            sb.append(resource.getName().getOrig());
        }
        sb.append("\n");
        SchemaDebugUtil.indentDebugDump(sb, indent + 1);
        sb.append("OID: ").append(getOid());
        sb.append(", wave ").append(wave);
        if (fullShadow) {
            sb.append(", full");
        } else {
            sb.append(", shadow");
        }
        sb.append(", exists=").append(isExists);
        if (!shadowExistsInRepo) {
            sb.append(" (shadow not in repo)");
        }
        sb.append(", assigned=").append(isAssignedOld).append("->").append(isAssigned);
        sb.append(", active=").append(isActive);
        sb.append(", legal=").append(isLegalOld).append("->").append(isLegal);
        sb.append(", recon=").append(doReconciliation);
        sb.append(", canProject=").append(canProject);
        sb.append(", syncIntent=").append(getSynchronizationIntent());
        sb.append(", decision=").append(synchronizationPolicyDecision);
        if (!isFresh()) {
            sb.append(", NOT FRESH");
        }
        if (resourceShadowDiscriminator != null && resourceShadowDiscriminator.isTombstone()) {
            sb.append(", TOMBSTONE");
        }
        if (syncAbsoluteTrigger) {
            sb.append(", SYNC TRIGGER");
        }
        if (getIteration() != 0) {
            sb.append(", iteration=").append(getIteration()).append(" (").append(getIterationToken()).append(")");
        }
        if (completed) {
            sb.append(", completed");
        }
        sb.append("\n");
        DebugUtil.debugDumpWithLabel(sb, getDebugDumpTitle("old"), objectOld, indent + 1);

        sb.append("\n");
        DebugUtil.debugDumpWithLabel(sb, getDebugDumpTitle("current"), objectCurrent, indent + 1);

        sb.append("\n");
        DebugUtil.debugDumpWithLabel(sb, getDebugDumpTitle("new"), objectNew, indent + 1);

        sb.append("\n");
        DebugUtil.debugDumpWithLabel(sb, getDebugDumpTitle("primary delta"), primaryDelta, indent + 1);

        sb.append("\n");
        DebugUtil.debugDumpWithLabel(sb, getDebugDumpTitle("secondary delta"), secondaryDelta, indent + 1);

        sb.append("\n");
        DebugUtil.debugDumpWithLabel(sb, getDebugDumpTitle("sync delta"), syncDelta, indent + 1);

        sb.append("\n");
        DebugUtil.debugDumpWithLabel(sb, getDebugDumpTitle("executed deltas"), getExecutedDeltas(), indent+1);

        if (showTriples) {

            sb.append("\n");
            DebugUtil.debugDumpWithLabel(sb, getDebugDumpTitle("evaluatedAssignedConstructionDeltaSetTriple"), evaluatedAssignedConstructionDeltaSetTriple, indent + 1);

            sb.append("\n");
            DebugUtil.debugDumpWithLabel(sb, getDebugDumpTitle("plain (schemaHandling) construction"), evaluatedPlainConstruction, indent + 1);

            sb.append("\n");
            DebugUtil.debugDumpWithLabel(sb, getDebugDumpTitle("squeezed attributes"), squeezedAttributes, indent + 1);

            sb.append("\n");
            DebugUtil.debugDumpWithLabel(sb, getDebugDumpTitle("squeezed associations"), squeezedAssociations, indent + 1);

            sb.append("\n");
            DebugUtil.debugDumpWithLabel(sb, getDebugDumpTitle("squeezed auxiliary object classes"), squeezedAuxiliaryObjectClasses, indent + 1);

            // This is just a debug thing
//            sb.append("\n");
//            DebugUtil.indentDebugDump(sb, indent);
//            sb.append("ACCOUNT dependencies\n");
//            sb.append(DebugUtil.debugDump(dependencies, indent + 1));
        }

        return sb.toString();
    }

    @Override
    protected String getElementDefaultDesc() {
        return "projection";
    }

    @Override
    public String toString() {
        return "LensProjectionContext(" + (getObjectTypeClass() == null ? "null" : getObjectTypeClass().getSimpleName()) + ":" + getOid() +
                ( resource == null ? "" : " on " + resource ) + ")";
    }

    /**
     * Return a human readable name of the projection object suitable for logs.
     */
    public String toHumanReadableString() {
        if (humanReadableString == null) {
            if (resourceShadowDiscriminator == null) {
                humanReadableString = "(null" + resource + ")";
            } else if (resource != null) {
                humanReadableString = "("+getKindValue(resourceShadowDiscriminator.getKind()) + " ("+resourceShadowDiscriminator.getIntent()+") on " + resource + ")";
            } else {
                humanReadableString = "("+getKindValue(resourceShadowDiscriminator.getKind()) + " ("+resourceShadowDiscriminator.getIntent()+") on " + resourceShadowDiscriminator.getResourceOid() + ")";
            }
        }
        return humanReadableString;
    }

    public String getHumanReadableKind() {
        if (resourceShadowDiscriminator == null) {
            return "resource object";
        }
        return getKindValue(resourceShadowDiscriminator.getKind());
    }

    private String getKindValue(ShadowKindType kind) {
        if (kind == null) {
            return "null";
        }
        return kind.value();
    }

    @Override
    protected String getElementDesc() {
        if (resourceShadowDiscriminator == null) {
            return "shadow";
        }
        return getKindValue(resourceShadowDiscriminator.getKind());
    }

    void addToPrismContainer(PrismContainer<LensProjectionContextType> lensProjectionContextTypeContainer, LensContext.ExportType exportType) throws SchemaException {
        LensProjectionContextType lensProjectionContextType = lensProjectionContextTypeContainer.createNewValue().asContainerable();
        super.storeIntoLensElementContextType(lensProjectionContextType, exportType);
        lensProjectionContextType.setWave(wave);
        lensProjectionContextType.setCompleted(completed);
        lensProjectionContextType.setResourceShadowDiscriminator(resourceShadowDiscriminator != null ?
                resourceShadowDiscriminator.toResourceShadowDiscriminatorType() : null);
        lensProjectionContextType.setFullShadow(fullShadow);
        lensProjectionContextType.setIsExists(isExists);
        lensProjectionContextType.setSynchronizationPolicyDecision(synchronizationPolicyDecision != null ? synchronizationPolicyDecision.toSynchronizationPolicyDecisionType() : null);
        lensProjectionContextType.setDoReconciliation(doReconciliation);
        lensProjectionContextType.setSynchronizationSituationDetected(synchronizationSituationDetected);
        lensProjectionContextType.setSynchronizationSituationResolved(synchronizationSituationResolved);
        if (exportType != LensContext.ExportType.MINIMAL) {
            lensProjectionContextType.setSyncDelta(syncDelta != null ? DeltaConvertor.toObjectDeltaType(syncDelta) : null);
            lensProjectionContextType.setIsAssigned(isAssigned);
            lensProjectionContextType.setIsAssignedOld(isAssignedOld);
            lensProjectionContextType.setIsActive(isActive);
            lensProjectionContextType.setIsLegal(isLegal);
            lensProjectionContextType.setIsLegalOld(isLegalOld);
            if (exportType != LensContext.ExportType.REDUCED && projectionSecurityPolicy != null) {
                ObjectReferenceType secRef = new ObjectReferenceType();
                secRef.asReferenceValue().setObject(projectionSecurityPolicy.asPrismObject());
                lensProjectionContextType.setProjectionSecurityPolicyRef(secRef);
            }
            lensProjectionContextType.setSyncAbsoluteTrigger(syncAbsoluteTrigger);
        }
    }

    @NotNull
    static LensProjectionContext fromLensProjectionContextType(LensProjectionContextType projectionContextType,
            LensContext lensContext, Task task, OperationResult result) throws SchemaException, ConfigurationException,
            ObjectNotFoundException, CommunicationException, ExpressionEvaluationException {

        String objectTypeClassString = projectionContextType.getObjectTypeClass();
        if (StringUtils.isEmpty(objectTypeClassString)) {
            throw new SystemException("Object type class is undefined in LensProjectionContextType");
        }
        ResourceShadowDiscriminator resourceShadowDiscriminator = ResourceShadowDiscriminator.fromResourceShadowDiscriminatorType(
                projectionContextType.getResourceShadowDiscriminator(), false);

        LensProjectionContext projectionContext = new LensProjectionContext(lensContext, resourceShadowDiscriminator);

        projectionContext.retrieveFromLensElementContextType(projectionContextType, task, result);
        if (projectionContextType.getSyncDelta() != null) {
            projectionContext.syncDelta = DeltaConvertor.createObjectDelta(projectionContextType.getSyncDelta(), lensContext.getPrismContext());
        } else {
            projectionContext.syncDelta = null;
        }
        ObjectDeltaType secondaryDeltaType = projectionContextType.getSecondaryDelta();
        projectionContext.secondaryDelta = secondaryDeltaType != null ?
                DeltaConvertor.createObjectDelta(secondaryDeltaType, lensContext.getPrismContext()) : null;
        ObjectType object = projectionContextType.getObjectNew() != null ? projectionContextType.getObjectNew() : projectionContextType.getObjectOld();
        projectionContext.fixProvisioningTypeInDelta(projectionContext.secondaryDelta, object, task, result);

        projectionContext.wave = projectionContextType.getWave() != null ? projectionContextType.getWave() : 0;
        projectionContext.completed = BooleanUtils.isTrue(projectionContextType.isCompleted());
        projectionContext.fullShadow = projectionContextType.isFullShadow() != null ? projectionContextType.isFullShadow() : false;
        projectionContext.isAssigned = projectionContextType.isIsAssigned() != null ? projectionContextType.isIsAssigned() : false;
        projectionContext.isAssignedOld = projectionContextType.isIsAssignedOld() != null ? projectionContextType.isIsAssignedOld() : false;
        projectionContext.isActive = projectionContextType.isIsActive() != null ? projectionContextType.isIsActive() : false;
        projectionContext.isLegal = projectionContextType.isIsLegal();
        projectionContext.isLegalOld = projectionContextType.isIsLegalOld();
        projectionContext.isExists = projectionContextType.isIsExists() != null ? projectionContextType.isIsExists() : false;
        projectionContext.synchronizationPolicyDecision = SynchronizationPolicyDecision.fromSynchronizationPolicyDecisionType(projectionContextType.getSynchronizationPolicyDecision());
        projectionContext.doReconciliation = projectionContextType.isDoReconciliation() != null ? projectionContextType.isDoReconciliation() : false;
        projectionContext.synchronizationSituationDetected = projectionContextType.getSynchronizationSituationDetected();
        projectionContext.synchronizationSituationResolved = projectionContextType.getSynchronizationSituationResolved();
        ObjectReferenceType projectionSecurityPolicyRef = projectionContextType.getProjectionSecurityPolicyRef();
        if (projectionSecurityPolicyRef != null) {
            projectionContext.projectionSecurityPolicy = (SecurityPolicyType) projectionSecurityPolicyRef.getObjectable();
        }
        projectionContext.syncAbsoluteTrigger = projectionContextType.isSyncAbsoluteTrigger();

        return projectionContext;
    }

    // determines whether full shadow is present, based on operation result got from provisioning
    public void determineFullShadowFlag(PrismObject<ShadowType> loadedShadow) {
        ShadowType shadowType = loadedShadow.asObjectable();
        if (ShadowUtil.isDead(shadowType) || !ShadowUtil.isExists(shadowType)) {
            setFullShadow(false);
            return;
        }
        OperationResultType fetchResult = shadowType.getFetchResult();
        AdministrativeAvailabilityStatusType resourceAdministrativeAvailabilityStatus = ResourceTypeUtil.getAdministrativeAvailabilityStatus(resource);

        if (AdministrativeAvailabilityStatusType.MAINTENANCE == resourceAdministrativeAvailabilityStatus) {
            setFullShadow(false); // resource is in the maintenance, shadow is from repo, result is success
        } else if (fetchResult != null
                && (fetchResult.getStatus() == OperationResultStatusType.PARTIAL_ERROR
                    || fetchResult.getStatus() == OperationResultStatusType.FATAL_ERROR)) {  // todo what about other kinds of status? [e.g. in-progress]
               setFullShadow(false);
        } else {
            setFullShadow(true);
        }
    }

    public boolean isToBeArchived() {
        return toBeArchived;
    }

    public void setToBeArchived(boolean toBeArchived) {
        this.toBeArchived = toBeArchived;
    }

    public String getResourceOid() {
        if (resource != null) {
            return resource.getOid();
        } else if (resourceShadowDiscriminator != null) {
            return resourceShadowDiscriminator.getResourceOid();
        } else {
            return null;
        }
    }

    public ResourceObjectVolatilityType getVolatility() throws SchemaException {
        RefinedObjectClassDefinition structuralObjectClassDefinition = getStructuralObjectClassDefinition();
        if (structuralObjectClassDefinition == null) {
            return null;
        }
        return structuralObjectClassDefinition.getVolatility();
    }

    public boolean hasPendingOperations() {
        PrismObject<ShadowType> current = getObjectCurrent();
        if (current == null) {
            return false;
        }
        return !current.asObjectable().getPendingOperation().isEmpty();
    }

    @Override
    public void forEachDelta(Consumer<ObjectDelta<ShadowType>> consumer) {
        super.forEachDelta(consumer);
        if (syncDelta != null) {
            consumer.accept(syncDelta);
        }
    }

    PolyString resolveNameIfKnown(Class<? extends ObjectType> objectClass, String oid) {
        if (ResourceType.class.equals(objectClass)) {
            if (resource != null && oid.equals(resource.getOid())) {
                return PolyString.toPolyString(resource.getName());
            }
        } else if (ShadowType.class.equals(objectClass)) {
            PrismObject<ShadowType> object = getObjectAny();
            if (object != null && oid.equals(object.getOid())) {
                if (object.getName() != null) {
                    return object.getName();
                } else {
                    try {
                        return ShadowUtil.determineShadowName(object);
                    } catch (SchemaException e) {
                        LoggingUtils.logUnexpectedException(LOGGER, "Couldn't determine shadow name for {}", e, object);
                        return null;
                    }
                }
            }
        }
        return null;
    }

    public String getResourceName() {
        ResourceType resource = getResource();
        return resource != null ? PolyString.getOrig(resource.getName()) : getResourceOid();
    }

    public boolean isSynchronizationSource() {
        return synchronizationSource;
    }

    public void setSynchronizationSource(boolean synchronizationSource) {
        this.synchronizationSource = synchronizationSource;
    }

    public String getDescription() {
        if (resource != null) {
            return resource + "("+ resourceShadowDiscriminator.getIntent()+")";
        } else {
            if (resourceShadowDiscriminator != null) {
                return resourceShadowDiscriminator.toString();
            } else {
                return "(UNKNOWN)";
            }
        }
    }

    @Override
    boolean doesPrimaryDeltaApply() {
        return true; // TODO is this OK?
    }

    /**
     * @return True if the projection is "current" i.e. it was not completed and its wave is
     * either not yet determined or equal to the current projection wave.
     */
    @Experimental
    public boolean isCurrentForProjection() {
        if (completed) {
            return false;
        }
        if (wave != -1 && wave != getLensContext().getProjectionWave()) {
            return false;
        }
        return true;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    @Override
    public void rot() {
        super.rot();
        setFullShadow(false);
        cachedValueMetadata = null;
    }

    public ValueMetadataType getCachedValueMetadata() {
        return cachedValueMetadata;
    }

    public void setCachedValueMetadata(ValueMetadataType cachedValueMetadata) {
        this.cachedValueMetadata = cachedValueMetadata;
    }

    public boolean isHigherOrder() {
        return resourceShadowDiscriminator != null && resourceShadowDiscriminator.getOrder() > 0;
    }

    public boolean isBroken() {
        return synchronizationPolicyDecision == SynchronizationPolicyDecision.BROKEN;
    }
}
