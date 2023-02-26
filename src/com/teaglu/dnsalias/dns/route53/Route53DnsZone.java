package com.teaglu.dnsalias.dns.route53;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.teaglu.dnsalias.dns.DnsRecord;
import com.teaglu.dnsalias.dns.DnsRecordType;
import com.teaglu.dnsalias.dns.DnsZone;
import com.teaglu.dnsalias.dns.exception.DnsApiException;
import com.teaglu.dnsalias.dns.exception.DnsException;
import com.teaglu.dnsalias.dns.record.ARecord;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.Change;
import software.amazon.awssdk.services.route53.model.ChangeAction;
import software.amazon.awssdk.services.route53.model.ChangeBatch;
import software.amazon.awssdk.services.route53.model.ChangeResourceRecordSetsRequest;
import software.amazon.awssdk.services.route53.model.ListResourceRecordSetsRequest;
import software.amazon.awssdk.services.route53.model.ListResourceRecordSetsResponse;
import software.amazon.awssdk.services.route53.model.RRType;
import software.amazon.awssdk.services.route53.model.ResourceRecord;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;

/**
 * Route53DnsZone
 * 
 * Implementation of DNS zone for AWS route53.
 *
 */
public class Route53DnsZone implements DnsZone {
	private static final Logger log= LoggerFactory.getLogger(Route53DnsZone.class);
	
	// Client passed in at creation
	private final @NonNull Route53Client client;
	
	// Hosted zone ID passed in at creation
	private final @NonNull String hostedZoneId;
	
	// Canonical root ending in dot, passed in at creation
	private final @NonNull String canonicalRoot;
	
	// Negative TTL value passed in at creation
	private final int negativeTtl;

	Route53DnsZone(
			@NonNull Route53Client client,
			@NonNull String hostedZoneId,
			@NonNull String canonicalRoot,
			int negativeTtl)
	{
		this.client= client;
		this.hostedZoneId= hostedZoneId;
		this.canonicalRoot= canonicalRoot;
		this.negativeTtl= negativeTtl;
	}

	@Override
	public long getNegativeTtl() {
		return negativeTtl;
	}


	@Override
	public @NonNull Iterable<@NonNull DnsRecord> findRecords(
			@NonNull String name,
			@NonNull DnsRecordType type) throws DnsException, IOException
	{
		List<@NonNull DnsRecord> rval= new ArrayList<>(4);
		
		String searchName= name + "." + canonicalRoot;
		RRType searchType= RRType.valueOf(type.toString());

		ListResourceRecordSetsRequest searchRequest= ListResourceRecordSetsRequest.builder()
				.hostedZoneId(hostedZoneId)
				.startRecordName(searchName)
				.startRecordType(searchType)
				.build();
		
		ListResourceRecordSetsResponse searchResponse=
				client.listResourceRecordSets(searchRequest);
		
		for (ResourceRecordSet set : searchResponse.resourceRecordSets()) {
			if (set.name().equals(searchName) && set.type() == searchType) {
				if (type == DnsRecordType.A) {
					List<Inet4Address> addresses= new ArrayList<>(4);
					
					for (ResourceRecord record : set.resourceRecords()) {
						String valueString= record.value();
						InetAddress address= InetAddress.getByName(valueString);
						if (!(address instanceof Inet4Address)) {
							throw new DnsException("Parsed A record is not an IPV4 address");
						}
						
						addresses.add((Inet4Address)address);
					}
					
					Integer ttl= null;
					if (set.ttl() != null) {
						ttl= (int)(long)set.ttl();
					}
					
					rval.add(ARecord.Create(name, addresses, ttl));
				} else {
					// FIXME
				}
			}
		}
		
		return rval;
	}
	
