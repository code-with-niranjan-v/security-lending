package com.example.fix_adapter_service.observability;

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.HashMap;
import java.util.Map;

public final class OutboxTrace {
  private static final W3CTraceContextPropagator PROPAGATOR = W3CTraceContextPropagator.getInstance();
  private static final TextMapGetter<Map<String, String>> GETTER = new TextMapGetter<>() {
    @Override public Iterable<String> keys(Map<String, String> carrier) {
      return carrier.keySet();
    }

    @Override public String get(Map<String, String> carrier, String key) {
      return carrier.get(key);
    }
  };

  private OutboxTrace() {}

  public static String capture() {
    var headers = new HashMap<String, String>();
    PROPAGATOR.inject(Context.current(), headers, Map::put);
    return headers.get("traceparent");
  }

  public static Scope restore(String traceParent) {
    return traceParent == null ? Context.current().makeCurrent()
        : PROPAGATOR.extract(Context.current(), Map.of("traceparent", traceParent), GETTER)
            .makeCurrent();
  }
}
