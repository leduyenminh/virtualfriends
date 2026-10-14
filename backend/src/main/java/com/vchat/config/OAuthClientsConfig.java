package com.vchat.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import java.util.ArrayList;
import java.util.List;

/** Registers Google / Facebook only when their client id + secret are configured; otherwise OAuth stays off. */
@Configuration
public class OAuthClientsConfig {

    @Bean
    @Conditional(AnyProviderConfigured.class)
    ClientRegistrationRepository clientRegistrationRepository(VchatProperties props) {
        VchatProperties.OAuth o = props.oauth();
        String base = VchatProperties.notBlank(o.publicBaseUrl()) ? o.publicBaseUrl().replaceAll("/$", "") : "{baseUrl}";
        String redirect = base + "/login/oauth2/code/{registrationId}";
        List<ClientRegistration> regs = new ArrayList<>();
        if (o.google()) {
            regs.add(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(o.googleClientId()).clientSecret(o.googleClientSecret())
                    .redirectUri(redirect).scope("openid", "profile", "email").build());
        }
        if (o.facebook()) {
            regs.add(CommonOAuth2Provider.FACEBOOK.getBuilder("facebook")
                    .clientId(o.facebookClientId()).clientSecret(o.facebookClientSecret())
                    .redirectUri(redirect).scope("public_profile", "email").build());
        }
        return new InMemoryClientRegistrationRepository(regs);
    }

    static class AnyProviderConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) {
            Environment env = ctx.getEnvironment();
            return pair(env, "google") || pair(env, "facebook");
        }

        private static boolean pair(Environment env, String p) {
            return VchatProperties.notBlank(env.getProperty("vchat.oauth." + p + "-client-id"))
                    && VchatProperties.notBlank(env.getProperty("vchat.oauth." + p + "-client-secret"));
        }
    }
}
