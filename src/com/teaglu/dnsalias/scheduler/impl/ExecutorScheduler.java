package com.teaglu.dnsalias.scheduler.impl;

import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jdt.annotation.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.composite.exception.UndefinedOptionException;
import com.teaglu.configure.exception.ConfigException;
import com.teaglu.configure.secret.SecretProvider;
import com.teaglu.dnsalias.alert.AlertCategory;
import com.teaglu.dnsalias.alert.impl.ConfigurableSinkProxy;
import com.teaglu.dnsalias.alias.Alias;
import com.teaglu.dnsalias.alias.impl.CompositeAlias;
import com.teaglu.dnsalias.dns.DnsProvider;
import com.teaglu.dnsalias.dns.DnsProviderFactory;
import com.teaglu.dnsalias.processor.Processor;
import com.teaglu.dnsalias.processor.dnsjava.DnsJavaProcessor;
import com.teaglu.dnsalias.processor.exception.LookupException;
import com.teaglu.dnsalias.processor.exception.UpdateException;
import com.teaglu.dnsalias.scheduler.Scheduler;

/**
 * ExecutorScheduler
 * 
 * Implementation of Scheduler that uses a priority queue based on next task execution, a
 * single dispatch thread that monitors the queue, and an executor service for actual task
 * execution.
 * 
 * This also handles configuration because they hit the same structures, which is kind of a
 * single responsibility problem - we can loop back to that later.
 *
 */
public class ExecutorScheduler implements Scheduler {
	private static final Logger log= LoggerFactory.getLogger(ExecutorScheduler.class);

	// This is the minimum amount of time out we'll schedule another event.  If events are
	// occuring more often than this, they'll be scheduled this far out.  This is normally the
	// result of timeouts taking so long they exceed the TTL
	private static final long MINIMUM_SCHEDULE_MSEC= 100;

	// This is the minimum amount we'll sleep to wait for an event.  It's assumed that if the
	// wait time is less than this it's pointless to go into a wait state - just execute the
	// task.
	private static final long SCHEDULE_SLACK_MSEC= 20;

	// If we pull a head-of-line event and it's more than this far in the task, we're getting
	// stolen CPU or something, or we have an algorithm problem.
	private static final long SCHEDULE_WARN_MSEC= 500;
	
	private @NonNull ConfigurableSinkProxy alertSinkProxy= new ConfigurableSinkProxy();
	
	private static class ProviderEntry {
		private @NonNull DnsProvider provider;
		private boolean active;
		
		private ProviderEntry(@NonNull DnsProvider provider) {
			this.provider= provider;
		}
	}
	
	private class AliasEntry implements Comparable<AliasEntry>, Runnable  {
		private long next;
		
		@SuppressWarnings("unused")
		private @NonNull String name;
		private @NonNull Processor processor;
		
		private AliasEntry(@NonNull String name, @NonNull Processor processor) {
			this.name= name;
			this.processor= processor;
		}
		
		private boolean active;

		@Override
		public int compareTo(AliasEntry o) {
			if (next < o.next) {
				return -1;
			} else if (next > o.next) {
				return 1;
			} else {
				return 0;
			}
		}

		@Override
		public void run() {
			long checkStart= System.currentTimeMillis();
			
			long recheckSeconds= 300;
			try {
				recheckSeconds= processor.process(alertSinkProxy);
			} catch (LookupException lookupException) {
				alertSinkProxy.sendAlert(
						AlertCategory.LOOKUP_EXCEPTION,
						"An exception occurred reading source data for " + processor.toString(),
						lookupException);
			} catch (UpdateException lookupException) {
				alertSinkProxy.sendAlert(
						AlertCategory.UPDATE_EXCEPTION,
						"An exception occurred the DNS provider for " + processor.toString(),
						lookupException);
			} catch (Exception generalException) {
				// This shouldn't really happen except for unchecked stuff
				log.error("Error processing alias", generalException);
				
				alertSinkProxy.sendAlert(
						AlertCategory.PROCESSING_EXCEPTION,
						"An exception occurred processing an alias",
						generalException);
			}
			
			if (active) {
				//log.info("Rechecking " + name + " after " + recheckSeconds + " seconds");
				
				next= checkStart + (recheckSeconds * 1000);
				
				long earliest= System.currentTimeMillis() + MINIMUM_SCHEDULE_MSEC;
				if (next < earliest) {
					log.warn("Check for " + processor.toString() +
							" is too fast - pushing out for " + MINIMUM_SCHEDULE_MSEC +
							" milliseconds");
					
					next= earliest;
				}

				queue(this);
			}
		}
	}
	
	private final Map<String, ProviderEntry> providerMap= new TreeMap<>();
	private final Map<String, AliasEntry> aliasMap= new TreeMap<>();
	
	public void configure(
			@NonNull Composite config,
			@NonNull SecretProvider secretProvider) throws SchemaException, ConfigException
	{
		Composite alertConfig= config.getOptionalObject("alerts");
		alertSinkProxy.configure(alertConfig, secretProvider);
		
		Composite providersConfig= config.getRequiredObject("providers");
		configureProviders(providersConfig, secretProvider);
		
		Composite aliasesConfig= config.getRequiredObject("aliases");
		configureAliases(aliasesConfig);
	}
	
