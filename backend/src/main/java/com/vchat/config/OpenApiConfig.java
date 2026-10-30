package com.vchat.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI 3 description of the REST API, generated from the controllers by springdoc:
 *   /v3/api-docs (JSON), /v3/api-docs.yaml, /swagger-ui.html (try requests with "Authorize").
 * Off by default under the prod profile; API_DOCS_ENABLED=true turns them back on.
 */
@Configuration
public class OpenApiConfig {
    static final String BEARER = "bearerAuth";
    static final String REFRESH_COOKIE = "refreshCookie";

    @Bean
    OpenAPI vchatOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("VirtualFriends API")
                        .version("1.0")
                        .description("""
                                REST API of the VirtualFriends chat app. A GraphQL API with the same data is at `/graphql` \
                                (explore it at `/graphiql`); realtime events use STOMP over the `/ws` WebSocket.

                                **Authentication.** `POST /api/auth/login` (or `/register`) returns a 15-minute access token in \
                                the body and sets a rotating refresh token as an httpOnly cookie. Send the access token as \
                                `Authorization: Bearer <token>`; when it expires call `POST /api/auth/refresh` (the browser \
                                sends the cookie). In Swagger UI: log in, copy `token`, click **Authorize**.

                                **Errors** are JSON `{"error": "human-readable message"}` with the usual HTTP status codes.""")
                        .license(new License().name("Private")))
                .externalDocs(new ExternalDocumentation().description("README").url("https://github.com/leduyenminh/virtualfriends"))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                                .bearerFormat("JWT").description("Access token from /api/auth/login, /register or /refresh"))
                        .addSecuritySchemes(REFRESH_COOKIE, new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE).name(AuthCookies.NAME)
                                .description("httpOnly refresh token, path /api/auth. Set by the server; browsers send it automatically")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .tags(List.of(
                        new Tag().name("Auth").description("Sign-in, sessions, social login providers"),
                        new Tag().name("Users").description("Profile, search, presence"),
                        new Tag().name("Conversations").description("Chats, messages, reactions, read state"),
                        new Tag().name("Bots & avatars").description("AI friends, Claude models, 3D/Live2D avatar models"),
                        new Tag().name("Stickers").description("Sticker packs"),
                        new Tag().name("Media").description("Uploads and text-to-speech"),
                        new Tag().name("Admin").description("Moderation (ADMIN role)")));
    }

    /**
     * Adds the error responses every endpoint can return, and marks the public auth endpoints as not needing a token,
     * so the generated document matches what SecurityConfig enforces.
     */
    @Bean
    OpenApiCustomizer commonResponses() {
        return api -> {
            // added here (not in the OpenAPI bean) because springdoc prunes schemas no controller references
            api.getComponents().addSchemas("ApiError", new ObjectSchema().addProperty("error",
                    new StringSchema().example("Wrong username or password")));
            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, op) -> {
                boolean publicAuth = path.startsWith("/api/auth/") && !path.equals("/api/auth/logout-all");
                if (publicAuth) op.setSecurity(List.of());
                if (path.equals("/api/auth/refresh") || path.equals("/api/auth/logout")) {
                    op.setSecurity(List.of(new SecurityRequirement().addList(REFRESH_COOKIE)));
                }
                add(op, "400", "Invalid input");
                if (!publicAuth) add(op, "401", "Missing, expired or revoked access token");
                if (path.startsWith("/api/admin/")) add(op, "403", "Requires the ADMIN role");
                else if (!publicAuth) add(op, "403", "Not a member of this chat / not the owner");
                add(op, "429", "Rate limit exceeded");
            }));
        };
    }

    private static void add(Operation op, String code, String description) {
        if (op.getResponses() == null || op.getResponses().containsKey(code)) return;
        op.getResponses().addApiResponse(code, new ApiResponse().description(description).content(new Content()
                .addMediaType("application/json", new MediaType().schema(new io.swagger.v3.oas.models.media.Schema<>()
                        .$ref("#/components/schemas/ApiError")))));
    }
}
