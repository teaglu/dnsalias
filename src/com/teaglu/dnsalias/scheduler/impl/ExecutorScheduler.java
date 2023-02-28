package com.teaglu.dnsalias.scheduler.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
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
import com.teaglu.dnsalias.processor.exception.SourceException;
import com.teaglu.dnsalias.processor.exception.DestinationException;
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

	// If we pull a head-of-line event and it's more than this far in the past, we're getting
	// stolen CPU or something, or we have an algorithm problem.
	private static final long SCHEDULE_WARN_MSEC= 500;

	// For exceptions that could be transient network errors, allow this many consecutive
	// exceptions before sending an alert.  This might need to be configurable in the future.
	private static final int ALLOWED_CONSECUTIVE_EXCEPTIONS= 1;
	
	private @NonNull ConfigurableSinkProxy alertSinkProxy= new ConfigurableSinkProxy();

	private final MessageDigest nodeDigest;
	private final Base64.Encoder base64Encoder;
	
	private ExecutorScheduler() {
		try {
			nodeDigest= MessageDigest.getInstance("SHA-1");
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA not available");
		}
		base64Encoder= Base64.getEncoder();
	}
	
	public static @NonNull Scheduler Create() {
		return new ExecutorScheduler();
	}
	
	private @NonNull String getNodeDigest(@NonNull Composite node) {
		@SuppressWarnings("null")
		@NonNull String digest= base64Encoder.encodeToString(
				nodeDigest.digest(node.toString().getBytes(StandardCharsets.UTF_8)));
		
		return digest;
	}
	
	private static class ProviderEntry {
		private @NonNull String digest;
		private @NonNull DnsProvider provider;
		private boolean configDelete;
		
		private ProviderEntry(
				@NonNull String digest,
				@NonNull DnsProvider provider)
		{
			this.digest= digest;
			this.provider= provider;
		}
	}
	
	private class AliasEntry implements Comparable<AliasEntry>, Runnable  {
		private @NonNull String digest;
		
		// Next scheduled time for execution.  This is not locked because only modified after
		// creation by the object itself when called on an executor service thread, and that is
		// guaranteed to only be in-flight on one thread.
		private long next;

		// Used for messages during debugging
		private final @NonNull String name;

		// This is used to reference the provider node during configuration, to know if we're
		// pointing to a stale copy of the provider.
		private final @NonNull ProviderEntry providerEntry;
		
		// Processor to run
		private final @NonNull Processor processor;
		
		private AliasEntry(
				@NonNull String digest,
				@NonNull String name,
				@NonNull ProviderEntry providerEntry,
				@NonNull Processor processor)
		{
			this.digest= digest;
			this.name= name;
			this.providerEntry= providerEntry;
			this.processor= processor;
		}
		
		// Java heap removal doesn't have a good O(), so if items don't need
		// rescheduling they're just discarded the next time they get to the
		// root of the heap
		private boolean active;

		// This is used by the configuration routine to delete items that aren't present in the
		// configuration any more.  It's separate from the active flag so that there isn't a race
		// condition between the configure processing and when the item might hit for scheduling.
		private boolean configDelete;

		// Count of consecutive exceptions - for things that are transient we want to give it 
		// a few times to avoid spurious alerts.
		private int consecutiveExceptions= 0;

		// Compare by next execution to order the priority queue.  The next member should
		// never be updated while the object is in the queue or the queue is inconsistent.
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

		// The run method of the entry is called when the dispatch thread removes it from
		// the heap and submits it to the executor service.  If it needs to be re-scheduled it
		// will re-submit itself
		@Override
		public void run() {
			// We base the next check on the TTL.  Starting that timer from the start of the check
			// not when it returns makes us error on the low side which we want, because TTL
			// guarantees a maximum.
			long checkStart= System.currentTimeMillis();
			
			// 300 is used in case of exception, so this is the amount of time to wait to retry
			// in the exception case.
			long recheckSeconds= 300;
			try {
				recheckSeconds= processor.process(alertSinkProxy);

				// Zero the counter
				consecutiveExceptions= 0;
			} catch (SourceException sourceException) {
				if (++consecutiveExceptions > ALLOWED_CONSECUTIVE_EXCEPTIONS) {
					alertSinkProxy.sendAlert(
							AlertCategory.LOOKUP_EXCEPTION,
							"An exception occurred reading source data for " +
							processor.toString(),
							sourceException);
				} else {
					log.warn(
							"Ignoring lookup exception because it might be transient",
							sourceException);
				}
			} catch (DestinationException destinationException) {
				if (++consecutiveExceptions > ALLOWED_CONSECUTIVE_EXCEPTIONS) {
					alertSinkProxy.sendAlert(
							AlertCategory.UPDATE_EXCEPTION,
							"An exception occurred in the DNS provider for " +
							processor.toString(),
							destinationException);
				} else {
					log.warn(
							"Ignoring update exception because it might be transient",
							destinationException);
				}
			} catch (Exception generalException) {
				// This shouldn't really happen except for unchecked stuff
				log.error("Error processing alias", generalException);
				
				alertSinkProxy.sendAlert(
						AlertCategory.PROCESSING_EXCEPTION,
						"An exception occurred processing an alias",
						generalException);
			}

			// The active flag is synchronized on the alias entry - otherwise there could be
			// a race condition with the configuration update
			boolean localActive= false;
			synchronized (this) {
				localActive= active;
			}
			
			if (localActive) {
				next= checkStart + (recheckSeconds * 1000);

				// Enforce the minimum amount of time we're willing to reschedule.  We don't want
				// somebody giving us a TTL less than the amount that's already passed.
				//
				// Note - AWS application load balancers send back some bizarre and low TTL times.
				long earliest= System.currentTimeMillis() + MINIMUM_SCHEDULE_MSEC;
				if (next < earliest) {
					log.warn("Check for " + processor.toString() +
							" is too fast - pushing out for " + MINIMUM_SCHEDULE_MSEC +
							" milliseconds");
					
					next= earliest;
				}

				queue(this);
			} else {
				log.info("Alias node " + name + " version " + digest +
						" is not being rescheduled because it is no longer current.");
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
				entry.getValue().configDelete= true;
			}
			
			for (Map.Entry<@NonNull String, @NonNull Composite> configEntry
					: config.getObjectMap())
			{
				@SuppressWarnings("null")
				String name= configEntry.getKey();
				
				@SuppressWarnings("null")
				Composite providerConfig= configEntry.getValue();			
				
				String digest= getNodeDigest(providerConfig);
				
				ProviderEntry entry= providerMap.get(name);
				if (entry != null) {
					if (!digest.equals(entry.digest)) {
						// Node will be over-written in the map below.
						
						// Note - configDelete will be left set as true, which will be noticed
						// by the alias config, which will restart the alias if it references
						// a provider that is no longer current.
						
						log.info("Provider entry for " + name + " has changed.");
						entry= null;
					}
				}
				
				if (entry == null) {
					DnsProvider provider= DnsProviderFactory.
							getInstance().
							create(providerConfig, secretProvider);
					
					entry= new ProviderEntry(digest, provider);
					
					providerMap.put(name, entry);
				}
				entry.configDelete= false;
			}
			
			Iterator<Map.Entry<String, ProviderEntry>> iter= providerMap.entrySet().iterator();
			
			while (iter.hasNext()) {
				Map.Entry<String, ProviderEntry> entry= iter.next();
				
				if (entry.getValue().configDelete) {
					iter.remove();
				}
			}
		}
	}
	private void configureAliases(@NonNull Composite config) throws SchemaException {
		synchronized (aliasMap) {
			for (Map.Entry<String, AliasEntry> mapEntry : aliasMap.entrySet()) {
				mapEntry.getValue().configDelete= true;
			}
			
			long checkTime= System.currentTimeMillis();
			
			for (Map.Entry<@NonNull String, @NonNull Composite> configEntry
					: config.getObjectMap())
			{
				@SuppressWarnings("null")
				String name= configEntry.getKey();
				
				@SuppressWarnings("null")
				Composite aliasConfig= configEntry.getValue();
				
				String digest= getNodeDigest(aliasConfig);
				
				AliasEntry entry= aliasMap.get(name);
				if (entry != null) {
					if (!digest.equals(entry.digest)) {
						// The entry will be overwritten in the mapping, but will still be
						// referenced from the queue.  Flag it as in-active so that it won't
						// be rescheduled.
						synchronized (entry) {
							entry.active= false;
						}
						
						log.info("Alias entry for " + name + " has changed.");
						entry= null;
					} else if (entry.providerEntry.configDelete) {
						synchronized (entry) {
							entry.active= false;
						}
						
						log.info("Alias entry for " + name + " is being recreated because " +
								"it references a provider that has been changed.");
						entry= null;
					}
				}
				
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
					
					entry= new AliasEntry(digest, name, providerEntry, processor);
					entry.next= checkTime;
					entry.active= true;
					entry.configDelete= false;
					
					// We don't want an entire config's worth of checks spawning off at the same
					// time, so spread them out 1 second between each one.  Otherwise it's the
					// "thundering herd" problem.
					checkTime+= 1000;
					
					aliasMap.put(name, entry);
					
					queue(entry);
				} else {
					entry.configDelete= false;
				}
			}
			
			Iterator<Map.Entry<String, AliasEntry>> iter= aliasMap.entrySet().iterator();
			
			while (iter.hasNext()) {
				Map.Entry<String, AliasEntry> mapEntry= iter.next();
				AliasEntry entry= mapEntry.getValue();

				if (entry.configDelete) {
					iter.remove();
					
					synchronized (entry) {
						entry.active= false;
					}
				}
			}
		}
	}

	// Main scheduling queue ordered by the next event time
	private final PriorityQueue<AliasEntry> dispatchQueue= new PriorityQueue<>();
	
	// Lock and wake for the scheduling loop
	private final Lock dispatchLock= new ReentrantLock();
	private final Condition dispatchWake= dispatchLock.newCondition();
	
	// Global flag to keep running, locked by the lock above
	private boolean dispatchRun;
	
	// Dispatch thread
	private Thread dispatchThread;
	
	// Executor service used for the actual running of tasks
	private ExecutorService executorService= null;
	
	// Counter to set thread names
	private AtomicInteger threadCounter= new AtomicInteger(1);
	
	// Queue a new entry for dispatch
	private void queue(@NonNull AliasEntry entry) {
		dispatchLock.lock();
		try {
			// This is O(n log n)
			dispatchQueue.add(entry);

			// If this is at the head of the priority queue it might be sooner than what was
			// there before, so kick the dispatch thread
			if (dispatchQueue.peek() == entry) {
				dispatchWake.signal();
			}
		} finally {
			dispatchLock.unlock();
		}
	}
	
	private void dispatchLoop() {
		long lastEvent= 0;
		
		for (boolean run= true; run; ) {
			AliasEntry entry= null;
			long now= System.currentTimeMillis();
			long waitUntil= 0;
			
			dispatchLock.lock(); // Use the same lock as the queue() call
			try {
				if (!dispatchQueue.isEmpty()) {
					entry= dispatchQueue.peek();
					if (entry.next <= (now + SCHEDULE_SLACK_MSEC)) {
						dispatchQueue.poll();
						
						long lag= now - entry.next;
						if (lag > SCHEDULE_WARN_MSEC) {
							log.warn(
									"Scheduler dispatch thread missed head-of-line event by " +
									lag + " milliseconds.");
						}
					} else {
						waitUntil= entry.next;
						entry= null;
						
						//log.info("Waiting " + (waitUntil - now) +
						//		" milliseconds to send next event");
					}
				}
			} finally {
				dispatchLock.unlock();
			}

			if (entry != null) {
				// This would happen if for some reason we had the ordering backwards on the
				// priority queue.  I'm paranoid about that for some reason.  :-)
				if (entry.next < lastEvent) {
					log.warn("Schedule events are arriving out of order");
				}
				lastEvent= entry.next;
				
				executorService.execute(entry);
			}
			
			// We could unroll this the other way around and only lock once...
			dispatchLock.lock();
			try {
				if (dispatchRun && (entry == null)) {
					try {
						if (waitUntil > 0) {
							dispatchWake.awaitUntil(new Date(waitUntil));
						} else {
							dispatchWake.await();
						}
					} catch (InterruptedException _ie) {
					}
				}
				
				run= dispatchRun;
			} finally {
				dispatchLock.unlock();
			}
		}
	}

	public void start() {
		// I'm not sure a cached thread pool is the best just because it doesn't have a
		// max size - that's something to meditate on.
		executorService= Executors.newCachedThreadPool(new ThreadFactory() {
			@Override
			public Thread newThread(Runnable runnable) {
				return new Thread(runnable,
						"scheduler-worker-" + threadCounter.getAndIncrement());
			}
		});
		
		// Go ahead and preload anything that might be waiting.  In practice this isn't used
		// because the config thread takes a while to pull the config.
		synchronized (aliasMap) {
			long startTime= System.currentTimeMillis();
			
			for (Map.Entry<String, AliasEntry> entry : aliasMap.entrySet()) {
				AliasEntry alias= entry.getValue();
				alias.next= startTime;

				// Don't cause a thundering herd
				startTime+= 5000;
			}
		}
		
		dispatchThread= new Thread(new Runnable() {
			@Override
			public void run() {
				dispatchLoop();
			}
			
		}, "scheduler-dispatcher");
		
		dispatchRun= true;
		dispatchThread.start();
	}

	public void stop() {
		// Wait for the dispatch thread to clean up
		dispatchLock.lock();
		try {
			dispatchRun= false;
			dispatchWake.signal();
		} finally {
			dispatchLock.unlock();
		}
		
		try {
			dispatchThread.join();
		} catch (InterruptedException _ie) {
		}

		// Wait for all the child tasks to finish
		executorService.shutdown();
		try {
			executorService.awaitTermination(30_000, TimeUnit.MILLISECONDS);
		} catch (InterruptedException _e) {
		}
		executorService= null;

		// Clear the queue references.  This has to be after the dispatch thread and the
		// executor service are done, since the tasks could re-queue themselves.  Not that it
		// matters - I just like to "stick the landing".
		dispatchLock.lock();
		try {
			dispatchQueue.clear();
		} finally {
			dispatchLock.unlock();
		}
	}
}
