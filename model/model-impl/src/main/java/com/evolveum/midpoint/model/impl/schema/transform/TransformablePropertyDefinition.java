/*
 * Copyright (c) 2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

package com.evolveum.midpoint.model.impl.schema.transform;

import java.util.Map;
import java.util.function.Consumer;

import javax.xml.namespace.QName;

import org.jetbrains.annotations.NotNull;

import com.evolveum.midpoint.common.refinery.RefinedAttributeDefinition;
import com.evolveum.midpoint.common.refinery.deleg.RefinedAttributeDefinitionDelegator;
import com.evolveum.midpoint.prism.ComplexTypeDefinition;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.MutablePrismPropertyDefinition;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismProperty;
import com.evolveum.midpoint.prism.PrismPropertyDefinition;
import com.evolveum.midpoint.prism.deleg.PropertyDefinitionDelegator;
import com.evolveum.midpoint.schema.processor.MutableResourceAttributeDefinition;
import com.evolveum.midpoint.schema.processor.ResourceAttributeDefinition;
import com.evolveum.midpoint.schema.processor.deleg.AttributeDefinitionDelegator;

public class TransformablePropertyDefinition<T> extends TransformableItemDefinition<PrismProperty<T>, PrismPropertyDefinition<T>>
    implements PropertyDefinitionDelegator<T>, PartiallyMutableItemDefinition.Property<T> {


    private static final long serialVersionUID = 1L;

    public TransformablePropertyDefinition(PrismPropertyDefinition<T> delegate) {
        super(delegate);
    }

    public static <T> PrismPropertyDefinition<T> of(PrismPropertyDefinition<T> originalItem) {
        if (originalItem instanceof TransformablePropertyDefinition) {
            return originalItem;
        }
        if (originalItem instanceof RefinedAttributeDefinition) {
            return new RefinedAttribute<>(originalItem);
        }
        if (originalItem instanceof ResourceAttributeDefinition) {
            return new ResourceAttribute<>(originalItem);
        }

        return new TransformablePropertyDefinition<>(originalItem);
    }

    @Override
    public void revive(PrismContext prismContext) {

    }

    @Override
    public boolean isImmutable() {
        return false;
    }

    @Override
    public void freeze() {
        // NOOP
    }

    @Override
    public @NotNull PrismPropertyDefinition<T> clone() {
        throw new UnsupportedOperationException();
    }

    @Override
    protected TransformablePropertyDefinition<T> copy() {
        return new TransformablePropertyDefinition<>(this);
    }

    @Override
    public MutablePrismPropertyDefinition<T> toMutable() {
        return this;
    }

    @Override
    public @NotNull PrismProperty<T> instantiate() {
        return instantiate(getItemName());
    }

    @Override
    public @NotNull PrismProperty<T> instantiate(QName name) {
        return getPrismContext().itemFactory().createProperty(name, this);
    }

    @SuppressWarnings("unchecked")
    @Override
    protected <ID extends ItemDefinition<?>> ID apply(ID originalItem) {
        return (ID) publicView();
    }



    @Override
    protected PrismPropertyDefinition<T> publicView() {
        return this;
    }

    public static class ResourceAttribute<T> extends TransformablePropertyDefinition<T> implements AttributeDefinitionDelegator<T>, PartiallyMutableItemDefinition.Attribute<T> {
        private static final long serialVersionUID = 1L;

        public ResourceAttribute(PrismPropertyDefinition<T> delegate) {
            super(delegate);
        }

        @Override
        public ResourceAttributeDefinition<T> delegate() {
            return (ResourceAttributeDefinition<T>) super.delegate();
        }

        @Override
        public @NotNull ResourceAttributeDefinition<T> clone() {
            return copy();
        }

        @Override
        protected ResourceAttribute<T> copy() {
            return new ResourceAttribute<>(this);
        }

        @Override
        public MutableResourceAttributeDefinition<T> toMutable() {
            return this;
        }

        @Override
        public @NotNull com.evolveum.midpoint.schema.processor.ResourceAttribute<T> instantiate() {
            return instantiate(getItemName());
        }

        @Override
        public @NotNull com.evolveum.midpoint.schema.processor.ResourceAttribute<T> instantiate(QName name) {
            var deleg = delegate().instantiate(name);
            deleg.setDefinition(this);
            return deleg;
        }
    }

    public static class RefinedAttribute<T> extends ResourceAttribute<T> implements RefinedAttributeDefinitionDelegator<T> {

        public RefinedAttribute(PrismPropertyDefinition<T> delegate) {
            super(delegate);
        }

        @Override
        public RefinedAttributeDefinition<T> delegate() {
            return (RefinedAttributeDefinition<T>) super.delegate();
        }

        @Override
        public @NotNull RefinedAttributeDefinition<T> clone() {
            return copy();
        }

        @Override
        protected RefinedAttribute<T> copy() {
            return new RefinedAttribute<>(this);
        }

        @Override
        public RefinedAttributeDefinition<T> deepClone(Map<QName, ComplexTypeDefinition> ctdMap,
                Map<QName, ComplexTypeDefinition> onThisPath, Consumer<ItemDefinition> postCloneAction) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean canAdd() {
            return delegate().canAdd();
        }

        @Override
        public boolean canModify() {
            return delegate().canModify();
        }

        @Override
        public boolean canRead() {
            return delegate().canRead();
        }

        @Override
        public @NotNull com.evolveum.midpoint.schema.processor.ResourceAttribute<T> instantiate() {
            return instantiate(getItemName());
        }

        @Override
        public @NotNull com.evolveum.midpoint.schema.processor.ResourceAttribute<T>  instantiate(QName name) {
            var deleg = delegate().instantiate(name);
            deleg.setDefinition(this);
            return deleg;
        }
    }


}
