package app.morphed.extension.twitch.patches;

import java.util.Set;

import app.morphed.extension.twitch.adblock.Problem;

/**
 * Runs at the end of the app's check for reasons not to play a client-side ad. Adding a reason makes
 * the viewer ineligible, so the app never starts an ad it plays itself.
 */
@SuppressWarnings("unused")
public final class ClientAdsPatch {
    static final String TURBO = "VAST_AD_INELIGIBLE_TURBO";

    private ClientAdsPatch() {
    }

    /** Adds the Turbo reason to {@code reasons}; {@code reasonType} is the app's obfuscated reasons enum. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void markIneligible(Set reasons, Class reasonType) {
        try {
            Object reason = turboReason(reasonType);
            if (reason == null) {
                StreamAdsPatch.PROBLEMS.report(Problem.CLIENT_ADS_NOT_BLOCKED, "no reasons in " + reasonType);
                return;
            }
            // Only a set that was empty meant an ad would have played; otherwise it was not eligible anyway.
            boolean wouldHavePlayed = reasons.isEmpty();
            reasons.add(reason);
            StreamAdsPatch.LOGGER.log("Client-side ad check: answered ineligible (" + reason + ")"
                    + (wouldHavePlayed ? ", an ad was due" : ""));
            if (wouldHavePlayed) StreamAdsPatch.adBlocked();
        } catch (Throwable t) {
            StreamAdsPatch.PROBLEMS.report(Problem.CLIENT_ADS_NOT_BLOCKED, t.toString());
        }
    }

    /** The Turbo reason, or else any reason of the enum: any reason at all means no ad. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object turboReason(Class reasonType) {
        if (reasonType == null || !reasonType.isEnum()) return null;
        try {
            return Enum.valueOf(reasonType, TURBO);
        } catch (IllegalArgumentException renamed) {
            Object[] all = reasonType.getEnumConstants();
            return all == null || all.length == 0 ? null : all[0];
        }
    }
}
