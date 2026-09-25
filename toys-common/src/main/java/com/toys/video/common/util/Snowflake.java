package com.toys.video.common.util;

/** 雪花 ID:41 位毫秒时间戳 + 5 位机房 + 5 位机器 + 12 位序列。 */
public class Snowflake {

    private static final long EPOCH = 1735689600000L; // 2025-01-01
    private static final long WORKER_ID_BITS = 5L;
    private static final long DATA_CENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATA_CENTER_ID = ~(-1L << DATA_CENTER_ID_BITS);
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATA_CENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATA_CENTER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private final long workerId;
    private final long dataCenterId;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public Snowflake(long workerId, long dataCenterId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId must be in [0," + MAX_WORKER_ID + "]");
        }
        if (dataCenterId < 0 || dataCenterId > MAX_DATA_CENTER_ID) {
            throw new IllegalArgumentException("dataCenterId must be in [0," + MAX_DATA_CENTER_ID + "]");
        }
        this.workerId = workerId;
        this.dataCenterId = dataCenterId;
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            // 时钟回拨:等待追平
            timestamp = lastTimestamp;
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (dataCenterId << DATA_CENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
