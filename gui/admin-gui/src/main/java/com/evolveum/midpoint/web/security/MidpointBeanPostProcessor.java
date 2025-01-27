/*
 * Copyright (c) 2010-2019 Evolveum and contributors
 *
 * This work is dual-licensed under the Apache License 2.0
 * and European Union Public License. See LICENSE file for details.
 */
package com.evolveum.midpoint.web.security;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.stereotype.Component;

/**
 * @author skublik
 */

@Component
class MidpointBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof CsrfFilter) {
            CsrfFilter csrfFilter = (CsrfFilter) bean;
            csrfFilter.setAccessDeniedHandler(new MidpointAccessDeniedHandler());
        }
        if (bean instanceof RegisterSessionAuthenticationStrategy) {
            RegisterSessionAuthenticationStrategy strategy = (RegisterSessionAuthenticationStrategy) bean;
            return new MidpointRegisterSessionAuthenticationStrategy(strategy);
        }
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }
}
