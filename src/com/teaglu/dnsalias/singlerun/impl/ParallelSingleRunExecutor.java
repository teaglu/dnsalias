package com.teaglu.dnsalias.singlerun.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.composite.exception.UndefinedOptionException;
import com.teaglu.configure.exception.ConfigException;
import com.teaglu.configure.secret.SecretProvider;
import com.teaglu.dnsalias.alert.AlertCategory;
import com.teaglu.dnsalias.alert.AlertSink;
import com.teaglu.dnsalias.alert.AlertSinkFactory;
import com.teaglu.dnsalias.alias.Alias;
import com.teaglu.dnsalias.alias.impl.CompositeAlias;
import com.teaglu.dnsalias.dns.DnsProvider;
import com.teaglu.dnsalias.dns.DnsProviderFactory;
import com.teaglu.dnsalias.processor.Processor;
import com.teaglu.dnsalias.processor.dnsjava.DnsJavaProcessor;
import com.teaglu.dnsalias.processor.exception.DestinationException;
import com.teaglu.dnsalias.processor.exception.SourceException;
import com.teaglu.dnsalias.singlerun.SingleRunExecutor;

/**
 * ParallelSingleRunExecutor
 * 
 * Implementation of a SingleRunExecutor that runs aliases in parallel.
 * 
 */
public class ParallelSingleRunExecutor implements SingleRunExecutor {
	private ParallelSingleRunExecutor() {}
	
	public static @NonNull SingleRunExecutor Create() {
		return new ParallelSingleRunExecutor();
	}
	
	// We run all the processors in parallel - this encapsulates a job in flight
	private static class Job implements Runnable {
		private static int threadNoCounter= 1;
		
		private @NonNull AlertSink alertSink;
		private @NonNull Processor processor;
		private @NonNull Thread thread;
		
		private Job(@NonNull Processor processor, @NonNull AlertSink alertSink) {
			this.processor= processor;
			this.alertSink= alertSink;
			
			this.thread= new Thread(this, "oneshot-" + (threadNoCounter++));
		}
		
		@Override
		public void run() {
			try {
				processor.process(alertSink);
			} catch (SourceException sourceException) {
				alertSink.sendAlert(
						AlertCategory.LOOKUP_EXCEPTION,
						"An exception occurred reading source data for " +
						processor.toString(),
						sourceException);
			} catch (DestinationException destinationException) {
				alertSink.sendAlert(
						AlertCategory.UPDATE_EXCEPTION,
						"An exception occurred in the DNS provider for " +
						processor.toString(),
						destinationException);
			} catch (Exception generalException) {
				alertSink.sendAlert(
						AlertCategory.PROCESSING_EXCEPTION,
						"An exception occurred processing an alias",
						generalException);
			}
		}
		
		private void start() {
			thread.start();
		}
		
		private void join() {
			try {
				thread.join();
			} catch (InterruptedException _ie) {
			}
		}
	}
	
	@Override
	public void run(
			@NonNull Composite config,
			@NonNull SecretProvider secretProvider,
			@NonNull AlertSink callerAlertSink) throws SchemaException, ConfigException
	{
		Composite alertsConfig= config.getOptionalObject("alerts");
		AlertSink configuredAlertSink= (alertsConfig == null) ? null :
				AlertSinkFactory.getInstance().create(alertsConfig);
		
		AlertSink alertShim= new AlertSink() {
			@Override
			public void sendAlert(
					@NonNull AlertCategory category,
					@NonNull String message,
					@Nullable Exception exception)
			{
				callerAlertSink.sendAlert(category, message, exception);
				
				if (configuredAlertSink != null) {
					configuredAlertSink.sendAlert(category, message, exception);
				}
			}
		};
		
		Map<String, DnsProvider> providerMap= new TreeMap<>();
		
		Composite providersConfig= config.getRequiredObject("providers");
		for (Map.Entry<@NonNull String, @NonNull Composite> configEntry
				: providersConfig.getObjectMap())
		{
			@SuppressWarnings("null")
			String name= configEntry.getKey();
			
			@SuppressWarnings("null")
			Composite providerConfig= configEntry.getValue();	
			
			DnsProvider provider= DnsProviderFactory.
					getInstance().
					create(providerConfig, secretProvider);
			
			providerMap.put(name, provider);
		}

		List<Job> jobs= new ArrayList<>(8);
		
		Composite aliasesConfig= config.getRequiredObject("aliases");
		for (Map.Entry<@NonNull String, @NonNull Composite> configEntry
				: aliasesConfig.getObjectMap())
		{
			@SuppressWarnings("null")
			Composite aliasConfig= configEntry.getValue();
			
			String providerName= aliasConfig.getRequiredString("provider");
			DnsProvider provider= providerMap.get(providerName);
			
			if (provider == null) {
				throw new UndefinedOptionException(
						"DNS provider " + providerName + " is not defined.");
			}
			
			Alias alias= CompositeAlias.Create(aliasConfig);
			Processor processor= DnsJavaProcessor.Create(alias, provider);
			
			jobs.add(new Job(processor, alertShim));
		}

		for (Job job : jobs) {
			job.start();
		}
		
		for (Job job : jobs) {
			job.join();
		}
	}
}
