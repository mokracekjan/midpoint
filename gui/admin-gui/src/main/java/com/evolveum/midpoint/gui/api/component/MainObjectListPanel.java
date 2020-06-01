/*
 * Copyright (c) 2016 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.gui.api.component;

import com.evolveum.midpoint.gui.api.GuiStyleConstants;
import com.evolveum.midpoint.gui.api.component.button.CsvDownloadButtonPanel;
import com.evolveum.midpoint.gui.api.page.PageBase;
import com.evolveum.midpoint.gui.api.util.WebComponentUtil;
import com.evolveum.midpoint.gui.impl.component.icon.CompositedIcon;
import com.evolveum.midpoint.gui.impl.component.icon.CompositedIconBuilder;
import com.evolveum.midpoint.gui.impl.component.icon.IconCssStyle;
import com.evolveum.midpoint.model.api.AssignmentObjectRelation;
import com.evolveum.midpoint.model.api.ModelAuthorizationAction;
import com.evolveum.midpoint.model.api.authentication.CompiledObjectCollectionView;
import com.evolveum.midpoint.model.common.util.DefaultColumnUtils;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismObject;
import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.delta.DeltaFactory;
import com.evolveum.midpoint.prism.delta.ObjectDelta;
import com.evolveum.midpoint.schema.GetOperationOptions;
import com.evolveum.midpoint.schema.ObjectDeltaOperation;
import com.evolveum.midpoint.schema.SelectorOptions;
import com.evolveum.midpoint.security.api.AuthorizationConstants;
import com.evolveum.midpoint.task.api.Task;
import com.evolveum.midpoint.util.QNameUtil;
import com.evolveum.midpoint.util.exception.*;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.web.component.AjaxIconButton;
import com.evolveum.midpoint.web.component.MultiFunctinalButtonDto;
import com.evolveum.midpoint.web.component.MultifunctionalButton;
import com.evolveum.midpoint.web.component.data.column.CheckBoxHeaderColumn;
import com.evolveum.midpoint.web.component.data.column.ObjectNameColumn;
import com.evolveum.midpoint.web.component.util.SelectableBean;
import com.evolveum.midpoint.web.component.util.VisibleBehaviour;
import com.evolveum.midpoint.web.component.util.VisibleEnableBehaviour;
import com.evolveum.midpoint.web.page.admin.configuration.PageImportObject;
import com.evolveum.midpoint.web.session.UserProfileStorage.TableId;
import com.evolveum.midpoint.xml.ns._public.common.common_3.*;
import com.evolveum.prism.xml.ns._public.query_3.SearchFilterType;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.behavior.AttributeAppender;
import org.apache.wicket.extensions.markup.html.repeater.data.table.DataTable;
import org.apache.wicket.extensions.markup.html.repeater.data.table.IColumn;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.list.ListItem;
import org.apache.wicket.markup.html.list.ListView;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;

import java.io.Serializable;
import java.util.*;

/**
 * @author katkav
 */
public abstract class MainObjectListPanel<O extends ObjectType> extends ObjectListPanel<O> {
    private static final long serialVersionUID = 1L;

    private static final String ID_BUTTON_BAR = "buttonBar";
    private static final String ID_BUTTON_REPEATER = "buttonsRepeater";
    private static final String ID_BUTTON = "button";
    private static final Trace LOGGER = TraceManager.getTrace(MainObjectListPanel.class);

//    private Boolean manualRefreshEnabled;

    public MainObjectListPanel(String id, Class<O> type, TableId tableId, Collection<SelectorOptions<GetOperationOptions>> options) {
        super(id, type, tableId, options);


    }

    @Override
    protected IColumn<SelectableBean<O>, String> createCheckboxColumn() {
        return new CheckBoxHeaderColumn<>();
    }

