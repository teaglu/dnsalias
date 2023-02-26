package com.teaglu.dnsalias.dns;

import java.io.IOException;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.teaglu.dnsalias.dns.exception.DnsException;

public interface DnsProvider {
	public @Nullable DnsZone getZone(
			@NonNull String apex) throws DnsException, IOException;
}
