/*
 * Copyright (c) 2010-2020 Evolveum
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

package com.evolveum.midpoint.web.security.module.authentication;

import com.evolveum.midpoint.model.api.authentication.ModuleAuthentication;
import com.evolveum.midpoint.model.api.authentication.AuthenticationModuleNameConstants;
import com.evolveum.midpoint.model.api.authentication.ModuleType;
import com.evolveum.midpoint.model.api.authentication.StateOfModule;
import com.evolveum.midpoint.util.annotation.Experimental;

/**
 * Created by Viliam Repan (lazyman).
 */
@Experimental
public class OtherModuleAuthentication extends ModuleAuthentication {

    public OtherModuleAuthentication() {
        super(AuthenticationModuleNameConstants.OTHER);
        setType(ModuleType.LOCAL);
        setState(StateOfModule.LOGIN_PROCESSING);
    }

    public ModuleAuthentication clone() {
        OtherModuleAuthentication module = new OtherModuleAuthentication();
        module.setAuthentication(this.getAuthentication());
        clone(module);
        return module;
    }
}
