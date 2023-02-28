package com.teaglu.dnsalias.dns;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Immutable interface for a DNS record.
 *
 */
public interface DnsRecord {
	/**
	 * getName
	 * 
	 * Get the name relative to the zone. I.e. for www.contoso.com return "www".
	 * 
	 * @return							Name
	 */
	public @NonNull String getName();
	
	/**
	 * getType
	 * 
	 * Get the type of the record.
	 * 
	 * @return							Type
	 */
	public @NonNull DnsRecordType getType();

	/**
	 * getValues
	 * 
	 * Get the values of the record.  If there are multiple records of the same value, this
	 * implementation expects them to be provided as a single record with multiple values.  This
	 * roughly aligns with AWS resource record sets in simple routing mode.
	 * 
	 * @return							Iterable of values
	 */
	public @NonNull Iterable<@NonNull String> getValues();

	/**
	 * getPositiveTtl
	 * 
	 * Return the TTL, or how many seconds the record should be cached on a positive cache hit.
	 * If a null is returned then the zone default is used.
	 * 
	 * @return							TTL in seconds
	 */
	public @Nullable Integer getPositiveTtl();
	
	/**
	 * getPriority
	 * 
	 * Return the record priority, which is only used for MX records
	 * 
	 * @return							Priority
	 */
	public @Nullable Integer getPriority();
}
