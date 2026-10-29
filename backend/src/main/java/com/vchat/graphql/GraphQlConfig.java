package com.vchat.graphql;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.language.StringValue;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLScalarType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * GraphQL runtime: the DateTime scalar, abuse limits, and the mapping from our exceptions to GraphQL errors.
 * Authentication is the normal Spring Security chain (JwtFilter) in front of /graphql, so resolvers see the same
 * SecurityContext as REST controllers and the same service-layer rules, rate limits and @PreAuthorize checks apply.
 */
@Configuration
public class GraphQlConfig {
    private static final Logger log = LoggerFactory.getLogger(GraphQlConfig.class);

    public static final GraphQLScalarType DATE_TIME = GraphQLScalarType.newScalar()
            .name("DateTime")
            .description("ISO-8601 instant in UTC")
            .coercing(new Coercing<Instant, String>() {
                @Override
                public String serialize(Object value) {
                    if (value instanceof Instant i) return i.toString();
                    throw new CoercingSerializeException("Expected an Instant but got " + value.getClass().getSimpleName());
                }

                @Override
                public Instant parseValue(Object input) {
                    try {
                        return Instant.parse(String.valueOf(input));
                    } catch (DateTimeParseException e) {
                        throw new CoercingParseValueException("Invalid DateTime: " + input);
                    }
                }

                @Override
                public Instant parseLiteral(Object input) {
                    if (input instanceof StringValue s) {
                        try {
                            return Instant.parse(s.getValue());
                        } catch (DateTimeParseException e) {
                            throw new CoercingParseLiteralException("Invalid DateTime: " + s.getValue());
                        }
                    }
                    throw new CoercingParseLiteralException("DateTime must be a string");
                }
            })
            .build();

    @Bean
    RuntimeWiringConfigurer scalars() {
        return wiring -> wiring.scalar(DATE_TIME);
    }

    /** A client can't send a 50-level-deep query: chat -> members -> ... is at most ~6 levels in real use. */
    @Bean
    MaxQueryDepthInstrumentation maxDepth() {
        return new MaxQueryDepthInstrumentation(10);
    }

    /** Caps total work per request (every field counts 1), e.g. asking messages of every chat with every reply. */
    @Bean
    MaxQueryComplexityInstrumentation maxComplexity() {
        return new MaxQueryComplexityInstrumentation(300);
    }

    /** Same status semantics as REST: 401/403/404/400/429 become typed GraphQL errors instead of "INTERNAL_ERROR". */
    @Bean
    DataFetcherExceptionResolverAdapter errors() {
        return new DataFetcherExceptionResolverAdapter() {
            @Override
            protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
                if (ex instanceof ResponseStatusException e) {
                    int status = e.getStatusCode().value();
                    ErrorType type = switch (status) {
                        case 401 -> ErrorType.UNAUTHORIZED;
                        case 403 -> ErrorType.FORBIDDEN;
                        case 404 -> ErrorType.NOT_FOUND;
                        default -> status >= 500 ? ErrorType.INTERNAL_ERROR : ErrorType.BAD_REQUEST;
                    };
                    return error(env, type, e.getReason(), status);
                }
                if (ex instanceof AccessDeniedException) return error(env, ErrorType.FORBIDDEN, "You don't have permission to do that", 403);
                if (ex instanceof IllegalArgumentException) return error(env, ErrorType.BAD_REQUEST, ex.getMessage(), 400);
                if (ex instanceof ConstraintViolationException cve) {
                    String msg = cve.getConstraintViolations().stream().map(ConstraintViolation::getMessage)
                            .distinct().collect(Collectors.joining(". "));
                    return error(env, ErrorType.BAD_REQUEST, msg, 400);
                }
                log.error("GraphQL resolver {} failed", env.getField().getName(), ex);
                return null; // default: INTERNAL_ERROR without leaking the exception message
            }

            private GraphQLError error(DataFetchingEnvironment env, ErrorType type, String message, int status) {
                return GraphqlErrorBuilder.newError(env).errorType(type).message(message)
                        .extensions(Map.of("status", status)).build();
            }
        };
    }
}
