package com.harshalpatil.ecommerce.platform.messaging;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Map;

/** {@link TraceHeaders} backed by whichever Micrometer tracer the service has configured. */
class MicrometerTraceHeaders implements TraceHeaders {

    private final ObjectProvider<Tracer> tracer;
    private final ObjectProvider<Propagator> propagator;

    MicrometerTraceHeaders(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public Map<String, String> current() {
        Tracer currentTracer = tracer.getIfAvailable();
        Propagator currentPropagator = propagator.getIfAvailable();
        if (currentTracer == null || currentPropagator == null) {
            return Map.of();
        }
        Span span = currentTracer.currentSpan();
        if (span == null) {
            return Map.of();
        }
        Map<String, String> headers = new HashMap<>();
        currentPropagator.inject(span.context(), headers, Map::put);
        return headers;
    }
}
