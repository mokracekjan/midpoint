/*
 * Copyright (c) 2010-2018 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.gui.impl.factory.wrapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.annotation.PostConstruct;

import com.evolveum.midpoint.gui.impl.prism.panel.MetadataContainerPanel;

import org.springframework.stereotype.Component;

import com.evolveum.midpoint.gui.api.factory.wrapper.ItemWrapperFactory;
import com.evolveum.midpoint.gui.api.factory.wrapper.PrismContainerWrapperFactory;
import com.evolveum.midpoint.gui.api.factory.wrapper.WrapperContext;
import com.evolveum.midpoint.gui.api.prism.ItemStatus;
import com.evolveum.midpoint.gui.api.prism.wrapper.ItemWrapper;
import com.evolveum.midpoint.gui.api.prism.wrapper.PrismContainerValueWrapper;
import com.evolveum.midpoint.gui.api.prism.wrapper.PrismContainerWrapper;
import com.evolveum.midpoint.gui.impl.prism.panel.PrismContainerPanel;
import com.evolveum.midpoint.gui.impl.prism.wrapper.PrismContainerValueWrapperImpl;
import com.evolveum.midpoint.gui.impl.prism.wrapper.PrismContainerWrapperImpl;
import com.evolveum.midpoint.prism.*;
import com.evolveum.midpoint.util.exception.SchemaException;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.web.component.prism.ValueStatus;

/**
 * @author katka
 *
 */
@Component
public class PrismContainerWrapperFactoryImpl<C extends Containerable> extends ItemWrapperFactoryImpl<PrismContainerWrapper<C>, PrismContainerValue<C>, PrismContainer<C>, PrismContainerValueWrapper<C>> implements PrismContainerWrapperFactory<C> {

    private static final Trace LOGGER = TraceManager.getTrace(PrismContainerWrapperFactoryImpl.class);

    @Override
    public boolean match(ItemDefinition<?> def) {
        return  def instanceof PrismContainerDefinition;
    }

    @PostConstruct
    @Override
    public void register() {
        getRegistry().addToRegistry(this);
    }

    @Override
    public int getOrder() {
        return Integer.MAX_VALUE;
    }


    @Override
    public PrismContainerValueWrapper<C> createValueWrapper(PrismContainerWrapper<C> parent, PrismContainerValue<C> value, ValueStatus status, WrapperContext context)
            throws SchemaException {
        PrismContainerValueWrapper<C> containerValueWrapper = createContainerValueWrapper(parent, value, status, context);
        containerValueWrapper.setExpanded(shouldBeExpanded(parent, value, context));
        containerValueWrapper.setShowEmpty(context.isShowEmpty());

        List<ItemWrapper<?, ?>> children = createChildren(parent, value, containerValueWrapper, context);

        containerValueWrapper.getItems().addAll((Collection) children);
        containerValueWrapper.setVirtualContainerItems(context.getVirtualItemSpecification());
        if (parent != null) {
            parent.setVirtual(context.getVirtualItemSpecification() != null);
        }
        return containerValueWrapper;
    }

    protected List<ItemWrapper<?, ?>> createChildren(PrismContainerWrapper<C> parent, PrismContainerValue<C> value, PrismContainerValueWrapper<C> containerValueWrapper, WrapperContext context) throws SchemaException {
        List<ItemWrapper<?,?>> wrappers = new ArrayList<>();
        for (ItemDefinition<?> def : getItemDefinitions(parent, value)) {
            addItemWrapper(def, containerValueWrapper, context, wrappers);
        }
        return wrappers;
    }

    protected List<? extends ItemDefinition> getItemDefinitions(PrismContainerWrapper<C> parent, PrismContainerValue<C> value) {
        if (parent == null){
            return new ArrayList<>();
        }
        return parent.getDefinitions();
    }

    protected void addItemWrapper(ItemDefinition<?> def, PrismContainerValueWrapper<?> containerValueWrapper,
            WrapperContext context, List<ItemWrapper<?,?>> wrappers) throws SchemaException {

        ItemWrapper<?,?> wrapper = createChildWrapper(def, containerValueWrapper, context);

        if (wrapper != null) {
            wrappers.add(wrapper);
        }
    }

    protected ItemWrapper<?, ?> createChildWrapper(ItemDefinition<?> def, PrismContainerValueWrapper<?> containerValueWrapper, WrapperContext context) throws SchemaException {
        ItemWrapperFactory<?, ?, ?> factory = getChildWrapperFactory(def, containerValueWrapper.getNewValue());
        ItemWrapper<?, ?> child = factory.createWrapper(containerValueWrapper, def, context);
        if (context.isMetadata() && ItemStatus.ADDED == child.getStatus()) {
            return null;
        }
        return child;
    }

    private ItemWrapperFactory<?,?,?> getChildWrapperFactory(ItemDefinition def, PrismContainerValue<?> parentValue) throws SchemaException {
        ItemWrapperFactory<?, ?, ?> factory = getRegistry().findWrapperFactory(def, parentValue);
        if (factory == null) {
            LOGGER.error("Cannot find factory for {}", def);
            throw new SchemaException("Cannot find factory for " + def);
        }

        LOGGER.trace("Found factory {} for {}", factory, def);
        return factory;
    }



    @Override
    protected PrismContainerValue<C> createNewValue(PrismContainer<C> item) {
        return item.createNewValue();
    }

    @Override
    protected PrismContainerWrapper<C> createWrapperInternal(PrismContainerValueWrapper<?> parent, PrismContainer<C> childContainer,
            ItemStatus status, WrapperContext ctx) {

        return new PrismContainerWrapperImpl<>(parent, childContainer, status);
    }

    @Override
    public void registerWrapperPanel(PrismContainerWrapper<C> wrapper) {
        if (wrapper.isMetadata()) {
            getRegistry().registerWrapperPanel(wrapper.getTypeName(), MetadataContainerPanel.class);
        } else {
            getRegistry().registerWrapperPanel(wrapper.getTypeName(), PrismContainerPanel.class);
        }
    }

    @Override
    public PrismContainerValueWrapper<C> createContainerValueWrapper(PrismContainerWrapper<C> objectWrapper, PrismContainerValue<C> objectValue, ValueStatus status, WrapperContext context) {
        return new PrismContainerValueWrapperImpl<>(objectWrapper, objectValue, status);
    }

    protected boolean shouldBeExpanded(PrismContainerWrapper<C> parent, PrismContainerValue<C> value, WrapperContext context) {

        if (context.getVirtualItemSpecification() != null) {
            return true;
        }

        if (value.isEmpty()) {
            return context.isShowEmpty() || containsEmphasizedItems(parent.getDefinitions());
        }

        return true;
    }

    private boolean containsEmphasizedItems(List<? extends ItemDefinition> definitions) {
        for (ItemDefinition def : definitions) {
            if (def.isEmphasized()) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected void setupWrapper(PrismContainerWrapper<C> wrapper) {
        boolean expanded = false;
        for (PrismContainerValueWrapper<C> valueWrapper : wrapper.getValues()) {
            if (valueWrapper.isExpanded()) {
                expanded = true;
            }
        }

        wrapper.setExpanded(expanded || wrapper.isSingleValue());
    }

}
