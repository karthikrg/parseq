package com.linkedin.parseq.guava;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.linkedin.parseq.BaseTask;
import com.linkedin.parseq.Context;
import com.linkedin.parseq.Task;
import com.linkedin.parseq.promise.Promise;
import com.linkedin.parseq.promise.SettablePromise;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Utility methods to convert between Parseq {@link Task} and Guava's {@link ListenableFuture}.
 */
public class ListenableFutureUtil {

  private static final Logger LOGGER = LoggerFactory.getLogger(ListenableFutureUtil.class);

  private ListenableFutureUtil() {
    // Prevent instantiation.
  }

  public static <T> Task<T> fromListenableFuture(ListenableFuture<T> future) {
    // Setup cancellation propagation from Task -> ListenableFuture.
    final ListenableFutureBridgedTask<T> task = new ListenableFutureBridgedTask<T>(future);

    // Setup forward event propagation ListenableFuture -> Task.
    SettablePromise<T> promise = task.getSettableDelegate();
    Runnable callbackRunnable = () -> {
      if (promise.isDone()) {
        boolean isPromiseFailed = promise.isFailed();
        LOGGER.warn("ListenableFuture callback triggered but ParSeq already done. "
                + "Future is done: {}, "
                + "Future is cancelled: {}"
                + "Promise is failed:{}"
            + (isPromiseFailed? " Promise hold error: {}" : "Promise hold data:{}"),
            future.isDone(),
            future.isCancelled(),
            isPromiseFailed,
            isPromiseFailed ? promise.getError(): promise.get()
            );
        return;
      }
      try {
        final T value = future.get();
        promise.done(value);
      } catch (CancellationException ex) {
        task.cancel(ex);
      } catch (ExecutionException ex) {
        promise.fail(ex.getCause());
      } catch (Exception | Error ex) {
        promise.fail(ex);
      }
    };
    future.addListener(callbackRunnable, MoreExecutors.directExecutor());

    return task;
  }

  public static <T> ListenableFuture<T> toListenableFuture(Task<T> task) {
    // Setup cancellation propagation from ListenableFuture -> Task.
    SettableFuture<T> listenableFuture = new SettableFuture<T>() {
      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
        return super.cancel(mayInterruptIfRunning) && task.cancel(new CancellationException());
      }

      @Override
      public boolean setException(Throwable ex) {
        if (!task.isDone() && ex instanceof CancellationException) {
          task.cancel((CancellationException) ex);
        }
        return super.setException(ex);
      }
    };

    // Setup forward event propagation Task -> ListenableFuture.
    task.addListener(promise -> {
      if (!promise.isFailed()) {
        listenableFuture.set(promise.get());
      }
      else {
        if (promise.getError() instanceof com.linkedin.parseq.CancellationException) {
          listenableFuture.cancel(true);
        } else {
          listenableFuture.setException(promise.getError());
        }
      }
    });

    return listenableFuture;
  }

  /**
   * A private helper class to assist toListenableFuture(), by overriding some methods to make them public.
   *
   * @param <T> The Settable future's type.
   */
  @VisibleForTesting
  static class SettableFuture<T> extends AbstractFuture<T> {
    @Override
    public boolean set(T value) {
      return super.set(value);
    }

    @Override
    public boolean setException(Throwable throwable) {
      return super.setException(throwable);
    }
  }

  @VisibleForTesting
  static class ListenableFutureBridgedTask<T> extends BaseTask<T> {

    private final ListenableFuture<T> _future;

    ListenableFutureBridgedTask(ListenableFuture<T> future) {
      super("fromListenableFuture: " + Task._taskDescriptor.getDescription(future.getClass().getName()));
      _future = future;
    }

    @Override
    public boolean cancel(Exception rootReason) {
      // <BaseTask>.cancel()'s result indicates whether cancel() successfully trigger state transition to "CANCELLED"
      // And we should only cancel GRPC future when the transition was conducted.
      boolean shouldCancelTask = super.cancel(rootReason);
      if (shouldCancelTask && !_future.isCancelled()) {
        boolean futureCancelResult = _future.cancel(true);
        if (!futureCancelResult) {
          LOGGER.warn("Unexpected: GRPC future was not cancelled but new attempt to cancel also failed.");
        }
      }
      return shouldCancelTask;
    }

    @Override
    protected Promise<? extends T> run(Context context) throws Throwable {
      return getDelegate();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Exposed as public for access from {@link ListenableFutureUtil#fromListenableFuture(ListenableFuture)}</p>
     */
    @Override
    public SettablePromise<T> getSettableDelegate() {
      return super.getSettableDelegate();
    }
  }
}
