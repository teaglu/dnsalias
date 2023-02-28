package com.teaglu.dnsalias.dns.cloudflare;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.dnsalias.dns.DnsRecord;
import com.teaglu.dnsalias.dns.DnsRecordType;
import com.teaglu.dnsalias.dns.DnsZone;
import com.teaglu.dnsalias.dns.exception.DnsApiException;
import com.teaglu.dnsalias.dns.exception.DnsException;
import com.teaglu.dnsalias.dns.record.ARecord;

public class CloudflareDnsZone implements DnsZone {
	private static final Logger log= LoggerFactory.getLogger(CloudflareDnsZone.class);
	
	private @NonNull CloudflareAccount account;
	private @NonNull String zoneName;
	private @NonNull String zoneId;

	CloudflareDnsZone(
			@NonNull CloudflareAccount account,
			@NonNull String apex) throws DnsException, IOException
	{
		this.zoneName= apex;
		this.account= account;
		
		try {
			Map<@NonNull String, @Nullable String> parameters= new TreeMap<>();
			parameters.put("name", apex);

			Iterable<@NonNull Composite> result= account.query("/zones", parameters);
			
			Iterator<@NonNull Composite> resultIter= result.iterator();
			if (!resultIter.hasNext()) {
				throw new DnsApiException("Unable to find zone ID for apex " + apex);
			}
			
			@SuppressWarnings("null")
			@NonNull Composite firstResult= resultIter.next();
			
			zoneId= firstResult.getRequiredString("id");
		} catch (SchemaException e) {
			throw new DnsApiException("Error searching for zone ID", e);
		}
	}

	@Override
	public long getNegativeTtl() throws DnsException {
		// Cloudflare is always one hours as of 221210 -DAW
		return 3600_000;
	}
	
	@Override
	public @NonNull Iterable<@NonNull DnsRecord> findRecords(
			@NonNull String name,
			@NonNull DnsRecordType type) throws DnsException, IOException
	{
		String searchName= name + "." + zoneName;
		if (name.isBlank()) {
			searchName= zoneName;
		}
		
		String searchType= type.toString();
		
		List<@NonNull DnsRecord> rval= new ArrayList<>(4);
		
		// Run a query to get all the matching record IDs
		Map<@NonNull String, @Nullable String> search= new TreeMap<>();
		search.put("name", searchName);
		search.put("type", searchType);
		Iterable<@NonNull Composite> results=
				account.query("/zones/" + zoneId + "/dns_records", search);
		
		// For A records
		List<@NonNull Inet4Address> v4Addresses= new ArrayList<>(4);
		
		// For AAAA records
		//List<Inet6Address> v6Addresses= new ArrayList<>(4);
		
		// For A / AAAA records
		Integer ttl= null;
		
		for (Composite record : results) {
			try {
				String recordName= record.getRequiredString("name");
				String recordType= record.getRequiredString("type");
				
				if (recordName.equalsIgnoreCase(searchName)) {
					if (recordType.equalsIgnoreCase(searchType)) {
						if (type == DnsRecordType.A) {
							// Bundle all A records into one at the end
							String recordContent= record.getRequiredString("content");
							try {
								InetAddress address= InetAddress.getByName(recordContent);
								
								if (address instanceof Inet4Address) {
									v4Addresses.add((Inet4Address)address);
								}
							} catch (NumberFormatException formatException) {
								log.warn("Ignoring unparseable address " + recordContent);
							}
							
							if (ttl == null) {
								ttl= record.getOptionalInteger("ttl");
							}
						} else {
							// FIXME
						}
					}
				}
			} catch (SchemaException schemaException) {
				throw new DnsApiException(
						"Error reading record ID from response", schemaException);
			}
		}

		if (type == DnsRecordType.A) {
			rval.add(ARecord.Create(searchName, v4Addresses, ttl));
		}
		
		return rval;
	}
	
	@Override
	public void createRecord(
			@NonNull DnsRecord record,
			boolean overwrite) throws DnsException, IOException
	{
		if (overwrite) {
			// Cloudflare doesn't really have the concept of a change set, but at some point
			// we might look at doing the delete after the create
			deleteRecord(record.getName(), record.getType());
		}

		String createName= record.getName();
		if (createName.isBlank()) {
			createName= "@";
		}

		String createType= record.getType().toString();
				
		Integer ttl= record.getPositiveTtl();
		if (ttl == null) {
			ttl= 1;
		} else {
			// The API will fail if you try to pass a TTL <60 or >86400
			if (ttl < 60) {
				log.warn("Requested record TTL for " + createName + " is " + ttl +
						" seconds, but clamping to Cloudflare minimum of 60.");

				ttl= 60;			
			} else if (ttl > 86400) {
				log.warn("Requested record TTL for " + createName + " is " + ttl +
						" seconds, but clamping to Cloudflare maximum of 86400.");
				
				ttl= 86400;
			}
		}
		
		for (String value : record.getValues()) {			
			JsonObject request= new JsonObject();
			request.addProperty("name", createName);
			request.addProperty("type", createType);
			request.addProperty("content", value);

			
			// 1 = automatic a/p docs
			request.addProperty("ttl", ttl);
			
			account.create("/zones/" + zoneId + "/dns_records", request);
		}
	}

	@Override
	public boolean deleteRecord(
			@NonNull String name,
			@NonNull DnsRecordType type) throws DnsException, IOException
	{
		// Cloudflare seems to take the relative name in the create call, but looks for the
		// full name without a trailing dot in the query call
		String searchName= name + "." + zoneName;
		if (name.isBlank()) {
			searchName= zoneName;
		}
		
		// Run a query to get all the matching record IDs
		Map<@NonNull String, @Nullable String> search= new TreeMap<>();
		search.put("name", searchName);
		search.put("type", type.toString());
		Iterable<@NonNull Composite> results=
				account.query("/zones/" + zoneId + "/dns_records", search);

		boolean anyDeleted= false;
		
		// Delete the records one by one
		for (Composite record : results) {
			try {
				String foundName= record.getRequiredString("name");
				if (foundName.equalsIgnoreCase(searchName)) {
					String recordId= record.getRequiredString("id");
					
					account.delete("/zones/" + zoneId + "/dns_records/" + recordId);
					anyDeleted= true;
				}
			} catch (SchemaException schemaException) {
				throw new DnsApiException(
						"Error reading record ID from response", schemaException);
			}
		}
		
		return anyDeleted;
	}
}