    @Override
    protected IColumn<SelectableBean<O>, String> createNameColumn(IModel<String> columnNameModel, String itemPath, ExpressionType expression) {
        if (StringUtils.isEmpty(itemPath) && expression == null) {
            return new ObjectNameColumn<O>(columnNameModel == null ? createStringResource("ObjectType.name") : columnNameModel) {
                private static final long serialVersionUID = 1L;

                @Override
                public void onClick(AjaxRequestTarget target, IModel<SelectableBean<O>> rowModel) {
                    O object = rowModel.getObject().getValue();
                    MainObjectListPanel.this.objectDetailsPerformed(target, object);
                }

                @Override
                public boolean isClickable(IModel<SelectableBean<O>> rowModel) {
                    return MainObjectListPanel.this.isObjectDetailsEnabled(rowModel);
                }
            };
        } else {
            return new ObjectNameColumn<O>(columnNameModel == null ? createStringResource("ObjectType.name") : columnNameModel,
                    itemPath, expression, getPageBase()) {
                private static final long serialVersionUID = 1L;

                @Override
                public void onClick(AjaxRequestTarget target, IModel<SelectableBean<O>> rowModel) {
                    O object = rowModel.getObject().getValue();
                    MainObjectListPanel.this.objectDetailsPerformed(target, object);
                }

                @Override
                public boolean isClickable(IModel<SelectableBean<O>> rowModel) {
                    return MainObjectListPanel.this.isObjectDetailsEnabled(rowModel);
                }
            };
        }
    }

    protected boolean isObjectDetailsEnabled(IModel<SelectableBean<O>> rowModel) {
        return true;
    }

    protected abstract void objectDetailsPerformed(AjaxRequestTarget target, O object);

    protected void newObjectPerformed(AjaxRequestTarget target, AssignmentObjectRelation relation, CompiledObjectCollectionView collectionView){
        if (collectionView == null){
            collectionView = getObjectCollectionView();
        }

        List<ObjectReferenceType> archetypeRef = getReferencesList(collectionView);
        try {
            WebComponentUtil.initNewObjectWithReference(getPageBase(),
                    WebComponentUtil.classToQName(getPrismContext(), getType()),
                    archetypeRef);
        } catch (SchemaException ex){
            getPageBase().getFeedbackMessages().error(MainObjectListPanel.this, ex.getUserFriendlyMessage());
            target.add(getPageBase().getFeedbackPanel());
        }
    }

    protected List<ObjectReferenceType> getReferencesList(CompiledObjectCollectionView collectionView) {
        if (!isArchetypedCollectionView(collectionView)) {
            return null;
        }

        ObjectReferenceType ref =  collectionView.getCollection().getCollectionRef();
        return Arrays.asList(ref);
    }

    @Override
    protected WebMarkupContainer createTableButtonToolbar(String id) {
        return new ButtonBar(id, ID_BUTTON_BAR, this, createToolbarButtonsList(ID_BUTTON));
    }

    protected List<MultiFunctinalButtonDto> loadButtonDescriptions() {
        List<MultiFunctinalButtonDto> multiFunctinalButtonDtos = new ArrayList<>();

        Collection<CompiledObjectCollectionView> compiledObjectCollectionViews = getNewObjectInfluencesList();

        if (CollectionUtils.isNotEmpty(compiledObjectCollectionViews)) {
            compiledObjectCollectionViews.forEach(collection -> {
                MultiFunctinalButtonDto buttonDesc = new MultiFunctinalButtonDto();
                buttonDesc.setCompositedIcon(createCompositedIcon(collection));
                buttonDesc.setAdditionalButtonDisplayType(collection.getDisplay());
                buttonDesc.setCollectionView(collection);
                multiFunctinalButtonDtos.add(buttonDesc);
            });
        }

        return multiFunctinalButtonDtos;
    }

    private CompositedIcon createCompositedIcon(CompiledObjectCollectionView collectionView) {
        DisplayType additionalButtonDisplayType = WebComponentUtil.getNewObjectDisplayTypeFromCollectionView(collectionView, getPageBase());
        CompositedIconBuilder builder = getNewObjectButtonAdditionalIconBuilder(collectionView, additionalButtonDisplayType);
        if (builder == null){
            return null;
        }
        return builder.build();
    }

