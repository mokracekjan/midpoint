/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.repo.sqale.qmodel.report;

import org.jetbrains.annotations.NotNull;

import com.evolveum.midpoint.repo.sqale.SqaleRepoContext;
import com.evolveum.midpoint.repo.sqale.qmodel.object.QAssignmentHolderMapping;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ReportType;

/**
 * Mapping between {@link QReport} and {@link ReportType}.
 */
public class QReportMapping
        extends QAssignmentHolderMapping<ReportType, QReport, MReport> {

    public static final String DEFAULT_ALIAS_NAME = "rep";

    public static QReportMapping init(@NotNull SqaleRepoContext repositoryContext) {
        return new QReportMapping(repositoryContext);
    }

    private QReportMapping(@NotNull SqaleRepoContext repositoryContext) {
        super(QReport.TABLE_NAME, DEFAULT_ALIAS_NAME,
                ReportType.class, QReport.class, repositoryContext);
    }

    @Override
    protected QReport newAliasInstance(String alias) {
        return new QReport(alias);
    }

    @Override
    public MReport newRowObject() {
        return new MReport();
    }
}
