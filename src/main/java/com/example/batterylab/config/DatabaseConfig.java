package com.example.batterylab.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;

@Configuration
public class DatabaseConfig {
    @Bean
    DataSourceInitializer dataSourceInitializer(DataSource dataSource) {
        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ClassPathResource("schema.sql"));
        populator.setContinueOnError(false);
        initializer.setDatabasePopulator(populator);
        return initializer;
    }
}
