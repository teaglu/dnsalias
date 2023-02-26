package com.teaglu.dnsalias.processor;

import org.eclipse.jdt.annotation.NonNull;

import com.teaglu.dnsalias.alert.AlertSink;
import com.teaglu.dnsalias.processor.exception.LookupException;
import com.teaglu.dnsalias.processor.exception.UpdateException;

/**
 * Processor
 * 
 * A processor is responsible for replicating an alias to a destination - both are usually
 * passed in as part of object creation.
 * 
 */
public interface Processor {
	/**
	 * process
	 * 
	 * Process the alias replication.  This method will only be called by one thread at a time
	 * and is not required to be thread-safe.  This object may contain state relevant to the
	 * replication, such as the last known value.
	 * 
	 * @param alertSink					A sink to send any relevant alerts
	 * @return							How many seconds the processor should be re-called after
	 * 
	 * @throws LookupException			A problem occurred getting the source data
	 * @throws UpdateException			A problem occurred updating the DNS provider
	 */
	public long process(
			@NonNull AlertSink alertSink) throws LookupException, UpdateException;
}
