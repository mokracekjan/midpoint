/*
 * Copyright (c) 2010-2019 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.web.component.dialog;

import java.util.*;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.gui.api.component.result.MessagePanel;
import com.evolveum.midpoint.web.component.message.FeedbackAlerts;
import com.evolveum.midpoint.web.component.util.VisibleBehaviour;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.feedback.FeedbackMessages;
import org.apache.wicket.markup.html.WebMarkupContainer;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.model.StringResourceModel;
import org.apache.wicket.model.util.ListModel;

import com.evolveum.midpoint.gui.api.component.BasePanel;
import com.evolveum.midpoint.gui.api.util.WebComponentUtil;
import com.evolveum.midpoint.web.component.AjaxButton;
import com.evolveum.midpoint.web.component.form.DropDownFormGroup;
import com.evolveum.midpoint.web.component.input.ListMultipleChoicePanel;
import com.evolveum.midpoint.web.component.input.QNameObjectTypeChoiceRenderer;
import com.evolveum.midpoint.web.page.admin.configuration.component.EmptyOnChangeAjaxFormUpdatingBehavior;
import com.evolveum.midpoint.xml.ns._public.common.common_3.FocusType;

public class ChooseFocusTypeAndRelationDialogPanel extends BasePanel implements Popupable{

    private static final String ID_OBJECT_TYPE = "type";
    private static final String ID_RELATION = "relation";
    private static final String ID_BUTTON_OK = "ok";
    private static final String ID_CANCEL_OK = "cancel";
    private static final String ID_INFO_MESSAGE = "infoMessage";
    private static final String ID_WARNING_FEEDBACK = "warningFeedback";
    private static final String ID_RELATION_REQUIRED = "relationRequired";

    private IModel<String> messageModel = null;

    public ChooseFocusTypeAndRelationDialogPanel(String id) {
        this(id, null);
    }

    public ChooseFocusTypeAndRelationDialogPanel(String id, IModel<String> messageModel) {
        super(id);
        this.messageModel = messageModel;
    }

    @Override
    protected void onInitialize() {
        super.onInitialize();
        initLayout();
    }

    private void initLayout(){
        MessagePanel warningMessage = new MessagePanel(ID_WARNING_FEEDBACK, MessagePanel.MessagePanelType.WARN, getWarningMessageModel());
        warningMessage.setOutputMarkupId(true);
        warningMessage.add(new VisibleBehaviour(() -> getWarningMessageModel() != null));
        add(warningMessage);

        DropDownFormGroup<QName> type = new DropDownFormGroup<QName>(ID_OBJECT_TYPE, Model.of(getDefaultObjectType()), Model.ofList(getSupportedObjectTypes()),
                new QNameObjectTypeChoiceRenderer(), createStringResource("chooseFocusTypeAndRelationDialogPanel.type"),
                "chooseFocusTypeAndRelationDialogPanel.tooltip.type", "col-md-4", "col-md-8", true);
        type.getInput().add(new EmptyOnChangeAjaxFormUpdatingBehavior());
        type.setOutputMarkupId(true);
        type.add(new VisibleBehaviour(() -> isFocusTypeSelectorVisible()));
        add(type);

            IModel<Map<String, String>> options = new Model(null);
            Map<String, String> optionsMap = new HashMap<>();
//            optionsMap.put("nonSelectedText", createStringResource("LoggingConfigPanel.appenders.Inherit").getString());
            options.setObject(optionsMap);
        ListMultipleChoicePanel<QName> relation = new ListMultipleChoicePanel<QName>(ID_RELATION, Model.ofList(getDefaultRelations()),
                new ListModel<QName>(getSupportedRelations()), WebComponentUtil.getRelationChoicesRenderer(getPageBase()), options);
        relation.getBaseFormComponent().add(new EmptyOnChangeAjaxFormUpdatingBehavior());
        relation.setOutputMarkupId(true);
        add(relation);

        WebMarkupContainer relationRequired = new WebMarkupContainer(ID_RELATION_REQUIRED);
        relationRequired.add(new VisibleBehaviour((() -> isRelationRequired())));
        add(relationRequired);

        Label label = new Label(ID_INFO_MESSAGE, messageModel);
        label.add(new VisibleBehaviour(() -> messageModel != null && messageModel.getObject() != null));
        add(label);

        AjaxButton confirmButton = new AjaxButton(ID_BUTTON_OK, createStringResource("Button.ok")) {

            private static final long serialVersionUID = 1L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                DropDownFormGroup<QName> type = (DropDownFormGroup<QName>) getParent().get(ID_OBJECT_TYPE);
                QName typeChosen = type.getModelObject();

                ListMultipleChoicePanel<QName> relation = (ListMultipleChoicePanel<QName>) getParent().get(ID_RELATION);
                Collection<QName> relationChosen = relation.getModelObject();
                ChooseFocusTypeAndRelationDialogPanel.this.okPerformed(typeChosen, relationChosen, target);
                getPageBase().hideMainPopup(target);

            }
        };
        add(confirmButton);

        AjaxButton cancelButton = new AjaxButton(ID_CANCEL_OK,
                createStringResource("Button.cancel")) {
            private static final long serialVersionUID = 1L;

            @Override
            public void onClick(AjaxRequestTarget target) {
                getPageBase().hideMainPopup(target);
            }
        };
        add(cancelButton);

    }

    protected IModel<String> getWarningMessageModel() {
        return null;
    }

    private boolean isRelationRequired() {
        return true;
    }

    protected void okPerformed(QName type, Collection<QName> relation, AjaxRequestTarget target) {
        // TODO Auto-generated method stub

    }

    protected List<QName> getSupportedObjectTypes() {
        return WebComponentUtil.createFocusTypeList(true);
    }

    protected QName getDefaultObjectType() {
        List<QName> supportedObjectTypes = getSupportedObjectTypes();
        if (CollectionUtils.isEmpty(supportedObjectTypes)) {
            return FocusType.COMPLEX_TYPE;
        }
        return supportedObjectTypes.iterator().next();
    }

    protected List<QName> getSupportedRelations() {
        return WebComponentUtil.getAllRelations(getPageBase());
    }

    protected List<QName> getDefaultRelations() {
        return new ArrayList<>();
    }

    protected boolean isFocusTypeSelectorVisible() {
        return true;
    }

    @Override
    public int getWidth() {
        return 400;
    }

    @Override
    public int getHeight() {
        return 300;
    }

    @Override
    public String getWidthUnit(){
        return "px";
    }

    @Override
    public String getHeightUnit(){
        return "px";
    }

    @Override
    public StringResourceModel getTitle() {
        return new StringResourceModel("ChooseFocusTypeDialogPanel.chooseType");
    }

    @Override
    public Component getComponent() {
        return this;
    }


}
