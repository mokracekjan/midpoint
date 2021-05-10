/*
 * Copyright (c) 2010-2020 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.report.impl.controller.fileformat;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.query.ObjectPaging;

import com.evolveum.midpoint.schema.GetOperationOptions;
import com.evolveum.midpoint.schema.SelectorOptions;

import com.google.common.collect.ImmutableSet;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;

import com.evolveum.midpoint.model.api.authentication.CompiledObjectCollectionView;
import com.evolveum.midpoint.model.common.util.DefaultColumnUtils;
import com.evolveum.midpoint.prism.*;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.path.NameItemPathSegment;
import com.evolveum.midpoint.repo.common.expression.ExpressionUtil;
import com.evolveum.midpoint.report.impl.ReportServiceImpl;
import com.evolveum.midpoint.report.impl.ReportUtils;
import com.evolveum.midpoint.schema.DeltaConvertor;
import com.evolveum.midpoint.schema.ObjectDeltaOperation;
import com.evolveum.midpoint.schema.constants.ExpressionConstants;
import com.evolveum.midpoint.schema.expression.ExpressionProfile;
import com.evolveum.midpoint.schema.expression.VariablesMap;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.task.api.RunningTask;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.exception.*;
import com.evolveum.midpoint.util.logging.LoggingUtils;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.audit_3.AuditEventRecordType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.*;

/**
 * @author skublik
 */

public abstract class FileFormatController {

    private static final Trace LOGGER = TraceManager.getTrace(FileFormatController.class);

    protected static final String LABEL_COLUMN = "label";
    protected static final String NUMBER_COLUMN = "number";
    protected static final String STATUS_COLUMN = "status";

    private static final Set<String> HEADS_OF_WIDGET =
            ImmutableSet.of(LABEL_COLUMN, NUMBER_COLUMN, STATUS_COLUMN);

    private final ReportServiceImpl reportService;
    private final FileFormatConfigurationType fileFormatConfiguration;
    private final ReportType report;
    private VariablesMap parameters;
    private VariablesMap variables;

    public FileFormatController(FileFormatConfigurationType fileFormatConfiguration, ReportType report, ReportServiceImpl reportService) {
        this.fileFormatConfiguration = fileFormatConfiguration;
        this.reportService = reportService;
        this.report = report;
    }

    protected ReportServiceImpl getReportService() {
        return reportService;
    }

    public FileFormatConfigurationType getFileFormatConfiguration() {
        return fileFormatConfiguration;
    }

    protected static Set<String> getHeadsOfWidget() {
        return HEADS_OF_WIDGET;
    }

    public abstract byte[] processDashboard(DashboardReportEngineConfigurationType dashboardConfig, Task task, OperationResult result) throws Exception;

    public abstract byte[] processCollection(String nameOfReport, ObjectCollectionReportEngineConfigurationType collectionConfig, Task task, OperationResult result) throws Exception;

    protected void recordProgress(Task task, long progress, OperationResult opResult, Trace logger) {
        try {
            task.setProgressImmediate(progress, opResult);
        } catch (ObjectNotFoundException e) {             // these exceptions are of so little probability and harmless, so we just log them and do not report higher
            LoggingUtils.logException(logger, "Couldn't record progress to task {}, probably because the task does not exist anymore", e, task);
        } catch (SchemaException e) {
            LoggingUtils.logException(logger, "Couldn't record progress to task {}, due to unexpected schema exception", e, task);
        }
    }

    public abstract String getTypeSuffix();

    public abstract String getType();

    protected String getMessage(String key) {
        return getMessage(key, null);
    }

    protected String getMessage(String key, Object... params) {
        return getReportService().getLocalizationService().translate(key, params, Locale.getDefault(), key);
    }

