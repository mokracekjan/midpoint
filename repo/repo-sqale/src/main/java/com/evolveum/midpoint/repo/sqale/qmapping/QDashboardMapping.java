/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.repo.sqale.qmapping;

import com.evolveum.midpoint.repo.sqale.qbean.MDashboard;
import com.evolveum.midpoint.repo.sqale.qmodel.QDashboard;
import com.evolveum.midpoint.repo.sqlbase.SqlRepoContext;
import com.evolveum.midpoint.repo.sqlbase.SqlTransformerContext;
import com.evolveum.midpoint.xml.ns._public.common.common_3.DashboardType;

/**
 * Mapping between {@link QDashboard} and {@link DashboardType}.
 */
public class QDashboardMapping
        extends QObjectMapping<DashboardType, QDashboard, MDashboard> {

    public static final String DEFAULT_ALIAS_NAME = "d";

    public static final QDashboardMapping INSTANCE = new QDashboardMapping();

    private QDashboardMapping() {
        super(QDashboard.TABLE_NAME, DEFAULT_ALIAS_NAME,
                DashboardType.class, QDashboard.class);
    }

    @Override
    protected QDashboard newAliasInstance(String alias) {
        return new QDashboard(alias);
    }

    @Override
    public ObjectSqlTransformer<DashboardType, QDashboard, MDashboard>
    createTransformer(SqlTransformerContext transformerContext, SqlRepoContext sqlRepoContext) {
        // no special class needed, no additional columns
        return new ObjectSqlTransformer<>(transformerContext, this);
    }

    @Override
    public MDashboard newRowObject() {
        return new MDashboard();
    }
}
