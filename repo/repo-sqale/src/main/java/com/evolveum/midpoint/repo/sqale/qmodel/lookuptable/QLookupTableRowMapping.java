/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.repo.sqale.qmodel.lookuptable;

import static com.evolveum.midpoint.xml.ns._public.common.common_3.LookupTableRowType.*;

import java.util.Objects;

import org.jetbrains.annotations.NotNull;

import com.evolveum.midpoint.prism.PrismConstants;
import com.evolveum.midpoint.repo.sqale.SqaleRepoContext;
import com.evolveum.midpoint.repo.sqale.qmodel.common.QContainerMapping;
import com.evolveum.midpoint.repo.sqlbase.JdbcSession;
import com.evolveum.midpoint.repo.sqlbase.mapping.TableRelationResolver;
import com.evolveum.midpoint.util.MiscUtil;
import com.evolveum.midpoint.xml.ns._public.common.common_3.LookupTableRowType;

/**
 * Mapping between {@link QLookupTableRow} and {@link LookupTableRowType}.
 */
public class QLookupTableRowMapping
        extends QContainerMapping<LookupTableRowType, QLookupTableRow, MLookupTableRow, MLookupTable> {

    public static final String DEFAULT_ALIAS_NAME = "ltr";

    private static QLookupTableRowMapping instance;

    // Explanation in class Javadoc for SqaleTableMapping
    public static QLookupTableRowMapping init(@NotNull SqaleRepoContext repositoryContext) {
        if (instance == null) {
            instance = new QLookupTableRowMapping(repositoryContext);
        }
        return instance;
    }

    // Explanation in class Javadoc for SqaleTableMapping
    public static QLookupTableRowMapping get() {
        return Objects.requireNonNull(instance);
    }

    private QLookupTableRowMapping(@NotNull SqaleRepoContext repositoryContext) {
        super(QLookupTableRow.TABLE_NAME, DEFAULT_ALIAS_NAME,
                LookupTableRowType.class, QLookupTableRow.class, repositoryContext);

        addRelationResolver(PrismConstants.T_PARENT,
                // mapping supplier is used to avoid cycles in the initialization code
                new TableRelationResolver<>(QLookupTableMapping::get,
                        (q, p) -> q.ownerOid.eq(p.oid)));

        addItemMapping(F_KEY, stringMapper(q -> q.key));
        addItemMapping(F_LABEL, polyStringMapper(
                q -> q.labelOrig, q -> q.labelNorm));
        addItemMapping(F_VALUE, stringMapper(q -> q.value));
        addItemMapping(F_LAST_CHANGE_TIMESTAMP,
                timestampMapper(q -> q.lastChangeTimestamp));
    }

    @Override
    protected QLookupTableRow newAliasInstance(String alias) {
        return new QLookupTableRow(alias);
    }

    @Override
    public MLookupTableRow newRowObject() {
        return new MLookupTableRow();
    }

    @Override
    public MLookupTableRow newRowObject(MLookupTable ownerRow) {
        MLookupTableRow row = newRowObject();
        row.ownerOid = ownerRow.oid;
        return row;
    }

    @Override
    public MLookupTableRow insert(LookupTableRowType lookupTableRow,
            MLookupTable ownerRow, JdbcSession jdbcSession) {

        MLookupTableRow row = initRowObject(lookupTableRow, ownerRow);
        row.key = lookupTableRow.getKey();
        row.value = lookupTableRow.getValue();
        setPolyString(lookupTableRow.getLabel(), o -> row.labelOrig = o, n -> row.labelNorm = n);
        row.lastChangeTimestamp = MiscUtil.asInstant(lookupTableRow.getLastChangeTimestamp());

        insert(row, jdbcSession);
        return row;
    }
}
