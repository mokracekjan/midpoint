/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.repo.sqale.mapping;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.xml.namespace.QName;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.*;
import com.querydsl.sql.ColumnMetadata;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.evolveum.midpoint.prism.Containerable;
import com.evolveum.midpoint.prism.SerializationOptions;
import com.evolveum.midpoint.prism.path.ItemName;
import com.evolveum.midpoint.repo.sqale.ExtensionProcessor;
import com.evolveum.midpoint.repo.sqale.SqaleRepoContext;
import com.evolveum.midpoint.repo.sqale.delta.item.*;
import com.evolveum.midpoint.repo.sqale.filtering.ArrayPathItemFilterProcessor;
import com.evolveum.midpoint.repo.sqale.filtering.JsonbPolysPathItemFilterProcessor;
import com.evolveum.midpoint.repo.sqale.filtering.UriItemFilterProcessor;
import com.evolveum.midpoint.repo.sqale.jsonb.Jsonb;
import com.evolveum.midpoint.repo.sqale.jsonb.JsonbPath;
import com.evolveum.midpoint.repo.sqale.qmodel.common.QUri;
import com.evolveum.midpoint.repo.sqale.qmodel.ext.MExtItemHolderType;
import com.evolveum.midpoint.repo.sqale.qmodel.object.MObjectType;
import com.evolveum.midpoint.repo.sqale.qmodel.object.QObject;
import com.evolveum.midpoint.repo.sqale.qmodel.ref.MReference;
import com.evolveum.midpoint.repo.sqale.qmodel.ref.QReferenceMapping;
import com.evolveum.midpoint.repo.sqlbase.JdbcSession;
import com.evolveum.midpoint.repo.sqlbase.RepositoryObjectParseResult;
import com.evolveum.midpoint.repo.sqlbase.filtering.item.EnumItemFilterProcessor;
import com.evolveum.midpoint.repo.sqlbase.filtering.item.PolyStringItemFilterProcessor;
import com.evolveum.midpoint.repo.sqlbase.filtering.item.SimpleItemFilterProcessor;
import com.evolveum.midpoint.repo.sqlbase.filtering.item.TimestampItemFilterProcessor;
import com.evolveum.midpoint.repo.sqlbase.mapping.ItemSqlMapper;
import com.evolveum.midpoint.repo.sqlbase.mapping.QueryModelMappingRegistry;
import com.evolveum.midpoint.repo.sqlbase.mapping.QueryTableMapping;
import com.evolveum.midpoint.repo.sqlbase.querydsl.FlexibleRelationalPathBase;
import com.evolveum.midpoint.repo.sqlbase.querydsl.UuidPath;
import com.evolveum.midpoint.schema.GetOperationOptions;
import com.evolveum.midpoint.schema.SelectorOptions;
import com.evolveum.midpoint.util.MiscUtil;
import com.evolveum.midpoint.util.QNameUtil;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import com.evolveum.prism.xml.ns._public.types_3.PolyStringType;

/**
 * Mapping superclass with common functions for {@link QObject} and non-objects (e.g. containers).
 * See javadoc in {@link QueryTableMapping} for more.
 *
 * Mappings are often initialized using static `init*(repositoryContext)` methods, various
 * suffixes are used for these reasons:
 *
 * * To differentiate various instances for the same mapping type, e.g. various references
 * stored in separate tables.
 * * To avoid return type clash of the `init` methods in the hierarchy.
 * Even though they are static and technically independent, Java meddles too much.
 * * And finally, to avoid accidental use of static method from the superclass (this should not
 * be even a thing!).
 *
 * Some subclasses (typically containers and refs) track their instances and avoid unnecessary
 * instance creation for the same init method; the instance is available via matching `get*()`.
 * Other subclasses (most objects) don't have `get*()` methods but can be obtained using
 * {@link QueryModelMappingRegistry#getByQueryType(Class)}, or by schema type (e.g. in tests).
 *
 * [IMPORTANT]
 * ====
 * The mappings are created in the constructors and subtypes depend on their supertypes and objects
 * depend on their parts (container/ref tables).
 * This does not create any confusion and `init` methods can be called multiple times from
 * various objects, whatever comes first initializes the mapping and the rest reuses it.
 *
 * *But cross-references can cause recursive initialization and stack overflow* and must be solved
 * differently, either after all the mappings are initialized or the mappings must be provided
 * indirectly/lazily, e.g. using {@link Supplier}, etc.
 * ====
 *
 * @param <S> schema type
 * @param <Q> type of entity path
 * @param <R> row type related to the {@link Q}
 * @see QueryTableMapping
 */
