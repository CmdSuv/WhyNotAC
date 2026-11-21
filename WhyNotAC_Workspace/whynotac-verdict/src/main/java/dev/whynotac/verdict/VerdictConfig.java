package dev.whynotac.verdict;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Typed, validated configuration for the verdict layer (CFG-01, VER-12).
 *
 * <p>Safe defaults keep every check in {@code SHADOW}, mitigations disabled, and automatic kicks/bans disabled.
 */
public record VerdictConfig(
        Set<String> armedChecks,
        boolean mitigationsEnabled,
        boolean hidesInformationEnabled,
        boolean autoKickEnabled,
        boolean autoBanEnabled,
        boolean secondVerifierEnabled,
        boolean requireCorroborationForTempBan,
        int maxUniquePenalizedPlayersPerMinute,
        int maxFalseBanBudgetPerWindow,
        long falseBanBudgetWindowMillis,
        long tempBanDurationMillis,
        double minScoreForMitigation,
        double minScoreForKick,
        long correlationHalfLifeNanos,
        double sameGroupCapMultiplier,
        int secondVerifierWorkers,
        long banWaveDelayMillis) {

    public VerdictConfig(
            Set<String> armedChecks,
            boolean mitigationsEnabled,
            boolean hidesInformationEnabled,
            boolean autoKickEnabled,
            boolean autoBanEnabled,
            boolean secondVerifierEnabled,
            boolean requireCorroborationForTempBan,
            int maxUniquePenalizedPlayersPerMinute,
            int maxFalseBanBudgetPerWindow,
            long falseBanBudgetWindowMillis,
            long tempBanDurationMillis,
            double minScoreForMitigation,
            double minScoreForKick,
            long correlationHalfLifeNanos,
            double sameGroupCapMultiplier,
            int secondVerifierWorkers) {
        this(armedChecks, mitigationsEnabled, hidesInformationEnabled, autoKickEnabled, autoBanEnabled,
                secondVerifierEnabled, requireCorroborationForTempBan, maxUniquePenalizedPlayersPerMinute,
                maxFalseBanBudgetPerWindow, falseBanBudgetWindowMillis, tempBanDurationMillis,
                minScoreForMitigation, minScoreForKick, correlationHalfLifeNanos, sameGroupCapMultiplier,
                secondVerifierWorkers, 0L);
    }

    public VerdictConfig {
        Objects.requireNonNull(armedChecks, "armedChecks");
        Set<String> cleaned = new LinkedHashSet<>();
        for (String check : armedChecks) {
            if (check == null || check.isBlank()) {
                throw new IllegalArgumentException("armedChecks must not contain blank check ids");
            }
            cleaned.add(check);
        }
        armedChecks = Set.copyOf(cleaned);
        if (maxUniquePenalizedPlayersPerMinute < 1) {
            throw new IllegalArgumentException("maxUniquePenalizedPlayersPerMinute must be >= 1");
        }
        if (maxFalseBanBudgetPerWindow < 0) {
            throw new IllegalArgumentException("maxFalseBanBudgetPerWindow must be >= 0");
        }
        if (falseBanBudgetWindowMillis < 1_000L) {
            throw new IllegalArgumentException("falseBanBudgetWindowMillis must be >= 1000 ms");
        }
        if (tempBanDurationMillis < 1_000L) {
            throw new IllegalArgumentException("tempBanDurationMillis must be >= 1000 ms");
        }
        if (!Double.isFinite(minScoreForMitigation) || minScoreForMitigation <= 0.0) {
            throw new IllegalArgumentException("minScoreForMitigation must be finite and > 0");
        }
        if (!Double.isFinite(minScoreForKick) || minScoreForKick <= 0.0) {
            throw new IllegalArgumentException("minScoreForKick must be finite and > 0");
        }
        if (correlationHalfLifeNanos < 1_000_000_000L) {
            throw new IllegalArgumentException("correlationHalfLifeNanos must be >= 1s");
        }
        if (!Double.isFinite(sameGroupCapMultiplier) || sameGroupCapMultiplier < 1.0) {
            throw new IllegalArgumentException("sameGroupCapMultiplier must be >= 1.0");
        }
        if (secondVerifierWorkers < 1) {
            throw new IllegalArgumentException("secondVerifierWorkers must be >= 1");
        }
        if (banWaveDelayMillis < 0L) {
            throw new IllegalArgumentException("banWaveDelayMillis must be >= 0");
        }
    }

    /** Conservative default configuration: every check stays SHADOW, zero player actions enabled. */
    public static VerdictConfig shadowDefaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Set<String> armedChecks = Set.of();
        private boolean mitigationsEnabled;
        private boolean hidesInformationEnabled;
        private boolean autoKickEnabled;
        private boolean autoBanEnabled;
        private boolean secondVerifierEnabled = true;
        private boolean requireCorroborationForTempBan = true;
        private int maxUniquePenalizedPlayersPerMinute = 5;
        private int maxFalseBanBudgetPerWindow = 1;
        private long falseBanBudgetWindowMillis = TimeUnit.DAYS.toMillis(7);
        private long tempBanDurationMillis = TimeUnit.HOURS.toMillis(24);
        private double minScoreForMitigation = 1.0;
        private double minScoreForKick = 3.0;
        private long correlationHalfLifeNanos = TimeUnit.SECONDS.toNanos(120);
        private double sameGroupCapMultiplier = 1.5;
        private int secondVerifierWorkers = 1;
        private long banWaveDelayMillis = 0L;

        public Builder armedChecks(Set<String> armedChecks) {
            this.armedChecks = armedChecks;
            return this;
        }

        public Builder mitigationsEnabled(boolean mitigationsEnabled) {
            this.mitigationsEnabled = mitigationsEnabled;
            return this;
        }

        public Builder hidesInformationEnabled(boolean hidesInformationEnabled) {
            this.hidesInformationEnabled = hidesInformationEnabled;
            return this;
        }

        public Builder autoKickEnabled(boolean autoKickEnabled) {
            this.autoKickEnabled = autoKickEnabled;
            return this;
        }

        public Builder autoBanEnabled(boolean autoBanEnabled) {
            this.autoBanEnabled = autoBanEnabled;
            return this;
        }

        public Builder secondVerifierEnabled(boolean secondVerifierEnabled) {
            this.secondVerifierEnabled = secondVerifierEnabled;
            return this;
        }

        public Builder requireCorroborationForTempBan(boolean requireCorroborationForTempBan) {
            this.requireCorroborationForTempBan = requireCorroborationForTempBan;
            return this;
        }

        public Builder maxUniquePenalizedPlayersPerMinute(int maxUniquePenalizedPlayersPerMinute) {
            this.maxUniquePenalizedPlayersPerMinute = maxUniquePenalizedPlayersPerMinute;
            return this;
        }

        public Builder maxFalseBanBudgetPerWindow(int maxFalseBanBudgetPerWindow) {
            this.maxFalseBanBudgetPerWindow = maxFalseBanBudgetPerWindow;
            return this;
        }

        public Builder falseBanBudgetWindowMillis(long falseBanBudgetWindowMillis) {
            this.falseBanBudgetWindowMillis = falseBanBudgetWindowMillis;
            return this;
        }

        public Builder tempBanDurationMillis(long tempBanDurationMillis) {
            this.tempBanDurationMillis = tempBanDurationMillis;
            return this;
        }

        public Builder minScoreForMitigation(double minScoreForMitigation) {
            this.minScoreForMitigation = minScoreForMitigation;
            return this;
        }

        public Builder minScoreForKick(double minScoreForKick) {
            this.minScoreForKick = minScoreForKick;
            return this;
        }

        public Builder correlationHalfLifeNanos(long correlationHalfLifeNanos) {
            this.correlationHalfLifeNanos = correlationHalfLifeNanos;
            return this;
        }

        public Builder sameGroupCapMultiplier(double sameGroupCapMultiplier) {
            this.sameGroupCapMultiplier = sameGroupCapMultiplier;
            return this;
        }

        public Builder secondVerifierWorkers(int secondVerifierWorkers) {
            this.secondVerifierWorkers = secondVerifierWorkers;
            return this;
        }

        public Builder banWaveDelayMillis(long banWaveDelayMillis) {
            this.banWaveDelayMillis = banWaveDelayMillis;
            return this;
        }

        public VerdictConfig build() {
            return new VerdictConfig(
                    armedChecks,
                    mitigationsEnabled,
                    hidesInformationEnabled,
                    autoKickEnabled,
                    autoBanEnabled,
                    secondVerifierEnabled,
                    requireCorroborationForTempBan,
                    maxUniquePenalizedPlayersPerMinute,
                    maxFalseBanBudgetPerWindow,
                    falseBanBudgetWindowMillis,
                    tempBanDurationMillis,
                    minScoreForMitigation,
                    minScoreForKick,
                    correlationHalfLifeNanos,
                    sameGroupCapMultiplier,
                    secondVerifierWorkers,
                    banWaveDelayMillis);
        }
    }
}
