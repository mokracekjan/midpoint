/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.repo.common.task.work.segmentation.content;

import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.query.ObjectFilter;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.xml.ns._public.common.common_3.AbstractWorkSegmentationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FilterWorkBucketContentType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.WorkBucketType;
import com.evolveum.prism.xml.ns._public.query_3.SearchFilterType;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Component
public class FilterWorkBucketContentHandler extends BaseWorkBucketContentHandler {

    @PostConstruct
    public void register() {
        registry.registerHandler(FilterWorkBucketContentType.class, this);
    }

    @NotNull
    @Override
    public List<ObjectFilter> createSpecificFilters(@NotNull WorkBucketType bucket,
            AbstractWorkSegmentationType configuration, Class<? extends ObjectType> type,
            Function<ItemPath, ItemDefinition<?>> itemDefinitionProvider) throws SchemaException {

        FilterWorkBucketContentType content = (FilterWorkBucketContentType) bucket.getContent();
        List<ObjectFilter> rv = new ArrayList<>();
        for (SearchFilterType filter : content.getFilter()) {
            rv.add(prismContext.getQueryConverter().createObjectFilter(type, filter));
        }
        return rv;
    }
}