    protected List<Component> createToolbarButtonsList(String buttonId){
        List<Component> buttonsList = new ArrayList<>();
        MultifunctionalButton createNewObjectButton = new MultifunctionalButton(buttonId, loadButtonDescriptions()){
            private static final long serialVersionUID = 1L;

            @Override
            protected void buttonClickPerformed(AjaxRequestTarget target, AssignmentObjectRelation relationSpec, CompiledObjectCollectionView collectionView){
                newObjectPerformed(target, relationSpec, collectionView);
            }

            @Override
            protected DisplayType getMainButtonDisplayType(){
                return getNewObjectButtonStandardDisplayType();
            }



            @Override
            protected Map<IconCssStyle, IconType> getMainButtonLayerIcons(){
                if (!isCollectionViewPanel()){
                    return null;
                }
                Map<IconCssStyle, IconType> layerIconMap = new HashMap<>();
                layerIconMap.put(IconCssStyle.BOTTOM_RIGHT_STYLE, WebComponentUtil.createIconType(GuiStyleConstants.CLASS_PLUS_CIRCLE, "green"));
                return layerIconMap;
            }


            @Override
            protected DisplayType getDefaultObjectButtonDisplayType(){
                return getNewObjectButtonSpecialDisplayType();
            }

        };
        createNewObjectButton.add(new VisibleBehaviour(() -> isCreateNewObjectEnabled()));
        createNewObjectButton.add(AttributeAppender.append("class", "btn-margin-right"));
        buttonsList.add(createNewObjectButton);

        AjaxIconButton importObject = new AjaxIconButton(buttonId, new Model<>(GuiStyleConstants.CLASS_UPLOAD),
                createStringResource("MainObjectListPanel.import")) {

            private static final long serialVersionUID = 1L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                ((PageBase) getPage()).navigateToNext(PageImportObject.class);
            }
        };
        importObject.add(AttributeAppender.append("class", "btn btn-default btn-sm"));
        importObject.add(new VisibleEnableBehaviour(){
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isVisible(){

                boolean isVisible = false;
                try {
                    isVisible = ((PageBase) getPage()).isAuthorized(ModelAuthorizationAction.IMPORT_OBJECTS.getUrl())
                            && WebComponentUtil.isAuthorized(AuthorizationConstants.AUTZ_UI_CONFIGURATION_ALL_URL,
                            AuthorizationConstants.AUTZ_UI_CONFIGURATION_IMPORT_URL);
                } catch (Exception ex){
                    LOGGER.error("Failed to check authorization for IMPORT action for " + getType().getSimpleName()
                            + " object, ", ex);
                }
                return isVisible;
            }
        });
        buttonsList.add(importObject);

