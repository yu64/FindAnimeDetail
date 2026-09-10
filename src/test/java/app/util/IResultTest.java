package app.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class IResultTest {

  @Test
  void castsReturnTheSameInstanceAndPreservePayloadTypes() {
    IResult<Integer, String> success = IResult.ok(42);
    IResult<Integer, String> failure = IResult.err("invalid");

    IResult.Ok<Integer, String> castSuccess = success.ok();
    IResult.Err<Long, String> castFailure = failure.err();

    assertSame(success, castSuccess);
    assertEquals(42, castSuccess.val());
    assertSame(failure, castFailure);
    assertEquals("invalid", castFailure.val());
  }

  @Test
  void castsRejectTheOppositeVariant() {
    assertThrows(ClassCastException.class, () -> IResult.err("invalid").ok());
    assertThrows(ClassCastException.class, () -> IResult.ok(42).err());
  }

  @Test
  void chainsSuccessAndStopsAfterFailure() {
    IResult<Integer, String> result = IResult.<String, String>ok("123")
      .map(Integer::parseInt)
      .flatMap(value -> IResult.<Integer, String>err("invalid: " + value))
      .map(value -> fail("map must not run after failure"))
      .flatMap(value -> fail("flatMap must not run after failure"));

    assertEquals(IResult.err("invalid: 123"), result);
  }

  @Test
  void transformsOnlyTheActiveBranch() {
    IResult<Integer, String> success = IResult.ok(3);
    IResult<Integer, String> failure = IResult.err("invalid");

    assertEquals(IResult.ok(6), success.flatMap(value -> IResult.ok(value * 2)));
    assertEquals(IResult.ok(3), success.mapErr(error -> fail("unexpected error")));
    assertEquals(IResult.err(7), failure.mapErr(error -> error.length()));
    assertEquals("value: 3", success.fold(value -> "value: " + value, error -> fail(error)));
    assertEquals("invalid", failure.fold(value -> fail("unexpected success"), error -> error));
  }

  @Test
  void rejectsNullPayloadsAndNullMappedResults() {
    assertThrows(NullPointerException.class, () -> IResult.ok(null));
    assertThrows(NullPointerException.class, () -> IResult.err(null));
    assertThrows(NullPointerException.class, () -> IResult.ok(1).map(value -> null));
    assertThrows(NullPointerException.class, () -> IResult.ok(1).flatMap(value -> null));
    assertThrows(NullPointerException.class, () -> IResult.err("error").mapErr(error -> null));
  }

  @Test
  void propagatesCallbackExceptions() {
    var exception = new IllegalArgumentException("callback failed");
    assertSame(exception, assertThrows(IllegalArgumentException.class,
      () -> IResult.ok(1).map(value -> { throw exception; })));
  }
}
