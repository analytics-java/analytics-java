package com.jiuzhilan.analytics.internal;

public final class AnalyticsVersion {
	private AnalyticsVersion() {
		throw new AssertionError("No instances allowed.");
	}

	public static String get() {
		return "1.0.1";
	}
}
