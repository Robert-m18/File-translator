package com.example.filetranslator;

import io.github.bucket4j.TimeMeter;

import java.time.Duration;

class MockTimeMeter implements TimeMeter {
    private long nanos = 0;

    void addSeconds(long seconds) {
        nanos += Duration.ofSeconds(seconds).toNanos();
    }

    @Override
    public long currentTimeNanos() {
        return nanos;
    }

    @Override
    public boolean isWallClockBased() {
        return true;
    }
}
