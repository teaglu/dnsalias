package com.teaglu.dnsalias.dns.record;

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.teaglu.dnsalias.dns.DnsRecord;
import com.teaglu.dnsalias.dns.DnsRecordType;

public class ARecord implements DnsRecord {
	private @NonNull String name;
	private @NonNull List<@NonNull String> values= new ArrayList<>(4);
	private Integer ttl;
	
	private ARecord(
			@NonNull String name,
			@NonNull Iterable<@NonNull Inet4Address> addresses,
			@Nullable Integer ttl)
	{
		this.name= name;
		this.ttl= ttl;
		
		for (Inet4Address address : addresses) {
			String stringAddress= address.getHostAddress();
			if (stringAddress == null) {
				throw new RuntimeException("Unexpected null return from getHostAddress()");
			}
			
			values.add(stringAddress);
		}
	}
	
	public static @NonNull DnsRecord Create(
			@NonNull String name,
			@NonNull Iterable<@NonNull Inet4Address> values,
			@Nullable Integer ttl)
	{
		return new ARecord(name, values, ttl);
	}
	
	@Override
	public @NonNull String getName() {
		return name;
	}

	@Override
	public @NonNull DnsRecordType getType() {
		return DnsRecordType.A;
	}

	@Override
	public @NonNull Iterable<@NonNull String> getValues() {
		return values;
	}

	@Override
	public @Nullable Integer getPositiveTtl() {
		return ttl;
	}

	@Override
	public @Nullable Integer getPriority() {
		return null;
	}
}
