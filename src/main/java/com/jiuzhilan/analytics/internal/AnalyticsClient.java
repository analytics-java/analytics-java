package com.jiuzhilan.analytics.internal;

import static com.jiuzhilan.analytics.Log.Level.DEBUG;
import static com.jiuzhilan.analytics.Log.Level.ERROR;
import static com.jiuzhilan.analytics.Log.Level.VERBOSE;

import com.google.gson.Gson;
import com.jiuzhilan.analytics.Callback;
import com.jiuzhilan.analytics.Log;
import com.jiuzhilan.analytics.http.EventService;
import com.jiuzhilan.analytics.http.UploadResponse;
import com.jiuzhilan.analytics.messages.Batch;
import com.jiuzhilan.analytics.messages.Message;
import com.jiuzhilan.backo.Backo;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import retrofit2.Call;
import retrofit2.Response;

public class AnalyticsClient {
	private static final Map<String, ?> CONTEXT;
	private static final int MESSAGE_QUEUE_MAX_BYTE_SIZE = 1024 * 32;

	static {
		Map<String, String> library = new LinkedHashMap<>();
		library.put("name", "analytics-java");
		library.put("version", AnalyticsVersion.get());
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("library", Collections.unmodifiableMap(library));
		CONTEXT = Collections.unmodifiableMap(context);
	}

	private final String writeKey;
	private final BlockingQueue<Message> messageQueue;
	private final EventService service;
	private final int size;
	private final int maximumRetries;
	private final Log log;
	private final List<Callback> callbacks;
	private final ExecutorService networkExecutor;
	private final ExecutorService looperExecutor;
	private final ScheduledExecutorService flushScheduler;
	private final AtomicBoolean isShutDown;

	public static AnalyticsClient create(String writeKey, EventService eventService, int flushQueueSize, long flushIntervalInMillis,
			int maximumRetries, Log log, ThreadFactory threadFactory, ExecutorService networkExecutor,
			List<Callback> callbacks) {
		return new AnalyticsClient(writeKey, new LinkedBlockingQueue<Message>(), eventService, flushQueueSize,
				flushIntervalInMillis, maximumRetries, log, threadFactory, networkExecutor, callbacks,
				new AtomicBoolean(false));
	}

	AnalyticsClient(String writeKey, BlockingQueue<Message> messageQueue, EventService service, int maxQueueSize,
			long flushIntervalInMillis, int maximumRetries, Log log, ThreadFactory threadFactory,
			ExecutorService networkExecutor, List<Callback> callbacks, AtomicBoolean isShutDown) {
		this.writeKey = writeKey;
		this.messageQueue = messageQueue;
		this.service = service;
		this.size = maxQueueSize;
		this.maximumRetries = maximumRetries;
		this.log = log;
		this.callbacks = callbacks;
		this.looperExecutor = Executors.newSingleThreadExecutor(threadFactory);
		this.networkExecutor = networkExecutor;
		this.isShutDown = isShutDown;

		Looper looper = new Looper();
		looper.setWriteKey(this.writeKey);
		looperExecutor.submit(looper);

		flushScheduler = Executors.newScheduledThreadPool(1, threadFactory);
		flushScheduler.scheduleAtFixedRate(new Runnable() {
			@Override
			public void run() {
				flush();
			}
		}, flushIntervalInMillis, flushIntervalInMillis, TimeUnit.MILLISECONDS);
	}

	public int messageSizeInBytes(Message message) {
		Gson gson = new Gson();
		String stringifiedMessage = gson.toJson(message);
		return stringifiedMessage.length();
	}

	private Boolean isBackPressured(List<Message> messages) {
		int messageQueueSize = 0;

		for (Message message : messages) {
			messageQueueSize += messageSizeInBytes(message);
		}

		return messageQueueSize >= MESSAGE_QUEUE_MAX_BYTE_SIZE;
	}

	public void enqueue(Message message) {
		if (message != StopMessage.STOP && isShutDown.get()) {
			log.print(ERROR, "Attempt to enqueue a message when shutdown has been called %s.", message);
			return;
		}

		try {
			messageQueue.put(message);
		} catch (InterruptedException e) {
			log.print(ERROR, e, "Interrupted while adding message %s.", message);
			Thread.currentThread().interrupt();
		}
	}

	public void flush() {
		if (!isShutDown.get()) {
			enqueue(FlushMessage.POISON);
		}
	}

	public void shutdown() {
		if (isShutDown.compareAndSet(false, true)) {
			final long start = System.currentTimeMillis();

			// first let's tell the system to stop
			enqueue(StopMessage.STOP);

			// we can shutdown the flush scheduler without worrying
			flushScheduler.shutdownNow();

			shutdownAndWait(looperExecutor, "looper");
			shutdownAndWait(networkExecutor, "network");

			log.print(VERBOSE, "Analytics client shut down in %s ms", (System.currentTimeMillis() - start));
		}
	}

