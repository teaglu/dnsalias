package com.teaglu.dnsalias.dns;

import java.io.IOException;
import org.eclipse.jdt.annotation.NonNull;

import com.teaglu.dnsalias.dns.exception.DnsException;

public interface DnsZone {
	public @NonNull Iterable<@NonNull DnsRecord> findRecords(
			@NonNull String name,
			@NonNull DnsRecordType type) throws DnsException, IOException;
	
	/**
	 * createRecord
	 * 
	 * Create a record in the DNS zone.  If the record has multiple values, all values should
	 * be returned for a query to the name and type.  If other records exist with the same
	 * name and type they should be left in place.
	 * 
	 * @param record					Record to create
	 * 
	 * @throws DnsException
	 * @throws IOException
	 */
	public void createRecord(
			@NonNull DnsRecord record,
			boolean overwrite) throws DnsException, IOException;

	/**
	 * deleteRecord
	 * 
	 * Delete all records matching this name and type.
	 * 
	 * @param name						Name of records to delete
	 * @param type						Type of records to delete
	 * 
	 * @throws DnsException
	 * @throws IOException
	 */
	public boolean deleteRecord(
			@NonNull String name,
			@NonNull DnsRecordType type) throws DnsException, IOException;
	
	/**
	 * getNegativeTtl
	 * 
	 * Return the negative TTL for the zone, which is the length of time a record not found
	 * will be cached as a negative result.
	 * 
	 * @return							TTL value in seconds.
	 * 
	 * @throws DnsException
	 * @throws IOException
	 */
	public long getNegativeTtl() throws DnsException, IOException;
}
