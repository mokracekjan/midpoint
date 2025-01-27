/*
 * Copyright (c) 2018 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.gui.impl.component;

import com.evolveum.midpoint.gui.api.component.DisplayNamePanel;
import com.evolveum.midpoint.gui.api.component.tabs.PanelTab;
import com.evolveum.midpoint.gui.api.prism.wrapper.ItemWrapper;
import com.evolveum.midpoint.gui.api.prism.wrapper.PrismContainerValueWrapper;
import com.evolveum.midpoint.gui.api.prism.wrapper.PrismContainerWrapper;
import com.evolveum.midpoint.gui.api.util.WebComponentUtil;
import com.evolveum.midpoint.gui.api.util.WebDisplayTypeUtil;
import com.evolveum.midpoint.gui.impl.component.icon.CompositedIconBuilder;
import com.evolveum.midpoint.gui.impl.component.icon.IconCssStyle;
import com.evolveum.midpoint.gui.impl.factory.panel.ItemRealValueModel;
import com.evolveum.midpoint.gui.impl.prism.panel.SingleContainerPanel;
import com.evolveum.midpoint.model.api.AssignmentObjectRelation;
import com.evolveum.midpoint.prism.Containerable;
import com.evolveum.midpoint.prism.PrismContainerValue;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.web.component.AjaxButton;
import com.evolveum.midpoint.web.component.CompositedIconButtonDto;
import com.evolveum.midpoint.web.component.data.column.CheckBoxHeaderColumn;
import com.evolveum.midpoint.web.component.menu.cog.InlineMenuItem;
import com.evolveum.midpoint.web.component.prism.ItemVisibility;
import com.evolveum.midpoint.web.component.util.VisibleBehaviour;
import com.evolveum.midpoint.web.component.util.VisibleEnableBehaviour;

import com.evolveum.midpoint.web.model.PrismContainerWrapperModel;
import com.evolveum.midpoint.web.page.admin.reports.component.EngineReportTabPanel;
import com.evolveum.midpoint.web.session.UserProfileStorage;
import com.evolveum.midpoint.xml.ns._public.common.common_3.DisplayType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectCollectionReportEngineConfigurationType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ReportType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.SearchFilterParameterType;

import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.extensions.markup.html.repeater.data.table.IColumn;
import org.apache.wicket.extensions.markup.html.tabs.ITab;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author skublik
 */

public abstract class BasicMultivalueContainerListPanel<C extends Containerable>
        extends MultivalueContainerListPanelWithDetailsPanel<C> {

    private static final long serialVersionUID = 1L;

    public BasicMultivalueContainerListPanel(String id, Class<C> type) {
        super(id, type);
    }

    protected String createHeaderClassIcon(){
        return "fa fa-sliders";
    };

    @Override
    protected boolean isCreateNewObjectVisible() {
        return true;
    }

    @Override
    protected List<CompositedIconButtonDto> createNewButtonDescription() {
        String title = getPageBase().createStringResource("PageAdminObjectDetails.title.new", getContainerNameForNewButton()).getString();
        DisplayType defaultButtonDisplayType = WebDisplayTypeUtil.createDisplayType("fa fa-plus", "green", title);
        CompositedIconButtonDto defaultButton = new CompositedIconButtonDto();
        CompositedIconBuilder builder = new CompositedIconBuilder();
        builder.setBasicIcon(WebComponentUtil.getIconCssClass(defaultButtonDisplayType), IconCssStyle.IN_ROW_STYLE)
                .appendColorHtmlValue(WebComponentUtil.getIconColor(defaultButtonDisplayType));

        defaultButton.setAdditionalButtonDisplayType(defaultButtonDisplayType);
        defaultButton.setCompositedIcon(builder.build());
        return Collections.singletonList(defaultButton);
    }

    protected String getContainerNameForNewButton(){
        return "";
    }

    @Override
    protected UserProfileStorage.TableId getTableId() {
        return null;
    }

    @Override
    protected Component createHeader(String headerId) {
        return new WebMarkupContainer(headerId);
    }

    @Override
    protected IColumn<PrismContainerValueWrapper<C>, String> createCheckboxColumn() {
        return new CheckBoxHeaderColumn<>();
    }

    @Override
    protected List<InlineMenuItem> createInlineMenu() {
        return getDefaultMenuActions();
    }

    @Override
    protected void newItemPerformed(AjaxRequestTarget target, AssignmentObjectRelation relationSepc) {
        PrismContainerValue<C> newParameter = getContainerModel().getObject().getItem().createNewValue();
        createNewItemContainerValueWrapper(newParameter, getContainerModel().getObject(), target);
        refreshTable(target);
        reloadSavePreviewButtons(target);
    }

}
