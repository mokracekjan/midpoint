/*
 * Copyright (c) 2010-2017 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.web.page.admin.users;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.evolveum.midpoint.gui.api.component.ObjectBrowserPanel;
import com.evolveum.midpoint.gui.impl.component.icon.CompositedIconBuilder;
import com.evolveum.midpoint.gui.impl.component.icon.IconCssStyle;
import com.evolveum.midpoint.prism.path.ItemPath;
import com.evolveum.midpoint.prism.query.ObjectFilter;
import com.evolveum.midpoint.util.MiscUtil;
import com.evolveum.midpoint.util.exception.ObjectNotFoundException;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.web.application.Url;
import com.evolveum.midpoint.web.component.data.column.*;
import com.evolveum.midpoint.web.component.form.MidpointForm;
import com.evolveum.midpoint.web.component.menu.cog.ButtonInlineMenuItem;
import com.evolveum.midpoint.web.component.menu.cog.InlineMenuItemAction;
import com.evolveum.midpoint.web.component.util.FocusListInlineMenuHelper;
import com.evolveum.midpoint.web.page.admin.PageAdmin;
import com.evolveum.midpoint.web.session.UserProfileStorage;
import com.evolveum.midpoint.xml.ns._public.common.common_3.*;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.model.IModel;
import org.apache.wicket.request.mapper.parameter.PageParameters;

import com.evolveum.midpoint.gui.api.GuiStyleConstants;
import com.evolveum.midpoint.gui.api.component.MainObjectListPanel;
import com.evolveum.midpoint.gui.api.model.LoadableModel;
import com.evolveum.midpoint.gui.api.util.WebComponentUtil;
import com.evolveum.midpoint.gui.api.util.WebModelServiceUtils;
import com.evolveum.midpoint.model.api.ModelExecuteOptions;
import com.evolveum.midpoint.prism.delta.ChangeType;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.security.api.AuthorizationConstants;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.logging.LoggingUtils;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.web.application.AuthorizationAction;
import com.evolveum.midpoint.web.application.PageDescriptor;
import com.evolveum.midpoint.web.component.menu.cog.InlineMenuItem;
import com.evolveum.midpoint.web.component.util.SelectableBean;
import com.evolveum.midpoint.web.page.admin.users.component.ExecuteChangeOptionsDto;
import com.evolveum.midpoint.web.session.UserProfileStorage.TableId;
import com.evolveum.midpoint.web.util.OnePageParameterEncoder;

import javax.xml.namespace.QName;

/**
 * @author lazyman
 */
@PageDescriptor(
        urls = {
                @Url(mountUrl = "/admin/users", matchUrlForSecurity = "/admin/users")
        },
        action = {
                @AuthorizationAction(actionUri = AuthorizationConstants.AUTZ_UI_USERS_ALL_URL,
                        label = "PageAdminUsers.auth.usersAll.label",
                        description = "PageAdminUsers.auth.usersAll.description"),
                @AuthorizationAction(actionUri = AuthorizationConstants.AUTZ_UI_USERS_URL,
                        label = "PageUsers.auth.users.label",
                        description = "PageUsers.auth.users.description"),
                @AuthorizationAction(actionUri = AuthorizationConstants.AUTZ_UI_USERS_VIEW_URL,
                        label = "PageUsers.auth.users.view.label",
                        description = "PageUsers.auth.users.view.description")
        })
public class PageUsers extends PageAdmin {
    private static final long serialVersionUID = 1L;

    private static final Trace LOGGER = TraceManager.getTrace(PageUsers.class);

    private static final String DOT_CLASS = PageUsers.class.getName() + ".";

    private static final String OPERATION_DISABLE_USERS = DOT_CLASS + "disableUsers";
    private static final String OPERATION_DISABLE_USER = DOT_CLASS + "disableUser";
    private static final String OPERATION_ENABLE_USERS = DOT_CLASS + "enableUsers";
    private static final String OPERATION_ENABLE_USER = DOT_CLASS + "enableUser";
    private static final String OPERATION_RECONCILE_USERS = DOT_CLASS + "reconcileUsers";
    private static final String OPERATION_RECONCILE_USER = DOT_CLASS + "reconcileUser";
    private static final String OPERATION_UNLOCK_USERS = DOT_CLASS + "unlockUsers";
    private static final String OPERATION_UNLOCK_USER = DOT_CLASS + "unlockUser";
    private static final String OPERATION_LOAD_MERGE_CONFIGURATION = DOT_CLASS + "loadMergeConfiguration";

    private static final String ID_MAIN_FORM = "mainForm";
    private static final String ID_TABLE = "table";

    public PageUsers() {
        this(null);
    }

    public PageUsers(PageParameters params) {
        super(params);
    }

