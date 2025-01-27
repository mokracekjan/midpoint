/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.repo.sqale.filtering;

import static com.querydsl.core.types.dsl.Expressions.booleanTemplate;
import static com.querydsl.core.types.dsl.Expressions.stringTemplate;

import static com.evolveum.midpoint.repo.sqale.jsonb.JsonbUtils.JSONB_POLY_NORM_KEY;
import static com.evolveum.midpoint.repo.sqale.jsonb.JsonbUtils.JSONB_POLY_ORIG_KEY;
import static com.evolveum.midpoint.repo.sqale.qmodel.ext.MExtItemCardinality.ARRAY;
import static com.evolveum.midpoint.repo.sqale.qmodel.ext.MExtItemCardinality.SCALAR;
import static com.evolveum.midpoint.repo.sqlbase.filtering.item.PolyStringItemFilterProcessor.*;

import java.util.List;
import java.util.function.Function;
import javax.xml.datatype.XMLGregorianCalendar;

import com.google.common.base.Strings;
import com.querydsl.core.types.ExpressionUtils;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.BooleanExpression;
import org.jetbrains.annotations.NotNull;

import com.evolveum.midpoint.prism.*;
import com.evolveum.midpoint.prism.polystring.PolyString;
import com.evolveum.midpoint.prism.query.ObjectFilter;
import com.evolveum.midpoint.prism.query.PropertyValueFilter;
import com.evolveum.midpoint.prism.query.RefFilter;
import com.evolveum.midpoint.prism.query.ValueFilter;
import com.evolveum.midpoint.repo.sqale.ExtUtils;
import com.evolveum.midpoint.repo.sqale.ExtensionProcessor;
import com.evolveum.midpoint.repo.sqale.SqaleQueryContext;
import com.evolveum.midpoint.repo.sqale.SqaleRepoContext;
import com.evolveum.midpoint.repo.sqale.jsonb.JsonbPath;
import com.evolveum.midpoint.repo.sqale.qmodel.ext.MExtItem;
import com.evolveum.midpoint.repo.sqale.qmodel.ext.MExtItemHolderType;
import com.evolveum.midpoint.repo.sqale.qmodel.object.MObjectType;
import com.evolveum.midpoint.repo.sqlbase.QueryException;
import com.evolveum.midpoint.repo.sqlbase.RepositoryException;
import com.evolveum.midpoint.repo.sqlbase.SqlQueryContext;
import com.evolveum.midpoint.repo.sqlbase.filtering.ValueFilterValues;
import com.evolveum.midpoint.repo.sqlbase.filtering.item.FilterOperation;
import com.evolveum.midpoint.repo.sqlbase.filtering.item.ItemValueFilterProcessor;
import com.evolveum.midpoint.repo.sqlbase.querydsl.FlexibleRelationalPathBase;
import com.evolveum.midpoint.util.DOMUtil;
import com.evolveum.midpoint.util.QNameUtil;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;

/**
 * Filter processor for extension items stored in JSONB.
 * This takes care of any supported type, scalar or array, and handles any operation.
 */
public class ExtensionItemFilterProcessor extends ItemValueFilterProcessor<ValueFilter<?, ?>> {

    // QName.toString produces different results, QNameUtil must be used here:
    public static final String STRING_TYPE = QNameUtil.qNameToUri(DOMUtil.XSD_STRING);
    public static final String INT_TYPE = QNameUtil.qNameToUri(DOMUtil.XSD_INT);
    public static final String INTEGER_TYPE = QNameUtil.qNameToUri(DOMUtil.XSD_INTEGER);
    public static final String SHORT_TYPE = QNameUtil.qNameToUri(DOMUtil.XSD_SHORT);
    public static final String LONG_TYPE = QNameUtil.qNameToUri(DOMUtil.XSD_LONG);
    public static final String DECIMAL_TYPE = QNameUtil.qNameToUri(DOMUtil.XSD_DECIMAL);
    public static final String DOUBLE_TYPE = QNameUtil.qNameToUri(DOMUtil.XSD_DOUBLE);
    public static final String FLOAT_TYPE = QNameUtil.qNameToUri(DOMUtil.XSD_FLOAT);
    public static final String BOOLEAN_TYPE = QNameUtil.qNameToUri(DOMUtil.XSD_BOOLEAN);
    public static final String DATETIME_TYPE = QNameUtil.qNameToUri(DOMUtil.XSD_DATETIME);
    public static final String POLY_STRING_TYPE = QNameUtil.qNameToUri(PolyStringType.COMPLEX_TYPE);

