/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.repo.sqlbase.filtering.item;

import java.util.function.Function;

import com.querydsl.core.types.Path;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.dsl.StringPath;

import com.evolveum.midpoint.prism.query.PropertyValueFilter;
import com.evolveum.midpoint.repo.sqlbase.QueryException;
import com.evolveum.midpoint.repo.sqlbase.SqlQueryContext;
import com.evolveum.midpoint.repo.sqlbase.filtering.ValueFilterValues;
import com.evolveum.midpoint.repo.sqlbase.mapping.DefaultItemSqlMapper;
import com.evolveum.midpoint.repo.sqlbase.mapping.ItemSqlMapper;
import com.evolveum.midpoint.repo.sqlbase.querydsl.FlexibleRelationalPathBase;

/**
 * Filter processor for a single path with straightforward type mapping and no conversions.
 *
 * @param <T> type parameter of processed {@link PropertyValueFilter}
 * @param <P> type of the Querydsl attribute path
 */
public class SimpleItemFilterProcessor<T, P extends Path<T>>
        extends SinglePathItemFilterProcessor<T, P> {

    public <Q extends FlexibleRelationalPathBase<R>, R> SimpleItemFilterProcessor(
            SqlQueryContext<?, Q, R> context, Function<Q, P> rootToQueryItem) {
        super(context, rootToQueryItem);
    }

    @Override
    public Predicate process(PropertyValueFilter<T> filter) throws QueryException {
        return createBinaryCondition(filter, path, ValueFilterValues.from(filter));
    }

    /**
     * Returns the mapper creating the string filter processor from context.
     * This is unbound version that will adapt to the types in the client code.
     */
    public static <Q extends FlexibleRelationalPathBase<R>, R>
    ItemSqlMapper<Q, R> stringMapper(Function<Q, StringPath> rootToQueryItem) {
        return new DefaultItemSqlMapper<>(
                ctx -> new SimpleItemFilterProcessor<>(ctx, rootToQueryItem),
                rootToQueryItem);
    }
}