        boolean canCountBeforeExporting = getType() == null || !ShadowType.class.isAssignableFrom(getType()) ||
                isRawOrNoFetchOption(getOptions());
        CsvDownloadButtonPanel exportDataLink = new CsvDownloadButtonPanel(buttonId, canCountBeforeExporting, getSearchModel()) {

            private static final long serialVersionUID = 1L;

            @Override
            protected DataTable<?, ?> getDataTable() {
                return getTable().getDataTable();
            }

            @Override
            protected String getFilename() {
                return getType().getSimpleName() +
                        "_" + createStringResource("MainObjectListPanel.exportFileName").getString();
            }

            @Override
            protected void createReportPerformed(String name, SearchFilterType filter, List<Integer> indexOfColumns, AjaxRequestTarget target) {
                PrismContext prismContext = getPageBase().getPrismContext();
                PrismObjectDefinition<ReportType> def = prismContext.getSchemaRegistry().findObjectDefinitionByType(ReportType.COMPLEX_TYPE);
                PrismObject<ReportType> obj = null;
                try {
                    obj = def.instantiate();
                } catch (SchemaException e) {
                    LOGGER.error("Couldn't instantiate new report", e);
                    getPageBase().error(getString("MainObjectListPanel.message.error.instantiateNewReport"));
                    target.add(getPageBase().getFeedbackPanel());
                    return;
                }
                ReportType report = obj.asObjectable();
                String oid = UUID.randomUUID().toString();
                report.setOid(oid);
                if (StringUtils.isEmpty(name)) {
                    name = getPageBase().createStringResource("ObjectTypeGuiDescriptor.report").getString() + "-" + oid;
                }
                report.setName(WebComponentUtil.createPolyFromOrigString(name));
                report.setReportEngine(ReportEngineSelectionType.COLLECTION);
                ObjectCollectionReportEngineConfigurationType objectCollection = new ObjectCollectionReportEngineConfigurationType();
                objectCollection.setUseOnlyReportView(true);
                CompiledObjectCollectionView view = getObjectCollectionView();
                CollectionRefSpecificationType collection = new CollectionRefSpecificationType();
                if (view == null) {
                    objectCollection.setView(resolveSelectedColumn(indexOfColumns, getDefaultView()));
                } else {
                    objectCollection.setView(resolveSelectedColumn(indexOfColumns, view.toGuiObjectListViewType()));
                    if (view.getCollection() != null && view.getCollection().getCollectionRef() != null) {
                        if (!QNameUtil.match(view.getCollection().getCollectionRef().getType(), ArchetypeType.COMPLEX_TYPE)) {
                            collection.setBaseCollectionRef(view.getCollection());
                        } else {
                            CollectionRefSpecificationType baseCollection = new CollectionRefSpecificationType();
                            try {
                                baseCollection.setFilter(getPageBase().getQueryConverter().createSearchFilterType(view.getFilter()));
                                collection.setBaseCollectionRef(baseCollection);
                            } catch (SchemaException e) {
                                LOGGER.error("Couldn't create filter for archetype");
                                getPageBase().error(getString("MainObjectListPanel.message.error.createArchetypeFilter"));
                                target.add(getPageBase().getFeedbackPanel());
                            }
                        }
                    }
                }
                if (filter != null) {
                    collection.setFilter(filter);
                } else if (view == null || view.getCollection() == null) {
                    try {
                        SearchFilterType allFilter = prismContext.getQueryConverter().createSearchFilterType(prismContext.queryFactory().createAll());
                        collection.setFilter(allFilter);
                    } catch (SchemaException e) {
                        LOGGER.error("Couldn't create all filter", e);
                        getPageBase().error(getString("MainObjectListPanel.message.error.createAllFilter"));
                        target.add(getPageBase().getFeedbackPanel());
                        return;
                    }
                }
                objectCollection.setCollection(collection);
                report.setObjectCollection(objectCollection);
                Collection<ObjectDelta<? extends ObjectType>> deltas = new ArrayList<>();
                ObjectDelta<ReportType> delta = DeltaFactory.Object.createAddDelta(report.asPrismObject());
                deltas.add(delta);
                Collection<ObjectDeltaOperation<? extends ObjectType>> ret = null;
                try {
                    Task task = getPageBase().createSimpleTask("Create report");
                    ret = getPageBase().getModelService().executeChanges(deltas, null, task, task.getResult());
                } catch (Exception e) {
                    LOGGER.error("Couldn't create report", e);
                    getPageBase().error(getString("MainObjectListPanel.message.error.createReport"));
                    target.add(getPageBase().getFeedbackPanel());
                    return;
                }
                if (ret != null) {
                    Task task = getPageBase().createSimpleTask("Run report");
                    PrismObject<ReportType> prismReport = (PrismObject<ReportType>) ret.iterator().next().getObjectDelta().getObjectToAdd();
                    try {
                        getPageBase().getReportManager().runReport(prismReport, null, task, task.getResult());
                    } catch (Exception e) {
                        LOGGER.error("Couldn't run report", e);
                        getPageBase().error(getString("MainObjectListPanel.message.error.runReport"));
                        task.getResult().recordFatalError(e);
                    } finally {
                        task.getResult().computeStatusIfUnknown();
                    }
                    getPageBase().showResult(task.getResult());
                    target.add(getPageBase().getFeedbackPanel());
                }
            }

        };
        exportDataLink.add(new VisibleEnableBehaviour(){
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isVisible(){
                return WebComponentUtil.isAuthorized(AuthorizationConstants.AUTZ_UI_ADMIN_CSV_EXPORT_ACTION_URI);
            }
        });
        exportDataLink.add(AttributeAppender.append("class", "btn-margin-right"));
        buttonsList.add(exportDataLink);

        AjaxIconButton refreshIcon = new AjaxIconButton(buttonId, new Model<>(GuiStyleConstants.CLASS_RECONCILE),
                createStringResource("MainObjectListPanel.refresh")) {

            private static final long serialVersionUID = 1L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                clearCache();
                refreshTable((Class<O>) getType(), target);

                target.add((Component) getTable());
            }
        };
        refreshIcon.add(AttributeAppender.append("class", "btn btn-default btn-margin-left btn-sm"));