    private final MExtItemHolderType holderType;
    private final JsonbPath path;

    public ExtensionItemFilterProcessor(
            SqlQueryContext<?, ?, ?> context,
            Function<FlexibleRelationalPathBase<?>, JsonbPath> rootToExtensionPath,
            MExtItemHolderType holderType) {
        super(context);

        this.path = rootToExtensionPath.apply(context.path());
        this.holderType = holderType;
    }

    @Override
    public Predicate process(ValueFilter<?, ?> filter) throws RepositoryException {
        ItemDefinition<?> definition = filter.getDefinition();
        MExtItem extItem = new ExtensionProcessor((SqaleRepoContext) context.repositoryContext())
                .resolveExtensionItem(definition, holderType);
        assert definition != null;
        if (extItem == null) {
            throw new QueryException("Extension item " + definition.getItemName()
                    + " is not indexed, filter: " + filter);
        }

        if (definition instanceof PrismReferenceDefinition) {
            return processReference(extItem, (RefFilter) filter);
        }

        PropertyValueFilter<?> propertyValueFilter = (PropertyValueFilter<?>) filter;
        ValueFilterValues<?, ?> values = ValueFilterValues.from(propertyValueFilter);
        FilterOperation operation = operation(filter);

        List<?> filterValues = filter.getValues();
        if (filterValues == null || filterValues.isEmpty()) {
            if (operation.isAnyEqualOperation()) {
                return extItemIsNull(extItem);
            } else {
                throw new QueryException("Null value for other than EQUAL filter: " + filter);
            }
        }
        if (filterValues.size() > 1) {
            // TODO where do we want to support eq with multiple values?
            throw new QueryException(
                    "Multiple values in filter are not supported for extension items: " + filter);
        }

        if (extItem.valueType.equals(STRING_TYPE)) {
            return processString(extItem, values, operation, filter);
        } else if (ExtUtils.isEnumDefinition((PrismPropertyDefinition<?>) definition)) {
            return processEnum(extItem, values, operation, filter);
        } else if (extItem.valueType.equals(INT_TYPE) || extItem.valueType.equals(INTEGER_TYPE)
                || extItem.valueType.equals(LONG_TYPE) || extItem.valueType.equals(SHORT_TYPE)
                || extItem.valueType.equals(DOUBLE_TYPE) || extItem.valueType.equals(FLOAT_TYPE)
                || extItem.valueType.equals(DECIMAL_TYPE)) {
            return processNumeric(extItem, values, operation, filter);
        } else if (extItem.valueType.equals(BOOLEAN_TYPE)) {
            return processBoolean(extItem, values, operation, filter);
        } else if (extItem.valueType.equals(DATETIME_TYPE)) {
            //noinspection unchecked
            PropertyValueFilter<XMLGregorianCalendar> dateTimeFilter =
                    (PropertyValueFilter<XMLGregorianCalendar>) filter;
            return processString(extItem,
                    ValueFilterValues.from(dateTimeFilter, ExtUtils::extensionDateTime),
                    operation, filter);
        } else if (extItem.valueType.equals(POLY_STRING_TYPE)) {
            return processPolyString(extItem, values, operation, propertyValueFilter);
        }

        throw new QueryException("Unsupported filter for extension item: " + filter);
    }

