/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.repo.sqale.filtering;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import com.querydsl.core.types.ExpressionUtils;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.EnumPath;
import com.querydsl.core.types.dsl.NumberPath;

import com.evolveum.midpoint.prism.PrismConstants;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.prism.query.RefFilter;
import com.evolveum.midpoint.repo.sqale.SqaleQueryContext;
import com.evolveum.midpoint.repo.sqale.qmodel.common.QUri;
import com.evolveum.midpoint.repo.sqale.qmodel.object.MObjectType;
import com.evolveum.midpoint.repo.sqlbase.SqlQueryContext;
import com.evolveum.midpoint.repo.sqlbase.filtering.item.ItemValueFilterProcessor;
import com.evolveum.midpoint.repo.sqlbase.querydsl.FlexibleRelationalPathBase;
import com.evolveum.midpoint.repo.sqlbase.querydsl.UuidPath;

/**
 * Filter processor for reference item paths embedded in table as three columns.
 * OID is represented by UUID column, type by ID (see {@link MObjectType} and relation
 * by Integer (foreign key) to {@link QUri}.
 */
public class RefItemFilterProcessor extends ItemValueFilterProcessor<RefFilter> {

    // only oidPath is strictly not-null, but then the filter better not ask for type or relation
    private final UuidPath oidPath;
    private final EnumPath<MObjectType> typePath;
    private final NumberPath<Integer> relationIdPath;

    public <Q extends FlexibleRelationalPathBase<R>, R> RefItemFilterProcessor(
            SqlQueryContext<?, Q, R> context,
            Function<Q, UuidPath> rootToOidPath,
            Function<Q, EnumPath<MObjectType>> rootToTypePath,
            Function<Q, NumberPath<Integer>> rootToRelationIdPath) {
        this(context,
                rootToOidPath.apply(context.path()),
                rootToTypePath != null ? rootToTypePath.apply(context.path()) : null,
                rootToRelationIdPath != null ? rootToRelationIdPath.apply(context.path()) : null);
    }

    // exposed mainly for RefTableItemFilterProcessor
    <Q extends FlexibleRelationalPathBase<R>, R> RefItemFilterProcessor(
            SqlQueryContext<?, Q, R> context,
            UuidPath oidPath, EnumPath<MObjectType> typePath, NumberPath<Integer> relationIdPath) {
        super(context);
        this.oidPath = oidPath;
        this.typePath = typePath;
        this.relationIdPath = relationIdPath;
    }

    @Override
    public Predicate process(RefFilter filter) {
        List<PrismReferenceValue> values = filter.getValues();
        if (values == null || values.isEmpty()) {
            return oidPath.isNull();
        }
        if (values.size() == 1) {
            return processSingleValue(filter, values.get(0));
        }

        Predicate predicate = null;
        for (PrismReferenceValue ref : values) {
            predicate = ExpressionUtils.or(predicate, processSingleValue(filter, ref));
        }
        return predicate;
    }

    private Predicate processSingleValue(RefFilter filter, PrismReferenceValue ref) {
        Predicate predicate = null;
        if (ref.getOid() != null) {
            predicate = predicateWithNotTreated(oidPath,
                    oidPath.eq(UUID.fromString(ref.getOid())));
        } else if (!filter.isOidNullAsAny()) {
            predicate = oidPath.isNull();
        }

        if (ref.getTargetType() != null) {
            MObjectType objectType = MObjectType.fromTypeQName(ref.getTargetType());
            predicate = ExpressionUtils.and(predicate,
                    predicateWithNotTreated(typePath, typePath.eq(objectType)));
        } else if (!filter.isTargetTypeNullAsAny()) {
            predicate = ExpressionUtils.and(predicate, typePath.isNull());
        }

        if (ref.getRelation() == null || !ref.getRelation().equals(PrismConstants.Q_ANY)) {
            Integer relationId = ((SqaleQueryContext<?, ?, ?>) context)
                    .searchCachedRelationId(ref.getRelation());
            predicate = ExpressionUtils.and(predicate,
                    predicateWithNotTreated(relationIdPath, relationIdPath.eq(relationId)));
        } else {
            // relation == Q_ANY, no additional predicate needed
        }

        return predicate;
    }
}
