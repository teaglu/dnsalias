package com.teaglu.dnsalias.dns.cloudflare;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import com.google.gson.JsonObject;
import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.dnsalias.dns.DnsRecord;
import com.teaglu.dnsalias.dns.DnsRecordType;
import com.teaglu.dnsalias.dns.DnsZone;
import com.teaglu.dnsalias.dns.exception.DnsApiException;
import com.teaglu.dnsalias.dns.exception.DnsException;

public class CloudflareDnsZone implements DnsZone {
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
		List<@NonNull DnsRecord> rval= new ArrayList<>(4);
		
		// FIXME implement
		
		return rval;
	}
	
	@Override
	public void createRecord(
			@NonNull DnsRecord record,
			boolean overwrite) throws DnsException, IOException
	{
		// FIXME deal with overwrite
		
		for (String value : record.getValues()) {
			JsonObject request= new JsonObject();
			request.addProperty("name", record.getName());
			request.addProperty("type", record.getType().toString());
			request.addProperty("content", value);
			
			Integer ttl= record.getPositiveTtl();
			
			// 1 = automatic a/p docs
			request.addProperty("ttl", ttl != null ? ttl : 1);
			
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