    private Predicate processReference(MExtItem extItem, RefFilter filter) {
        List<PrismReferenceValue> values = filter.getValues();
        if (values == null || values.isEmpty()) {
            return extItemIsNull(extItem);
        }

        if (values.size() == 1) {
            return processSingleReferenceValue(extItem, filter, values.get(0));
        }

        Predicate predicate = null;
        for (PrismReferenceValue ref : values) {
            predicate = ExpressionUtils.or(predicate,
                    processSingleReferenceValue(extItem, filter, ref));
        }
        return predicate;
    }

    private Predicate processSingleReferenceValue(MExtItem extItem, RefFilter filter, PrismReferenceValue ref) {
        // We always store oid+type+relation, nothing is null or the whole item is null => missing.
        // So if we ask for OID IS NULL or for target type IS NULL we actually ask "item IS NULL".
        if (ref.getOid() == null && !filter.isOidNullAsAny()
                || ref.getTargetType() == null && !filter.isTargetTypeNullAsAny()) {
            return extItemIsNull(extItem);
        }

        StringBuilder json = new StringBuilder("{");
        boolean commaNeeded = false;

        if (ref.getOid() != null) {
            json.append("\"o\":\"").append(ref.getOid()).append("\"");
            commaNeeded = true;
        }

        if (ref.getTargetType() != null) {
            MObjectType objectType = MObjectType.fromTypeQName(ref.getTargetType());
            if (commaNeeded) {
                json.append(',');
            }
            json.append("\"t\":\"").append(objectType).append("\"");
            commaNeeded = true;
        }

        if (ref.getRelation() == null || !ref.getRelation().equals(PrismConstants.Q_ANY)) {
            Integer relationId = ((SqaleQueryContext<?, ?, ?>) context)
                    .searchCachedRelationId(ref.getRelation());
            if (commaNeeded) {
                json.append(',');
            }
            json.append("\"r\":").append(relationId);
        } else {
            // relation == Q_ANY, no additional predicate needed
        }

        // closing } for inner object is in String.format
        return predicateWithNotTreated(path, booleanTemplate("{0} @> {1}::jsonb", path,
                String.format("{\"%d\":%s}}", extItem.id, json)));
    }

    private Predicate processString(
            MExtItem extItem, ValueFilterValues<?, ?> values, FilterOperation operation, ObjectFilter filter)
            throws QueryException {
        if (extItem.cardinality == SCALAR) {
            if (operation.isEqualOperation()) {
                return predicateWithNotTreated(path, booleanTemplate("{0} @> {1}::jsonb", path,
                        String.format("{\"%d\":\"%s\"}", extItem.id, values.singleValue())));
            } else {
                // {1s} means "as string", this is replaced before JDBC driver, just as path is,
                // but for path types it's automagic, integer would turn to param and ?.
                // IMPORTANT: To get string from JSONB we want to use ->> or #>>'{}' operators,
                // that properly escape the value. Using ::TEXT cast would return the string with
                // double-quotes. For more: https://dba.stackexchange.com/a/234047/157622
                return singleValuePredicate(stringTemplate("{0}->>'{1s}'", path, extItem.id),
                        operation, values.singleValue());
            }
        } else { // multi-value ext item
            if (operation.isEqualOperation()) {
                return predicateWithNotTreated(path, booleanTemplate("{0} @> {1}::jsonb", path,
                        String.format("{\"%d\":[\"%s\"]}", extItem.id, values.singleValue())));
            } else {
                throw new QueryException("Only equals is supported for"
                        + " multi-value extensions; used filter: " + filter);
            }
        }
    }

    private Predicate processEnum(
            MExtItem extItem, ValueFilterValues<?, ?> values, FilterOperation operation, ObjectFilter filter)
            throws QueryException {
        if (!operation.isEqualOperation()) {
            throw new QueryException(
                    "Only equals is supported for enum extensions; used filter: " + filter);
        }

        return predicateWithNotTreated(path, booleanTemplate("{0} @> {1}::jsonb", path,
                String.format(
                        extItem.cardinality == SCALAR ? "{\"%d\":\"%s\"}" : "{\"%d\":[\"%s\"]}",
                        extItem.id, values.singleValue())));
    }

