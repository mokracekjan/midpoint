/*
 * Copyright (C) 2010-2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.repo.common.activity.handlers;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import com.evolveum.midpoint.repo.common.activity.Activity;
import com.evolveum.midpoint.repo.common.activity.ActivityStateDefinition;
import com.evolveum.midpoint.repo.common.activity.StandaloneActivity;
import com.evolveum.midpoint.repo.common.activity.definition.ActivityDefinition;
import com.evolveum.midpoint.repo.common.activity.definition.WorkDefinition;
import com.evolveum.midpoint.repo.common.activity.definition.WorkDefinitionFactory;
import com.evolveum.midpoint.repo.common.activity.execution.AbstractActivityExecution;
import com.evolveum.midpoint.repo.common.activity.execution.ExecutionInstantiationContext;
import com.evolveum.midpoint.repo.common.activity.definition.CompositeWorkDefinition;

import com.evolveum.midpoint.repo.common.activity.execution.PureCompositeActivityExecution;

import com.evolveum.midpoint.util.exception.SchemaException;

import com.evolveum.midpoint.xml.ns._public.common.common_3.PureCompositeWorkStateType;

import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.evolveum.midpoint.schema.result.OperationResult;

import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * TODO
 */
@Component
public class PureCompositeActivityHandler implements ActivityHandler<CompositeWorkDefinition, PureCompositeActivityHandler> {

    @Autowired ActivityHandlerRegistry handlerRegistry;
    @Autowired WorkDefinitionFactory workDefinitionFactory;

    @PostConstruct
    public void register() {
        handlerRegistry.registerHandler(CompositeWorkDefinition.class, this);
    }

    @PreDestroy
    public void unregister() {
        handlerRegistry.unregisterHandler(CompositeWorkDefinition.class);
    }

    @Override
    public @NotNull AbstractActivityExecution<CompositeWorkDefinition, PureCompositeActivityHandler, ?> createExecution(
            @NotNull ExecutionInstantiationContext<CompositeWorkDefinition, PureCompositeActivityHandler> context,
            @NotNull OperationResult result) {
        return new PureCompositeActivityExecution(context);
    }

    @Override
    public ArrayList<Activity<?, ?>> createChildActivities(Activity<CompositeWorkDefinition, PureCompositeActivityHandler> parent) throws SchemaException {
        CompositeWorkDefinition workDefinition = parent.getWorkDefinition();
        return workDefinition.createChildDefinitions(workDefinitionFactory).stream()
                .map(definition -> createChildActivity(definition, parent))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private <WD extends WorkDefinition, AH extends ActivityHandler<WD, AH>> Activity<?, ?> createChildActivity(
            ActivityDefinition<WD> definition, Activity<CompositeWorkDefinition, ?> parent) {
        AH handler = handlerRegistry.getHandler(definition);
        return StandaloneActivity.createNonRoot(definition, handler, parent);
    }

    @Override
    public String getIdentifierPrefix() {
        return "composition";
    }

    @Override
    public @NotNull ActivityStateDefinition<?> getRootActivityStateDefinition() {
        return ActivityStateDefinition.normal(PureCompositeWorkStateType.COMPLEX_TYPE);
    }
}