    @Override
    protected void onInitialize() {
        super.onInitialize();
        initLayout();
    }

    protected void initLayout() {

        Form mainForm = new MidpointForm(ID_MAIN_FORM);
        add(mainForm);

        MainObjectListPanel<UserType> table = new MainObjectListPanel<>(ID_TABLE, UserType.class) {
            @Override
            protected void objectDetailsPerformed(AjaxRequestTarget target, UserType user) {
                PageParameters parameters = new PageParameters();
                parameters.add(OnePageParameterEncoder.PARAMETER, user.getOid());
                navigateToNext(PageUser.class, parameters);
            }

            @Override
            protected UserProfileStorage.TableId getTableId() {
                return TableId.TABLE_USERS;
            }

            @Override
            protected List<InlineMenuItem> createInlineMenu() {
                return createRowActions();
            }

            @Override
            protected List<ItemPath> getFixedSearchItems() {
                List<ItemPath> fixedSearchItems = new ArrayList<>();
                fixedSearchItems.add(UserType.F_NAME);
                fixedSearchItems.add(UserType.F_GIVEN_NAME);
                fixedSearchItems.add(UserType.F_FAMILY_NAME);
                return fixedSearchItems;
            }

            @Override
            protected String getNothingSelectedMessage() {
                return getString("pageUsers.message.nothingSelected");
            }

            @Override
            protected String getConfirmMessageKeyForSingleObject() {
                return "pageUsers.message.confirmationMessageForMultipleObject";
            }

            @Override
            protected String getConfirmMessageKeyForMultiObject() {
                return "pageUsers.message.confirmationMessageForSingleObject";
            }
        };
        table.setOutputMarkupId(true);
        mainForm.add(table);
    }

    private List<InlineMenuItem> createRowActions() {
        List<InlineMenuItem> menu = new ArrayList<>();
        ButtonInlineMenuItem enableItem = new ButtonInlineMenuItem(createStringResource("pageUsers.menu.enable")) {
            private static final long serialVersionUID = 1L;

            @Override
            public InlineMenuItemAction initAction() {
                return new ColumnMenuAction<SelectableBean<UserType>>() {

                    @Override
                    public void onClick(AjaxRequestTarget target) {
                        if (getRowModel() == null){
                            updateActivationPerformed(target, true, null);
                        } else {
                            SelectableBean<UserType> rowDto = getRowModel().getObject();
                            updateActivationPerformed(target, true, rowDto.getValue());
                        }
                    }
                };
            }

            @Override
            public CompositedIconBuilder getIconCompositedBuilder(){
                return getDefaultCompositedIconBuilder(GuiStyleConstants.CLASS_OBJECT_USER_ICON);
            }

            @Override
            public IModel<String> getConfirmationMessageModel(){
                String actionName = createStringResource("pageUsers.message.enableAction").getString();
                return getTable().getConfirmationMessageModel((ColumnMenuAction) getAction(), actionName);
            }
        };
        enableItem.setVisibilityChecker(FocusListInlineMenuHelper::isObjectDisabled);
        menu.add(enableItem);

        ButtonInlineMenuItem disableItem = new ButtonInlineMenuItem(createStringResource("pageUsers.menu.disable")) {
                     private static final long serialVersionUID = 1L;

            @Override
            public InlineMenuItemAction initAction() {
                return new ColumnMenuAction<SelectableBean<UserType>>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void onClick(AjaxRequestTarget target) {
                        if (getRowModel() == null) {
                            updateActivationPerformed(target, false, null);
                        } else {
                            SelectableBean<UserType> rowDto = getRowModel().getObject();
                            updateActivationPerformed(target, false, rowDto.getValue());
                        }
                    }
                };
            }

            @Override
            public CompositedIconBuilder getIconCompositedBuilder(){
                CompositedIconBuilder builder = getDefaultCompositedIconBuilder(GuiStyleConstants.CLASS_OBJECT_USER_ICON);
                builder.appendLayerIcon(WebComponentUtil.createIconType(GuiStyleConstants.CLASS_BAN), IconCssStyle.BOTTOM_RIGHT_STYLE);
                return builder;
            }

            @Override
            public IModel<String> getConfirmationMessageModel() {
                String actionName = createStringResource("pageUsers.message.disableAction").getString();
                return getTable().getConfirmationMessageModel((ColumnMenuAction) getAction(), actionName);
            }
        };
        disableItem.setVisibilityChecker(FocusListInlineMenuHelper::isObjectEnabled);
        menu.add(disableItem);

