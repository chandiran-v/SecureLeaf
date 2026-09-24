package com.secureleaf.common.config;

import graphql.scalars.ExtendedScalars;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

/**
 * Registers custom GraphQL scalars used in the schema:
 * {@code DateTime} → maps to Java {@code Instant}/{@code OffsetDateTime}
 * {@code BigDecimal} → maps to Java {@code BigDecimal}
 * {@code Long} → maps to Java {@code long}/{@code Long} (64-bit; GraphQL's Int is only 32-bit)
 */
@Configuration
public class GraphQlConfig {

    @Bean
    public RuntimeWiringConfigurer runtimeWiringConfigurer() {
        return wiringBuilder -> wiringBuilder
                .scalar(ExtendedScalars.DateTime)
                .scalar(ExtendedScalars.GraphQLBigDecimal)
                .scalar(ExtendedScalars.GraphQLLong);
    }

}
