package app.freerouting.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Routing threads run one step below normal priority, and the threads they start inherit it. */
class StoppableThreadPriorityTest {

  @Test
  void routingThreadsAndTheirWorkersRunBelowNormal() throws InterruptedException {
    AtomicInteger worker = new AtomicInteger();
    StoppableThread job =
        new StoppableThread() {
          @Override
          protected void threadAction() {
            Thread child = new Thread(() -> worker.set(Thread.currentThread().getPriority()));
            child.start();
            try {
              child.join();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        };
    assertEquals(StoppableThread.WORKER_PRIORITY, job.getPriority());
    assertTrue(StoppableThread.WORKER_PRIORITY < Thread.NORM_PRIORITY);
    job.start();
    job.join();
    assertEquals(StoppableThread.WORKER_PRIORITY, worker.get());
  }
}