    private Predicate processNumeric(
            MExtItem extItem, ValueFilterValues<?, ?> values, FilterOperation operation, ObjectFilter filter)
            throws QueryException {
        if (extItem.cardinality == SCALAR) {
            if (operation.isEqualOperation()) {
                return predicateWithNotTreated(path, booleanTemplate("{0} @> {1}::jsonb", path,
                        String.format("{\"%d\":%s}", extItem.id, values.singleValue())));
            } else {
                // {1s} means "as string", this is replaced before JDBC driver, just as path is,
                // but for path types it's automagic, integer would turn to param and ?.
                return singleValuePredicate(
                        stringTemplate("({0}->'{1s}')::numeric", path, extItem.id),
                        operation,
                        values.singleValue());
            }
        } else { // multi-value ext item
            if (operation.isEqualOperation()) {
                return predicateWithNotTreated(path,
                        booleanTemplate("{0} @> {1}::jsonb", path,
                                String.format("{\"%d\":[%s]}", extItem.id, values.singleValue())));
            } else {
                throw new QueryException("Only equals is supported for"
                        + " multi-value numeric extensions; used filter: " + filter);
            }
        }
    }

    private Predicate processBoolean(
            MExtItem extItem, ValueFilterValues<?, ?> values, FilterOperation operation, ObjectFilter filter)
            throws QueryException {
        if (!operation.isEqualOperation()) {
            throw new QueryException(
                    "Only equals is supported for boolean extensions; used filter: " + filter);
        }

        // array for booleans doesn't make any sense, but whatever...
        return predicateWithNotTreated(path, booleanTemplate("{0} @> {1}::jsonb", path,
                String.format(
                        extItem.cardinality == SCALAR ? "{\"%d\":%s}" : "{\"%d\":[%s]}",
                        extItem.id, values.singleValue())));
    }

    // filter should be PropertyValueFilter<PolyString>, but pure Strings are handled fine

    private Predicate processPolyString(MExtItem extItem, ValueFilterValues<?, ?> values,
            FilterOperation operation, PropertyValueFilter<?> filter)
            throws QueryException {
        String matchingRule = filter.getMatchingRule() != null
                ? filter.getMatchingRule().getLocalPart() : null;

        if (extItem.cardinality == ARRAY && !operation.isEqualOperation()) {
            throw new QueryException("Only equals is supported for"
                    + " multi-value extensions; used filter: " + filter);
        }

        if (Strings.isNullOrEmpty(matchingRule) || DEFAULT.equals(matchingRule)
                || STRICT.equals(matchingRule) || STRICT_IGNORE_CASE.equals(matchingRule)) {
            // The value here should be poly-string, otherwise it never matches both orig and norm.
            return processPolyStringBoth(extItem, values, operation);
        } else if (ORIG.equals(matchingRule) || ORIG_IGNORE_CASE.equals(matchingRule)) {
            return processPolyStringComponent(
                    extItem, convertPolyValuesToString(values, filter, p -> p.getOrig()),
                    JSONB_POLY_ORIG_KEY, operation);
        } else if (NORM.equals(matchingRule) || NORM_IGNORE_CASE.equals(matchingRule)) {
            return processPolyStringComponent(
                    extItem, convertPolyValuesToString(values, filter, p -> p.getNorm()),
                    JSONB_POLY_NORM_KEY, operation);
        } else {
            throw new QueryException("Unknown matching rule '" + matchingRule + "'.");
        }
    }

    @NotNull
    private ValueFilterValues<?, ?> convertPolyValuesToString(ValueFilterValues<?, ?> values,
            PropertyValueFilter<?> filter, Function<PolyString, String> extractor) {
        // In case it is string already we don't need to do anything.
        if (values.singleValueRaw() instanceof String) {
            return values;
        }

        //noinspection unchecked
        return ValueFilterValues.from((PropertyValueFilter<PolyString>) filter, extractor);
    }

