package dev.hartforge.foundryadditions.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * an audit line per request from day one, so the day a write verb is added
 * there is already a journal. unix sockets expose no peer identity to java
 * (the critique is explicit about that), so the line carries what exists:
 * verb, outcome, duration.
 */
public final class OpsAudit {

    private static final Logger AUDIT = LoggerFactory.getLogger("foundryadditions.ops.audit");

    public static void log(String verb, boolean ok, String error, long durMs) {
        AUDIT.info("verb={} ok={} error={} durMs={}", verb, ok, error == null ? "-" : error, durMs);
    }

    private OpsAudit() {}
}
