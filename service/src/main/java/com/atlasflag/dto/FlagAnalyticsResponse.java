package com.atlasflag.dto;

import java.time.Instant;
import java.util.List;

public class FlagAnalyticsResponse {

    private String flagKey;
    private String environment;
    private int hours;
    private long totalEvaluations;
    private long trueCount;
    private long falseCount;
    private double truePercent;
    private List<HourlyBucket> hourly;

    public FlagAnalyticsResponse() {}

    public FlagAnalyticsResponse(String flagKey, String environment, int hours,
                                  long totalEvaluations, long trueCount, long falseCount,
                                  double truePercent, List<HourlyBucket> hourly) {
        this.flagKey = flagKey;
        this.environment = environment;
        this.hours = hours;
        this.totalEvaluations = totalEvaluations;
        this.trueCount = trueCount;
        this.falseCount = falseCount;
        this.truePercent = truePercent;
        this.hourly = hourly;
    }

    public String getFlagKey()            { return flagKey; }
    public String getEnvironment()        { return environment; }
    public int getHours()                 { return hours; }
    public long getTotalEvaluations()     { return totalEvaluations; }
    public long getTrueCount()            { return trueCount; }
    public long getFalseCount()           { return falseCount; }
    public double getTruePercent()        { return truePercent; }
    public List<HourlyBucket> getHourly() { return hourly; }

    public static class HourlyBucket {
        private Instant hour;
        private long trueCount;
        private long falseCount;

        public HourlyBucket() {}

        public HourlyBucket(Instant hour, long trueCount, long falseCount) {
            this.hour = hour;
            this.trueCount = trueCount;
            this.falseCount = falseCount;
        }

        public Instant getHour()      { return hour; }
        public long getTrueCount()    { return trueCount; }
        public long getFalseCount()   { return falseCount; }
    }
}