public abstract class SqaleTableMapping<S, Q extends FlexibleRelationalPathBase<R>, R>
        extends QueryTableMapping<S, Q, R>
        implements SqaleMappingMixin<S, Q, R> {

    protected SqaleTableMapping(
            @NotNull String tableName,
            @NotNull String defaultAliasName,
            @NotNull Class<S> schemaType,
            @NotNull Class<Q> queryType,
            @NotNull SqaleRepoContext repositoryContext) {
        super(tableName, defaultAliasName, schemaType, queryType, repositoryContext);
    }

    @Override
    public SqaleRepoContext repositoryContext() {
        return (SqaleRepoContext) super.repositoryContext();
    }

    /**
     * Returns the mapper creating the string filter/delta processors from context.
     */
    @Override
    protected ItemSqlMapper<Q, R> stringMapper(
            Function<Q, StringPath> rootToQueryItem) {
        return new SqaleItemSqlMapper<>(
                ctx -> new SimpleItemFilterProcessor<>(ctx, rootToQueryItem),
                ctx -> new SimpleItemDeltaProcessor<>(ctx, rootToQueryItem),
                rootToQueryItem);
    }

    /**
     * Returns the mapper creating the integer filter/delta processors from context.
     */
    @Override
    public ItemSqlMapper<Q, R> integerMapper(
            Function<Q, NumberPath<Integer>> rootToQueryItem) {
        return new SqaleItemSqlMapper<>(
                ctx -> new SimpleItemFilterProcessor<>(ctx, rootToQueryItem),
                ctx -> new SimpleItemDeltaProcessor<>(ctx, rootToQueryItem),
                rootToQueryItem);
    }

    /**
     * Returns the mapper creating the boolean filter/delta processors from context.
     */
    @Override
    protected ItemSqlMapper<Q, R> booleanMapper(
            Function<Q, BooleanPath> rootToQueryItem) {
        return new SqaleItemSqlMapper<>(
                ctx -> new SimpleItemFilterProcessor<>(ctx, rootToQueryItem),
                ctx -> new SimpleItemDeltaProcessor<>(ctx, rootToQueryItem),
                rootToQueryItem);
    }

    /**
     * Returns the mapper creating the UUID filter/delta processors from context.
     */
    @Override
    protected ItemSqlMapper<Q, R> uuidMapper(Function<Q, UuidPath> rootToQueryItem) {
        return new SqaleItemSqlMapper<>(
                ctx -> new SimpleItemFilterProcessor<>(ctx, rootToQueryItem),
                ctx -> new SimpleItemDeltaProcessor<>(ctx, rootToQueryItem),
                rootToQueryItem);
    }

    /**
     * Returns the mapper creating the timestamp filter/delta processors from context.
     */
    @Override
    protected <T extends Comparable<T>> ItemSqlMapper<Q, R> timestampMapper(
            Function<Q, DateTimePath<T>> rootToQueryItem) {
        return new SqaleItemSqlMapper<>(
                ctx -> new TimestampItemFilterProcessor<>(ctx, rootToQueryItem),
                ctx -> new TimestampItemDeltaProcessor<>(ctx, rootToQueryItem),
                rootToQueryItem);
    }

    /**
     * Returns the mapper creating the polystring filter/delta processors from context.
     */
    @Override
    protected ItemSqlMapper<Q, R> polyStringMapper(
            @NotNull Function<Q, StringPath> origMapping,
            @NotNull Function<Q, StringPath> normMapping) {
        return new SqaleItemSqlMapper<>(
                ctx -> new PolyStringItemFilterProcessor(ctx, origMapping, normMapping),
                ctx -> new PolyStringItemDeltaProcessor(ctx, origMapping, normMapping),
                origMapping);
    }

    /**
     * Returns the mapper creating the cached URI filter/delta processors from context.
     */
    protected ItemSqlMapper<Q, R> uriMapper(
            Function<Q, NumberPath<Integer>> rootToPath) {
        return new SqaleItemSqlMapper<>(
                ctx -> new UriItemFilterProcessor(ctx, rootToPath),
                ctx -> new UriItemDeltaProcessor(ctx, rootToPath));
    }

    /**
     * Returns the mapper creating the enum filter/delta processors from context.
     */
    public <E extends Enum<E>> ItemSqlMapper<Q, R> enumMapper(
            @NotNull Function<Q, EnumPath<E>> rootToQueryItem) {
        return new SqaleItemSqlMapper<>(
                ctx -> new EnumItemFilterProcessor<>(ctx, rootToQueryItem),
                ctx -> new EnumItemDeltaProcessor<>(ctx, rootToQueryItem),
                rootToQueryItem);
    }

    /**
     * Returns the mapper creating string multi-value filter/delta processors from context.
     */
    protected ItemSqlMapper<Q, R> multiStringMapper(
            Function<Q, ArrayPath<String[], String>> rootToQueryItem) {
        return new SqaleItemSqlMapper<>(
                ctx -> new ArrayPathItemFilterProcessor<String, String>(
                        ctx, rootToQueryItem, "TEXT", String.class, null),
                ctx -> new ArrayItemDeltaProcessor<String, String>(
                        ctx, rootToQueryItem, String.class, null));
    }

    /**
     * Returns the mapper creating poly-string multi-value filter/delta processors from context.
     */
    protected ItemSqlMapper<Q, R> multiPolyStringMapper(
            @NotNull Function<Q, JsonbPath> rootToQueryItem) {
        return new SqaleItemSqlMapper<>(
                ctx -> new JsonbPolysPathItemFilterProcessor<>(ctx, rootToQueryItem),
                ctx -> new JsonbPolysItemDeltaProcessor(ctx, rootToQueryItem));
    }

    /**
     * Returns the mapper creating cached URI multi-value filter/delta processors from context.
     */
    protected ItemSqlMapper<Q, R> multiUriMapper(
            Function<Q, ArrayPath<Integer[], Integer>> rootToQueryItem) {
        return new SqaleItemSqlMapper<>(
                ctx -> new ArrayPathItemFilterProcessor<>(
                        ctx, rootToQueryItem, "INTEGER", Integer.class,
                        ((SqaleRepoContext) ctx.repositoryContext())::searchCachedUriId),
                ctx -> new ArrayItemDeltaProcessor<>(ctx, rootToQueryItem, Integer.class,
                        ((SqaleRepoContext) ctx.repositoryContext())::processCacheableUri));
    }

    /**
     * Implemented for searchable containers that do not use fullObject for their recreation.
     */
    @Override
    public S toSchemaObject(R row) {
        throw new UnsupportedOperationException("Use toSchemaObject(Tuple,...)");
    }

    /**
     * Transforms row Tuple containing {@link R} under entity path and extension columns.
     */
    @Override
    public S toSchemaObject(Tuple tuple, Q entityPath,
            Collection<SelectorOptions<GetOperationOptions>> options)
            throws SchemaException {
        S schemaObject = toSchemaObject(tuple.get(entityPath));
        processExtensionColumns(schemaObject, tuple, entityPath);
        return schemaObject;
    }

    // TODO reconsider, if not necessary in 2023 DELETE (originally meant for ext item per column,
    //  but can this be used for adding index-only exts to schema object even from JSON?)
    @SuppressWarnings("unused")
    protected void processExtensionColumns(S schemaObject, Tuple tuple, Q entityPath) {
        // empty by default, can be overridden
    }

    /**
     * Returns {@link ObjectReferenceType} with specified oid, proper type based on
     * {@link MObjectType} and, optionally, target name/description.
     * Returns {@code null} if OID is null.
     * Fails if OID is not null and {@code repoObjectType} is null.
     */
    @Nullable
    protected ObjectReferenceType objectReference(
            @Nullable UUID oid, MObjectType repoObjectType, Integer relationId) {
        if (oid == null) {
            return null;
        }
        if (repoObjectType == null) {
            throw new IllegalArgumentException(
                    "NULL object type provided for object reference with OID " + oid);
        }

        return new ObjectReferenceType()
                .oid(oid.toString())
                .type(repositoryContext().schemaClassToQName(repoObjectType.getSchemaType()))
                .relation(resolveUriIdToQName(relationId));
    }

    /**
     * Trimming the value to the column size from column metadata (must be specified).
     */
    protected @Nullable String trim(
            @Nullable String value, @NotNull ColumnMetadata columnMetadata) {
        if (!columnMetadata.hasSize()) {
            throw new IllegalArgumentException(
                    "trimString with column metadata without specified size: " + columnMetadata);
        }
        return MiscUtil.trimString(value, columnMetadata.getSize());
    }

    /**
     * Returns ID for relation QName creating new {@link QUri} row in DB as needed.
     * Relation is normalized before consulting the cache.
     * Never returns null, returns default ID for configured default relation.
     */
    protected Integer processCacheableRelation(QName qName) {
        return repositoryContext().processCacheableRelation(qName);
    }

    /** Returns ID for URI creating new cache row in DB as needed. */
    protected Integer processCacheableUri(String uri) {
        return uri != null
                ? repositoryContext().processCacheableUri(uri)
                : null;
    }

    /** Returns ID for URI creating new cache row in DB as needed. */
    protected Integer processCacheableUri(QName qName) {
        return qName != null
                ? repositoryContext().processCacheableUri(QNameUtil.qNameToUri(qName))
                : null;
    }

    /**
     * Returns IDs as Integer array for URI strings creating new cache row in DB as needed.
     * Returns null for null or empty list on input.
     */
    protected Integer[] processCacheableUris(List<String> uris) {
        if (uris == null || uris.isEmpty()) {
            return null;
        }
        return uris.stream()
                .map(uri -> processCacheableUri(uri))
                .toArray(Integer[]::new);
    }

    public String resolveIdToUri(Integer uriId) {
        return repositoryContext().resolveIdToUri(uriId);
    }

    public QName resolveUriIdToQName(Integer uriId) {
        return repositoryContext().resolveUriIdToQName(uriId);
    }

    protected @Nullable UUID oidToUUid(@Nullable String oid) {
        return oid != null ? UUID.fromString(oid) : null;
    }

    protected MObjectType schemaTypeToObjectType(QName schemaType) {
        return schemaType == null ? null :
                MObjectType.fromSchemaType(repositoryContext().qNameToSchemaClass(schemaType));
    }

    protected void setPolyString(PolyStringType polyString,
            Consumer<String> origConsumer, Consumer<String> normConsumer) {
        if (polyString != null) {
            origConsumer.accept(polyString.getOrig());
            normConsumer.accept(polyString.getNorm());
        }
    }

    protected void setReference(ObjectReferenceType ref,
            Consumer<UUID> targetOidConsumer, Consumer<MObjectType> targetTypeConsumer,
            Consumer<Integer> relationIdConsumer) {
        if (ref != null) {
            targetOidConsumer.accept(oidToUUid(ref.getOid()));
            targetTypeConsumer.accept(schemaTypeToObjectType(ref.getType()));
            relationIdConsumer.accept(processCacheableRelation(ref.getRelation()));
        }
    }

    protected <REF extends MReference, OQ extends FlexibleRelationalPathBase<OR>, OR> void storeRefs(
            @NotNull OR ownerRow, @NotNull List<ObjectReferenceType> refs,
            @NotNull QReferenceMapping<?, REF, OQ, OR> mapping, @NotNull JdbcSession jdbcSession) {
        if (!refs.isEmpty()) {
            refs.forEach(ref -> mapping.insert(ref, ownerRow, jdbcSession));
        }
    }

    protected String[] stringsToArray(List<String> strings) {
        if (strings == null || strings.isEmpty()) {
            return null;
        }
        return strings.toArray(String[]::new);
    }

    /** Convenient insert shortcut when the row is fully populated. */
    protected void insert(R row, JdbcSession jdbcSession) {
        jdbcSession.newInsert(defaultAlias())
                .populate(row)
                .execute();
    }

    /**
     * Adds extension container mapping, mainly the resolver for the extension container path.
     */
    public void addExtensionMapping(
            @NotNull ItemName itemName,
            @NotNull MExtItemHolderType holderType,
            @NotNull Function<Q, JsonbPath> rootToPath) {
        ExtensionMapping<Q, R> mapping =
                new ExtensionMapping<>(holderType, queryType(), rootToPath);
        addRelationResolver(itemName, new ExtensionMappingResolver<>(mapping, rootToPath));
        addItemMapping(itemName, new SqaleItemSqlMapper<>(
                ctx -> new ExtensionContainerDeltaProcessor<>(ctx, mapping, rootToPath)));
    }

    /** Converts extension container to the JSONB value. */
    protected Jsonb processExtensions(Containerable extContainer, MExtItemHolderType holderType) {
        if (extContainer == null) {
            return null;
        }

        return new ExtensionProcessor(repositoryContext())
                .processExtensions(extContainer, holderType);
    }

    protected S parseSchemaObject(byte[] fullObject, String identifier) throws SchemaException {
        String serializedForm = new String(fullObject, StandardCharsets.UTF_8);
        try {
            RepositoryObjectParseResult<S> result =
                    repositoryContext().parsePrismObject(serializedForm, schemaType());
            S schemaObject = result.prismObject;
            if (result.parsingContext.hasWarnings()) {
                logger.warn("Object {} parsed with {} warnings",
                        schemaObject.toString(),
                        result.parsingContext.getWarnings().size());
            }
            return schemaObject;
        } catch (SchemaException | RuntimeException | Error e) {
            // This is a serious thing. We have corrupted XML in the repo. This may happen even
            // during system init. We want really loud and detailed error here.
            logger.error("Couldn't parse object {} {}: {}: {}\n{}",
                    schemaType().getSimpleName(), identifier,
                    e.getClass().getName(), e.getMessage(), serializedForm, e);
            throw e;
        }
    }

    /** Creates serialized (byte array) form of an object or a container. */
    public <C extends Containerable> byte[] createFullObject(C container) throws SchemaException {
        return repositoryContext().createStringSerializer()
                .itemsToSkip(fullObjectItemsToSkip())
                .options(SerializationOptions
                        .createSerializeReferenceNamesForNullOids()
                        .skipIndexOnly(true)
                        .skipTransient(true))
                .serialize(container.asPrismContainerValue())
                .getBytes(StandardCharsets.UTF_8);
    }

    protected Collection<? extends QName> fullObjectItemsToSkip() {
        // TODO extend later, things like FocusType.F_JPEG_PHOTO, see ObjectUpdater#updateFullObject
        return Collections.emptyList();
    }
}