    protected String getRealValueAsString(GuiObjectColumnType column, PrismContainer<? extends Containerable> object, ItemPath itemPath,
            ExpressionType expression, Task task, OperationResult result) {
        Item valueObject = object;

        if (itemPath != null && !DefaultColumnUtils.isSpecialColumn(itemPath, object)) {
            Iterator<?> iterator = itemPath.getSegments().iterator();
            while (iterator.hasNext()) {
                Object segment = iterator.next();
                QName name;
                if (segment instanceof QName) {
                    name = (QName) segment;
                } else if (segment instanceof NameItemPathSegment) {
                    name = ((NameItemPathSegment) segment).getName();
                } else {
                    continue;
                }
                if (valueObject == null) {
                    break;
                }
                valueObject = (Item) valueObject.find(ItemPath.create(name));
                if (valueObject instanceof PrismProperty && iterator.hasNext()) {
                    throw new IllegalArgumentException("Found object is PrismProperty, but ItemPath isn't empty");
                }
                if (valueObject instanceof PrismReference) {
                    if (valueObject.isSingleValue()) {
                        Referencable ref = ((PrismReference) valueObject).getRealValue();
                        if (iterator.hasNext()) {
                            valueObject = getReportService().getObjectFromReference(ref);
                        }
                    } else {
                        if (iterator.hasNext()) {
                            throw new IllegalArgumentException("Found reference object is multivalue, but ItemPath isn't empty");
                        }
                    }

                }
            }
        }
        if (expression != null) {
            Object value = evaluateExportExpression(expression, object, valueObject, task, result);
            if (value instanceof Collection) {
                if (DisplayValueType.NUMBER.equals(column.getDisplayValue())) {
                    return String.valueOf(((Collection) value).size());
                }
                return processListOfRealValues((Collection) value);
            }
            if (DisplayValueType.NUMBER.equals(column.getDisplayValue())) {
                if (value == null) {
                    return "0";
                }
                if (value instanceof PrismValue && ((PrismValue)value).getRealValue() instanceof Collection){
                    return String.valueOf(((Collection<?>) ((PrismValue)value).getRealValue()).size());
                }
                return "1";
            }
            return processListOfRealValues(Collections.singletonList(value));
        }
        if (DisplayValueType.NUMBER.equals(column.getDisplayValue())) {
            if (valueObject == null) {
                return "0";
            }
            return String.valueOf(valueObject.getValues().size());
        }
        if (itemPath == null) {
            throw new IllegalArgumentException("Path and expression for column " + column.getName() + " is null");
        }
        if (DefaultColumnUtils.isSpecialColumn(itemPath, object)) {
            return DefaultColumnUtils.processSpecialColumn(itemPath, object, getReportService().getLocalizationService());
        }
        if (valueObject instanceof PrismContainer) {
            throw new IllegalArgumentException("Found object is PrismContainer, but expression is null and should be display real value");
        }
        if (valueObject == null) {
            return "";
        }
        @NotNull List<PrismValue> values = valueObject.getValues();
        return processListOfRealValues(values);
    }

    private <O extends Object> String processListOfRealValues(Collection<?> values) {
        StringBuilder sb = new StringBuilder();
        values.forEach(value -> {
            if (!sb.toString().isEmpty() && sb.lastIndexOf(getMultivalueDelimiter()) != (sb.length() - getMultivalueDelimiter().length())) {
                appendMultivalueDelimiter(sb);
            }
            if (value instanceof PrismPropertyValue) {
                String stringValue;
                O realObject = ((PrismPropertyValue<O>) value).getRealValue();
                if (realObject == null) {
                    stringValue = "";
                } else if (realObject instanceof Collection) {
                    stringValue = processListOfRealValues((Collection) realObject);
                } else if (realObject instanceof Enum) {
                    stringValue = ReportUtils.prettyPrintForReport((Enum) realObject);
                } else if (realObject instanceof XMLGregorianCalendar) {
                    stringValue = ReportUtils.prettyPrintForReport((XMLGregorianCalendar) realObject);
                } else if (realObject instanceof ObjectDeltaOperationType) {
                    try {
                        ObjectDeltaOperation convertedDelta = DeltaConvertor.createObjectDeltaOperation((ObjectDeltaOperationType) realObject, getReportService().getPrismContext());
                        stringValue = ReportUtils.printDelta(convertedDelta);
                    } catch (SchemaException e) {
                        LOGGER.error("Couldn't convert delta from ObjectDeltaOperationType to ObjectDeltaOperation " + realObject.toString());
                        stringValue = "";
                    }
                } else {
                    stringValue = ReportUtils.prettyPrintForReport(realObject);
                }
                sb.append(stringValue);
            } else if (value instanceof PrismReferenceValue) {
                sb.append(getObjectNameFromRef(((PrismReferenceValue) value).getRealValue()));
            } else {
                sb.append(ReportUtils.prettyPrintForReport(value));
            }
        });
        if (!sb.toString().isEmpty()
                && sb.lastIndexOf(getMultivalueDelimiter()) == (sb.length() - getMultivalueDelimiter().length())) {
            sb.replace(sb.lastIndexOf(getMultivalueDelimiter()), sb.length(), "");
        }
        return sb.toString();
    }