        menu.add(new ButtonInlineMenuItem(createStringResource("pageUsers.menu.reconcile")) {
            private static final long serialVersionUID = 1L;

            @Override
            public InlineMenuItemAction initAction() {
                return new ColumnMenuAction<SelectableBean<UserType>>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void onClick(AjaxRequestTarget target) {
                        if (getRowModel() == null) {
                            reconcilePerformed(target, null);
                        } else {
                            SelectableBean<UserType> rowDto = getRowModel().getObject();
                            reconcilePerformed(target, rowDto.getValue());
                        }
                    }
                };
            }

            @Override
            public CompositedIconBuilder getIconCompositedBuilder(){
                return getDefaultCompositedIconBuilder(GuiStyleConstants.CLASS_RECONCILE_MENU_ITEM);
            }

            @Override
            public IModel<String> getConfirmationMessageModel() {
                String actionName = createStringResource("pageUsers.message.reconcileAction").getString();
                return getTable().getConfirmationMessageModel((ColumnMenuAction) getAction(), actionName);
            }
        });

        menu.add(new InlineMenuItem(createStringResource("pageUsers.menu.unlock")) {
            private static final long serialVersionUID = 1L;

            @Override
            public InlineMenuItemAction initAction() {
                return new ColumnMenuAction<SelectableBean<UserType>>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void onClick(AjaxRequestTarget target) {
                        if (getRowModel() == null) {
                            unlockPerformed(target, null);
                        } else {
                            SelectableBean<UserType> rowDto = getRowModel().getObject();
                            unlockPerformed(target, rowDto.getValue());
                        }
                    }
                };
            }

            @Override
            public IModel<String> getConfirmationMessageModel(){
                String actionName = createStringResource("pageUsers.message.unlockAction").getString();
                return getTable().getConfirmationMessageModel((ColumnMenuAction) getAction(), actionName);
            }
        });

        menu.add(getTable().createDeleteInlineMenu());

        menu.add(new InlineMenuItem(createStringResource("pageUsers.menu.merge")) {
            private static final long serialVersionUID = 1L;

            @Override
            public InlineMenuItemAction initAction() {
                return new ColumnMenuAction<SelectableBean<UserType>>() {

                    @Override
                    public void onClick(AjaxRequestTarget target) {
                        OperationResult result = new OperationResult(OPERATION_LOAD_MERGE_CONFIGURATION);
                        List<MergeConfigurationType> mergeConfiguration;
                        try {
                            mergeConfiguration = getModelInteractionService().getMergeConfiguration(result);
                        } catch (ObjectNotFoundException | SchemaException ex){
                            LOGGER.error("Couldn't load merge configuration: {}", ex.getLocalizedMessage());
                            result.recomputeStatus();
                            getFeedbackMessages().error(PageUsers.this, ex.getLocalizedMessage());
                            target.add(getFeedbackPanel());
                            return;
                        }

                        if (mergeConfiguration == null || mergeConfiguration.size() == 0){
                            getFeedbackMessages().warn(PageUsers.this, createStringResource("PageUsers.noMergeConfigurationMessage").getString());
                            target.add(getFeedbackPanel());
                            return;
                        }
                        if (getRowModel() == null) {
                            mergePerformed(target, null);
                        } else {
                            SelectableBean<UserType> rowDto = getRowModel().getObject();
                            mergePerformed(target, rowDto.getValue());
                        }
                    }
                };
            }

            @Override
            public boolean isHeaderMenuItem(){
                return false;
            }
        });
        return menu;
    }


    private MainObjectListPanel<UserType> getTable() {
        return (MainObjectListPanel<UserType>) get(createComponentPath(ID_MAIN_FORM, ID_TABLE));
    }

    private void mergePerformed(AjaxRequestTarget target, final UserType selectedUser) {
        List<QName> supportedTypes = new ArrayList<>();
        supportedTypes.add(UserType.COMPLEX_TYPE);
        ObjectFilter filter = getPrismContext().queryFactory().createInOid(selectedUser.getOid());
        ObjectFilter notFilter = getPrismContext().queryFactory().createNot(filter);
        ObjectBrowserPanel<UserType> panel = new ObjectBrowserPanel<>(
                getMainPopupBodyId(), UserType.class,
                supportedTypes, false, PageUsers.this, notFilter) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void onSelectPerformed(AjaxRequestTarget target, UserType user) {
                hideMainPopup(target);
                mergeConfirmedPerformed(selectedUser, user, target);
            }

        };
        panel.setOutputMarkupId(true);
        showMainPopup(panel, target);
    }

    private void mergeConfirmedPerformed(UserType mergeObject, UserType mergeWithObject, AjaxRequestTarget target) {
        setResponsePage(new PageMergeObjects(mergeObject, mergeWithObject, UserType.class));
    }

    private void unlockPerformed(AjaxRequestTarget target, UserType selectedUser) {
        List<UserType> users = getTable().isAnythingSelected(target, selectedUser);
        if (users.isEmpty()) {
            return;
        }
        OperationResult result = new OperationResult(OPERATION_UNLOCK_USERS);
        for (UserType user : users) {
            OperationResult opResult = result.createSubresult(getString(OPERATION_UNLOCK_USER, user));
            try {
                Task task = createSimpleTask(OPERATION_UNLOCK_USER + user);
                // TODO skip the operation if the user has no password
                // credentials specified (otherwise this would create
                // almost-empty password container)
                ObjectDelta delta = getPrismContext().deltaFactory().object().createModificationReplaceProperty(
                        UserType.class, user.getOid(), ItemPath.create(UserType.F_ACTIVATION,
                                ActivationType.F_LOCKOUT_STATUS),
                        LockoutStatusType.NORMAL);
                Collection<ObjectDelta<? extends ObjectType>> deltas = MiscUtil.createCollection(delta);
                getModelService().executeChanges(deltas, null, task, opResult);
                opResult.computeStatusIfUnknown();
            } catch (Exception ex) {
                opResult.recomputeStatus();
                opResult.recordFatalError(getString("PageUsers.message.unlock.fatalError", user), ex);
                LoggingUtils.logUnexpectedException(LOGGER, "Couldn't unlock user " + user + ".", ex);
            }
        }

        result.recomputeStatus();

        showResult(result);
        target.add(getFeedbackPanel());
        getTable().refreshTable(target);
        getTable().clearCache();
    }

    private void reconcilePerformed(AjaxRequestTarget target, UserType selectedUser) {
        List<UserType> users = getTable().isAnythingSelected(target, selectedUser);
        if (users.isEmpty()) {
            return;
        }

        OperationResult result = new OperationResult(OPERATION_RECONCILE_USERS);
        for (UserType user : users) {
            OperationResult opResult = result.createSubresult(getString(OPERATION_RECONCILE_USER, user));
            try {
                Task task = createSimpleTask(OPERATION_RECONCILE_USER + user);
                ObjectDelta delta = getPrismContext().deltaFactory().object().createEmptyModifyDelta(UserType.class, user.getOid()
                );
                Collection<ObjectDelta<? extends ObjectType>> deltas = MiscUtil.createCollection(delta);
                getModelService().executeChanges(deltas, executeOptions().reconcile(), task,
                        opResult);
                opResult.computeStatusIfUnknown();
            } catch (Exception ex) {
                opResult.recomputeStatus();
                opResult.recordFatalError(getString("PageUsers.message.reconcile.fatalError", user), ex);
                LoggingUtils.logUnexpectedException(LOGGER, "Couldn't reconcile user " + user + ".", ex);
            }
        }

        result.setSummarizeSuccesses(true);
        result.summarize();
        result.recomputeStatus();

        showResult(result);
        target.add(getFeedbackPanel());
        getTable().refreshTable(target);
        getTable().clearCache();
    }

    /**
     * This method updates user activation. If userOid parameter is not null,
     * than it updates only that user, otherwise it checks table for selected
     * users.
     */
    private void updateActivationPerformed(AjaxRequestTarget target, boolean enabling,
            UserType selectedUser) {
        List<UserType> users = getTable().isAnythingSelected(target, selectedUser);
        if (users.isEmpty()) {
            return;
        }

        String operation = enabling ? OPERATION_ENABLE_USERS : OPERATION_DISABLE_USERS;
        OperationResult result = new OperationResult(operation);
        for (UserType user : users) {
            operation = enabling ? OPERATION_ENABLE_USER : OPERATION_DISABLE_USER;
            OperationResult subResult = result.createSubresult(operation);
            try {
                Task task = createSimpleTask(operation);

                ObjectDelta objectDelta = WebModelServiceUtils.createActivationAdminStatusDelta(
                        UserType.class, user.getOid(), enabling, getPrismContext());

                ExecuteChangeOptionsDto executeOptions = getTable().getExecuteOptions();
                ModelExecuteOptions options = executeOptions.createOptions(getPrismContext());
                LOGGER.debug("Using options {}.", executeOptions);
                getModelService().executeChanges(MiscUtil.createCollection(objectDelta), options,
                        task, subResult);
                subResult.recordSuccess();
            } catch (Exception ex) {
                subResult.recomputeStatus();
                if (enabling) {
                    subResult.recordFatalError(getString("PageUsers.message.enable.fatalError"), ex);
                    LoggingUtils.logUnexpectedException(LOGGER, "Couldn't enable user", ex);
                } else {
                    subResult.recordFatalError(getString("PageUsers.message.disable.fatalError"), ex);
                    LoggingUtils.logUnexpectedException(LOGGER, "Couldn't disable user", ex);
                }
            }
        }
        result.recomputeStatus();

        showResult(result);
        target.add(getFeedbackPanel());
        getTable().clearCache();
        getTable().refreshTable(target);
    }
}
