/*
 * Copyright (c) 2010-2015 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.web.page.admin.roles;

import com.evolveum.midpoint.gui.api.component.MainObjectListPanel;
import com.evolveum.midpoint.gui.api.util.WebComponentUtil;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.security.api.AuthorizationConstants;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.web.application.AuthorizationAction;
import com.evolveum.midpoint.web.application.PageDescriptor;
import com.evolveum.midpoint.web.application.Url;
import com.evolveum.midpoint.web.component.data.column.ColumnMenuAction;
import com.evolveum.midpoint.web.component.data.column.ColumnUtils;
import com.evolveum.midpoint.web.component.form.MidpointForm;
import com.evolveum.midpoint.web.component.menu.cog.InlineMenuItem;
import com.evolveum.midpoint.web.component.search.Search;
import com.evolveum.midpoint.web.component.util.FocusListInlineMenuHelper;
import com.evolveum.midpoint.web.component.util.SelectableBean;
import com.evolveum.midpoint.web.page.admin.PageAdmin;
import com.evolveum.midpoint.web.session.UserProfileStorage;
import com.evolveum.midpoint.web.util.OnePageParameterEncoder;
import com.evolveum.midpoint.xml.ns._public.common.common_3.*;

import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.extensions.markup.html.repeater.data.table.IColumn;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.model.IModel;
import org.apache.wicket.request.mapper.parameter.PageParameters;

import java.util.ArrayList;
import java.util.List;

/**
 * @author lazyman
 */
@PageDescriptor(
        urls = {
                @Url(mountUrl = "/admin/roles")
        },
        action = {
        @AuthorizationAction(actionUri = AuthorizationConstants.AUTZ_UI_ROLES_ALL_URL,
                label = "PageAdminRoles.auth.roleAll.label",
                description = "PageAdminRoles.auth.roleAll.description"),
        @AuthorizationAction(actionUri = AuthorizationConstants.AUTZ_UI_ROLES_URL,
                label = "PageRoles.auth.roles.label",
                description = "PageRoles.auth.roles.description"),
        @AuthorizationAction(actionUri = AuthorizationConstants.AUTZ_UI_ROLES_VIEW_URL,
                label = "PageRoles.auth.roles.view.label",
                description = "PageRoles.auth.roles.view.description")})
public class PageRoles extends PageAdmin {

    private static final Trace LOGGER = TraceManager.getTrace(PageRoles.class);
    private static final String DOT_CLASS = PageRoles.class.getName() + ".";

    private static final String OPERATION_SEARCH_MEMBERS = DOT_CLASS + "searchMembers";

    private static final String ID_MAIN_FORM = "mainForm";
    private static final String ID_TABLE = "table";

    private IModel<Search> searchModel;

    public PageRoles() {
        super();
    }

    @Override
    protected void onInitialize() {
        super.onInitialize();
        initLayout();
    }

    protected void initLayout() {

        Form mainForm = new MidpointForm(ID_MAIN_FORM);
        add(mainForm);

        MainObjectListPanel<RoleType> table = new MainObjectListPanel<RoleType>(ID_TABLE, RoleType.class) {
            @Override
            protected void objectDetailsPerformed(AjaxRequestTarget target, RoleType role) {
                PageRoles.this.roleDetailsPerformed(target, role.getOid());
            }

            @Override
            protected UserProfileStorage.TableId getTableId() {
                return UserProfileStorage.TableId.TABLE_ROLES;
            }

            @Override
            protected List<InlineMenuItem> createInlineMenu() {
                FocusListInlineMenuHelper<RoleType> listInlineMenuHelper = new FocusListInlineMenuHelper<RoleType>(RoleType.class, PageRoles.this, this){
                    private static final long serialVersionUID = 1L;

                    protected boolean isShowConfirmationDialog(ColumnMenuAction action){
                        return PageRoles.this.isShowConfirmationDialog(action);
                    }

                    protected IModel<String> getConfirmationMessageModel(ColumnMenuAction action, String actionName){
                        return PageRoles.this.getConfirmationMessageModel(action, actionName);
                    }

                };
                return listInlineMenuHelper.createRowActions(getType());
            }

            @Override
            protected List<IColumn<SelectableBean<RoleType>, String>> createDefaultColumns() {
                return ColumnUtils.getDefaultRoleColumns();
            }

            @Override
            protected List<ItemPath> getFixedSearchItems() {
                List<ItemPath> fixedSearchItems = new ArrayList<>();
                fixedSearchItems.add(ObjectType.F_NAME);
                fixedSearchItems.add(AbstractRoleType.F_DISPLAY_NAME);
                fixedSearchItems.add(AbstractRoleType.F_IDENTIFIER);
                return fixedSearchItems;
            }
        };
        table.setOutputMarkupId(true);
        mainForm.add(table);
    }

    private void roleDetailsPerformed(AjaxRequestTarget target, String oid) {
        PageParameters parameters = new PageParameters();
        parameters.add(OnePageParameterEncoder.PARAMETER, oid);
        navigateToNext(PageRole.class, parameters);
    }

    private IModel<String> getConfirmationMessageModel(ColumnMenuAction action, String actionName){
        return WebComponentUtil.createAbstractRoleConfirmationMessage(actionName, action, getObjectListPanel(), this);

    }

    private MainObjectListPanel<RoleType> getObjectListPanel() {
        return (MainObjectListPanel<RoleType>) get(createComponentPath(ID_MAIN_FORM, ID_TABLE));
    }

    private boolean isShowConfirmationDialog(ColumnMenuAction action){
        return action.getRowModel() != null ||
                getObjectListPanel().getSelectedObjectsCount() > 0;
    }
}