    private String getObjectNameFromRef(Referencable ref) {
        if (ref == null) {
            return "";
        }
        if (ref.getTargetName() != null && ref.getTargetName().getOrig() != null) {
            return ref.getTargetName().getOrig();
        }
        PrismObject object = getReportService().getObjectFromReference(ref);

        if (object == null) {
            return ref.getOid();
        }

        if (object.getName() == null || object.getName().getOrig() == null) {
            return "";
        }
        return object.getName().getOrig();
    }

    protected abstract void appendMultivalueDelimiter(StringBuilder sb);

    protected abstract String getMultivalueDelimiter();

    private Object evaluateExportExpression(ExpressionType expression, Item object, Item valueItem, Task task, OperationResult result) {
        Object valueObject;
        if (valueItem == null) {
            valueObject = null;
        } else {
            if (valueItem.isSingleValue()) {
                valueObject = valueItem.getRealValue();
            } else {
                valueObject = new ArrayList<>();
                valueItem.getValues().forEach(value -> ((List)valueObject).add(((PrismValue)value).getRealValue()));
            }
        }
        return evaluateExportExpression(expression, object, valueObject, task, result);
    }

    private Object evaluateExportExpression(ExpressionType expression, Item object, Object input, Task task, OperationResult result) {
        checkVariables(task);
        if (variables.containsKey(ExpressionConstants.VAR_INPUT)) {
            variables.remove(ExpressionConstants.VAR_INPUT);
        }
        if (!variables.containsKey(ExpressionConstants.VAR_OBJECT)) {
            variables.put(ExpressionConstants.VAR_OBJECT, object, object.getDefinition());
        }
        if (input == null) {
            variables.put(ExpressionConstants.VAR_INPUT, null, Object.class);
        } else {
            variables.put(ExpressionConstants.VAR_INPUT, input, input.getClass());
        }
        Object values = null;
        try {
            values = getReportService().evaluateScript(report.asPrismObject(), expression, variables, "value for column (export)", task, result);
        } catch (Exception e) {
            LOGGER.error("Couldn't execute expression " + expression, e);
        }
        if (values == null || (values instanceof Collection && ((Collection) values).isEmpty())) {
            values = "";
        }
        return values;
    }

    protected Object evaluateImportExpression(ExpressionType expression, String input, Task task, OperationResult result) {
        VariablesMap variables = new VariablesMap();
        variables.put(ExpressionConstants.VAR_INPUT, input, String.class);
        return evaluateImportExpression(expression, variables, task, result);
    }

    protected Object evaluateImportExpression(ExpressionType expression, List<String> input, Task task, OperationResult result) {
        VariablesMap variables = new VariablesMap();
        variables.put(ExpressionConstants.VAR_INPUT, input, List.class);
        return evaluateImportExpression(expression, variables, task, result);
    }

    private Object evaluateImportExpression(ExpressionType expression, VariablesMap variables, Task task, OperationResult result) {
        Object value = null;
        try {
            value = getReportService().evaluateScript(report.asPrismObject(), expression, variables, "value for column (import)", task, task.getResult());
        } catch (Exception e) {
            LOGGER.error("Couldn't execute expression " + expression, e);
        }
        if (value instanceof PrismValue) {
            return ((PrismValue) value).getRealValue();
        }
        return value;
    }

    private ExpressionProfile determineExpressionProfile(OperationResult result) throws SchemaException, ConfigurationException {
        return getReportService().determineExpressionProfile(report.asPrismContainer(), result);
    }

