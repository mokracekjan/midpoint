/*
 * Copyright (c) 2010-2015 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.evolveum.midpoint.schema.util;

import com.evolveum.midpoint.prism.*;
import com.evolveum.midpoint.xml.ns._public.common.common_3.CaseType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.CaseWorkItemType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.List;


/**
 * @author bpowers
 */
public class CaseWorkItemUtil {

    @NotNull
    public static CaseType getCaseRequired(CaseWorkItemType workItem) {
        CaseType rv = getCase(workItem);
        if (rv != null) {
            return rv;
        } else {
            throw new IllegalStateException("No parent case for " + workItem);
        }
    }

    public static CaseType getCase(CaseWorkItemType workItem) {
        if (workItem == null) {
            return null;
        }
        @SuppressWarnings({"unchecked", "raw"})
        PrismContainerable<CaseWorkItemType> parent = workItem.asPrismContainerValue().getParent();
        if (!(parent instanceof PrismContainer)) {
            return null;
        }
        PrismValue parentParent = ((PrismContainer<CaseWorkItemType>) parent).getParent();
        if (!(parentParent instanceof PrismContainerValue)) {
            return null;
        }
        @SuppressWarnings({"unchecked", "raw"})
        PrismContainerValue<CaseType> parentParentPcv = (PrismContainerValue<CaseType>) parentParent;
        return parentParentPcv.asContainerable();
    }

    public static WorkItemId getId(CaseWorkItemType workItem) {
        return WorkItemId.of(workItem);
    }

    public static CaseWorkItemType getWorkItem(CaseType aCase, long id) {
        for (CaseWorkItemType workItem : aCase.getWorkItem()) {
            if (workItem.getId() != null && workItem.getId() == id) {
                return workItem;
            }
        }
        return null;
    }

    public static boolean isCaseWorkItemNotClosed(CaseWorkItemType workItem){
        return workItem != null && workItem.getCloseTimestamp() == null;
    }

    public static boolean isWorkItemClaimable(CaseWorkItemType workItem){
        return workItem != null && (workItem.getOriginalAssigneeRef() == null || StringUtils.isEmpty(workItem.getOriginalAssigneeRef().getOid()))
                && !doesAssigneeExist(workItem) && CollectionUtils.isNotEmpty(workItem.getCandidateRef());
    }

    public static boolean doesAssigneeExist(CaseWorkItemType workItem){
        if (workItem == null || CollectionUtils.isEmpty(workItem.getAssigneeRef())){
            return false;
        }
        for (ObjectReferenceType assignee : workItem.getAssigneeRef()){
            if (StringUtils.isNotEmpty(assignee.getOid())){
                return true;
            }
        }
        return false;
    }


}
