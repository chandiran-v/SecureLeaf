package com.secureleaf.creator;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PayoutProperties.class)
public class PayoutConfig {}
