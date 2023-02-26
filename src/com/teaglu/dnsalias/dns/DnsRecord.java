package com.teaglu.dnsalias.dns;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

public interface DnsRecord {
	public @NonNull String getName();
	public @NonNull DnsRecordType getType();
	
	public @NonNull Iterable<@NonNull String> getValues();
	
	public @Nullable Integer getPositiveTtl();
	public @Nullable Integer getPriority();
}
