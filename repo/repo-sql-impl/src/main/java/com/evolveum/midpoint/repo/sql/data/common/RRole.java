/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.repo.sql.data.common;

import javax.persistence.*;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.ForeignKey;
import org.hibernate.annotations.Persister;

import com.evolveum.midpoint.repo.sql.data.RepositoryContext;
import com.evolveum.midpoint.repo.sql.data.common.embedded.RPolyString;
import com.evolveum.midpoint.repo.sql.query.definition.JaxbName;
import com.evolveum.midpoint.repo.sql.query.definition.NeverNull;
import com.evolveum.midpoint.repo.sql.util.DtoTranslationException;
import com.evolveum.midpoint.repo.sql.util.IdGeneratorResult;
import com.evolveum.midpoint.repo.sql.util.MidPointJoinedPersister;
import com.evolveum.midpoint.xml.ns._public.common.common_3.RoleType;

@Entity
@ForeignKey(name = "fk_role")
@Table(uniqueConstraints = @UniqueConstraint(name = "uc_role_name", columnNames = { "name_norm" }),
        indexes = {
                @Index(name = "iRoleNameOrig", columnList = "name_orig"),
        }
)
@Persister(impl = MidPointJoinedPersister.class)
@DynamicUpdate
public class RRole extends RAbstractRole {

    private RPolyString nameCopy;
    @Deprecated //todo remove in 3.9
    private String roleType;

    public String getRoleType() {
        return roleType;
    }

    @JaxbName(localPart = "name")
    @AttributeOverrides({
            @AttributeOverride(name = "orig", column = @Column(name = "name_orig")),
            @AttributeOverride(name = "norm", column = @Column(name = "name_norm"))
    })
    @Embedded
    @NeverNull
    public RPolyString getNameCopy() {
        return nameCopy;
    }

    public void setNameCopy(RPolyString nameCopy) {
        this.nameCopy = nameCopy;
    }

    public void setRoleType(String roleType) {
        this.roleType = roleType;
    }

    // dynamically called
    public static void copyFromJAXB(RoleType jaxb, RRole repo, RepositoryContext repositoryContext,
            IdGeneratorResult generatorResult) throws DtoTranslationException {
        RAbstractRole.copyFromJAXB(jaxb, repo, repositoryContext, generatorResult);

        repo.setRoleType(jaxb.getRoleType());
        repo.setNameCopy(RPolyString.copyFromJAXB(jaxb.getName()));
    }
}
