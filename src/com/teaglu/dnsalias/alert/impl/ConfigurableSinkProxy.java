package com.teaglu.dnsalias.alert.impl;

import java.util.Set;
import java.util.TreeSet;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.configure.secret.SecretProvider;
import com.teaglu.dnsalias.alert.AlertCategory;
import com.teaglu.dnsalias.alert.AlertSink;
import com.teaglu.dnsalias.alert.AlertSinkFactory;

public class ConfigurableSinkProxy implements AlertSink {
	private AlertSink current= null;
	
	private Set<AlertCategory> categories= new TreeSet<>();
	
	public ConfigurableSinkProxy() {
		// Default to everything
		for (AlertCategory category : AlertCategory.class.getEnumConstants()) {
			categories.add(category);
		}
	}
	
	@Override
	public void sendAlert(
			@NonNull AlertCategory category,
			@NonNull String message,
			@Nullable Exception exception)
	{
		AlertSink target= null;
		synchronized (this) {
			if (categories.contains(category)) {
				target= current;
			}
		}
		
		if (target != null) {
			target.sendAlert(category, message, exception);
		}
	}
	
	public void configure(
			@Nullable Composite config,
			@NonNull SecretProvider secretProvider) throws SchemaException
	{
		AlertSink newSink= null;
		
		if (config != null) {
			newSink= AlertSinkFactory.Create(config);
		}
		
		synchronized (this) {
			current= newSink;
		}
	}
}