	public void shutdownAndWait(ExecutorService executor, String name) {
		try {
			executor.shutdown();
			final boolean executorTerminated = executor.awaitTermination(1, TimeUnit.SECONDS);

			log.print(VERBOSE, "%s executor %s.", name, executorTerminated ? "terminated normally" : "timed out");
		} catch (InterruptedException e) {
			log.print(ERROR, e, "Interrupted while stopping %s executor.", name);
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Looper runs on a background thread and takes messages from the queue. Once it
	 * collects enough messages, it triggers a flush.
	 */
	class Looper implements Runnable {
		private boolean stop;
		private String writeKey;

		public Looper() {
			this.stop = false;
		}

		public void setWriteKey(String writeKey) {
			this.writeKey = writeKey;
		}

		@Override
		public void run() {
			List<Message> messages = new ArrayList<>();
			try {
				while (!stop) {
					Message message = messageQueue.take();

					if (message == StopMessage.STOP) {
						log.print(VERBOSE, "Stopping the Looper");
						stop = true;
					} else if (message == FlushMessage.POISON) {
						if (!messages.isEmpty()) {
							log.print(VERBOSE, "Flushing messages.");
						}
					} else {
						messages.add(message);
					}

					Boolean isBlockingSignal = message == FlushMessage.POISON || message == StopMessage.STOP;
					Boolean isOverflow = messages.size() >= size;

					if (isBackPressured(messages)) {
						log.print(VERBOSE, "Maximum storage size has been hit. Flushing");
						isOverflow = true;
					}

					if (!messages.isEmpty() && (isOverflow || isBlockingSignal)) {
						String messageId = UUID.randomUUID().toString();
						
						Batch batch = Batch.create(this.writeKey, messageId, CONTEXT, messages);
						log.print(VERBOSE, "Batching %s message(s) into batch %s.", messages.size(), batch.sequence());
						networkExecutor.submit(BatchUploadTask.create(AnalyticsClient.this, batch, maximumRetries));
						messages = new ArrayList<>();
					}
				}
			} catch (InterruptedException e) {
				log.print(DEBUG, "Looper interrupted while polling for messages.");
				Thread.currentThread().interrupt();
			}
			log.print(VERBOSE, "Looper stopped");
		}
	}

	static class BatchUploadTask implements Runnable {
		private static final Backo BACKO = Backo.builder() //
				.base(TimeUnit.SECONDS, 15) //
				.cap(TimeUnit.HOURS, 1) //
				.jitter(1) //
				.build();
		private static final int MAX_ATTEMPTS = 50; // Max 50 hours ~ 2 days

		private final AnalyticsClient client;
		private final Backo backo;
		final Batch batch;
		private final int maxRetries;

		static BatchUploadTask create(AnalyticsClient client, Batch batch, int maxRetries) {
			return new BatchUploadTask(client, BACKO, batch, maxRetries);
		}

		BatchUploadTask(AnalyticsClient client, Backo backo, Batch batch, int maxRetries) {
			this.client = client;
			this.batch = batch;
			this.backo = backo;
			this.maxRetries = maxRetries;
		}

		private void notifyCallbacksWithException(Batch batch, Exception exception) {
			for (Message message : batch.batch()) {
				for (Callback callback : client.callbacks) {
					callback.failure(message, exception);
				}
			}
		}

		/**
		 * Returns {@code true} to indicate a batch should be retried. {@code false}
		 * otherwise.
		 */
		boolean upload() {
			client.log.print(VERBOSE, "Uploading batch %s.", batch.sequence());

			try {
				Call<UploadResponse> call = client.service.upload(batch);
				Response<UploadResponse> response = call.execute();

				if (response.isSuccessful()) {
					client.log.print(VERBOSE, "Uploaded batch %s.", batch.sequence());

					for (Message message : batch.batch()) {
						for (Callback callback : client.callbacks) {
							callback.success(message);
						}
					}

					return false;
				}

				int status = response.code();
				if (is5xx(status)) {
					client.log.print(DEBUG, "Could not upload batch %s due to server error. Retrying.",
							batch.sequence());
					return true;
				} else if (status == 429) {
					client.log.print(DEBUG, "Could not upload batch %s due to rate limiting. Retrying.",
							batch.sequence());
					return true;
				}
				client.log.print(DEBUG, "Could not upload batch %s[%d]. Giving up.", batch.sequence(), status);

				notifyCallbacksWithException(batch, new IOException("HTTP Error"));

				return false;
			} catch (IOException error) {
				client.log.print(DEBUG, error, "Could not upload batch %s. Retrying.", batch.sequence());

				return true;
			} catch (Exception exception) {
				client.log.print(DEBUG, exception, "Could not upload batch %s. Giving up.", batch.sequence());

				notifyCallbacksWithException(batch, exception);

				return false;
			}
		}

		@Override
		public void run() {
			int attempt = 0;
			for (; attempt <= maxRetries; attempt++) {
				boolean retry = upload();
				if (!retry)
					return;
				try {
					backo.sleep(attempt);
				} catch (InterruptedException e) {
					client.log.print(DEBUG, "Thread interrupted while backing off for batch %s.", batch.sequence());
					return;
				}
			}

			client.log.print(ERROR, "Could not upload batch %s. Retries exhausted.", batch.sequence());
			notifyCallbacksWithException(batch, new IOException(Integer.toString(attempt) + " retries exhausted"));
		}

		private static boolean is5xx(int status) {
			return status >= 500 && status < 600;
		}
	}
}
