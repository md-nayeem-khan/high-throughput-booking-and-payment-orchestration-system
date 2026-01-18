package com.booking.orchestration.common.exception;

/** Base exception for all booking orchestration system exceptions */
public class BookingOrchestractionException extends RuntimeException {

  public BookingOrchestractionException(String message) {
    super(message);
  }

  public BookingOrchestractionException(String message, Throwable cause) {
    super(message, cause);
  }
}
