package com.atlasflag.service;

import com.atlasflag.dto.FlagAnalyticsResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EvaluationAnalyticsServiceTest {

    @Mock JdbcTemplate jdbcTemplate;

    @InjectMocks EvaluationAnalyticsService service;

    @Test
    void getBulkEvalCounts_aggregatesCountsPerFlag() {
        when(jdbcTemplate.queryForList(anyString(), any(String.class), any(Timestamp.class)))
            .thenReturn(List.of(
                Map.of("flag_key", "flag-a", "total", 100L),
                Map.of("flag_key", "flag-b", "total", 42L)
            ));

        Map<String, Long> result = service.getBulkEvalCounts("PRODUCTION", 24);

        assertThat(result).hasSize(2)
            .containsEntry("flag-a", 100L)
            .containsEntry("flag-b", 42L);
    }

    @Test
    void getBulkEvalCounts_empty_returnsEmptyMap() {
        when(jdbcTemplate.queryForList(anyString(), any(String.class), any(Timestamp.class)))
            .thenReturn(List.of());

        assertThat(service.getBulkEvalCounts("PRODUCTION", 24)).isEmpty();
    }

    @Test
    void getAnalytics_aggregatesTrueAndFalseCounts() {
        Timestamp bucket = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.HOURS));
        when(jdbcTemplate.queryForList(anyString(), any(String.class), any(String.class), any(Timestamp.class)))
            .thenReturn(List.of(
                Map.of("hour_bucket", bucket, "result", true,  "count", 80L),
                Map.of("hour_bucket", bucket, "result", false, "count", 20L)
            ));

        FlagAnalyticsResponse result = service.getAnalytics("dark-mode", "PRODUCTION", 24);

        assertThat(result.getFlagKey()).isEqualTo("dark-mode");
        assertThat(result.getEnvironment()).isEqualTo("PRODUCTION");
        assertThat(result.getTotalEvaluations()).isEqualTo(100L);
        assertThat(result.getTrueCount()).isEqualTo(80L);
        assertThat(result.getFalseCount()).isEqualTo(20L);
        assertThat(result.getTruePercent()).isEqualTo(80.0);
    }

    @Test
    void getAnalytics_noData_returnsAllZeros() {
        when(jdbcTemplate.queryForList(anyString(), any(String.class), any(String.class), any(Timestamp.class)))
            .thenReturn(List.of());

        FlagAnalyticsResponse result = service.getAnalytics("unknown", "PRODUCTION", 24);

        assertThat(result.getTotalEvaluations()).isZero();
        assertThat(result.getTrueCount()).isZero();
        assertThat(result.getFalseCount()).isZero();
        assertThat(result.getTruePercent()).isZero();
        assertThat(result.getHourly()).isEmpty();
    }

    @Test
    void getAnalytics_combinesMultipleHourBuckets() {
        Timestamp hour1 = Timestamp.from(Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS));
        Timestamp hour2 = Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS));
        when(jdbcTemplate.queryForList(anyString(), any(String.class), any(String.class), any(Timestamp.class)))
            .thenReturn(List.of(
                Map.of("hour_bucket", hour1, "result", true,  "count", 30L),
                Map.of("hour_bucket", hour1, "result", false, "count", 10L),
                Map.of("hour_bucket", hour2, "result", true,  "count", 50L)
            ));

        FlagAnalyticsResponse result = service.getAnalytics("flag", "DEV", 24);

        assertThat(result.getTotalEvaluations()).isEqualTo(90L);
        assertThat(result.getTrueCount()).isEqualTo(80L);
        assertThat(result.getHourly()).hasSize(2);
    }

    @Test
    void record_callsUpsertWithCorrectParameters() {
        // @Async is not active in unit tests — method runs synchronously
        service.record("my-flag", "PRODUCTION", true);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(
            argThat(sql -> sql.contains("ON CONFLICT") && sql.contains("flag_evaluations")),
            eq("my-flag"), eq("PRODUCTION"), eq(true), any(Timestamp.class)
        );
    }

    @Test
    void record_jdbcFailure_doesNotThrow() {
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
            .thenThrow(new RuntimeException("DB error"));

        assertThatCode(() -> service.record("flag", "DEV", false))
            .doesNotThrowAnyException();
    }
}