    protected String getColumnLabel(GuiObjectColumnType column, PrismContainerDefinition objectDefinition) {
        ItemPath path = column.getPath() == null ? null : column.getPath().getItemPath();

        DisplayType columnDisplay = column.getDisplay();
        String label;
        if (columnDisplay != null && columnDisplay.getLabel() != null) {
            label = getMessage(columnDisplay.getLabel().getOrig());
        } else {

            String name = column.getName();
            String displayName = null;
            if (path != null) {
                ItemDefinition def = objectDefinition.findItemDefinition(path);
                if (def == null) {
                    throw new IllegalArgumentException("Could'n find item for path " + path);
                }
                displayName = def.getDisplayName();

            }
            if (StringUtils.isNotEmpty(displayName)) {
                label = getMessage(displayName);
            } else {
                label = name;
            }
        }
        return label;
    }

    protected PrismContainer<? extends Containerable> getAuditRecordAsContainer(AuditEventRecordType record) throws SchemaException {
        PrismContainerValue prismValue = record.asPrismContainerValue();
        prismValue.setPrismContext(getReportService().getPrismContext());
        return prismValue.asSingleValuedContainer(AuditEventRecordType.COMPLEX_TYPE);
    }

    public abstract void importCollectionReport(ReportType report, VariablesMap listOfVariables, RunningTask task, OperationResult result);

    public abstract List<VariablesMap> createVariablesFromFile(ReportType report, ReportDataType reportData, boolean useImportScript, Task task, OperationResult result) throws IOException;

    protected boolean evaluateCondition(ExpressionType condition, PrismContainer value, Task task, OperationResult result)
            throws CommunicationException, ObjectNotFoundException, SchemaException, SecurityViolationException, ConfigurationException, ExpressionEvaluationException {
        checkVariables(task);
        if (!variables.containsKey(ExpressionConstants.VAR_OBJECT)) {
            variables.put(ExpressionConstants.VAR_OBJECT, value, value.getDefinition());
        }
        PrismPropertyValue<Boolean> conditionValue = ExpressionUtil.evaluateCondition(variables, condition, null, getReportService().getExpressionFactory(),
                "Evaluate condition", task, result);
        if (conditionValue == null || Boolean.FALSE.equals(conditionValue.getRealValue())) {
            return false;
        }
        return true;
    }

    private void checkVariables(Task task) {
        if (variables == null) {
            if (parameters == null) {
                parameters = getReportService().getParameters(task);
            }
            variables = new VariablesMap();
            variables.putAll(parameters);
        }
    }

    protected void evaluateSubreportParameters(List<SubreportParameterType> subreports, PrismContainer value, Task task) {
        if (subreports != null && !subreports.isEmpty()){
            cleanUpVariables();
            checkVariables(task);
            if (!variables.containsKey(ExpressionConstants.VAR_OBJECT)) {
                variables.put(ExpressionConstants.VAR_OBJECT, value, value.getDefinition());
            }
            getReportService().evaluateSubreportParameters(report.asPrismObject(), variables, task, task.getResult());
        }
    }

    protected void cleanUpVariables() {
        if (variables != null) {
            variables.clear();
            variables = null;
        }
    }

    protected void initializationParameters(List<SearchFilterParameterType> parametersType, Task task) {
        VariablesMap variables = getReportService().getParameters(task);
        for (SearchFilterParameterType parameter : parametersType) {
            if (!variables.containsKey(parameter.getName())) {
                Class<?> clazz = getReportService().getPrismContext().getSchemaRegistry().determineClassForType(parameter.getType());
                variables.put(parameter.getName(), null, clazz);
            }
        }
        parameters = variables;
    }

    protected void searchObjectFromCollection(CollectionRefSpecificationType collectionConfig, QName typeForFilter, Predicate<PrismContainer> handler,
            Collection<SelectorOptions<GetOperationOptions>> defaultOptions, Task task, OperationResult result, boolean recordProgress)
            throws SchemaException, ObjectNotFoundException, SecurityViolationException, CommunicationException, ConfigurationException, ExpressionEvaluationException {
        checkVariables(task);
        getReportService().getModelInteractionService().processObjectsFromCollection(
                collectionConfig, typeForFilter, handler, defaultOptions, variables, task, result, recordProgress);
    }
}
