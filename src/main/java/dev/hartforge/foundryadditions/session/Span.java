package dev.hartforge.foundryadditions.session;

/** one [start, end] interval, epoch ms utc. end == null means still open. */
public final class Span {

    public final long start;
    public Long end;

    public Span(long start, Long end) {
        this.start = start;
        this.end = end;
    }

    public boolean open() {
        return end == null;
    }
}
