package com.atlasflag.service;

import com.atlasflag.dto.FlagAnalyticsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class EvaluationAnalyticsService {

    private static final Logger logger = LoggerFactory.getLogger(EvaluationAnalyticsService.class);

    private final JdbcTemplate jdbcTemplate;

    public EvaluationAnalyticsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Async
    public void record(String flagKey, String environment, boolean result) {
        Instant hourBucket = Instant.now().truncatedTo(ChronoUnit.HOURS);
        try {
            jdbcTemplate.update(
                "INSERT INTO flag_evaluations (flag_key, environment, result, hour_bucket, count) "
                + "VALUES (?, ?, ?, ?, 1) "
                + "ON CONFLICT (flag_key, environment, result, hour_bucket) "
                + "DO UPDATE SET count = flag_evaluations.count + 1",
                flagKey, environment, result, Timestamp.from(hourBucket)
            );
        } catch (Exception e) {
            logger.warn("Failed to record evaluation analytics for '{}'", flagKey, e);
        }
    }

    public FlagAnalyticsResponse getAnalytics(String flagKey, String environment, int hours) {
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT hour_bucket, result, count FROM flag_evaluations "
            + "WHERE flag_key = ? AND environment = ? AND hour_bucket >= ? "
            + "ORDER BY hour_bucket",
            flagKey, environment, Timestamp.from(since)
        );

        long trueCount = 0, falseCount = 0;
        Map<Instant, long[]> hourlyMap = new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            boolean result = (Boolean) row.get("result");
            long count = ((Number) row.get("count")).longValue();
            Instant hour = ((Timestamp) row.get("hour_bucket")).toInstant();

            if (result) trueCount += count;
            else falseCount += count;

            long[] bucket = hourlyMap.computeIfAbsent(hour, k -> new long[2]);
            bucket[result ? 0 : 1] += count;
        }

        long total = trueCount + falseCount;
        double truePercent = total > 0 ? (trueCount * 100.0 / total) : 0;

        List<FlagAnalyticsResponse.HourlyBucket> hourly = new ArrayList<>();
        hourlyMap.forEach((h, counts) ->
            hourly.add(new FlagAnalyticsResponse.HourlyBucket(h, counts[0], counts[1])));

        return new FlagAnalyticsResponse(flagKey, environment, hours, total, trueCount, falseCount, truePercent, hourly);
    }

    public Map<String, Long> getBulkEvalCounts(String environment, int hours) {
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
            "SELECT flag_key, SUM(count) AS total FROM flag_evaluations "
            + "WHERE environment = ? AND hour_bucket >= ? "
            + "GROUP BY flag_key",
            environment, Timestamp.from(since)
        );

        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put((String) row.get("flag_key"), ((Number) row.get("total")).longValue());
        }
        return counts;
    }
}