//        refreshIcon.add(AttributeAppender.append("class", "btn-margin-right"));
        buttonsList.add(refreshIcon);

        AjaxIconButton playPauseIcon = new AjaxIconButton(buttonId, getRefreshPausePlayButtonModel(),
                getRefreshPausePlayButtonTitleModel()) {

            private static final long serialVersionUID = 1L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                clearCache();
                setManualRefreshEnabled(!isRefreshEnabled());
                target.add(getTable());
            }
        };
        playPauseIcon.add(AttributeAppender.append("class", "btn btn-default btn-sm"));
        buttonsList.add(playPauseIcon);

        return buttonsList;
    }

    private GuiObjectListViewType resolveSelectedColumn(List<Integer> indexOfColumns, GuiObjectListViewType view){
        List<GuiObjectColumnType> newColumns = new ArrayList<>();
        List<GuiObjectColumnType> oldColumns;
        if (view.getColumn().isEmpty()) {
            oldColumns = getDefaultView().getColumn();
        } else {
            oldColumns = view.getColumn();
        }
        for (Integer index : indexOfColumns) {
            newColumns.add(oldColumns.get(index-2).clone());
        }
        view.getColumn().clear();
        view.getColumn().addAll(newColumns);
        return view;
    }

    protected GuiObjectListViewType getDefaultView(){
        return DefaultColumnUtils.getDefaultView(getType());
    }

    private IModel<String> getRefreshPausePlayButtonModel() {
        return () -> {
            if (isRefreshEnabled()) {
                return GuiStyleConstants.CLASS_PAUSE;
            }

            return GuiStyleConstants.CLASS_PLAY;
        };
    }

    private IModel<String> getRefreshPausePlayButtonTitleModel() {
        return () -> {
            if (isRefreshEnabled()) {
                return createStringResource("MainObjectListPanel.refresh.pause").getString();
            }
            return createStringResource("MainObjectListPanel.refresh.start").getString();
        };
    }

    private boolean isRawOrNoFetchOption(Collection<SelectorOptions<GetOperationOptions>> options){
        if (options == null){
            return false;
        }
        for (SelectorOptions<GetOperationOptions> option : options){
            if (Boolean.TRUE.equals(option.getOptions().getRaw()) ||
                    Boolean.TRUE.equals(option.getOptions().getNoFetch())){
                return true;
            }
        }
        return false;
    }

    protected boolean isCreateNewObjectEnabled(){
        return true;
    }

    protected List<CompiledObjectCollectionView> getNewObjectInfluencesList(){
        if (isCollectionViewPanel()){
            return new ArrayList<>();
        }
        return getAllApplicableArchetypeViews();
    }

    protected DisplayType getNewObjectButtonStandardDisplayType(){
        if (isCollectionViewPanel()) {

            CompiledObjectCollectionView view = getObjectCollectionView();
            if (isArchetypedCollectionView(view)) {
                return WebComponentUtil.getNewObjectDisplayTypeFromCollectionView(view, getPageBase());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(createStringResource("MainObjectListPanel.newObject").getString());
        sb.append(" ");
        sb.append(createStringResource("ObjectTypeLowercase." + getType().getSimpleName()).getString());
        return WebComponentUtil.createDisplayType(GuiStyleConstants.CLASS_ADD_NEW_OBJECT, "green",
                sb.toString());
    }

    private boolean isArchetypedCollectionView(CompiledObjectCollectionView view) {
        if (view == null) {
            return false;
        }

        CollectionRefSpecificationType collectionRefSpecificationType = view.getCollection();
        if (collectionRefSpecificationType == null) {
            return false;
        }

        ObjectReferenceType collectionRef = collectionRefSpecificationType.getCollectionRef();
        if (collectionRef == null) {
            return false;
        }

        if (!QNameUtil.match(ArchetypeType.COMPLEX_TYPE, collectionRef.getType())) {
            return false;
        }

        return true;
    }

    protected DisplayType getNewObjectButtonSpecialDisplayType(){
        String iconCssStyle = WebComponentUtil.createDefaultBlackIcon(WebComponentUtil.classToQName(getPageBase().getPrismContext(), getType()));

        StringBuilder sb = new StringBuilder();
        sb.append(createStringResource("MainObjectListPanel.newObject").getString());
        sb.append(" ");
        sb.append(createStringResource("ObjectTypeLowercase." + getType().getSimpleName()).getString());

        return WebComponentUtil.createDisplayType(iconCssStyle, "", sb.toString());
    }


    protected CompositedIconBuilder getNewObjectButtonAdditionalIconBuilder(CompiledObjectCollectionView influencingObject, DisplayType additionalButtonDisplayType){
        return null;
    }

    private static class ButtonBar extends Fragment {

        private static final long serialVersionUID = 1L;

        public <O extends ObjectType, S extends Serializable> ButtonBar(String id, String markupId, MainObjectListPanel<O> markupProvider, List<Component> buttonsList) {
            super(id, markupId, markupProvider);

            initLayout(buttonsList);
        }

        private <O extends ObjectType> void initLayout(final List<Component> buttonsList) {
            ListView<Component> buttonsView = new ListView<Component>(ID_BUTTON_REPEATER, Model.ofList(buttonsList)) {
                @Override
                protected void populateItem(ListItem<Component> listItem) {
                    listItem.add(listItem.getModelObject());
                }
            };
            add(buttonsView);
        }
    }


}
