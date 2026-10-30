package com.vchat.aop;

import com.vchat.config.VchatProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.TimeUnit;

/**
 * Performance monitor for the service layer, without touching any service code:
 *  - records a Micrometer timer "vchat.service" (tags: method, outcome) -> /actuator/metrics/vchat.service,
 *    exportable to Prometheus/OTLP;
 *  - logs every call at DEBUG and anything slower than vchat.monitoring.slow-call-ms at WARN;
 *  - logs unexpected exceptions once, with the method name (expected 4xx errors are not logged as errors).
 * Arguments are never logged: they include passwords and message contents.
 *
 * Spring AOP is proxy-based, so calls from one method to another inside the same bean are not intercepted.
 */
@Aspect
@Component
@Order(2)
public class MonitoringAspect {
    private static final Logger log = LoggerFactory.getLogger(MonitoringAspect.class);

    private final MeterRegistry registry;
    private final long slowMs;

    public MonitoringAspect(MeterRegistry registry, VchatProperties props) {
        this.registry = registry;
        this.slowMs = props.monitoring() == null ? 500 : props.monitoring().slowCallMs();
    }

    @Pointcut("execution(public * com.vchat.service..*(..))")
    void serviceLayer() {}

    @Around("serviceLayer()")
    public Object monitor(ProceedingJoinPoint pjp) throws Throwable {
        String method = pjp.getSignature().getDeclaringType().getSimpleName() + "." + pjp.getSignature().getName();
        long start = System.nanoTime();
        String outcome = "success";
        try {
            return pjp.proceed();
        } catch (ResponseStatusException e) {
            outcome = String.valueOf(e.getStatusCode().value());
            throw e;
        } catch (IllegalArgumentException e) {
            outcome = "400";
            throw e;
        } catch (Throwable t) {
            outcome = t.getClass().getSimpleName();
            log.error("{} failed: {}", method, t.toString());
            throw t;
        } finally {
            long nanos = System.nanoTime() - start;
            Timer.builder("vchat.service").description("Service-layer call latency")
                    .tag("method", method).tag("outcome", outcome)
                    .register(registry).record(nanos, TimeUnit.NANOSECONDS);
            long ms = nanos / 1_000_000;
            if (ms >= slowMs) log.warn("Slow call {} took {} ms ({})", method, ms, outcome);
            else if (log.isDebugEnabled()) log.debug("{} took {} ms ({})", method, ms, outcome);
        }
    }
}