    private Predicate processPolyStringBoth(
            MExtItem extItem, ValueFilterValues<?, ?> values, FilterOperation operation) {
        PolyString poly = (PolyString) values.singleValueRaw(); // must be Poly here
        assert poly != null; // empty values treated in main process()

        if (extItem.cardinality == SCALAR) {
            if (operation.isEqualOperation()) {
                return predicateWithNotTreated(path, booleanTemplate("{0} @> {1}::jsonb", path,
                        String.format("{\"%d\":{\"" + JSONB_POLY_ORIG_KEY + "\":\"%s\",\""
                                        + JSONB_POLY_NORM_KEY + "\":\"%s\"}}",
                                extItem.id, poly.getOrig(), poly.getNorm())));
            } else {
                return ExpressionUtils.and(
                        singleValuePredicate(
                                stringTemplate("{0}->'{1s}'->>'{2s}'",
                                        path, extItem.id, JSONB_POLY_ORIG_KEY),
                                operation, poly.getOrig()),
                        singleValuePredicate(
                                stringTemplate("{0}->'{1s}'->>'{2s}'",
                                        path, extItem.id, JSONB_POLY_NORM_KEY),
                                operation, poly.getNorm()));
            }
        } else { // multi-value ext item, only EQ operation can get here
            return predicateWithNotTreated(path, booleanTemplate("{0} @> {1}::jsonb", path,
                    String.format("{\"%d\":[{\"" + JSONB_POLY_ORIG_KEY + "\":\"%s\",\""
                                    + JSONB_POLY_NORM_KEY + "\":\"%s\"}]}",
                            extItem.id, poly.getOrig(), poly.getNorm())));
        }
    }

    private Predicate processPolyStringComponent(MExtItem extItem,
            ValueFilterValues<?, ?> values, String subKey, FilterOperation operation)
            throws QueryException {
        // Here the values are converted to Strings already
        if (extItem.cardinality == SCALAR) {
            if (operation.isEqualOperation()) {
                return predicateWithNotTreated(path, booleanTemplate("{0} @> {1}::jsonb", path,
                        String.format("{\"%d\":{\"%s\":\"%s\"}}",
                                extItem.id, subKey, values.singleValue())));
            } else {
                return singleValuePredicate(
                        stringTemplate("{0}->'{1s}'->>'{2s}'", path, extItem.id, subKey),
                        operation, values.singleValue());
            }
        } else { // multi-value ext item, EQ
            return predicateWithNotTreated(path, booleanTemplate("{0} @> {1}::jsonb", path,
                    String.format("{\"%d\":[{\"%s\":\"%s\"}]}",
                            extItem.id, subKey, values.singleValue())));
        }
    }

    private static final String STRING_IGNORE_CASE =
            PrismConstants.STRING_IGNORE_CASE_MATCHING_RULE_NAME.getLocalPart();

    @Override
    protected boolean isIgnoreCaseFilter(ValueFilter<?, ?> filter) {
        String matchingRule = filter.getMatchingRule() != null
                ? filter.getMatchingRule().getLocalPart() : null;

        // The first equals substitutes default super.isIgnoreCaseFilter(), the rest is for polys.
        return STRING_IGNORE_CASE.equals(matchingRule)
                || STRICT_IGNORE_CASE.equals(matchingRule)
                || ORIG_IGNORE_CASE.equals(matchingRule)
                || NORM_IGNORE_CASE.equals(matchingRule);
    }

    private BooleanExpression extItemIsNull(MExtItem extItem) {
        // ?? is "escaped" ? operator, PG JDBC driver understands it. Alternative is to use
        // function jsonb_exists but that does NOT use GIN index, only operators do!
        // We have to use parenthesis with AND shovelled into the template like this.
        return booleanTemplate("({0} ?? {1} AND {0} is not null)",
                path, extItem.id.toString()).not();
    }
}
