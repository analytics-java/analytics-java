package com.jiuzhilan.analytics.http;

import com.jiuzhilan.analytics.messages.Batch;

import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.POST;

/** REST interface for the Event API. */
public interface EventService {
	@POST("v1")
	Call<UploadResponse> upload(@Body Batch batch);
}
