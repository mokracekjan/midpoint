/*
 * Copyright (c) 2010-2019 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.web.page.admin.roles;

import java.util.*;
import javax.xml.bind.JAXBElement;
import javax.xml.namespace.QName;

import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;

import org.apache.commons.lang3.StringUtils;
import org.apache.wicket.ajax.AjaxRequestTarget;

import com.evolveum.midpoint.gui.api.component.ChooseArchetypeMemberPopup;
import com.evolveum.midpoint.gui.api.component.ChooseMemberPopup;
import com.evolveum.midpoint.gui.api.component.ChooseOrgMemberPopup;
import com.evolveum.midpoint.gui.api.page.PageBase;
import com.evolveum.midpoint.gui.api.util.WebComponentUtil;
import com.evolveum.midpoint.model.api.ModelAuthorizationAction;
import com.evolveum.midpoint.model.api.ModelPublicConstants;
import com.evolveum.midpoint.prism.*;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.prism.query.builder.S_AtomicFilterExit;
import com.evolveum.midpoint.prism.query.builder.S_FilterEntry;
import com.evolveum.midpoint.schema.GetOperationOptions;
import com.evolveum.midpoint.schema.SelectorOptions;
import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.schema.util.MiscSchemaUtil;
import com.evolveum.midpoint.schema.util.ObjectTypeUtil;
import com.evolveum.midpoint.security.api.MidPointPrincipal;
import com.evolveum.midpoint.security.enforcer.api.AuthorizationParameters;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.DOMUtil;
import com.evolveum.midpoint.util.QNameUtil;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.logging.LoggingUtils;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.web.page.admin.dto.ObjectViewDto;
import com.evolveum.midpoint.web.page.admin.roles.AbstractRoleMemberPanel.QueryScope;
import com.evolveum.midpoint.web.security.util.SecurityUtils;
import com.evolveum.midpoint.xml.ns._public.common.common_3.*;
import com.evolveum.midpoint.xml.ns._public.model.scripting_3.ActionExpressionType;
import com.evolveum.midpoint.xml.ns._public.model.scripting_3.ActionParameterValueType;
import com.evolveum.midpoint.xml.ns._public.model.scripting_3.ExecuteScriptType;
import com.evolveum.prism.xml.ns._public.query_3.QueryType;
import com.evolveum.prism.xml.ns._public.types_3.RawType;

public class MemberOperationsHelper {

    private static final Trace LOGGER = TraceManager.getTrace(AbstractRoleMemberPanel.class);

    public static final String UNASSIGN_OPERATION = "unassign";
    public static final String ASSIGN_OPERATION = "assign";
    public static final String DELETE_OPERATION = "delete";
    public static final String RECOMPUTE_OPERATION = "recompute";
    public static final String ROLE_PARAMETER = "role";
    public static final String RELATION_PARAMETER = "relation";

    public static <R extends AbstractRoleType> void unassignMembersPerformed(PageBase pageBase, R targetObject, QueryScope scope,
            ObjectQuery query, Collection<QName> relations, QName type, AjaxRequestTarget target) {
        String taskNameBuilder = getTaskName("Remove", scope, targetObject, "from");
        Task operationalTask = pageBase.createSimpleTask(taskNameBuilder);
        String taskName = pageBase.createStringResource(taskNameBuilder,
                WebComponentUtil.getDisplayNameOrName(targetObject.asPrismObject())).getString();

        ExecuteScriptType script = new ExecuteScriptType();
        ActionExpressionType expression = new ActionExpressionType();
        expression.setType(UNASSIGN_OPERATION);

        //hack using fake definition because of type
        PrismPropertyDefinition<Object> def = pageBase.getPrismContext().definitionFactory().createPropertyDefinition(
                AbstractRoleType.F_NAME, DOMUtil.XSD_STRING);
        PrismValue value = pageBase.getPrismContext().itemFactory().createValue(targetObject.getOid());
        try {
            value.applyDefinition(def);
        } catch (SchemaException e) {
            LoggingUtils.logUnexpectedException(LOGGER, "Can not apply definition " + def, e);
            operationalTask.getResult().recordFatalError(pageBase.createStringResource("MemberOperationsHelper.message.unassignMembersPerformed.fatalError", def).getString(), e);
        }
        expression.parameter(new ActionParameterValueType().name(ROLE_PARAMETER).value(
                new RawType(value, DOMUtil.XSD_STRING, pageBase.getPrismContext())));
        if(relations != null) {
            relations.forEach(relation -> expression.parameter(new ActionParameterValueType().name(RELATION_PARAMETER).value(QNameUtil.qNameToUri(relation))));
        }
        script.setScriptingExpression(new JAXBElement<>(SchemaConstants.S_ACTION,
                ActionExpressionType.class, expression));

        createAndExecuteScriptingMemberOperationTask(pageBase, operationalTask, type, query, script,
                WebComponentUtil.createPolyFromOrigString(taskName), target);
    }

    public static void assignMembersPerformed(AbstractRoleType targetObject, ObjectQuery query,
            QName relation, QName type, AjaxRequestTarget target, PageBase pageBase) {
        String taskName = pageBase.createStringResource(getTaskName("Add", null, targetObject, "to"),
                WebComponentUtil.getDisplayNameOrName(targetObject.asPrismObject())).getString();
        Task operationalTask = pageBase.createSimpleTask(taskName);

        ExecuteScriptType script = new ExecuteScriptType();
        ActionExpressionType expression = new ActionExpressionType();
        expression.setType(ASSIGN_OPERATION);

        PrismReferenceValue value = pageBase.getPrismContext().itemFactory()
                .createReferenceValue(targetObject.getOid(), WebComponentUtil.classToQName(pageBase.getPrismContext(), targetObject.getClass()));
        expression.parameter(new ActionParameterValueType().name(ROLE_PARAMETER).value(
                new RawType(value, ObjectReferenceType.COMPLEX_TYPE, pageBase.getPrismContext())));
        if(relation != null) {
            expression.parameter(new ActionParameterValueType().name(RELATION_PARAMETER).value(QNameUtil.qNameToUri(relation)));
        }
        script.setScriptingExpression(new JAXBElement<>(SchemaConstants.S_ACTION,
                ActionExpressionType.class, expression));

        createAndExecuteScriptingMemberOperationTask(pageBase, operationalTask, type, query, script,
                WebComponentUtil.createPolyFromOrigString(taskName), target);
    }

    public static <R extends AbstractRoleType> void deleteMembersPerformed(R targetObject,
            PageBase pageBase, QueryScope scope, ObjectQuery query, AjaxRequestTarget target) {
        Task task = createDeleteMembersTask(targetObject, pageBase, scope, query, target);
        if (task != null) {
            executeMemberOperationTask(pageBase, task, target);
        }
    }

    public static <R extends AbstractRoleType> void recomputeMembersPerformed(R targetObject,
            PageBase pageBase, QueryScope scope, ObjectQuery query, AjaxRequestTarget target) {
        Task task = createRecomputeMembersTask(targetObject, pageBase, scope, query, target);
        if (task != null) {
            executeMemberOperationTask(pageBase, task, target);
        }
    }

    public static <R extends AbstractRoleType> Task createRecomputeMembersTask(R targetObject, PageBase pageBase, QueryScope scope,
            ObjectQuery query, AjaxRequestTarget target) {
        String taskNameBuilder = getTaskName("Recompute", scope, targetObject, "of");
        Task operationalTask = pageBase.createSimpleTask(taskNameBuilder);
        String taskName = pageBase.createStringResource(taskNameBuilder,
                WebComponentUtil.getDisplayNameOrName(targetObject.asPrismObject())).getString();

        OperationResult parentResult = operationalTask.getResult();
        return createRecomputeMemberOperationTask(operationalTask, AssignmentHolderType.COMPLEX_TYPE, query,
                null, parentResult, pageBase, WebComponentUtil.createPolyFromOrigString(taskName), target);
    }

    private static <R extends AbstractRoleType> Task createDeleteMembersTask(R targetObject, PageBase pageBase, QueryScope scope,
            ObjectQuery query, AjaxRequestTarget target) {
        QName defaultType = AssignmentHolderType.COMPLEX_TYPE;

        String taskNameBuilder = getTaskName(DELETE_OPERATION, scope, targetObject, "of");
        Task operationalTask = pageBase.createSimpleTask(taskNameBuilder);
        String taskName = pageBase.createStringResource(taskNameBuilder,
                WebComponentUtil.getDisplayNameOrName(targetObject.asPrismObject())).getString();

        ExecuteScriptType script = new ExecuteScriptType();
        ActionExpressionType expression = new ActionExpressionType();
        expression.setType("delete");

        script.setScriptingExpression(new JAXBElement<>(SchemaConstants.S_ACTION,
                ActionExpressionType.class, expression));

        return createScriptingMemberOperationTask(pageBase, operationalTask, defaultType, query, script,
                SelectorOptions.createCollection(GetOperationOptions.createDistinct()),
                WebComponentUtil.createPolyFromOrigString(taskName), target);
    }

    public static <R extends AbstractRoleType> void assignMembers(PageBase pageBase, R targetRefObject, AjaxRequestTarget target,
                                                                  RelationSearchItemConfigurationType relationConfig, List<QName> objectTypes) {
        assignMembers(pageBase, targetRefObject, target, relationConfig, objectTypes, true);

    }

    public static <R extends AbstractRoleType> void assignMembers(PageBase pageBase, R targetRefObject, AjaxRequestTarget target,
                                                                  RelationSearchItemConfigurationType relationConfig, List<QName> objectTypes, boolean isOrgTreePanelVisible) {
        assignMembers(pageBase, targetRefObject, target, relationConfig, objectTypes, new ArrayList<>(), isOrgTreePanelVisible);
    }

    public static <O extends ObjectType, R extends AbstractRoleType> void assignMembers(PageBase pageBase, R targetRefObject, AjaxRequestTarget target,
                                                                                        RelationSearchItemConfigurationType relationConfig, List<QName> objectTypes, List<ObjectReferenceType> archetypeRefList, boolean isOrgTreePanelVisible) {

        ChooseMemberPopup<O, R> browser = new ChooseMemberPopup<O, R>(pageBase.getMainPopupBodyId(), relationConfig) {
            private static final long serialVersionUID = 1L;

            @Override
            protected R getAssignmentTargetRefObject(){
                return targetRefObject;
            }

            @Override
            protected List<QName> getAvailableObjectTypes(){
                return objectTypes;
            }

            @Override
            protected List<ObjectReferenceType> getArchetypeRefList(){
                return archetypeRefList;
            }

            @Override
            protected boolean isOrgTreeVisible(){
                return isOrgTreePanelVisible;
            }
        };
        browser.setOutputMarkupId(true);
        pageBase.showMainPopup(browser, target);
    }

    public static <O extends ObjectType> void assignOrgMembers(PageBase pageBase, OrgType targetRefObject, AjaxRequestTarget target,
                                                               RelationSearchItemConfigurationType relationConfig, List<QName> objectTypes, List<ObjectReferenceType> archetypeRefList) {
        ChooseOrgMemberPopup<O> browser = new ChooseOrgMemberPopup<O>(pageBase.getMainPopupBodyId(), relationConfig) {

            private static final long serialVersionUID = 1L;

            @Override
            protected OrgType getAssignmentTargetRefObject(){
                return targetRefObject;
            }

            @Override
            protected List<QName> getAvailableObjectTypes(){
                return objectTypes;
            }

            @Override
            protected List<ObjectReferenceType> getArchetypeRefList(){
                return archetypeRefList;
            }
        };

        browser.setOutputMarkupId(true);
        pageBase.showMainPopup(browser, target);
    }

    public static <O extends AssignmentHolderType> void assignArchetypeMembers(PageBase pageBase, ArchetypeType targetRefObject, AjaxRequestTarget target,
                                                                               RelationSearchItemConfigurationType relationConfig, List<QName> objectTypes, List<ObjectReferenceType> archetypeRefList) {
        ChooseArchetypeMemberPopup<O> browser = new ChooseArchetypeMemberPopup<O>(pageBase.getMainPopupBodyId(), relationConfig) {

            private static final long serialVersionUID = 1L;

            @Override
            protected ArchetypeType getAssignmentTargetRefObject(){
                return targetRefObject;
            }

            @Override
            protected List<QName> getAvailableObjectTypes(){
                return objectTypes;
            }

            @Override
            protected List<ObjectReferenceType> getArchetypeRefList(){
                return archetypeRefList;
            }
        };

        browser.setOutputMarkupId(true);
        pageBase.showMainPopup(browser, target);
    }

    public static <R extends AbstractRoleType> ObjectQuery createDirectMemberQuery(R targetObject, QName objectType, Collection<QName> relations, ObjectViewDto<OrgType> tenantObject, ObjectViewDto<OrgType> projectObject, PrismContext prismContext) {
        ObjectReferenceType tenant = null;
        ObjectReferenceType project = null;
        if (tenantObject != null && tenantObject.getObject() != null) {
            tenant = new ObjectReferenceType();
            tenant.setOid(tenantObject.getObject().getOid());
        }
        if (projectObject != null && projectObject.getObject() != null) {
            project = new ObjectReferenceType();
            project.setOid(projectObject.getObject().getOid());
        }
        return createDirectMemberQuery(targetObject, objectType, relations, tenant, project, prismContext);
    }

    public static <R extends AbstractRoleType> ObjectQuery createDirectMemberQuery(R targetObject, QName objectType, Collection<QName> relations, ObjectReferenceType tenant, ObjectReferenceType project, PrismContext prismContext) {
        // We assume tenantRef.relation and orgRef.relation are always default ones (see also MID-3581)
        S_FilterEntry q0 = prismContext.queryFor(AssignmentHolderType.class);
        if (objectType != null && !AssignmentHolderType.COMPLEX_TYPE.equals(objectType)) {
            q0 = q0.type(objectType);
        }

        // Use exists filter to build a query like this:
        // $a/targetRef = oid1 and $a/tenantRef = oid2 and $a/orgRef = oid3
        S_AtomicFilterExit q = q0.exists(AssignmentHolderType.F_ASSIGNMENT)
                .block()
                .item(AssignmentType.F_TARGET_REF)
                .ref(createReferenceValuesList(targetObject, relations));

        if (tenant != null && StringUtils.isNotEmpty(tenant.getOid())) {
            q = q.and().item(AssignmentType.F_TENANT_REF).ref(tenant.getOid());
        }

        if (project != null && StringUtils.isNotEmpty(project.getOid())) {
            q = q.and().item(AssignmentType.F_ORG_REF).ref(project.getOid());
        }

        ObjectQuery query = q.endBlock().build();
        if (LOGGER.isTraceEnabled()) {
            LOGGER.trace("Searching members of role {} with query:\n{}", targetObject.getOid(), query.debugDump());
        }
        return query;
    }

    public static <R extends AbstractRoleType> List<PrismReferenceValue> createReferenceValuesList(R targetObject, Collection<QName> relations) {
        List<PrismReferenceValue> referenceValuesList = new ArrayList<>();
        relations.forEach(relation -> referenceValuesList.add(createReference(targetObject, relation).asReferenceValue()));
        return referenceValuesList;
    }

    public static <O extends ObjectType> ObjectQuery createSelectedObjectsQuery(List<O> selectedObjects,
            PrismContext prismContext) {
        Set<String> oids = getFocusOidToRecompute(selectedObjects);
        return prismContext.queryFor(AssignmentHolderType.class).id(oids.toArray(new String[0])).build();
    }

    public static <O extends ObjectType> Set<String> getFocusOidToRecompute(List<O> selectedObjects) {
        Set<String> oids = new HashSet<>();
        selectedObjects.forEach(f -> oids.add(f.getOid()));
        return oids;
    }

    private static <R extends AbstractRoleType> String getTaskName(String operation, QueryScope scope, R targetObject, String preposition) {
        StringBuilder nameBuilder = new StringBuilder(operation);
        nameBuilder.append(".");
        if (scope != null) {
            nameBuilder.append(scope.name());
            nameBuilder.append(".");
        }
        nameBuilder.append("members").append(".");
        if (targetObject != null) {
            nameBuilder.append(preposition != null ? preposition : "").append(preposition != null ? "." : "");
            String objectType = targetObject.getClass().getSimpleName();
            if (objectType.endsWith("Type")) {
                objectType = objectType.substring(0, objectType.indexOf("Type"));
            }
            nameBuilder.append(objectType);
        }
        return nameBuilder.toString().toLowerCase();
    }

    public static <R extends AbstractRoleType> ObjectReferenceType createReference(R targetObject, QName relation) {
        return ObjectTypeUtil.createObjectRef(targetObject, relation);
    }

    protected static Task createScriptingMemberOperationTask(PageBase modelServiceLocator, Task operationalTask, QName type, ObjectQuery memberQuery,
            ExecuteScriptType script, Collection<SelectorOptions<GetOperationOptions>> option, PolyStringType taskName, AjaxRequestTarget target) {

        OperationResult parentResult = operationalTask.getResult();
        return createScriptingMemberOperationTask(operationalTask, type, memberQuery, script, option, parentResult,
                modelServiceLocator, taskName, target);
    }

    protected static void createAndExecuteScriptingMemberOperationTask(PageBase modelServiceLocator, Task operationalTask,
            QName type, ObjectQuery memberQuery, ExecuteScriptType script, PolyStringType taskName, AjaxRequestTarget target) {

        OperationResult parentResult = operationalTask.getResult();
        Task executableTask = createScriptingMemberOperationTask(operationalTask, type, memberQuery, script, null, parentResult,
                modelServiceLocator, taskName, target);
        if (executableTask != null) {
            executeMemberOperationTask(executableTask, parentResult, modelServiceLocator);
        }
        target.add(modelServiceLocator.getFeedbackPanel());
    }

    protected static void executeMemberOperationTask(PageBase modelServiceLocator, Task operationalTask, AjaxRequestTarget target) {
        OperationResult parentResult = operationalTask.getResult();
        executeMemberOperationTask(operationalTask, parentResult, modelServiceLocator);
        target.add(modelServiceLocator.getFeedbackPanel());
    }

    public static Task createScriptingMemberOperationTask(Task operationalTask, QName type, ObjectQuery memberQuery,
            ExecuteScriptType script, Collection<SelectorOptions<GetOperationOptions>> option, OperationResult parentResult,
            PageBase pageBase, PolyStringType taskName, AjaxRequestTarget target) {

        try {
            createTask(operationalTask, type, memberQuery, option, taskName, pageBase);
            pageBase.getSecurityEnforcer().authorize(ModelAuthorizationAction.EXECUTE_SCRIPT.getUrl(),
                    null, AuthorizationParameters.EMPTY, null, operationalTask, parentResult);
            operationalTask.setExtensionPropertyValue(SchemaConstants.SE_EXECUTE_SCRIPT, script);
            operationalTask.setHandlerUri(ModelPublicConstants.ITERATIVE_SCRIPT_EXECUTION_TASK_HANDLER_URI);
            operationalTask.addArchetypeInformationIfMissing(SystemObjectsType.ARCHETYPE_ITERATIVE_BULK_ACTION_TASK.value());
            return operationalTask;
        } catch (Exception e) {
            parentResult.recordFatalError(pageBase.createStringResource("WebComponentUtil.message.startPerformed.fatalError.createTask").getString(), e);
            LoggingUtils.logUnexpectedException(LOGGER, "Couldn't create bulk action task", e);
            target.add(pageBase.getFeedbackPanel());
            return null;
        }
    }

    public static Task createRecomputeMemberOperationTask(Task operationalTask, QName type, ObjectQuery memberQuery,
            Collection<SelectorOptions<GetOperationOptions>> option, OperationResult parentResult,
            PageBase pageBase, PolyStringType taskName, AjaxRequestTarget target) {
        try {
            createTask(operationalTask, type, memberQuery, option, taskName, pageBase);
            pageBase.getSecurityEnforcer().authorize(ModelAuthorizationAction.RECOMPUTE.getUrl(),
                    null, AuthorizationParameters.EMPTY, null, operationalTask, parentResult);
            operationalTask.setHandlerUri(ModelPublicConstants.RECOMPUTE_HANDLER_URI);
            operationalTask.addArchetypeInformationIfMissing(SystemObjectsType.ARCHETYPE_RECOMPUTATION_TASK.value());
            return operationalTask;
        } catch (Exception e) {
            parentResult.recordFatalError(pageBase.createStringResource("WebComponentUtil.message.startPerformed.fatalError.createTask").getString(), e);
            LoggingUtils.logUnexpectedException(LOGGER, "Couldn't create bulk action task", e);
            target.add(pageBase.getFeedbackPanel());
            return null;
        }
    }

    private static void createTask(Task operationalTask, QName type, ObjectQuery memberQuery, Collection<SelectorOptions<GetOperationOptions>> option,
            PolyStringType taskName, PageBase pageBase) throws SchemaException {
        MidPointPrincipal owner = SecurityUtils.getPrincipalUser();
        operationalTask.setOwner(owner.getFocus().asPrismObject());

        operationalTask.setInitiallyRunnable();
        operationalTask.setThreadStopAction(ThreadStopActionType.RESTART);
        ScheduleType schedule = new ScheduleType();
        schedule.setMisfireAction(MisfireActionType.EXECUTE_IMMEDIATELY);
        operationalTask.makeSingle(schedule);
        operationalTask.setName("operation." + taskName);

        QueryType queryType = pageBase.getQueryConverter().createQueryType(memberQuery);
        operationalTask.setExtensionPropertyValue(SchemaConstants.MODEL_EXTENSION_OBJECT_QUERY, queryType);
        operationalTask.setExtensionPropertyValue(SchemaConstants.MODEL_EXTENSION_OBJECT_TYPE, type);
        if (option != null) {
            operationalTask.setExtensionContainerValue(SchemaConstants.MODEL_EXTENSION_SEARCH_OPTIONS,
                    MiscSchemaUtil.optionsToOptionsType(option));
        }
    }

    public static void executeMemberOperationTask(Task operationalTask, OperationResult parentResult, PageBase pageBase) {
        OperationResult result = parentResult.createSubresult("evaluateExpressionInBackground");
        pageBase.getTaskManager().switchToBackground(operationalTask, result);
        result.computeStatus();
        parentResult.recordInProgress();
        parentResult.setBackgroundTaskOid(operationalTask.getOid());
        pageBase.showResult(parentResult);
    }

}
