package com.teaglu.dnsalias.dns.option;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.FormatException;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.configure.exception.ConfigException;
import com.teaglu.configure.secret.SecretProvider;
import com.teaglu.dnsalias.dns.DnsProvider;
import com.teaglu.dnsalias.dns.DnsProviderFactory;
import com.teaglu.dnsalias.dns.DnsZone;
import com.teaglu.dnsalias.dns.exception.DnsException;

public class OptionDnsProvider implements DnsProvider {
	private DnsProvider defaultProvider;
	private Map<@NonNull String, DnsProvider> providerMap= new TreeMap<>();
	
	private OptionDnsProvider(
			@NonNull Composite spec,
			@NonNull SecretProvider secretProvider) throws SchemaException, ConfigException
	{
		Iterable<@NonNull Composite> providerSpecs= spec.getRequiredObjectArray("options");
		
		for (Composite providerSpec : providerSpecs) {
			DnsProvider provider= DnsProviderFactory.Create(providerSpec, secretProvider);
			
			Iterable<@NonNull String> zones= providerSpec.getOptionalStringArray("zones");
			if (zones == null) {
				if (defaultProvider == null) {
					throw new FormatException("Multiple options are declared as default.");
				}
				defaultProvider= provider;
			} else {
				for (String zone : zones) {
					@SuppressWarnings("null")
					@NonNull String key= zone.toLowerCase();
					providerMap.put(key, provider);
				}
			}
		}
	}
	
	public static @NonNull DnsProvider Create(
			@NonNull Composite config,
			@NonNull SecretProvider secretProvider) throws SchemaException, ConfigException
	{
		return new OptionDnsProvider(config, secretProvider);
	}

	@Override
	public @Nullable DnsZone getZone(
			@NonNull String zoneName) throws DnsException, IOException
	{
		DnsProvider provider= providerMap.get(zoneName);
		if (provider == null) {
			provider= defaultProvider;
		}
		
		DnsZone zone= null;
		if (provider != null) {
			zone= provider.getZone(zoneName);
		}
		
		return zone;
	}
}
