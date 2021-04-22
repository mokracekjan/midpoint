/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.repo.sqale.mapping;

import java.util.Objects;
import java.util.function.Function;

import com.querydsl.core.types.Path;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.evolveum.midpoint.repo.sqale.SqaleUpdateContext;
import com.evolveum.midpoint.repo.sqale.delta.ItemDeltaProcessor;
import com.evolveum.midpoint.repo.sqale.delta.ItemDeltaValueProcessor;
import com.evolveum.midpoint.repo.sqlbase.SqlQueryContext;
import com.evolveum.midpoint.repo.sqlbase.filtering.item.ItemFilterProcessor;
import com.evolveum.midpoint.repo.sqlbase.mapping.ItemSqlMapper;
import com.evolveum.midpoint.repo.sqlbase.querydsl.FlexibleRelationalPathBase;

/**
 * Declarative information how an item (from schema/prism world) is to be processed
 * when interpreting query or applying delta (delta application is addition to sqlbase superclass).
 *
 * @param <S> schema type owning the mapped item (not the target type)
 * @param <Q> entity path owning the mapped item (not the target type)
 * @param <R> row type with the mapped item (not the target type)
 */
public class SqaleItemSqlMapper<S, Q extends FlexibleRelationalPathBase<R>, R>
        extends ItemSqlMapper<S, Q, R> {

    @NotNull private final
    Function<SqaleUpdateContext<S, Q, R>, ItemDeltaValueProcessor<?>> deltaProcessorFactory;

    public <P extends Path<?>> SqaleItemSqlMapper(
            @NotNull Function<SqlQueryContext<S, Q, R>, ItemFilterProcessor<?>> filterProcessorFactory,
            @NotNull Function<SqaleUpdateContext<S, Q, R>, ItemDeltaValueProcessor<?>> deltaProcessorFactory,
            @Nullable Function<Q, P> primaryItemMapping) {
        super(filterProcessorFactory, primaryItemMapping);
        this.deltaProcessorFactory = Objects.requireNonNull(deltaProcessorFactory);
    }

    public SqaleItemSqlMapper(
            @NotNull Function<SqlQueryContext<S, Q, R>, ItemFilterProcessor<?>> filterProcessorFactory,
            @NotNull Function<SqaleUpdateContext<S, Q, R>, ItemDeltaValueProcessor<?>> deltaProcessorFactory) {
        super(filterProcessorFactory);
        this.deltaProcessorFactory = Objects.requireNonNull(deltaProcessorFactory);
    }

    /**
     * Creates {@link ItemDeltaProcessor} based on this mapping.
     * Provided {@link SqaleUpdateContext} is used to figure out the query paths when this is
     * executed (as the entity path instance is not yet available when the mapping is configured
     * in a declarative manner).
     *
     * The type of the returned processor is adapted to the client code needs for convenience.
     * Also the type of the provided context is flexible, but with proper mapping it's all safe.
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public <T> ItemDeltaValueProcessor<T> createItemDeltaProcessor(
            SqaleUpdateContext<?, ?, ?> sqlUpdateContext) {
        return deltaProcessorFactory.apply((SqaleUpdateContext) sqlUpdateContext);
    }
}
