package com.foggy.navigator.session.service;

import com.foggy.navigator.spi.agent.TaskStateRepairedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(SessionForwardTransactionBoundaryTest.Config.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:forward_transaction_boundary;MODE=MYSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class SessionForwardTransactionBoundaryTest {

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({
            DataSourceAutoConfiguration.class,
            SessionForwardTransactionBoundary.class
    })
    static class Config {

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
    }

    @Autowired
    private SessionForwardTransactionBoundary boundary;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void resetDisposableFixture() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("drop table if exists forward_tx_probe");
        jdbc.execute("create table forward_tx_probe (id integer primary key, marker varchar(40) not null)");
    }

    @Test
    void beanIsProxiedAndExistingTargetKeepsReadCommittedRollbackPolicy() {
        assertThat(AopUtils.isAopProxy(boundary)).isTrue();

        String result = boundary.executeExistingTarget(() -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                    .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
            jdbc.update("insert into forward_tx_probe (id, marker) values (1, 'committed')");
            return "done";
        });
        assertThat(result).isEqualTo("done");

        assertThatThrownBy(() -> boundary.executeExistingTarget(() -> {
            jdbc.update("insert into forward_tx_probe (id, marker) values (2, 'rolled-back')");
            throw new IllegalStateException("ordinary failure");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("ordinary failure");

        assertThatThrownBy(() -> boundary.executeExistingTarget(() -> {
            jdbc.update("insert into forward_tx_probe (id, marker) values (3, 'repaired')");
            throw new RepairedState("retry after durable repair");
        })).isInstanceOf(RepairedState.class)
                .hasMessage("retry after durable repair");

        assertThat(markers()).containsExactly("committed", "repaired");
    }

    @Test
    void newTargetRunsWithoutTransactionAndSuspendsThenResumesAnOuterTransaction() {
        assertThat(boundary.executeNewTarget(
                TransactionSynchronizationManager::isActualTransactionActive)).isFalse();

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> outer.executeWithoutResult(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            boundary.executeNewTarget(() -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                jdbc.update("insert into forward_tx_probe (id, marker) values (10, 'new-target')");
                return null;
            });
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            jdbc.update("insert into forward_tx_probe (id, marker) values (11, 'outer')");
            throw new IllegalStateException("rollback outer");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback outer");

        assertThat(markers()).containsExactly("new-target");
    }

    private java.util.List<String> markers() {
        return jdbc.queryForList(
                "select marker from forward_tx_probe order by id", String.class);
    }

    private static final class RepairedState extends TaskStateRepairedException {

        private RepairedState(String message) {
            super(message);
        }
    }
}
