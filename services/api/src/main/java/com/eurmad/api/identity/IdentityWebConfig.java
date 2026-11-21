package com.eurmad.api.identity;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class IdentityWebConfig implements WebMvcConfigurer {

    private final CustomerRepository customers;

    IdentityWebConfig(CustomerRepository customers) {
        this.customers = customers;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentCustomerArgumentResolver(customers));
    }
}
