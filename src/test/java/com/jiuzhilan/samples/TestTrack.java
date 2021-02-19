package com.jiuzhilan.samples;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.jiuzhilan.analytics.Analytics;
import com.jiuzhilan.analytics.Log;
import com.jiuzhilan.analytics.messages.IdentifyMessage;
import com.jiuzhilan.analytics.messages.TrackMessage;

public class TestTrack {

	public static void main(String[] args) {
		Analytics analytics = Analytics.builder("test_java")
				.endpoint("http://n.com")
				.log(new Log() {

					@Override
					public void print(Level level, String format, Object... args) {
						System.out.println("[" + level + "] " + String.format(format, args));
					}

					@Override
					public void print(Level level, Throwable error, String format, Object... args) {
						this.print(level, format, args);
						error.printStackTrace();
					}
					
				})
				.flushQueueSize(5)
				.retries(1)
				.build();

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("title", "Snow Fall");
		properties.put("subtitle", "The Avalance at Tunnel Creek");
		properties.put("author", "John Branch");
		
		Map<String, Object> context = new LinkedHashMap<>();
		Map<String, Object> contextPage = new LinkedHashMap<String, Object>() {
			{
				this.put("path", "/academy/");
				this.put("url", "https://jiuzhilan.com/academy");
			}
		};
		context.put("userAgent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_11_0) AppleWebKit/537.36");
		context.put("ip", "108.0.78.21");
		context.put("page", contextPage);
		
		analytics.enqueue(TrackMessage.builder("Article_Bookmarked")
				.userId("f4ca124298")
				.anonymousId(UUID.randomUUID().toString())
				.properties(properties)
				.context(properties));

		analytics.enqueue(IdentifyMessage.builder()
				.userId("f4ca124298")
				.anonymousId(UUID.randomUUID().toString())
				.context(properties));
		analytics.flush();
		analytics.shutdown();

		System.out.println("aaaaaaaaaaaaaaaaaaaa");
	}

}
