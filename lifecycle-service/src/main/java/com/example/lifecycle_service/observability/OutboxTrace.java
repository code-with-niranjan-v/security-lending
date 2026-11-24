package com.example.lifecycle_service.observability;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import java.util.HashMap;
import java.util.Map;

public final class OutboxTrace {
  private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
  private OutboxTrace() {}

  public static String capture() {
    var headers = new HashMap<String, String>();
    PROPAGATOR.inject(Context.current(), headers, Map::put);
    return headers.get("traceparent");
  }
}
