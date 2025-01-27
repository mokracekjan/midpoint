package com.evolveum.midpoint.model.impl.schema.transform;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import javax.xml.namespace.QName;

import com.evolveum.midpoint.prism.ComplexTypeDefinition;
import com.evolveum.midpoint.prism.Item;
import com.evolveum.midpoint.prism.ItemDefinition;
import com.evolveum.midpoint.prism.ItemProcessing;
import com.evolveum.midpoint.prism.MutableItemDefinition;
import com.evolveum.midpoint.prism.PrismContainerDefinition;
import com.evolveum.midpoint.prism.PrismContext;
import com.evolveum.midpoint.prism.PrismItemAccessDefinition;
import com.evolveum.midpoint.prism.PrismObjectDefinition;
import com.evolveum.midpoint.prism.PrismPropertyDefinition;
import com.evolveum.midpoint.prism.PrismReferenceDefinition;
import com.evolveum.midpoint.prism.PrismReferenceValue;
import com.evolveum.midpoint.prism.deleg.ItemDefinitionDelegator;
import com.evolveum.midpoint.xml.ns._public.common.common_3.ObjectTemplateItemDefinitionType;
import com.google.common.base.Preconditions;

/*
 * Copyright (c) 2021 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */

public abstract class TransformableItemDefinition<I extends Item<?,?>,D extends ItemDefinition<I>>
        implements ItemDefinitionDelegator<I>, PrismItemAccessDefinition.Mutable, PartiallyMutableItemDefinition<I> {

    private static final long serialVersionUID = 1L;

    private D delegate;


    private boolean allowAdd = true;
    private boolean allowRead = true;
    private boolean allowModify = true;

    private String displayName;

    private String help;
    private Integer displayOrder;
    private Boolean emphasized;
    private Boolean deprecated;
    private Boolean experimental;

    private Integer minOccurs;
    private Integer maxOccurs;
    private ItemProcessing processing;
    private PrismReferenceValue valueEnumerationRef;

    protected TransformableItemDefinition(D delegate) {
        if (delegate instanceof TransformableItemDefinition) {
            // CopyOf constructor

            @SuppressWarnings("unchecked")
            TransformableItemDefinition<?, D> copyOf = (TransformableItemDefinition<?,D>) delegate;
            this.allowAdd = copyOf.allowAdd;
            this.allowRead = copyOf.allowRead;
            this.allowModify = copyOf.allowModify;
            this.minOccurs = copyOf.minOccurs;
            this.maxOccurs = copyOf.maxOccurs;
            this.processing = copyOf.processing;

            this.help = copyOf.help;
            this.displayOrder = copyOf.displayOrder;
            this.emphasized = copyOf.emphasized;
            this.deprecated = copyOf.deprecated;
            this.experimental = copyOf.experimental;

            this.valueEnumerationRef = copyOf.valueEnumerationRef;
            this.delegate = copyOf.delegate();
        } else {
            this.delegate = delegate;
        }


    }


    @SuppressWarnings("unchecked")
    public static <ID extends ItemDefinition<?>> TransformableItemDefinition<?, ID> from(ID originalItem) {
        if (originalItem == null) {
            return null;
        }
        if (originalItem instanceof TransformableItemDefinition<?, ?>) {
            return ((TransformableItemDefinition<?,ID>) originalItem);
        }
        if (originalItem instanceof PrismPropertyDefinition<?>) {
            return (TransformableItemDefinition<?,ID>) TransformablePropertyDefinition.of((PrismPropertyDefinition<?>) originalItem);
        }
        if (originalItem instanceof PrismReferenceDefinition) {
            return (TransformableItemDefinition<?,ID>) TransformableReferenceDefinition.of((PrismReferenceDefinition) originalItem);
        }
        if (originalItem instanceof PrismObjectDefinition<?>) {
            return (TransformableItemDefinition<?,ID>) TransformableObjectDefinition.of((PrismObjectDefinition<?>) originalItem);
        }
        if (originalItem instanceof PrismContainerDefinition<?>) {
            return (TransformableItemDefinition<?,ID>) TransformableContainerDefinition.of((PrismContainerDefinition<?>) originalItem);
        }
        throw new IllegalArgumentException("Unsupported item definition type " + originalItem.getClass());
    }


    public static <ID extends ItemDefinition<?>> ID publicFrom(ID definition) {
        TransformableItemDefinition<?, ID> accessDef = from(definition);
        if (accessDef != null) {
            return accessDef.publicView();
        }
        return null;
    }


    public static boolean isMutableAccess(ItemDefinition<?> definition) {
        return definition instanceof TransformableItemDefinition<?, ?>;
    }


    @Override
    public D delegate() {
        return delegate;
    }

    @Override
    public boolean canAdd() {
        return allowAdd;
    }

    @Override
    public boolean canModify() {
        return allowModify;
    }

    @Override
    public boolean canRead() {
        return allowRead;
    }

    @Override
    public int getMinOccurs() {
        return preferLocal(minOccurs, delegate().getMinOccurs());
    }


    @Override
    public int getMaxOccurs() {
        return preferLocal(maxOccurs, delegate.getMaxOccurs());
    }


    @Override
    public ItemProcessing getProcessing() {
        return preferLocal(processing, delegate.getProcessing());
    }


    @Override
    public void setCanAdd(boolean val) {
        allowAdd = val;
    }

    @Override
    public void setCanModify(boolean val) {
        allowModify = val;
    }

    @Override
    public void setCanRead(boolean val) {
        allowRead = val;
    }


    @SuppressWarnings("unchecked")
    protected <ID extends ItemDefinition<?>> ID apply(ID originalItem) {
        if (delegate == null) {
            delegate = (D) originalItem;
        }
        return (ID) publicView();
    }

    protected abstract D publicView();

    @Override
    public ItemDefinition<I> clone() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void freeze() {
        // Intentional Noop for now

    }

    @Override
    public boolean isImmutable() {
        // Intentional Noop for now
        return false;
    }

    @Override
    public void revive(PrismContext prismContext) {
        delegate.revive(prismContext);
    }

    public static TransformableItemDefinition<?, ?> access(ItemDefinition<?> itemDef) {
        Preconditions.checkArgument(itemDef instanceof TransformableItemDefinition<?, ?>, "Definition must be %s", TransformableItemDefinition.class.getName());
        return (TransformableItemDefinition<?, ?>) itemDef;
    }

    public void applyTemplate(ObjectTemplateItemDefinitionType apply) {
        if (apply.getDisplayName() != null) {
            this.setDisplayName(apply.getDisplayName());
        }
        if (apply.getHelp() != null) {
            this.setHelp(apply.getHelp());
        }
        if (apply.getDisplayOrder() != null) {
            this.setDisplayOrder(apply.getDisplayOrder());
        }
        if (apply.isEmphasized() != null) {
            this.setEmphasized(apply.isEmphasized());
        }
        if (apply.isDeprecated() != null) {
            this.setDeprecated(apply.isDeprecated());
        }
        if (apply.isExperimental() != null) {
            this.setExperimental(apply.isExperimental());
        }
    }

    @Override
    public String getDisplayName() {
        return preferLocal(this.displayName, delegate().getDisplayName());
    }

    @Override
    public String getHelp() {
        return preferLocal(this.help, delegate().getHelp());
    }

    @Override
    public Integer getDisplayOrder() {
        return preferLocal(this.displayOrder, delegate().getDisplayOrder());
    }

    @Override
    public boolean isEmphasized() {
        return preferLocal(this.emphasized, delegate().isEmphasized());
    }

    @Override
    public boolean isDeprecated() {
        return preferLocal(this.deprecated, delegate().isDeprecated());
    }

    @Override
    public boolean isExperimental() {
        return preferLocal(this.experimental, delegate().isExperimental());
    }

    private <T> T preferLocal(T fromTemplate, T fromDelegate) {
        return fromTemplate != null ? fromTemplate : fromDelegate;
    }

    @Override
    public void setMinOccurs(int value) {
        this.minOccurs = value;
    }

    @Override
    public void setMaxOccurs(int value) {
        this.maxOccurs = value;
    }

    @Override
    public void setValueEnumerationRef(PrismReferenceValue valueEnumerationRVal) {
        this.valueEnumerationRef = valueEnumerationRVal;
    }

    @Override
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }


    @Override
    public void setHelp(String help) {
        this.help = help;
    }


    @Override
    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }


    @Override
    public void setEmphasized(boolean emphasized) {
        this.emphasized = emphasized;
    }


    @Override
    public void setDeprecated(boolean deprecated) {
        this.deprecated = deprecated;
    }


    @Override
    public void setExperimental(boolean experimental) {
        this.experimental = experimental;
    }


    @Override
    public PrismReferenceValue getValueEnumerationRef() {
        return preferLocal(valueEnumerationRef, delegate().getValueEnumerationRef());
    }

    @Override
    public void setProcessing(ItemProcessing itemProcessing) {
        this.processing = itemProcessing;
    }

    static void apply(ItemDefinition<?> overriden, ItemDefinition<?> originalItem) {
        if (overriden instanceof TransformableItemDefinition) {
            ((TransformableItemDefinition) overriden).apply(originalItem);
        }
    }

    @Override
    public MutableItemDefinition<I> toMutable() {
        return this;
    }

    @Override
    public ItemDefinition<I> deepClone(boolean ultraDeep, Consumer<ItemDefinition> postCloneAction) {
        return deepClone(new HashMap<>(), new HashMap<>(), postCloneAction);
    }

    @Override
    public ItemDefinition<I> deepClone(Map<QName, ComplexTypeDefinition> ctdMap,
            Map<QName, ComplexTypeDefinition> onThisPath, Consumer<ItemDefinition> postCloneAction) {
        return copy();
    }

    protected abstract TransformableItemDefinition<I,D> copy();

    @Override
    public String toString() {
        return "Transformable:" + delegate.toString();
    }

}
