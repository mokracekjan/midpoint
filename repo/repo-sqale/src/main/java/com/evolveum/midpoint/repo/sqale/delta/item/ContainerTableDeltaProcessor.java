/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.repo.sqale.delta.item;

import java.util.Collection;

import com.evolveum.midpoint.util.exception.SchemaException;

import org.jetbrains.annotations.NotNull;

import com.evolveum.midpoint.prism.Containerable;
import com.evolveum.midpoint.repo.sqale.delta.ItemDeltaValueProcessor;
import com.evolveum.midpoint.repo.sqale.qmodel.common.MContainer;
import com.evolveum.midpoint.repo.sqale.qmodel.common.QContainer;
import com.evolveum.midpoint.repo.sqale.qmodel.common.QContainerMapping;
import com.evolveum.midpoint.repo.sqale.update.SqaleUpdateContext;
import com.evolveum.midpoint.repo.sqlbase.querydsl.FlexibleRelationalPathBase;

/**
 * Delta value processor for multi-value containers stored in separate tables.
 *
 * @param <T> schema type for container
 * @param <Q> query type for container table
 * @param <R> row type for container table, related to {@link Q}
 * @param <OQ> query type of the table owning this container
 * @param <OR> row type of the table owning this container, related to {@link OQ}
 */
public class ContainerTableDeltaProcessor<
        T extends Containerable, Q extends QContainer<R, OR>, R extends MContainer,
        OQ extends FlexibleRelationalPathBase<OR>, OR>
        extends ItemDeltaValueProcessor<T> {

    private final SqaleUpdateContext<?, OQ, OR> context;
    private final QContainerMapping<T, Q, R, OR> containerTableMapping;

    public ContainerTableDeltaProcessor(
            @NotNull SqaleUpdateContext<?, OQ, OR> context,
            @NotNull QContainerMapping<T, Q, R, OR> containerTableMapping) {
        super(context);
        this.context = context;
        this.containerTableMapping = containerTableMapping;
    }

    @Override
    public void addValues(Collection<T> values) throws SchemaException {
        for (T ref: values) {
            context.insertOwnedRow(containerTableMapping, ref);
        }
    }

    @Override
    public void deleteValues(Collection<T> values) {
        Q c = containerTableMapping.defaultAlias();
        for (T container : values) {
            context.jdbcSession().newDelete(c)
                    .where(c.isOwnedBy(context.row())
                            .and(c.cid.eq(container.asPrismContainerValue().getId())))
                    .execute();
        }
    }

    @Override
    public void delete() {
        QContainer<R, OR> c = containerTableMapping.defaultAlias();
        context.jdbcSession().newDelete(c)
                .where(c.isOwnedBy(context.row()))
                .execute();
    }
}
