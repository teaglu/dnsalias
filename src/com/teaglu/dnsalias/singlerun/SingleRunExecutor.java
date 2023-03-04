package com.teaglu.dnsalias.singlerun;

import org.eclipse.jdt.annotation.NonNull;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.configure.exception.ConfigException;
import com.teaglu.configure.secret.SecretProvider;
import com.teaglu.dnsalias.alert.AlertSink;

/**
 * SingleRunExecutor
 *
 * Interface to run a configuration a single time.
 */
public interface SingleRunExecutor {
	/**
	 * run
	 * 
	 * Run a configuration one time.
	 * 
	 * @param config					Configuration
	 * @param secretProvider			Secret provider
	 * @param alertSink					Sink for alerts
	 * 
	 * @throws SchemaException
	 * @throws ConfigException
	 */
	public void run(
			@NonNull Composite config,
			@NonNull SecretProvider secretProvider,
			@NonNull AlertSink alertSink) throws SchemaException, ConfigException;
}