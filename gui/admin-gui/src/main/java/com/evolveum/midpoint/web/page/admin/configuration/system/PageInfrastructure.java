/*
 * Copyright (c) 2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.web.page.admin.configuration.system;

import com.evolveum.midpoint.security.api.AuthorizationConstants;
import com.evolveum.midpoint.web.application.AuthorizationAction;
import com.evolveum.midpoint.web.application.PageDescriptor;
import com.evolveum.midpoint.web.application.Url;
import com.evolveum.midpoint.web.page.admin.configuration.PageAdminConfiguration;
import com.evolveum.midpoint.xml.ns._public.common.common_3.InfrastructureConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SystemConfigurationType;

import org.apache.wicket.extensions.markup.html.tabs.AbstractTab;
import org.apache.wicket.extensions.markup.html.tabs.ITab;
import org.apache.wicket.markup.html.WebMarkupContainer;

import java.util.ArrayList;
import java.util.List;

@PageDescriptor(
        urls = {
                @Url(mountUrl = "/admin/config/system/infrastructure", matchUrlForSecurity = "/admin/config/system/infrastructure"),
        },
        action = {
                @AuthorizationAction(actionUri = PageAdminConfiguration.AUTH_CONFIGURATION_ALL,
                        label = PageAdminConfiguration.AUTH_CONFIGURATION_ALL_LABEL,
                        description = PageAdminConfiguration.AUTH_CONFIGURATION_ALL_DESCRIPTION),
                @AuthorizationAction(actionUri = AuthorizationConstants.AUTZ_UI_CONFIGURATION_SYSTEM_CONFIG_URL,
                        label = "PageSystemConfiguration.auth.configSystemConfiguration.label",
                        description = "PageSystemConfiguration.auth.configSystemConfiguration.description")
        })
public class PageInfrastructure extends PageAbstractSystemConfiguration {


    @Override
    protected List<ITab> createTabs() {
        List<ITab> tabs = new ArrayList<>();
        tabs.add(new AbstractTab(createStringResource("pageSystemConfiguration.infrastructure.title")) {

            private static final long serialVersionUID = 1L;

            @Override
            public WebMarkupContainer getPanel(String panelId) {
                return createContainerPanel(panelId, getObjectModel(), SystemConfigurationType.F_INFRASTRUCTURE, InfrastructureConfigurationType.COMPLEX_TYPE);
            }
        });
        return tabs;
    }
}
