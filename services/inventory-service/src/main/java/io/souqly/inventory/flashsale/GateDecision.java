package io.souqly.inventory.flashsale;

import java.util.List;

import io.souqly.inventory.reservation.ReservationLine;

/**
 * Outcome of claiming flash-sale tokens. {@code soldOutSku} and {@code tokensLeft} describe
 * the line that was refused and are set only when not admitted; {@code newlyClaimed} lists the
 * lines whose tokens this call took (not earlier attempts of the same order).
 */
public record GateDecision(boolean admitted, String soldOutSku, long tokensLeft,
        List<ReservationLine> newlyClaimed) {

    static GateDecision admit(List<ReservationLine> newlyClaimed) {
        return new GateDecision(true, null, 0, List.copyOf(newlyClaimed));
    }

    static GateDecision reject(String soldOutSku, long tokensLeft) {
        return new GateDecision(false, soldOutSku, tokensLeft, List.of());
    }
}