	public void configureProviders(
			@NonNull Composite config,
			@NonNull SecretProvider secretProvider) throws SchemaException, ConfigException
	{
		synchronized (providerMap) {
			for (Map.Entry<String, ProviderEntry> entry : providerMap.entrySet()) {
				entry.getValue().active= false;
			}
			
			for (Map.Entry<@NonNull String, @NonNull Composite> configEntry
					: config.getObjectMap())
			{
				@SuppressWarnings("null")
				String name= configEntry.getKey();
				
				@SuppressWarnings("null")
				Composite providerConfig= configEntry.getValue();			
				
				ProviderEntry entry= providerMap.get(name);
				if (entry == null) {
					entry= new ProviderEntry(
							DnsProviderFactory.Create(providerConfig, secretProvider));
					
					providerMap.put(name, entry);
				}
				entry.active= true;
			}
			
			Iterator<Map.Entry<String, ProviderEntry>> iter= providerMap.entrySet().iterator();
			
			while (iter.hasNext()) {
				Map.Entry<String, ProviderEntry> entry= iter.next();
				
				if (!entry.getValue().active) {
					iter.remove();
				}
			}
		}
	}
	private void configureAliases(@NonNull Composite config) throws SchemaException {
		synchronized (aliasMap) {
			for (Map.Entry<String, AliasEntry> entry : aliasMap.entrySet()) {
				entry.getValue().active= false;
			}
			
			long checkTime= System.currentTimeMillis();
			
			for (Map.Entry<@NonNull String, @NonNull Composite> configEntry
					: config.getObjectMap())
			{
				@SuppressWarnings("null")
				String name= configEntry.getKey();
				
				@SuppressWarnings("null")
				Composite aliasConfig= configEntry.getValue();
				
				AliasEntry entry= aliasMap.get(name);
				if (entry == null) {
					String providerName= aliasConfig.getRequiredString("provider");
					ProviderEntry providerEntry= null;
					synchronized (providerMap) {
						providerEntry= providerMap.get(providerName);
					}
					
					if (providerEntry == null) {
						throw new UndefinedOptionException(
								"DNS provider " + providerName + " is not defined.");
					}
					
					Alias alias= CompositeAlias.Create(aliasConfig);
					Processor processor= DnsJavaProcessor.Create(alias, providerEntry.provider);
					
					entry= new AliasEntry(name, processor);
					entry.next= checkTime;
					entry.active= true;
					
					// Spread over time
					checkTime+= 5000;
					
					aliasMap.put(name, entry);
					
					queue(entry);
				} else {
					entry.active= true;
				}
			}
			
			Iterator<Map.Entry<String, AliasEntry>> iter= aliasMap.entrySet().iterator();
			
			while (iter.hasNext()) {
				Map.Entry<String, AliasEntry> entry= iter.next();
				if (!entry.getValue().active) {
					iter.remove();
				}
			}
		}
	}
	
	private final PriorityQueue<AliasEntry> runQueue= new PriorityQueue<>();
	private final Lock runLock= new ReentrantLock();
	private final Condition runWake= runLock.newCondition();
	private boolean runFlag;
	private Thread runThread;
	
	private ExecutorService executorService= null;
	private AtomicInteger threadCounter= new AtomicInteger(1);
	
	private void queue(@NonNull AliasEntry entry) {
		runLock.lock();
		try {
			runQueue.add(entry);
			
			if (runQueue.peek() == entry) {
				runWake.signal();
			}
		} finally {
			runLock.unlock();
		}
	}
	
	private void runloop() {
		long lastEvent= 0;
		
		for (boolean run= true; run; ) {
			AliasEntry alias= null;
			long now= System.currentTimeMillis();
			long waitUntil= 0;
			
			synchronized (runQueue) {
				if (!runQueue.isEmpty()) {
					alias= runQueue.peek();
					if (alias.next <= (now + SCHEDULE_SLACK_MSEC)) {
						runQueue.poll();
						
						long lag= now - alias.next;
						if (lag > SCHEDULE_WARN_MSEC) {
							log.warn(
									"Scheduler dispatch thread missed head-of-line event by " +
									lag + " milliseconds.");
						}
					} else {
						waitUntil= alias.next;
						alias= null;
						
						//log.info("Waiting " + (waitUntil - now) +
						//		" milliseconds to send next event");
					}
				}
			}

			if (alias != null) {
				if (alias.next < lastEvent) {
					log.warn("Schedule events are arriving out of order");
				}
				lastEvent= alias.next;
				
				executorService.execute(alias);
			}
			
			runLock.lock();
			try {
				if (runFlag && (alias == null)) {
					try {
						if (waitUntil > 0) {
							runWake.awaitUntil(new Date(waitUntil));
						} else {
							runWake.await();
						}
					} catch (InterruptedException _ie) {
					}
				}
				
				run= runFlag;
			} finally {
				runLock.unlock();
			}
		}
	}

	public void start() {
		executorService= Executors.newCachedThreadPool(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable runnable) {
				return new Thread(runnable,
						"scheduler-worker-" + threadCounter.getAndIncrement());
			}
		});
		
		synchronized (aliasMap) {
			long startTime= System.currentTimeMillis();
			
			for (Map.Entry<String, AliasEntry> entry : aliasMap.entrySet()) {
				AliasEntry alias= entry.getValue();
				alias.next= startTime;

				// Don't cause a thundering herd
				startTime+= 5000;
			}
		}
		
		runThread= new Thread(new Runnable() {
			@Override
			public void run() {
				runloop();
			}
			
		}, "scheduler-dispatcher");
		
		runFlag= true;
		runThread.start();
	}

	public void stop() {
		runLock.lock();
		try {
			runFlag= false;
			runWake.signal();
		} finally {
			runLock.unlock();
		}
		
		try {
			runThread.join();
		} catch (InterruptedException _ie) {
		}
		
		runLock.lock();
		try {
			runQueue.clear();
		} finally {
			runLock.unlock();
		}
		
		executorService.shutdown();
		try {
			executorService.awaitTermination(30_000, TimeUnit.MILLISECONDS);
		} catch (InterruptedException _e) {
		}
		executorService= null;
	}
}
