package com.foggy.navigator.metadata.config.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DatasourceLookupService {

    private final ApplicationContext applicationContext;

    public Optional<DataSource> getDataSource(String tenantId, String configId) {
        String beanName = String.format("datasource_%s_%s", tenantId, configId);
        return getBeanSafely(beanName, DataSource.class);
    }

    public Optional<MongoTemplate> getMongoTemplate(String tenantId, String configId) {
        String beanName = String.format("mongoTemplate_%s_%s", tenantId, configId);
        return getBeanSafely(beanName, MongoTemplate.class);
    }

    public Optional<DataSource> getDataSourceByConfigId(String configId) {
        return applicationContext.getBeansOfType(DataSource.class).entrySet().stream()
            .filter(entry -> entry.getKey().endsWith("_" + configId))
            .map(entry -> entry.getValue())
            .findFirst();
    }

    public Optional<MongoTemplate> getMongoTemplateByConfigId(String configId) {
        return applicationContext.getBeansOfType(MongoTemplate.class).entrySet().stream()
            .filter(entry -> entry.getKey().endsWith("_" + configId))
            .map(entry -> entry.getValue())
            .findFirst();
    }

    private <T> Optional<T> getBeanSafely(String beanName, Class<T> beanType) {
        try {
            if (applicationContext.containsBean(beanName)) {
                return Optional.of(applicationContext.getBean(beanName, beanType));
            }
        } catch (Exception e) {
            log.warn("Failed to get bean: name={}, type={}", beanName, beanType.getSimpleName(), e);
        }
        return Optional.empty();
    }

    public boolean isDatasourceAvailable(String tenantId, String configId) {
        String beanName = String.format("datasource_%s_%s", tenantId, configId);
        return applicationContext.containsBean(beanName);
    }

    public boolean isMongoTemplateAvailable(String tenantId, String configId) {
        String beanName = String.format("mongoTemplate_%s_%s", tenantId, configId);
        return applicationContext.containsBean(beanName);
    }
}
