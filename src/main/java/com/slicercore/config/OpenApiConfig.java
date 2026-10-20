package com.slicercore.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * The API contract is generated from the code, served at {@code /v3/api-docs} and browsable
 * at {@code /api-docs}. The frontend generates its TypeScript models from it, so a changed
 * response shape fails the frontend build instead of surprising a customer.
 */
@Configuration
@OpenAPIDefinition(info = @Info(
        title = "3DBurg shop API",
        version = "v1",
        description = "Quotes for uploaded 3D models, orders, payment confirmation and the owner's admin area.",
        contact = @Contact(name = "3DBurg", email = "info@3dburg.ch")))
@SecurityScheme(name = OpenApiConfig.ADMIN_AUTH, type = SecuritySchemeType.HTTP, scheme = "basic",
        description = "Owner login (ADMIN_USERNAME / ADMIN_PASSWORD)")
public class OpenApiConfig {

    public static final String ADMIN_AUTH = "adminBasic";
}
