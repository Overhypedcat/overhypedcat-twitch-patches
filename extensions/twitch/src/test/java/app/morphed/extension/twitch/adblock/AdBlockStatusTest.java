package app.morphed.extension.twitch.adblock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** The line under the Ad blocker switch: what it does for this stream, live, and its problem until the next stream. */
public class AdBlockStatusTest {
    private final List<AdBlockStatus.Status> told = new ArrayList<>();

    @Before
    public void fresh() {
        AdBlockStatus.reset();
    }

    @Test
    public void followsTheStreamAsItHappens() {
        AdBlockStatus.Listener listener = told::add;
        AdBlockStatus.addListener(listener);
        try {
            AdBlockStatus.watching();
            assertEquals("No ads so far", AdBlockStatus.status().line());
            AdBlockStatus.blocking("480p");
            assertEquals("Blocking an ad, 480p meanwhile", AdBlockStatus.status().line());
            AdBlockStatus.blocking("480p"); // every poll of the break: told once
            AdBlockStatus.blocking(null);
            assertEquals("Cutting an ad out (no ad-free stream)", AdBlockStatus.status().line());
            AdBlockStatus.breakOver();
            assertEquals("Ad break over", AdBlockStatus.status().line());
            assertEquals(4, told.size());
        } finally {
            AdBlockStatus.removeListener(listener);
        }
    }

    @Test
    public void aProblemShowsEveryTimeUntilTheNextStream() {
        AdBlockStatus.watching();
        AdBlockStatus.problem(Problem.BACKUPS_UNAVAILABLE, "usher returned 403");
        AdBlockStatus.Status s = AdBlockStatus.status();
        assertEquals("Ad blocker couldn't get ad-free streams (E2)", s.line());
        assertEquals(Problem.BACKUPS_UNAVAILABLE.description + ": usher returned 403", s.problemDetail);
        AdBlockStatus.blocking(null);
        assertEquals("the problem stays shown while the ad is handled", "Ad blocker couldn't get ad-free streams (E2)",
                AdBlockStatus.status().line());
        AdBlockStatus.problem(Problem.UNRECOGNIZED_AD_MARKERS, "");
        assertEquals("a newer one replaces it", "Ad blocker needs an update (E1)", AdBlockStatus.status().line());
        assertEquals(Problem.UNRECOGNIZED_AD_MARKERS.description, AdBlockStatus.status().problemDetail);
        AdBlockStatus.watching();
        assertNull("another stream starts clean", AdBlockStatus.status().problem);
        assertEquals("No ads so far", AdBlockStatus.status().line());
    }
}
