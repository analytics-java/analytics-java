package com.jiuzhilan.analytics.http;

import com.google.auto.value.AutoValue;
import com.jiuzhilan.analytics.gson.AutoGson;

@AutoValue
@AutoGson
public abstract class UploadResponse {
	public abstract boolean success();
}
