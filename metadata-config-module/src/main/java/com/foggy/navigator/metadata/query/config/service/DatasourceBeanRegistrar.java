package com.foggy.navigator.metadata.query.config.service;

import com.foggy.navigator.common.entity.DatasourceConfigEntity;
import com.foggy.navigator.common.enums.ConfigItemStatus;
import com.foggy.navigator.common.enums.DatasourceType;
import com.foggy.navigator.common.event.DatasourceConfigEvent;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatasourceBeanRegistrar {

    private final ApplicationContext applicationContext;
    private final CredentialEncryptor credentialEncryptor;
    private final Map<String, String> registeredBeans = new ConcurrentHashMap<>();

    @Async("datasourceRegistrarExecutor")
    @EventListener
    public void onDatasourceConfigEvent(DatasourceConfigEvent event) {
        log.info("Received DatasourceConfigEvent: type={}, configId={}",
            event.getEventType(), event.getConfig().getId());

        switch (event.getEventType()) {
            case STATUS_CHANGED -> handleStatusChange(event);
            case UPDATED -> handleUpdate(event);
            case DELETED -> destroyDatasourceBean(event.getConfig().getId());
            case CREATED -> {
                if (event.getConfig().getStatus() == ConfigItemStatus.VALIDATED) {
                    registerDatasourceBean(event.getConfig());
                }
            }
        }
    }

    private void handleStatusChange(DatasourceConfigEvent event) {
        ConfigItemStatus previous = event.getPreviousStatus();
        ConfigItemStatus current = event.getCurrentStatus();

        if (current == ConfigItemStatus.VALIDATED && previous != ConfigItemStatus.VALIDATED) {
            registerDatasourceBean(event.getConfig());
        } else if (previous == ConfigItemStatus.VALIDATED && current != ConfigItemStatus.VALIDATED) {
            destroyDatasourceBean(event.getConfig().getId());
        }
    }

    private void handleUpdate(DatasourceConfigEvent event) {
        DatasourceConfigEntity config = event.getConfig();
        if (config.getStatus() == ConfigItemStatus.VALIDATED) {
            destroyDatasourceBean(config.getId());
            registerDatasourceBean(config);
        }
    }

    private void registerDatasourceBean(DatasourceConfigEntity config) {
        try {
            if (config.getType() == DatasourceType.JDBC) {
                registerJdbcDataSource(config);
            } else if (config.getType() == DatasourceType.MONGO) {
                registerMongoTemplate(config);
            } else {
                log.warn("Unsupported datasource type: {}", config.getType());
            }
        } catch (Exception e) {
            log.error("Failed to register datasource bean: configId={}", config.getId(), e);
        }
    }

    private void registerJdbcDataSource(DatasourceConfigEntity config) {
        String beanName = buildBeanName("datasource", config);

        if (registeredBeans.containsKey(config.getId())) {
            log.warn("JDBC datasource bean already registered: {}", beanName);
            return;
        }

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(buildJdbcUrl(config));
        hikariConfig.setUsername(config.getUsername());
        hikariConfig.setPassword(credentialEncryptor.decrypt(config.getPassword()));
        hikariConfig.setMaximumPoolSize(10);
        hikariConfig.setMinimumIdle(2);
        hikariConfig.setPoolName("HikariPool-" + config.getId());
        hikariConfig.setConnectionTimeout(30000);
        hikariConfig.setIdleTimeout(600000);
        hikariConfig.setMaxLifetime(1800000);

        HikariDataSource dataSource = new HikariDataSource(hikariConfig);

        DefaultListableBeanFactory beanFactory = getBeanFactory();
        beanFactory.registerSingleton(beanName, dataSource);
        registeredBeans.put(config.getId(), beanName);

        log.info("JDBC datasource bean registered: {}", beanName);
    }

    private void registerMongoTemplate(DatasourceConfigEntity config) {
        String beanName = buildBeanName("mongoTemplate", config);

        if (registeredBeans.containsKey(config.getId())) {
            log.warn("MongoDB template bean already registered: {}", beanName);
            return;
        }

        String connectionString = buildMongoConnectionString(config);
        SimpleMongoClientDatabaseFactory factory = new SimpleMongoClientDatabaseFactory(connectionString);
        MongoTemplate mongoTemplate = new MongoTemplate(factory);

        DefaultListableBeanFactory beanFactory = getBeanFactory();
        beanFactory.registerSingleton(beanName, mongoTemplate);
        registeredBeans.put(config.getId(), beanName);

        log.info("MongoDB template bean registered: {}", beanName);
    }

    private void destroyDatasourceBean(String configId) {
        String beanName = registeredBeans.remove(configId);
        if (beanName == null) {
            log.debug("No registered bean found for configId: {}", configId);
            return;
        }

        try {
            DefaultListableBeanFactory beanFactory = getBeanFactory();
            Object bean = beanFactory.getSingleton(beanName);

            if (bean instanceof HikariDataSource hikari) {
                hikari.close();
                log.info("HikariDataSource closed: {}", beanName);
            } else if (bean instanceof MongoTemplate mongoTemplate) {
                var mongoClient = mongoTemplate.getMongoDatabaseFactory().getMongoDatabase().getCodecRegistry();
                // MongoTemplate doesn't expose direct close, factory handles it
                log.info("MongoTemplate will be garbage collected: {}", beanName);
            }

            beanFactory.destroySingleton(beanName);
            log.info("Datasource bean destroyed: {}", beanName);
        } catch (Exception e) {
            log.error("Failed to destroy datasource bean: {}", beanName, e);
        }
    }

    private String buildBeanName(String prefix, DatasourceConfigEntity config) {
        return String.format("%s_%s_%s", prefix, config.getTenantId(), config.getId());
    }

    private String buildJdbcUrl(DatasourceConfigEntity config) {
        if (StringUtils.hasText(config.getJdbcUrl())) {
            return config.getJdbcUrl();
        }

        String dbType = config.getDbType() != null ? config.getDbType().toLowerCase() : "mysql";
        String host = config.getHost();
        Integer port = config.getPort();
        String database = config.getDatabaseName();

        String url = switch (dbType) {
            case "mysql" -> String.format("jdbc:mysql://%s:%d/%s", host, port != null ? port : 3306, database);
            case "postgresql", "postgres" -> String.format("jdbc:postgresql://%s:%d/%s", host, port != null ? port : 5432, database);
            case "sqlserver", "mssql" -> String.format("jdbc:sqlserver://%s:%d;databaseName=%s", host, port != null ? port : 1433, database);
            case "oracle" -> String.format("jdbc:oracle:thin:@%s:%d:%s", host, port != null ? port : 1521, database);
            default -> String.format("jdbc:%s://%s:%d/%s", dbType, host, port != null ? port : 3306, database);
        };

        if (StringUtils.hasText(config.getExtraParams())) {
            url += (url.contains("?") ? "&" : "?") + config.getExtraParams();
        }

        return url;
    }

    private String buildMongoConnectionString(DatasourceConfigEntity config) {
        if (StringUtils.hasText(config.getConnectionString())) {
            return config.getConnectionString();
        }

        String host = config.getHost();
        Integer port = config.getPort() != null ? config.getPort() : 27017;
        String database = config.getDatabaseName();
        String username = config.getUsername();
        String password = credentialEncryptor.decrypt(config.getPassword());

        if (StringUtils.hasText(username) && StringUtils.hasText(password)) {
            return String.format("mongodb://%s:%s@%s:%d/%s", username, password, host, port, database);
        }
        return String.format("mongodb://%s:%d/%s", host, port, database);
    }

    private DefaultListableBeanFactory getBeanFactory() {
        return (DefaultListableBeanFactory) applicationContext.getAutowireCapableBeanFactory();
    }

    public boolean isRegistered(String configId) {
        return registeredBeans.containsKey(configId);
    }

    public String getBeanName(String configId) {
        return registeredBeans.get(configId);
    }
}
