package com.example.platform;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.scheduling.annotation.Scheduled;

@Aspect
public class ScheduledLeaseAspect {

  private final WorkCoordinator coordinator;

  public ScheduledLeaseAspect(WorkCoordinator coordinator) {
    this.coordinator = coordinator;
  }

  @Around("@annotation(scheduled)")
  public Object run(ProceedingJoinPoint invocation, Scheduled scheduled) {
    coordinator.lead(
      invocation.getSignature().getDeclaringTypeName() +
        "." +
        invocation.getSignature().getName(),
      () -> {
        try {
          invocation.proceed();
        } catch (RuntimeException | Error exception) {
          throw exception;
        } catch (Throwable exception) {
          throw new IllegalStateException(exception);
        }
      }
    );
    return null;
  }
}
