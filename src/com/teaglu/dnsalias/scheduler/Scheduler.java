package com.teaglu.dnsalias.scheduler;

import org.eclipse.jdt.annotation.NonNull;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.configure.exception.ConfigException;
import com.teaglu.configure.secret.SecretProvider;

/**
 * Scheduler
 * 
 * An object that runs processors, can be configured, and can started and stopped.
 *
 */
public interface Scheduler {
	/**
	 * configure
	 * 
	 * Apply a new configuration to the scheduler.
	 * 
	 * @param config					New configuration
	 * @param secretProvider			New secret provider
	 * 
	 * @throws SchemaException			Configuration schema issues
	 * @throws ConfigException			Configuration issues
	 */
	public void configure(
			@NonNull Composite config,
			@NonNull SecretProvider secretProvider) throws SchemaException, ConfigException;
	
	/**
	 * start
	 * 
	 * Start the scheduler, creating any background threads and resources required
	 */
	public void start();
	
	/**
	 * stop
	 * 
	 * Stop the scheduler, stopping any background threads and freeing any resources
	 */
	public void stop();
}
