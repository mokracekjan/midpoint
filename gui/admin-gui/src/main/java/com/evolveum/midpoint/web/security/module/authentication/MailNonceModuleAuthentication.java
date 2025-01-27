/*
 * Copyright (c) 2010-2019 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.web.security.module.authentication;

import com.evolveum.midpoint.model.api.authentication.ModuleAuthentication;
import com.evolveum.midpoint.model.api.authentication.AuthenticationModuleNameConstants;

/**
 * @author skublik
 */

public class MailNonceModuleAuthentication extends CredentialModuleAuthentication{

    public MailNonceModuleAuthentication() {
        super(AuthenticationModuleNameConstants.MAIL_NONCE);
    }

    public ModuleAuthentication clone() {
        MailNonceModuleAuthentication module = new MailNonceModuleAuthentication();
        module.setAuthentication(this.getAuthentication());
        super.clone(module);
        return module;
    }

}
