/*
 * Copyright (c) 2010-2017 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.web.component.objectdetails;

import java.util.ArrayList;
import java.util.List;

import com.evolveum.midpoint.gui.impl.component.icon.CompositedIconBuilder;
import com.evolveum.midpoint.web.component.data.ISelectableDataProvider;
import com.evolveum.midpoint.web.component.util.SelectableBean;
import com.evolveum.midpoint.web.session.UserProfileStorage;

import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.extensions.markup.html.repeater.data.table.IColumn;
import org.apache.wicket.model.IModel;

import com.evolveum.midpoint.gui.api.GuiStyleConstants;
import com.evolveum.midpoint.gui.api.component.MainObjectListPanel;
import com.evolveum.midpoint.gui.api.model.LoadableModel;
import com.evolveum.midpoint.gui.api.prism.wrapper.PrismObjectWrapper;
import com.evolveum.midpoint.gui.api.util.WebComponentUtil;
import com.evolveum.midpoint.gui.api.util.WebModelServiceUtils;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.query.ObjectQuery;
import com.evolveum.midpoint.prism.query.QueryFactory;
import com.evolveum.midpoint.schema.result.OperationResult;
import com.evolveum.midpoint.web.component.data.column.ColumnMenuAction;
import com.evolveum.midpoint.web.component.form.MidpointForm;
import com.evolveum.midpoint.web.component.menu.cog.ButtonInlineMenuItem;
import com.evolveum.midpoint.web.component.menu.cog.InlineMenuItem;
import com.evolveum.midpoint.web.component.menu.cog.InlineMenuItemAction;
import com.evolveum.midpoint.web.component.util.SelectableBeanImpl;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusType;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectReferenceType;

/**
 * @author honchar
 */
public class FocusPersonasTabPanel<F extends FocusType> extends AbstractObjectTabPanel<F> {
    private static final long serialVersionUID = 1L;
    private static final String DOT_CLASS = FocusPersonasTabPanel.class.getName() + ".";
    private static final String OPERATION_SEARCH_PERSONAS_OBJECTS = DOT_CLASS + "searchPersonas";

    private static final String ID_PERSONAS_TABLE = "personasTable";

    public FocusPersonasTabPanel(String id, MidpointForm mainForm, LoadableModel<PrismObjectWrapper<F>> focusModel) {
        super(id, mainForm, focusModel);
    }

    @Override
    protected void onInitialize(){
        super.onInitialize();
        initLayout();
    }

    private void initLayout() {
        MainObjectListPanel<F> userListPanel =
                new MainObjectListPanel<F>(ID_PERSONAS_TABLE,
                (Class<F>) FocusType.class, null) {
            private static final long serialVersionUID = 1L;

            @Override
            protected IColumn<SelectableBean<F>, String> createCheckboxColumn(){
                return null;
            }

            @Override
            protected List<InlineMenuItem> createInlineMenu() {
                List<InlineMenuItem> menuItems = new ArrayList<>();
                menuItems.add(new ButtonInlineMenuItem(createStringResource("AssignmentPanel.viewTargetObject")) {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public CompositedIconBuilder getIconCompositedBuilder() {
                        return getDefaultCompositedIconBuilder(GuiStyleConstants.CLASS_NAVIGATE_ARROW);
                    }

                    @Override
                    public InlineMenuItemAction initAction() {
                        return new ColumnMenuAction<SelectableBeanImpl<F>>() {
                            private static final long serialVersionUID = 1L;

                            @Override
                            public void onClick(AjaxRequestTarget target) {
                                SelectableBean<F> personaRefSelectableBean = getRowModel().getObject();
                                F personaRefObj = personaRefSelectableBean.getValue();
                                ObjectReferenceType ort = new ObjectReferenceType();
                                ort.setOid(personaRefObj.getOid());
                                ort.setType(WebComponentUtil.classToQName(FocusPersonasTabPanel.this.getPrismContext(), personaRefObj.getClass()));
                                WebComponentUtil.dispatchToObjectDetailsPage(ort, FocusPersonasTabPanel.this, false);
                            }
                        };
                    }

                    @Override
                    public boolean isHeaderMenuItem(){
                        return false;
                    }
                });
                return menuItems;          }

            @Override
            protected void objectDetailsPerformed(AjaxRequestTarget target, F object) {
            }

            @Override
            protected boolean isCreateNewObjectEnabled() {
                        return false;
                    }

                    @Override
                    protected ISelectableDataProvider<F, SelectableBean<F>> createProvider() {
                        return createSelectableBeanObjectDataProvider(() -> getFocusPersonasQuery(), null);
                    }

//                    @Override
//            protected ObjectQuery getCustomizeContentQuery() {
//
//            }

            @Override
            protected boolean isObjectDetailsEnabled(IModel<SelectableBean<F>> rowModel) {
                return false;
            }

            @Override
            protected UserProfileStorage.TableId getTableId() {
                return null;
            }

            @Override
            protected boolean enableSavePageSize() {
                        return false;
                    }
        };
        userListPanel.setOutputMarkupId(true);
        add(userListPanel);
    }

    private ObjectQuery getFocusPersonasQuery() {
        List<String> personaOidsList = getPersonasOidsList();
        ObjectQuery query = getPageBase().getPrismContext().queryFor(FocusType.class)
                .id(personaOidsList.toArray(new String[0]))
                .build();
        return query;
    }

    private LoadableModel<List<PrismObject<FocusType>>> loadModel(){
        return new LoadableModel<List<PrismObject<FocusType>>>(false) {
            @Override
            protected List<PrismObject<FocusType>> load() {
                List<String> personaOidsList = getPersonasOidsList();
                List<PrismObject<FocusType>> personasList = new ArrayList<>();
                if (personaOidsList.size() > 0){
                    QueryFactory factory = getPrismContext().queryFactory();
                    ObjectQuery query = factory.createQuery(factory.createInOid(personaOidsList));
                    OperationResult result = new OperationResult(OPERATION_SEARCH_PERSONAS_OBJECTS);
                    personasList = WebModelServiceUtils.searchObjects(FocusType.class, query, result, getPageBase());
                }
                return personasList;
            }
        };
    }

    private List<String> getPersonasOidsList(){
        List<ObjectReferenceType> personasRefList = getObjectWrapper().getObject().asObjectable().getPersonaRef();
        List<String> oidsList = new ArrayList<>();
        if (personasRefList != null){
            for (ObjectReferenceType ref : personasRefList){
                oidsList.add(ref.getOid());
            }
        }
        return oidsList;
    }
}
