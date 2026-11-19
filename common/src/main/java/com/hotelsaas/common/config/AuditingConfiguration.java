package com.hotelsaas.common.config;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@AutoConfiguration
@ConditionalOnBean(DataSource.class)
@EnableJpaAuditing
public class AuditingConfiguration {}