	@Override
	public void createRecord(
			@NonNull DnsRecord record,
			boolean overwrite) throws DnsException
	{
		String searchName= record.getName() + "." + canonicalRoot;
		RRType searchType= RRType.valueOf(record.getType().toString());
		
		Integer ttl= record.getPositiveTtl();
		if (ttl == null) {
			ttl= 600;
		}		
		
		try {
			List<Change> changes= new ArrayList<>(1);

			List<ResourceRecord> resourceRecords= new ArrayList<>(10);
			// Go ahead and build the new resource record we want, which will have multiple
			// values.
			for (String value : record.getValues()) {
				ResourceRecord.Builder resourceRecordBuilder= ResourceRecord.builder();

				switch (record.getType()) {
				case TXT:
					resourceRecordBuilder.value(formatTxtRecord(value));
					break;
					
				default:
					resourceRecordBuilder.value(value);
					break;				
				}
				
				ResourceRecord resourceRecord= resourceRecordBuilder.build();
				resourceRecords.add(resourceRecord);
			}


			// Search for matching records.  Since R53 uses resource record set, we can't just 
			// add another record we have to update the record set.  There is a concept of
			// multi-value records where multiples would exist, but that's used for weighted
			// balancing and we're not dealing with that now.
			ListResourceRecordSetsRequest searchRequest= ListResourceRecordSetsRequest.builder()
					.hostedZoneId(hostedZoneId)
					.startRecordName(searchName)
					.startRecordType(searchType)
					.build();
			
			ListResourceRecordSetsResponse searchResponse=
					client.listResourceRecordSets(searchRequest);

			boolean updateFound= false;
			
			for (ResourceRecordSet set : searchResponse.resourceRecordSets()) {
				// There isn't really a search in the API just a "starts with".  We get other
				// records afterwards that are alphabetically later.  We can set the max result
				// size to 1 but that created errors when I used it elsewhere.
				if (set.name().equals(searchName) && set.type() == searchType) {
					if (updateFound) {
						// This shouldn't happen
						log.warn(
								"Found more than one matching resource record set.  That's weird.");
					}
					
					if (!overwrite) {
						// Add in all the old records
						for (ResourceRecord oldRecord : set.resourceRecords()) {
							resourceRecords.add(oldRecord);
						}
					}
					
					// Use toBuilder to copy an other properties of our recordset, then replace
					// the records member and set multivalue to true so it handles multiple
					// records by returning all values.
					ResourceRecordSet.Builder resourceRecordSetBuilder= set.toBuilder();
					
					resourceRecordSetBuilder.resourceRecords(resourceRecords);
					//if (resourceRecords.size() > 1) {
					//	resourceRecordSetBuilder.multiValueAnswer(true);
					//	resourceRecordSetBuilder.setIdentifier("1");
					//}
					ResourceRecordSet resourceRecordSet= resourceRecordSetBuilder.build();

					// Create an update
					Change change= Change.builder()
							.action(ChangeAction.UPSERT)
							.resourceRecordSet(resourceRecordSet)
							.build();
					
					changes.add(change);
					updateFound= true;
				}
			}

			if (!updateFound) {
				ResourceRecordSet.Builder resourceRecordSetBuilder= ResourceRecordSet.builder();
				
				resourceRecordSetBuilder.name(record.getName() + "." + canonicalRoot);
				resourceRecordSetBuilder.type(record.getType().toString());
				resourceRecordSetBuilder.ttl((long)ttl);
				resourceRecordSetBuilder.resourceRecords(resourceRecords);
				//if (resourceRecords.size() > 1) {
				//	resourceRecordSetBuilder.multiValueAnswer(true);
				//	resourceRecordSetBuilder.setIdentifier("2");
				//}
				ResourceRecordSet resourceRecordSet= resourceRecordSetBuilder.build();
			
				Change change= Change.builder()
						.action(ChangeAction.CREATE)
						.resourceRecordSet(resourceRecordSet)
						.build();
				
				changes.add(change);
			}
			
			ChangeBatch changeBatch= ChangeBatch.builder()
					.changes(changes)
					.build();
			
			ChangeResourceRecordSetsRequest request= ChangeResourceRecordSetsRequest.builder()
					.hostedZoneId(hostedZoneId)
					.changeBatch(changeBatch)
					.build();
			
			client.changeResourceRecordSets(request);
		} catch (SdkException sdkException) {
			throw new DnsApiException(
					"Failed to create record via SDK", sdkException);
		}
	}
	
	private String formatTxtRecord(@NonNull String value) {
			int valueLen= value.length();
			StringBuilder output= new StringBuilder();
			boolean first= true;
			
			for (int pos= 0; pos < valueLen; pos+= 255) {
				int len= valueLen - pos;
				if (len > 255) {
					len= 255;
				}

				if (first) {
					first= false;
				} else {
					output.append(' ');
				}
				output.append('\"');
				output.append(value.substring(pos, pos + len));
				output.append('\"');
			}
			
			return output.toString();
	}

	@Override
	public boolean deleteRecord(
			@NonNull String name,
			@NonNull DnsRecordType type) throws DnsException, IOException
	{
		String searchName= name + "." + canonicalRoot;
		RRType searchType= RRType.valueOf(type.toString());
		
		boolean deleted= false;
		
		try {
			List<Change> changes= new ArrayList<>(4);

			// For a delete request you have to EXACTLY match what R53 has on file, so do
			// a scan for anything that matches and use those records to feed back into the
			// delete call
			
			ListResourceRecordSetsRequest searchRequest= ListResourceRecordSetsRequest.builder()
					.hostedZoneId(hostedZoneId)
					.startRecordName(searchName)
					.startRecordType(searchType)
					.build();
			
			ListResourceRecordSetsResponse searchResponse=
					client.listResourceRecordSets(searchRequest);
			
			for (ResourceRecordSet set : searchResponse.resourceRecordSets()) {
				// Delete anything with a matching name and type
				if (set.name().equals(searchName) && set.type() == searchType) {
					Change change= Change.builder()
							.action(ChangeAction.DELETE)
							.resourceRecordSet(set)
							.build();
					
					changes.add(change);
				}
			}

			if (!changes.isEmpty()) {
				ChangeBatch changeBatch= ChangeBatch.builder()
						.changes(changes)
						.build();
				
				ChangeResourceRecordSetsRequest request= ChangeResourceRecordSetsRequest.builder()
						.hostedZoneId(hostedZoneId)
						.changeBatch(changeBatch)
						.build();
				
				client.changeResourceRecordSets(request);
				deleted= true;
			}
			
			return deleted;
		} catch (SdkException sdkException) {
			throw new DnsApiException(
					"Failed to delete record via SDK", sdkException);
		}
	}
}
