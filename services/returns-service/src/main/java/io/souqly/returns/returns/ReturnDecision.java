package io.souqly.returns.returns;

import java.time.Instant;
import java.util.UUID;

/** One party's decision at one stage, kept for the audit trail and for disputes. */
public record ReturnDecision(UUID returnId, String stage, String actorId, String decision, String note,
        Instant decidedAt) {
}
