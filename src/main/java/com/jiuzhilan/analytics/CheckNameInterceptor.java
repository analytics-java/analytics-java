package com.jiuzhilan.analytics;

import java.util.regex.Pattern;

import com.jiuzhilan.analytics.MessageInterceptor.Typed;
import com.jiuzhilan.analytics.messages.AliasMessage;
import com.jiuzhilan.analytics.messages.GroupMessage;
import com.jiuzhilan.analytics.messages.IdentifyMessage;
import com.jiuzhilan.analytics.messages.PageMessage;
import com.jiuzhilan.analytics.messages.ScreenMessage;
import com.jiuzhilan.analytics.messages.TrackMessage;

public class CheckNameInterceptor extends Typed {
	private static Pattern pattern = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");
	
	public CheckNameInterceptor() {
		
	}
	
	private boolean checkName(String name) {
		return pattern.matcher(name).find();
	}
	
	@Override
	AliasMessage alias(AliasMessage message) {
		return message;
	}

	@Override
	GroupMessage group(GroupMessage message) {
		return message;
	}

	@Override
	IdentifyMessage identify(IdentifyMessage message) {
		return message;
	}

	@Override
	ScreenMessage screen(ScreenMessage message) {
		for (String key : message.properties().keySet()) {
			if (!checkName(key)) {
				return null;
			}
		}
		return message;
	}

	@Override
	PageMessage page(PageMessage message) {
		for (String key : message.properties().keySet()) {
			if (!checkName(key)) {
				return null;
			}
		}
		return message;
	}

	@Override
	TrackMessage track(TrackMessage message) {
		if (!checkName(message.event())) {
			return null;
		}
		
		for (String key : message.properties().keySet()) {
			if (!checkName(key)) {
				return null;
			}
		}
		return message;
	}

}
