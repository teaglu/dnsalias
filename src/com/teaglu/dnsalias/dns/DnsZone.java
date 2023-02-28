package com.teaglu.dnsalias.dns;

import java.io.IOException;
import org.eclipse.jdt.annotation.NonNull;

import com.teaglu.dnsalias.dns.exception.DnsException;

/**
 * DnsZone
 * 
 * Representation of a DNS zone with operations
 *
 */
public interface DnsZone {
	/**
	 * findRecords
	 * 
	 * Find all matching records by type and name
	 * 
	 * @param name						Name relative to zone, or "" or "@" to mean the apex
	 * @param type						DNS record type
	 * 
	 * @return							List of records
	 * 
	 * @throws DnsException
	 * @throws IOException
	 */
	public @NonNull Iterable<@NonNull DnsRecord> findRecords(
			@NonNull String name,
			@NonNull DnsRecordType type) throws DnsException, IOException;
	
	/**
	 * createRecord
	 * 
	 * Create a record in the DNS zone.  If the overwrite parameter is true then this
	 * will replace all values with the same name and type, otherwise the values will
	 * be added as additional entries.
	 * 
	 * @param record					Record to create
	 * @param overwrite					Replace all records with this one
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
