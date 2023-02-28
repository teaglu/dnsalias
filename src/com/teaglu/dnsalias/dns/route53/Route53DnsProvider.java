package com.teaglu.dnsalias.dns.route53;

import java.io.IOException;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.configure.exception.ConfigException;
import com.teaglu.configure.secret.SecretProvider;
import com.teaglu.dnsalias.dns.DnsProvider;
import com.teaglu.dnsalias.dns.DnsZone;
import com.teaglu.dnsalias.dns.exception.DnsApiException;
import com.teaglu.dnsalias.dns.exception.DnsException;
import com.teaglu.dnsalias.dns.exception.DnsZoneNotFoundException;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.HostedZone;
import software.amazon.awssdk.services.route53.model.ListHostedZonesByNameRequest;
import software.amazon.awssdk.services.route53.model.ListHostedZonesByNameResponse;
import software.amazon.awssdk.services.route53.model.ListResourceRecordSetsRequest;
import software.amazon.awssdk.services.route53.model.ListResourceRecordSetsResponse;
import software.amazon.awssdk.services.route53.model.RRType;
import software.amazon.awssdk.services.route53.model.ResourceRecord;
import software.amazon.awssdk.services.route53.model.ResourceRecordSet;

/**
 * Route53DnsProvider
 * 
 * Implementation of DnsProvider for AWS Route53
 *
 */
public class Route53DnsProvider implements DnsProvider {
	private @NonNull AwsConnection connection;
	private @NonNull Route53Client client;
	
	private Route53DnsProvider(
			@NonNull Composite config,
			@NonNull SecretProvider secretProvider) throws SchemaException, ConfigException
	{
		connection= new AwsConnectionImpl("route53", config);
		client= connection.buildRoute53Client();
	}
	
	public static @NonNull DnsProvider Create(
			@NonNull Composite config,
			@NonNull SecretProvider secretProvider) throws SchemaException, ConfigException
	{
		return new Route53DnsProvider(config, secretProvider);
	}
	
	@Override
	public @Nullable DnsZone getZone(
			@NonNull String name) throws DnsException, IOException
	{
		try {
			// Convert to canonical form with dot on end
			String canonicalRoot= name;
			if (!canonicalRoot.endsWith(".")) {
				canonicalRoot= canonicalRoot + ".";
			}
			
			String hostedZoneId= getHostedZoneId(canonicalRoot);
			int negativeTtl= getNegativeTtl(hostedZoneId, name);
			
			return new Route53DnsZone(client,
					hostedZoneId, canonicalRoot, negativeTtl);
		} catch (SdkException sdkException) {
			throw new DnsApiException("Failed to call AWS endpoint", sdkException);
		}
	}
	
	private @NonNull String getHostedZoneId(
			@NonNull String canonicalRoot) throws DnsException
	{
		String hostedZoneId= null;
		
		try {
			ListHostedZonesByNameRequest request= ListHostedZonesByNameRequest
					.builder()
					.dnsName(canonicalRoot)
					.build();
			
			ListHostedZonesByNameResponse response= client.listHostedZonesByName(request);
			
			for (HostedZone zone : response.hostedZones()) {
				if (zone.name().equals(canonicalRoot)) {
					if (hostedZoneId == null) {
						hostedZoneId= zone.id();
						if (hostedZoneId.startsWith("/hostedzone/")) {
							hostedZoneId= hostedZoneId.substring(12);
						}
					} else {
						throw new DnsApiException(
								"More than one hosted zone matches the requested apex");
					}
				}
			}
			
			if (hostedZoneId == null) {
				throw new DnsZoneNotFoundException("Unable to locate DNS zone " + canonicalRoot);
			}
			
			return hostedZoneId;
		} catch (SdkException sdkException) {
			throw new DnsApiException(
					"Unable to query AWS for hosted zone ID", sdkException);
		}
	}
	
	private int getNegativeTtl(
			@NonNull String hostedZoneId,
			@NonNull String name) throws DnsException
	{
		int ttl= 0;
		
		try {
			ListResourceRecordSetsRequest soaRequest= ListResourceRecordSetsRequest.builder()
					.hostedZoneId(hostedZoneId)
					.startRecordName(".")
					.startRecordType(RRType.SOA)
					.build();
			
			ListResourceRecordSetsResponse soaResponse= client.listResourceRecordSets(soaRequest);
			for (ResourceRecordSet set : soaResponse.resourceRecordSets()) {
				if (set.type() == RRType.SOA) {
					for (ResourceRecord record : set.resourceRecords()) {
						String value= record.value();
						String parts[]= value.split("\\s+");
						
						if (parts.length != 7) {
							throw new DnsApiException(
									"SOA record for zone " + name + " does not have 7 parts.");
						}
						
						try {
							ttl= Integer.parseInt(parts[6]);
						} catch (NumberFormatException nfe) {
							throw new DnsApiException(
									"SOA record TTL for zone " + name + " is not a valid number.");
						}
					}
				}
			}
		} catch (SdkException sdkException) {
			throw new DnsApiException(
					"Unable to scan SOA record for negative TTL", sdkException);
		}
		
		return ttl;
	}
}
