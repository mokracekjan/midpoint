/*
 * Copyright (c) 2010-2019 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.report.impl;

import java.util.*;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.common.Clock;
import com.evolveum.midpoint.common.LocalizationService;
import com.evolveum.midpoint.model.api.ModelInteractionService;
import com.evolveum.midpoint.model.api.ScriptingService;
import com.evolveum.midpoint.model.api.authentication.CompiledObjectCollectionView;
import com.evolveum.midpoint.model.api.interaction.DashboardService;
import com.evolveum.midpoint.model.common.util.DefaultColumnUtils;
import com.evolveum.midpoint.repo.common.commandline.CommandLineScriptExecutor;

import com.evolveum.midpoint.report.api.ReportConstants;
import com.evolveum.midpoint.schema.*;

import com.evolveum.midpoint.xml.ns._public.common.audit_3.AuditEventRecordType;

import com.evolveum.prism.xml.ns._public.types_3.RawType;

import org.apache.commons.lang.Validate;
import org.apache.commons.lang3.ObjectUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.evolveum.midpoint.audit.api.AuditService;
import com.evolveum.midpoint.model.api.ModelAuthorizationAction;
import com.evolveum.midpoint.model.api.ModelService;
import com.evolveum.midpoint.model.common.ArchetypeManager;
import com.evolveum.midpoint.model.common.expression.ExpressionEnvironment;
import com.evolveum.midpoint.model.common.expression.ModelExpressionThreadLocalHolder;
import com.evolveum.midpoint.model.common.expression.functions.FunctionLibrary;
import com.evolveum.midpoint.model.common.expression.script.ScriptExpression;
import com.evolveum.midpoint.model.common.expression.script.ScriptExpressionEvaluationContext;
import com.evolveum.midpoint.model.common.expression.script.ScriptExpressionEvaluatorFactory;
import com.evolveum.midpoint.model.common.expression.script.ScriptExpressionFactory;
import com.evolveum.midpoint.model.common.expression.script.groovy.GroovyScriptEvaluator;
import com.evolveum.midpoint.prism.*;
import com.evolveum.midpoint.repo.common.ObjectResolver;
import com.evolveum.midpoint.repo.common.expression.ExpressionFactory;
import com.evolveum.midpoint.repo.common.expression.ExpressionUtil;
import com.evolveum.midpoint.schema.expression.VariablesMap;
import com.evolveum.midpoint.report.api.ReportService;
import com.evolveum.midpoint.schema.expression.*;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.security.enforcer.api.AuthorizationParameters;
import com.evolveum.midpoint.security.enforcer.api.SecurityEnforcer;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.task.api.TaskManager;
import com.evolveum.midpoint.util.exception.*;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_3.*;

@Component
public class ReportServiceImpl implements ReportService {

    private static final Trace LOGGER = TraceManager.getTrace(ReportServiceImpl.class);

    @Autowired private ModelService model;
    @Autowired private TaskManager taskManager;
    @Autowired private PrismContext prismContext;
    @Autowired private SchemaService schemaService;
    @Autowired private ExpressionFactory expressionFactory;
    @Autowired @Qualifier("modelObjectResolver") private ObjectResolver objectResolver;
    @Autowired private AuditService auditService;
    @Autowired private FunctionLibrary logFunctionLibrary;
    @Autowired private FunctionLibrary basicFunctionLibrary;
    @Autowired private FunctionLibrary midpointFunctionLibrary;
    @Autowired private SecurityEnforcer securityEnforcer;
    @Autowired private ScriptExpressionFactory scriptExpressionFactory;
    @Autowired private ArchetypeManager archetypeManager;

    @Autowired private Clock clock;
    @Autowired private ModelService modelService;
    @Autowired private ModelInteractionService modelInteractionService;
    @Autowired private DashboardService dashboardService;
    @Autowired private LocalizationService localizationService;
    @Autowired private CommandLineScriptExecutor commandLineScriptExecutor;
    @Autowired private ScriptingService scriptingService;

    @Override
    public Object evaluateScript(PrismObject<ReportType> report, @NotNull ExpressionType expression, VariablesMap variables, String shortDesc, Task task, OperationResult result)
                    throws SchemaException, ExpressionEvaluationException, ObjectNotFoundException, CommunicationException, ConfigurationException, SecurityViolationException {
        Object o;
        if (expression.getExpressionEvaluator().size() == 1
                && expression.getExpressionEvaluator().get(0).getValue() instanceof ScriptExpressionEvaluatorType) {
            ScriptExpressionEvaluationContext context = new ScriptExpressionEvaluationContext();
            context.setVariables(variables);
            context.setContextDescription(shortDesc);
            context.setTask(task);
            context.setResult(result);
            setupExpressionProfiles(context, report);

            ScriptExpressionEvaluatorType expressionType = (ScriptExpressionEvaluatorType)expression.getExpressionEvaluator().get(0).getValue();
            if (expressionType.getObjectVariableMode() == null) {
                ScriptExpressionEvaluatorConfigurationType defaultScriptConfiguration = report.asObjectable().getDefaultScriptConfiguration();
                expressionType.setObjectVariableMode(defaultScriptConfiguration == null ? ObjectVariableModeType.OBJECT : defaultScriptConfiguration.getObjectVariableMode());
            }
            context.setExpressionType(expressionType);
            context.setFunctions(createFunctionLibraries());
            context.setObjectResolver(objectResolver);

            ScriptExpression scriptExpression = scriptExpressionFactory.createScriptExpression(
                    expressionType, context.getOutputDefinition(), context.getExpressionProfile(), expressionFactory, context.getContextDescription(),
                    context.getResult());

            ModelExpressionThreadLocalHolder.pushExpressionEnvironment(new ExpressionEnvironment<>(context.getTask(), context.getResult()));
            @NotNull List<PrismValue> expressionResult;
            try {
                expressionResult = scriptExpression.evaluate(context);
            } finally {
                ModelExpressionThreadLocalHolder.popExpressionEnvironment();
            }

            if (expressionResult.isEmpty()) {
                return null;
            }
            if (expressionResult.size() > 1) {
                throw new ExpressionEvaluationException("Too many results from expression "+context.getContextDescription());
            }
            if (expressionResult.get(0) == null ) {
                return null;
            }
            return expressionResult.get(0).getRealValue();

        } else {
            o = ExpressionUtil.evaluateExpression(null, variables, null, expression,
                    determineExpressionProfile(report, result), expressionFactory, shortDesc, task, result);
        }
        return o;
    }

    private Collection<FunctionLibrary> createFunctionLibraries() {
        FunctionLibrary midPointLib = new FunctionLibrary();
        midPointLib.setVariableName("report");
        midPointLib.setNamespace("http://midpoint.evolveum.com/xml/ns/public/function/report-3");
        ReportFunctions reportFunctions = new ReportFunctions(prismContext, schemaService, model, taskManager, auditService);
        midPointLib.setGenericFunctions(reportFunctions);

        Collection<FunctionLibrary> functions = new ArrayList<>();
        functions.add(basicFunctionLibrary);
        functions.add(logFunctionLibrary);
        functions.add(midpointFunctionLibrary);
        functions.add(midPointLib);
        return functions;
    }

    public PrismContext getPrismContext() {
        return prismContext;
    }

    public ExpressionProfile determineExpressionProfile(PrismObject<ReportType> report, OperationResult result) throws SchemaException, ConfigurationException {
        if (report == null) {
            throw new IllegalArgumentException("No report defined, cannot determine profile");
        }
        return archetypeManager.determineExpressionProfile(report, result);
    }

    private void setupExpressionProfiles(ScriptExpressionEvaluationContext context, PrismObject<ReportType> report) throws SchemaException, ConfigurationException {
        ExpressionProfile expressionProfile = determineExpressionProfile(report, context.getResult());
        LOGGER.trace("Using expression profile '"+(expressionProfile==null?null:expressionProfile.getIdentifier())+"' for report evaluation, determined from: {}", report);
        context.setExpressionProfile(expressionProfile);
        context.setScriptExpressionProfile(findScriptExpressionProfile(expressionProfile, report));
    }

    private ScriptExpressionProfile findScriptExpressionProfile(ExpressionProfile expressionProfile, PrismObject<ReportType> report) {
        if (expressionProfile == null) {
            return null;
        }
        ExpressionEvaluatorProfile scriptEvaluatorProfile = expressionProfile.getEvaluatorProfile(ScriptExpressionEvaluatorFactory.ELEMENT_NAME);
        if (scriptEvaluatorProfile == null) {
            return null;
        }
        return scriptEvaluatorProfile.getScriptExpressionProfile(getScriptLanguageName(report));
    }

    private String getScriptLanguageName(PrismObject<ReportType> report) {
        // Hardcoded for now
        return GroovyScriptEvaluator.LANGUAGE_NAME;
    }

    @Override
    public PrismObject<ReportType> getReportDefinition(String reportOid, Task task, OperationResult result) throws ObjectNotFoundException, SchemaException, SecurityViolationException, CommunicationException, ConfigurationException, ExpressionEvaluationException {
        return model.getObject(ReportType.class, reportOid, null, task, result);
    }

    @Override
    public boolean isAuthorizedToRunReport(PrismObject<ReportType> report, Task task, OperationResult result) throws SchemaException, ObjectNotFoundException, ExpressionEvaluationException, CommunicationException, ConfigurationException, SecurityViolationException {
        AuthorizationParameters<ReportType,ObjectType> params = AuthorizationParameters.Builder.buildObject(report);
        return securityEnforcer.isAuthorized(ModelAuthorizationAction.RUN_REPORT.getUrl(), null, params, null, task, result);
    }

    @Override
    public boolean isAuthorizedToImportReport(PrismObject<ReportType> report, Task task, OperationResult result) throws SchemaException, ObjectNotFoundException, ExpressionEvaluationException, CommunicationException, ConfigurationException, SecurityViolationException {
        AuthorizationParameters<ReportType,ObjectType> params = AuthorizationParameters.Builder.buildObject(report);
        return securityEnforcer.isAuthorized(ModelAuthorizationAction.IMPORT_REPORT.getUrl(), null, params, null, task, result);
    }

    public VariablesMap getParameters(Task task) {
        VariablesMap variables = new VariablesMap();
        PrismContainer<ReportParameterType> reportParams = (PrismContainer) task.getExtensionItemOrClone(ReportConstants.REPORT_PARAMS_PROPERTY_NAME);
        if (reportParams != null) {
            PrismContainerValue<ReportParameterType> reportParamsValues = reportParams.getValue();
            Collection<Item<?, ?>> items = reportParamsValues.getItems();
            for (Item item : items) {
                String paramName = item.getPath().lastName().getLocalPart();
                Object value = null;
                if (!item.getRealValues().isEmpty()) {
                    value = item.getRealValues().iterator().next();
                }
                if (item.getRealValue() instanceof RawType){
                    try {
                        ObjectReferenceType parsedRealValue = ((RawType) item.getRealValue()).getParsedRealValue(ObjectReferenceType.class);
                        variables.put(paramName, new TypedValue(parsedRealValue, ObjectReferenceType.class));
                    } catch (SchemaException e) {
                        LOGGER.error("Couldn't parse ObjectReferenceType from raw type. " + item.getRealValue());
                    }
                } else {
                    variables.put(paramName, new TypedValue(value, item.getRealValue().getClass()));
                }
            }
        }
        return variables;
    }

    public CompiledObjectCollectionView createCompiledView(ObjectCollectionReportEngineConfigurationType collectionConfig, boolean useDefaultView, Task task, OperationResult result)
            throws CommunicationException, ObjectNotFoundException, SchemaException, SecurityViolationException, ConfigurationException, ExpressionEvaluationException {
        Validate.notNull(collectionConfig, "Collection engine in report couldn't be null.");
        CollectionRefSpecificationType collectionRefSpecification = collectionConfig.getCollection();
        ObjectReferenceType ref = null;
        if (collectionRefSpecification != null) {
            ref = collectionRefSpecification.getCollectionRef();
        }
        ObjectCollectionType collection = null;
        if (ref != null && ref.getOid() != null) {
            Class<ObjectType> type = getPrismContext().getSchemaRegistry().determineClassForType(ref.getType());
            collection = (ObjectCollectionType) getModelService()
                    .getObject(type, ref.getOid(), null, task, result)
                    .asObjectable();
        }
        CompiledObjectCollectionView compiledCollection = new CompiledObjectCollectionView();
        if (!Boolean.TRUE.equals(collectionConfig.isUseOnlyReportView())) {
            if (collection != null) {
                getModelInteractionService().applyView(compiledCollection, collection.getDefaultView());
                if (compiledCollection.getContainerType() == null) {
                    compiledCollection.setContainerType(collection.getType());
                }
            } else if (collectionRefSpecification != null && collectionRefSpecification.getBaseCollectionRef() != null
                    && collectionRefSpecification.getBaseCollectionRef().getCollectionRef() != null
                    && collectionRefSpecification.getBaseCollectionRef().getCollectionRef().getOid() != null) {
                ObjectCollectionType baseCollection = (ObjectCollectionType) getObjectFromReference(collectionRefSpecification.getBaseCollectionRef().getCollectionRef()).asObjectable();
                getModelInteractionService().applyView(compiledCollection, baseCollection.getDefaultView());
                if (compiledCollection.getContainerType() == null) {
                    compiledCollection.setContainerType(baseCollection.getType());
                }
            }
        }

        GuiObjectListViewType reportView = collectionConfig.getView();
        if (reportView != null) {
            getModelInteractionService().applyView(compiledCollection, reportView);
        }
        if (compiledCollection.getColumns().isEmpty()) {
            if (useDefaultView) {
                Class<Containerable> type = resolveTypeForReport(collectionRefSpecification, compiledCollection);
                getModelInteractionService().applyView(compiledCollection, DefaultColumnUtils.getDefaultView(type));
            } else {
                return null;
            }
        }
        return compiledCollection;
    }

    public Class<Containerable> resolveTypeForReport(CollectionRefSpecificationType collectionRef, CompiledObjectCollectionView compiledCollection) {
        QName type = resolveTypeQNameForReport(collectionRef, compiledCollection);
        ComplexTypeDefinition def = getPrismContext().getSchemaRegistry().findComplexTypeDefinitionByType(type);
        if (def != null) {
            Class<?> clazz = def.getCompileTimeClass();
            if (Containerable.class.isAssignableFrom(clazz)) {
                return (Class<Containerable>) clazz;
            }
        }
        throw new IllegalArgumentException("Couldn't define type for QName " + type);
    }

    public QName resolveTypeQNameForReport(CollectionRefSpecificationType collectionRef, CompiledObjectCollectionView compiledCollection) {
        QName type;
        if (collectionRef.getCollectionRef() != null && collectionRef.getCollectionRef().getOid() != null) {
            ObjectCollectionType collection = (ObjectCollectionType) getObjectFromReference(collectionRef.getCollectionRef()).asObjectable();
            if (collection.getAuditSearch() != null) {
                type = AuditEventRecordType.COMPLEX_TYPE;
            } else {
                type = collection.getType();
            }
        } else if (collectionRef.getBaseCollectionRef() != null && collectionRef.getBaseCollectionRef().getCollectionRef() != null
                && collectionRef.getBaseCollectionRef().getCollectionRef().getOid() != null) {
            ObjectCollectionType collection = (ObjectCollectionType) getObjectFromReference(collectionRef.getBaseCollectionRef().getCollectionRef()).asObjectable();
            type = collection.getType();
        } else {
            type = compiledCollection.getContainerType();
        }
        if (type == null) {
            LOGGER.error("Couldn't define type for objects");
            throw new IllegalArgumentException("Couldn't define type for objects");
        }
        return type;
    }

    public PrismObject<ObjectType> getObjectFromReference(Referencable ref) {
        Task task = getTaskManager().createTaskInstance("Get object");
        Class<ObjectType> type = getPrismContext().getSchemaRegistry().determineClassForType(ref.getType());

        if (ref.asReferenceValue().getObject() != null) {
            return ref.asReferenceValue().getObject();
        }

        PrismObject<ObjectType> object = null;
        try {
            object = getModelService().getObject(type, ref.getOid(), null, task, task.getResult());
        } catch (Exception e) {
            LOGGER.error("Couldn't get object from objectRef " + ref, e);
        }
        return object;
    }

    public VariablesMap evaluateSubreportParameters(PrismObject<ReportType> report, VariablesMap variables, Task task, OperationResult result) {
        VariablesMap subreportVariable = new VariablesMap();
        if (report != null && report.asObjectable().getObjectCollection() != null
                && report.asObjectable().getObjectCollection().getSubreport() != null
                && !report.asObjectable().getObjectCollection().getSubreport().isEmpty()){
            List<SubreportParameterType> sortedSubreports = new ArrayList<>();
            Collection<SubreportParameterType> subreports = report.asObjectable().getObjectCollection().getSubreport();
            sortedSubreports.addAll(subreports);
            sortedSubreports.sort(Comparator.comparingInt(s -> ObjectUtils.defaultIfNull(s.getOrder(), Integer.MAX_VALUE)));
            for (SubreportParameterType subreport : sortedSubreports) {
                if (subreport.getExpression() == null || subreport.getName() == null) {
                    continue;
                }
                Object subreportParameter;
                ExpressionType expression = subreport.getExpression();
                try {
                    subreportParameter = evaluateScript(report, expression, variables, "subreport parameter", task, result);
                    Class<?> subreportParameterClass;
                    if (subreport.getType() != null) {
                        subreportParameterClass = getPrismContext().getSchemaRegistry().determineClassForType(subreport.getType());
                    } else {
                        subreportParameterClass = subreportParameter.getClass();
                    }
                    subreportVariable.put(subreport.getName(), subreportParameter, subreportParameterClass);
                } catch (Exception e) {
                    LOGGER.error("Couldn't execute expression " + expression, e);
                }
            }
            return subreportVariable;
        }
        return subreportVariable;
    }

    public Clock getClock() {
        return clock;
    }

    public TaskManager getTaskManager() {
        return taskManager;
    }

    public ModelService getModelService() {
        return modelService;
    }

    public ModelInteractionService getModelInteractionService() {
        return modelInteractionService;
    }

    public ObjectResolver getObjectResolver() {
        return objectResolver;
    }

    public DashboardService getDashboardService() {
        return dashboardService;
    }

    public LocalizationService getLocalizationService() {
        return localizationService;
    }

    public ExpressionFactory getExpressionFactory() {
        return expressionFactory;
    }

    public CommandLineScriptExecutor getCommandLineScriptExecutor() {
        return commandLineScriptExecutor;
    }

    public SchemaService getSchemaService() {
        return schemaService;
    }

    public ScriptingService getScriptingService() {
        return scriptingService;
    }
}
