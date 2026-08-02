package com.foggy.navigator.session.lifecycle;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseLifecycleAuthorityClockTest {
    @Test
    void readsDatabaseUtcDirectlyAsLocalDateTime() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        LocalDateTime expected = LocalDateTime.of(
                2026, 8, 2, 1, 17, 49, 630_000_000);
        when(jdbc.queryForObject(
                "select utc_timestamp(6)", LocalDateTime.class))
                .thenReturn(expected);

        LocalDateTime actual =
                new DatabaseLifecycleAuthorityClock(jdbc).databaseNow();

        assertThat(actual).isEqualTo(expected);
        verify(jdbc).queryForObject(
                "select utc_timestamp(6)", LocalDateTime.class);
    }
}
