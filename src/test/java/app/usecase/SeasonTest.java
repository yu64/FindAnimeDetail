package app.usecase;

import static org.junit.jupiter.api.Assertions.*;

import app.usecase.Season.Name;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SeasonTest {

  @Test
  void mapsMonthsToSeasonsByConvention() {
    assertEquals(new Season(2027, Name.WINTER), Season.of(LocalDate.parse("2027-03-31")));
    assertEquals(new Season(2027, Name.SPRING), Season.of(LocalDate.parse("2027-04-01")));
    assertEquals(new Season(2027, Name.SUMMER), Season.of(LocalDate.parse("2027-09-30")));
    assertEquals(new Season(2026, Name.AUTUMN), Season.of(LocalDate.parse("2026-10-26")));
  }

  @Test
  void parsesLabelsAndRelativeSeasons() {
    var today = LocalDate.parse("2026-10-26");
    assertEquals(Optional.of(new Season(2027, Name.WINTER)), Season.parse(" 2027-Winter ", today));
    assertEquals(Optional.of(new Season(2026, Name.AUTUMN)), Season.parse("now", today));
    assertEquals(Optional.of(new Season(2027, Name.WINTER)), Season.parse("NEXT", today));
    for(String invalid : new String[]{"2027-fall", "27-winter", "2027winter", "", "later"}) {
      assertTrue(Season.parse(invalid, today).isEmpty(), invalid);
    }
  }

  @Test
  void movesAcrossYearsAndFormatsLikeAnnict() {
    var autumn = new Season(2026, Name.AUTUMN);
    assertEquals(new Season(2027, Name.WINTER), autumn.next());
    assertEquals(autumn, autumn.next().previous());
    assertTrue(autumn.next().isAfter(autumn));
    assertFalse(autumn.isAfter(autumn));
    assertEquals("2026-autumn", autumn.toString());
  }
}
