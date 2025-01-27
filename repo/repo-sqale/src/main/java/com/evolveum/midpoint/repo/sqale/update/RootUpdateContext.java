/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.repo.sqale.update;

import static com.evolveum.midpoint.repo.sqale.SqaleUtils.objectVersionAsInt;

import java.util.Collection;
import java.util.UUID;

import com.querydsl.core.types.Expression;
import com.querydsl.core.types.Path;
import com.querydsl.sql.dml.SQLUpdateClause;
import org.jetbrains.annotations.NotNull;

import com.evolveum.midpoint.prism.*;
import com.evolveum.midpoint.prism.delta.ItemDelta;
import com.evolveum.midpoint.prism.equivalence.EquivalenceStrategy;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.repo.sqale.ContainerValueIdGenerator;
import com.evolveum.midpoint.repo.sqale.SqaleRepoContext;
import com.evolveum.midpoint.repo.sqale.SqaleUtils;
import com.evolveum.midpoint.repo.sqale.delta.DelegatingItemDeltaProcessor;
import com.evolveum.midpoint.repo.sqale.qmodel.object.MObject;
import com.evolveum.midpoint.repo.sqale.qmodel.object.QObject;
import com.evolveum.midpoint.repo.sqale.qmodel.object.QObjectMapping;
import com.evolveum.midpoint.repo.sqlbase.JdbcSession;
import com.evolveum.midpoint.repo.sqlbase.RepositoryException;
import com.evolveum.midpoint.util.DebugUtil;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.exception.SystemException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;

/**
 * Root context of the update context tree, see {@link SqaleUpdateContext} for more information.
 *
 * @param <S> schema type
 * @param <Q> type of entity path
 * @param <R> row type related to the {@link Q}
 */
public class RootUpdateContext<S extends ObjectType, Q extends QObject<R>, R extends MObject>
        extends SqaleUpdateContext<S, Q, R> {

    private final S object;
    protected final QObjectMapping<S, Q, R> mapping;
    private final Q rootPath;
    private final SQLUpdateClause update;
    private final int objectVersion;

    private ContainerValueIdGenerator cidGenerator;

    public RootUpdateContext(SqaleRepoContext repositoryContext,
            JdbcSession jdbcSession, S object, R rootRow) {
        super(repositoryContext, jdbcSession, rootRow);

        this.object = object;
        mapping = repositoryContext.getMappingBySchemaType(SqaleUtils.getClass(object));
        rootPath = mapping.defaultAlias();
        objectVersion = objectVersionAsInt(object);
        // root context always updates, at least version and full object, so we can create it early
        update = jdbcSession.newUpdate(rootPath)
                .where(rootPath.oid.eq(rootRow.oid)
                        .and(rootPath.version.eq(objectVersion)));
    }

    public Q entityPath() {
        return rootPath;
    }

    @Override
    public QObjectMapping<S, Q, R> mapping() {
        return mapping;
    }

    /**
     * Applies modifications, executes necessary updates and returns narrowed modifications.
     * If returned narrowed modifications are empty, no update was made and version stays the same!
     */
    public Collection<? extends ItemDelta<?, ?>> execute(
            Collection<? extends ItemDelta<?, ?>> modifications)
            throws SchemaException, RepositoryException {

        PrismObject<S> prismObject = getPrismObject();

        // I reassign here, we DON'T want original modifications to be used further by accident
        modifications = prismObject.narrowModifications(
                modifications, EquivalenceStrategy.DATA,
                EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS, true);
        logger.trace("Narrowed modifications:\n{}", DebugUtil.debugDumpLazily(modifications));

        if (modifications.isEmpty()) {
            return modifications; // no need to execute any update
        }

        cidGenerator = new ContainerValueIdGenerator()
                .forModifyObject(getPrismObject(), row.containerIdSeq);

        for (ItemDelta<?, ?> modification : modifications) {
            try {
                processModification(modification);
            } catch (IllegalArgumentException e) {
                logger.warn("Modification failed/not implemented yet: {}", e.toString());
                throw new SystemException(e);
            }
        }

        repositoryContext().normalizeAllRelations(prismObject);
        finishExecution();

        return modifications;
    }

    private void processModification(ItemDelta<?, ?> modification)
            throws RepositoryException, SchemaException {
        cidGenerator.processModification(modification);
        resolveContainerIdsForValuesToDelete(modification);
        modification.applyTo(getPrismObject());

        new DelegatingItemDeltaProcessor(this).process(modification);
    }

    private void resolveContainerIdsForValuesToDelete(ItemDelta<?, ?> modification) {
        if (!modification.isDelete()) {
            return;
        }

        PrismContainer<Containerable> container =
                getPrismObject().findContainer(modification.getPath());
        if (container != null) {
            for (PrismValue value : modification.getValuesToDelete()) {
                //noinspection unchecked
                PrismContainerValue<Containerable> pcv = (PrismContainerValue<Containerable>) value;
                if (pcv.getId() == null) {
                    PrismContainerValue<Containerable> existingValue = container.findValue(
                            pcv, EquivalenceStrategy.REAL_VALUE_CONSIDER_DIFFERENT_IDS);
                    // We will set CID and use that for DB updates.
                    pcv.setId(existingValue.getId());
                }
            }
        }
    }

    /**
     * Executes all necessary SQL updates (including sub-entity inserts/deletes)
     * for the enclosed {@link #object}.
     * This also increments the version information and serializes `fullObject`.
     */
    protected void finishExecutionOwn() throws SchemaException, RepositoryException {
        int newVersion = objectVersionAsInt(object) + 1;
        object.setVersion(String.valueOf(newVersion));
        update.set(rootPath.version, newVersion);

        update.set(rootPath.containerIdSeq, cidGenerator.lastUsedId() + 1);
        update.set(rootPath.fullObject, mapping.createFullObject(object));

        long rows = update.execute();
        if (rows != 1) {
            throw new RepositoryException("Object " + objectOid() + " with supposed version "
                    + objectVersion + " could not be updated (concurrent access?).");
        }
    }

    @Override
    public <V extends PrismValue> Item<V, ?> findItem(@NotNull ItemPath path) {
        return object.asPrismObject().findItem(path);
    }

    public SQLUpdateClause update() {
        return update;
    }

    public <P extends Path<T>, T> void set(P path, T value) {
        update.set(path, value);
    }

    @Override
    public <P extends Path<T>, T> void set(P path, Expression<T> expression) {
        update.set(path, expression);
    }

    @Override
    public <P extends Path<T>, T> void setNull(P path) {
        update.setNull(path);
    }

    public UUID objectOid() {
        return row.oid;
    }

    public PrismObject<S> getPrismObject() {
        //noinspection unchecked
        return (PrismObject<S>) object.asPrismObject();
    }
}
