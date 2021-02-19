package com.jiuzhilan.analytics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jiuzhilan.analytics.gson.AutoValueAdapterFactory;
import com.jiuzhilan.analytics.gson.ISO8601DateAdapter;
import com.jiuzhilan.analytics.http.EventService;
import com.jiuzhilan.analytics.internal.AnalyticsClient;
import com.jiuzhilan.analytics.internal.AnalyticsVersion;
import com.jiuzhilan.analytics.messages.Message;
import com.jiuzhilan.analytics.messages.MessageBuilder;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * The entry point into the Event API for Java library.
 *
 * <p>
 * Analytics for Java will automatically batch events and upload it periodically
 * to Event's servers for you. 
 *
 * <p>
 * This class is the main entry point into the client API. Use {@link #builder}
 * to construct your own instances.
 *
 */
public class Analytics {
	private final AnalyticsClient client;
	private final List<MessageTransformer> messageTransformers;
	private final List<MessageInterceptor> messageInterceptors;
	private final Log log;

	private final static CheckNameInterceptor DEFAULT_INTERCEPTOR = new CheckNameInterceptor();

	Analytics(AnalyticsClient client, List<MessageTransformer> messageTransformers,
			List<MessageInterceptor> messageInterceptors, Log log) {
		this.client = client;
		this.messageTransformers = messageTransformers;
		this.messageInterceptors = messageInterceptors;
		this.log = log;
	}

	/**
	 * Start building an {@link Analytics} instance.
	 */
	public static Builder builder(String writeKey) {
		return new Builder(writeKey);
	}

	/** Enqueue the given message to be uploaded to Event API. */
	public void enqueue(MessageBuilder builder) {
		for (MessageTransformer messageTransformer : messageTransformers) {
			boolean shouldContinue = messageTransformer.transform(builder);
			if (!shouldContinue) {
				log.print(Log.Level.VERBOSE, "Skipping message %s.", builder);
				return;
			}
		}
		Message message = builder.build();
		for (MessageInterceptor messageInterceptor : messageInterceptors) {
			message = messageInterceptor.intercept(message);
			if (message == null) {
				log.print(Log.Level.VERBOSE, "Skipping message %s.", builder);
				return;
			}
		}
		client.enqueue(message);
	}

	/** Flush events in the message queue. */
	public void flush() {
		client.flush();
	}

	/** Stops this instance from processing further requests. */
	public void shutdown() {
		client.shutdown();
	}

	/** Fluent API for creating {@link Analytics} instances. */
	public static class Builder {
		private static final HttpUrl DEFAULT_ENDPOINT = HttpUrl.parse("https://event.jiuzhilan.com");
		private static final String DEFAULT_USER_AGENT = "analytics-java/" + AnalyticsVersion.get();

		private final String writeKey;
		private OkHttpClient client;
		private Log log;
		public HttpUrl endpoint;
		public HttpUrl uploadURL;
		private String userAgent = DEFAULT_USER_AGENT;
		private List<MessageTransformer> messageTransformers;
		private List<MessageInterceptor> messageInterceptors;
		private ExecutorService networkExecutor;
		private ThreadFactory threadFactory;
		private int flushQueueSize;
		private int maximumFlushAttempts;
		private long flushIntervalInMillis;
		private List<Callback> callbacks;

		Builder(String writeKey) {
			if (writeKey == null || writeKey.trim().length() == 0) {
				throw new NullPointerException("writeKey cannot be null or empty.");
			}
			this.writeKey = writeKey;
		}

		/** Set a custom networking client. */
		public Builder client(OkHttpClient client) {
			if (client == null) {
				throw new NullPointerException("Null client");
			}
			this.client = client;
			return this;
		}

		/** Configure debug logging mechanism. By default, nothing is logged. */
		public Builder log(Log log) {
			if (log == null) {
				throw new NullPointerException("Null log");
			}
			this.log = log;
			return this;
		}

		/**
		 * Set an endpoint (host only) that this client should upload events to. 
		 * Uses {@code
		 * https://event.jiuzhilan.com} by default.
		 */
		public Builder endpoint(String endpoint) {
			if (endpoint == null || endpoint.trim().length() == 0) {
				throw new NullPointerException("endpoint cannot be null or empty.");
			}
			this.endpoint = HttpUrl.parse(endpoint);
			return this;
		}

		/**
		 * Set an endpoint (host and prefix) that this client should upload events to.
		 * Uses {@code
		 * https://event.jiuzhilan.com/v1} by default.
		 */
		public Builder setUploadURL(String uploadURL) {
			if (uploadURL == null || uploadURL.trim().length() == 0) {
				throw new NullPointerException("endpoint cannot be null or empty.");
			}
			this.uploadURL = HttpUrl.parse(uploadURL);
			return this;
		}

		/** Sets a user agent for HTTP requests. */
		public Builder userAgent(String userAgent) {
			if (userAgent == null || userAgent.trim().length() == 0) {
				throw new NullPointerException("userAgent cannot be null or empty.");
			}
			this.userAgent = userAgent;
			return this;
		}

		/** Add a {@link MessageTransformer} for transforming messages. */
		@Beta
		public Builder messageTransformer(MessageTransformer transformer) {
			if (transformer == null) {
				throw new NullPointerException("Null transformer");
			}
			if (messageTransformers == null) {
				messageTransformers = new ArrayList<>();
			}
			if (messageTransformers.contains(transformer)) {
				throw new IllegalStateException("MessageTransformer is already registered.");
			}
			messageTransformers.add(transformer);
			return this;
		}

		/** Add a {@link MessageInterceptor} for intercepting messages. */
		@Beta
		public Builder messageInterceptor(MessageInterceptor interceptor) {
			if (interceptor == null) {
				throw new NullPointerException("Null interceptor");
			}
			if (messageInterceptors == null) {
				messageInterceptors = new ArrayList<>();
			}
			if (messageInterceptors.contains(interceptor)) {
				throw new IllegalStateException("MessageInterceptor is already registered.");
			}
			messageInterceptors.add(interceptor);
			return this;
		}

		/** Set the queueSize at which flushes should be triggered. */
		@Beta
		public Builder flushQueueSize(int flushQueueSize) {
			if (flushQueueSize < 1) {
				throw new IllegalArgumentException("flushQueueSize must not be less than 1.");
			}
			this.flushQueueSize = flushQueueSize;
			return this;
		}

		/** Set the interval at which the queue should be flushed. */
		@Beta
		public Builder flushInterval(long flushInterval, TimeUnit unit) {
			long flushIntervalInMillis = unit.toMillis(flushInterval);
			if (flushIntervalInMillis < 1000) {
				throw new IllegalArgumentException("flushInterval must not be less than 1 second.");
			}
			this.flushIntervalInMillis = flushIntervalInMillis;
			return this;
		}

		/** Set how many retries should happen before getting exhausted */
		public Builder retries(int maximumRetries) {
			if (maximumRetries < 1) {
				throw new IllegalArgumentException("retries must be at least 1");
			}
			this.maximumFlushAttempts = maximumRetries;
			return this;
		}

		/** Set the {@link ExecutorService} on which all HTTP requests will be made. */
		public Builder networkExecutor(ExecutorService networkExecutor) {
			if (networkExecutor == null) {
				throw new NullPointerException("Null networkExecutor");
			}
			this.networkExecutor = networkExecutor;
			return this;
		}

		/** Set the {@link ThreadFactory} used to create threads. */
		@Beta
		public Builder threadFactory(ThreadFactory threadFactory) {
			if (threadFactory == null) {
				throw new NullPointerException("Null threadFactory");
			}
			this.threadFactory = threadFactory;
			return this;
		}

		/** Add a {@link Callback} to be notified when an event is processed. */
		public Builder callback(Callback callback) {
			if (callback == null) {
				throw new NullPointerException("Null callback");
			}
			if (callbacks == null) {
				callbacks = new ArrayList<>();
			}
			if (callbacks.contains(callback)) {
				throw new IllegalStateException("Callback is already registered.");
			}
			callbacks.add(callback);
			return this;
		}

		/** Use a {@link Plugin} to configure the builder. */
		@Beta
		public Builder plugin(Plugin plugin) {
			if (plugin == null) {
				throw new NullPointerException("Null plugin");
			}
			plugin.configure(this);
			return this;
		}

		/** Create a {@link Analytics} client. */
		public Analytics build() {
			Gson gson = new GsonBuilder() //
					.registerTypeAdapterFactory(new AutoValueAdapterFactory()) //
					.registerTypeAdapter(Date.class, new ISO8601DateAdapter()) //
					.create();

			if (endpoint == null) {
				endpoint = DEFAULT_ENDPOINT;

				if (uploadURL != null) {
					endpoint = uploadURL;
				}
			}

			if (client == null) {
				client = Platform.get().defaultClient();
			}
			if (log == null) {
				log = Log.NONE;
			}
			if (flushIntervalInMillis == 0) {
				flushIntervalInMillis = Platform.get().defaultFlushIntervalInMillis();
			}
			if (flushQueueSize == 0) {
				flushQueueSize = Platform.get().defaultFlushQueueSize();
			}
			if (messageTransformers == null) {
				messageTransformers = Collections.emptyList();
			} else {
				messageTransformers = Collections.unmodifiableList(messageTransformers);
			}
			if (messageInterceptors == null) {
				messageInterceptors = new ArrayList<MessageInterceptor>();
			}
			messageInterceptors.add(0, DEFAULT_INTERCEPTOR); // naming convention check
			messageInterceptors = Collections.unmodifiableList(messageInterceptors);

			if (networkExecutor == null) {
				networkExecutor = Platform.get().defaultNetworkExecutor();
			}
			if (threadFactory == null) {
				threadFactory = Platform.get().defaultThreadFactory();
			}
			if (callbacks == null) {
				callbacks = Collections.emptyList();
			} else {
				callbacks = Collections.unmodifiableList(callbacks);
			}

			HttpLoggingInterceptor interceptor = new HttpLoggingInterceptor(new HttpLoggingInterceptor.Logger() {
				@Override
				public void log(String message) {
					log.print(Log.Level.VERBOSE, "%s", message);
				}
			});

			interceptor.setLevel(HttpLoggingInterceptor.Level.BASIC);

			client = client.newBuilder().addInterceptor(new AnalyticsRequestInterceptor(writeKey, userAgent))
					.addInterceptor(interceptor).build();

			Retrofit restAdapter = new Retrofit.Builder().addConverterFactory(GsonConverterFactory.create(gson))
					.baseUrl(endpoint).client(client).build();

			EventService eventService = restAdapter.create(EventService.class);

			AnalyticsClient analyticsClient = AnalyticsClient.create(this.writeKey, eventService, flushQueueSize,
					flushIntervalInMillis, maximumFlushAttempts, log, threadFactory, networkExecutor, callbacks);
			return new Analytics(analyticsClient, messageTransformers, messageInterceptors, log);
		}
	}
}
