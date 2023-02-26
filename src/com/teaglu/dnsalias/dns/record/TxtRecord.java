package com.teaglu.dnsalias.dns.record;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.teaglu.dnsalias.dns.DnsRecord;
import com.teaglu.dnsalias.dns.DnsRecordType;
import com.teaglu.dnsalias.util.IterableOfOne;

public class TxtRecord implements DnsRecord {
	private @NonNull String name;
	private @NonNull String value;
	private Integer ttl;
	
	private TxtRecord(
			@NonNull String name,
			@NonNull String value,
			@Nullable Integer ttl)
	{
		this.name= name;
		this.value= value;
		this.ttl= ttl;
	}
	
	public static @NonNull DnsRecord Create(
			@NonNull String name,
			@NonNull String value,
			@Nullable Integer ttl)
	{
		return new TxtRecord(name, value, ttl);
	}
	
	@Override
	public @NonNull String getName() {
		// TODO Auto-generated method stub
		return name;
	}

	@Override
	public @NonNull DnsRecordType getType() {
		return DnsRecordType.TXT;
	}

	@Override
	public @NonNull Iterable<@NonNull String> getValues() {
		return new IterableOfOne<String>(value);
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
