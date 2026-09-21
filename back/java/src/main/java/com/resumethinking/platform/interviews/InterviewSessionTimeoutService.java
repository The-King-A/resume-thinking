package com.resumethinking.platform.interviews;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

/** Moves abandoned interview work to a terminal state so clients cannot poll forever. */
@Component
public class InterviewSessionTimeoutService {
    private static final String MODEL_UNAVAILABLE = "INTERVIEW_MODEL_UNAVAILABLE";
    private static final List<InterviewSession.State> IN_FLIGHT = List.copyOf(EnumSet.of(
            InterviewSession.State.QUESTION_GENERATING, InterviewSession.State.ANSWER_ANALYZING));

    private final InterviewSessionRepository sessions;
    private final Clock clock;
    private final Duration lease;

    @Autowired
    public InterviewSessionTimeoutService(InterviewSessionRepository sessions, Clock clock,
                                          @Value("${app.interview-session-processing-lease:PT5M}") Duration lease) {
        this.sessions = sessions;
        this.clock = clock;
        if (lease == null || lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("interview session processing lease must be positive");
        }
        this.lease = lease;
    }

    @Scheduled(fixedDelayString = "${app.interview-session-timeout-check-delay:60000}")
    @Transactional
    public int timeoutStaleSessions() {
        Instant cutoff = clock.instant().minus(lease);
        int timedOut = 0;
        for (InterviewSession session : sessions.findInFlightUpdatedBeforeForUpdate(IN_FLIGHT, cutoff)) {
            if (IN_FLIGHT.contains(session.getState()) && session.getUpdatedAt().isBefore(cutoff)) {
                session.markFailed(MODEL_UNAVAILABLE, clock.instant());
                sessions.saveAndFlush(session);
                timedOut++;
            }
        }
        return timedOut;
    }
}
