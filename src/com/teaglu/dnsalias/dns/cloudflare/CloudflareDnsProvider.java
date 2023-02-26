package com.teaglu.dnsalias.dns.cloudflare;

import java.io.IOException;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.configure.exception.ConfigException;
import com.teaglu.configure.secret.SecretProvider;
import com.teaglu.dnsalias.dns.DnsProvider;
import com.teaglu.dnsalias.dns.DnsZone;
import com.teaglu.dnsalias.dns.exception.DnsException;

public class CloudflareDnsProvider implements DnsProvider {
	private @NonNull CloudflareAccount account;
	
	private CloudflareDnsProvider(
			@NonNull Composite config,
			@NonNull SecretProvider secretProvider) throws SchemaException, ConfigException
	{
		account= new CloudflareAccountImpl(config, secretProvider);
	}
	
	public static @NonNull DnsProvider Create(
			@NonNull Composite config,
			@NonNull SecretProvider secretProvider) throws SchemaException, ConfigException
	{
		return new CloudflareDnsProvider(config, secretProvider);
	}

	@Override
	public @Nullable DnsZone getZone(
			@NonNull String apex) throws DnsException, IOException
	{
		return new CloudflareDnsZone(account, apex);
	}
}
