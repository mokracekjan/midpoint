/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.repo.sqale.qmodel;

import java.sql.Types;
import java.time.Instant;

import com.querydsl.core.types.dsl.ArrayPath;
import com.querydsl.core.types.dsl.DateTimePath;
import com.querydsl.core.types.dsl.NumberPath;
import com.querydsl.core.types.dsl.StringPath;
import com.querydsl.sql.ColumnMetadata;

import com.evolveum.midpoint.repo.sqale.qbean.MTask;

/**
 * Querydsl query type for {@value #TABLE_NAME} table.
 */
@SuppressWarnings("unused")
public class QTask extends QObject<MTask> {

    private static final long serialVersionUID = 6249403929032616177L;

    public static final String TABLE_NAME = "m_task";

    public static final ColumnMetadata BINDING =
            ColumnMetadata.named("binding").ofType(Types.INTEGER);
    public static final ColumnMetadata CATEGORY =
            ColumnMetadata.named("category").ofType(Types.VARCHAR).withSize(255);
    public static final ColumnMetadata COMPLETION_TIMESTAMP =
            ColumnMetadata.named("completionTimestamp").ofType(Types.TIMESTAMP_WITH_TIMEZONE);
    public static final ColumnMetadata EXECUTION_STATUS =
            ColumnMetadata.named("executionStatus").ofType(Types.INTEGER);
    public static final ColumnMetadata FULL_RESULT =
            ColumnMetadata.named("fullResult").ofType(Types.BINARY);
    public static final ColumnMetadata HANDLER_URI =
            ColumnMetadata.named("handlerUri").ofType(Types.VARCHAR).withSize(255);
    public static final ColumnMetadata LAST_RUN_FINISH_TIMESTAMP =
            ColumnMetadata.named("lastRunFinishTimestamp").ofType(Types.TIMESTAMP_WITH_TIMEZONE);
    public static final ColumnMetadata LAST_RUN_START_TIMESTAMP =
            ColumnMetadata.named("lastRunStartTimestamp").ofType(Types.TIMESTAMP_WITH_TIMEZONE);
    public static final ColumnMetadata NODE =
            ColumnMetadata.named("node").ofType(Types.VARCHAR).withSize(255);
    public static final ColumnMetadata OBJECT_REF_TARGET_OID =
            ColumnMetadata.named("objectRef_targetOid").ofType(Types.OTHER);
    public static final ColumnMetadata OBJECT_REF_TARGET_TYPE =
            ColumnMetadata.named("objectRef_targetType").ofType(Types.INTEGER);
    public static final ColumnMetadata OBJECT_REF_RELATION_ID =
            ColumnMetadata.named("objectRef_relation_id").ofType(Types.INTEGER);
    public static final ColumnMetadata OWNER_REF_TARGET_OID =
            ColumnMetadata.named("ownerRef_targetOid").ofType(Types.OTHER);
    public static final ColumnMetadata OWNER_REF_TARGET_TYPE =
            ColumnMetadata.named("ownerRef_targetType").ofType(Types.INTEGER);
    public static final ColumnMetadata OWNER_REF_RELATION_ID =
            ColumnMetadata.named("ownerRef_relation_id").ofType(Types.INTEGER);
    public static final ColumnMetadata PARENT =
            ColumnMetadata.named("parent").ofType(Types.VARCHAR).withSize(255);
    public static final ColumnMetadata RECURRENCE =
            ColumnMetadata.named("recurrence").ofType(Types.INTEGER);
    public static final ColumnMetadata STATUS =
            ColumnMetadata.named("status").ofType(Types.INTEGER);
    public static final ColumnMetadata TASK_IDENTIFIER =
            ColumnMetadata.named("taskIdentifier").ofType(Types.VARCHAR).withSize(255);
    public static final ColumnMetadata THREAD_STOP_ACTION =
            ColumnMetadata.named("threadStopAction").ofType(Types.INTEGER);
    public static final ColumnMetadata WAITING_REASON =
            ColumnMetadata.named("waitingReason").ofType(Types.INTEGER);

    // columns and relations
    public final NumberPath<Integer> binding = createInteger("binding", BINDING);
    public final StringPath category = createString("category", CATEGORY);
    public final DateTimePath<Instant> completionTimestamp = createInstant("completionTimestamp", COMPLETION_TIMESTAMP);
    public final NumberPath<Integer> executionStatus = createInteger("executionStatus", EXECUTION_STATUS);
    public final ArrayPath<byte[], Byte> fullResult = createByteArray("fullResult", FULL_RESULT);
    public final StringPath handlerUri = createString("handlerUri", HANDLER_URI);
    public final DateTimePath<Instant> lastRunFinishTimestamp = createInstant("lastRunFinishTimestamp", LAST_RUN_FINISH_TIMESTAMP);
    public final DateTimePath<Instant> lastRunStartTimestamp = createInstant("lastRunStartTimestamp", LAST_RUN_START_TIMESTAMP);
    public final StringPath node = createString("node", NODE);
    public final StringPath objectRefTargetOid = createString("objectRefTargetOid", OBJECT_REF_TARGET_OID);
    public final NumberPath<Integer> objectRefTargetType = createInteger("objectRefTargetType", OBJECT_REF_TARGET_TYPE);
    public final NumberPath<Integer> objectRefRelationId = createInteger("objectRefRelationId", OBJECT_REF_RELATION_ID);
    public final StringPath ownerRefTargetOid = createString("ownerRefTargetOid", OWNER_REF_TARGET_OID);
    public final NumberPath<Integer> ownerRefTargetType = createInteger("ownerRefTargetType", OWNER_REF_TARGET_TYPE);
    public final NumberPath<Integer> ownerRefRelationId = createInteger("ownerRefRelationId", OWNER_REF_RELATION_ID);
    public final StringPath parent = createString("parent", PARENT);
    public final NumberPath<Integer> recurrence = createInteger("recurrence", RECURRENCE);
    public final NumberPath<Integer> status = createInteger("status", STATUS);
    public final StringPath taskIdentifier = createString("taskIdentifier", TASK_IDENTIFIER);
    public final NumberPath<Integer> threadStopAction = createInteger("threadStopAction", THREAD_STOP_ACTION);
    public final NumberPath<Integer> waitingReason = createInteger("waitingReason", WAITING_REASON);

    public QTask(String variable) {
        this(variable, DEFAULT_SCHEMA_NAME, TABLE_NAME);
    }

    public QTask(String variable, String schema, String table) {
        super(MTask.class, variable, schema, table);
    }
}