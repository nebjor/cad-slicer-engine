package com.slicercore.order;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Puts the session check in front of order placement. */
@Configuration
public class OrderWebConfig implements WebMvcConfigurer {

    /** Resolved on first use, so web slices that never place orders need no session port. */
    private final ObjectProvider<CurrentAccount> currentAccount;

    public OrderWebConfig(ObjectProvider<CurrentAccount> currentAccount) {
        this.currentAccount = currentAccount;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new OrderSessionInterceptor(currentAccount::getObject)).addPathPatterns("/api/orders");
    }
}
