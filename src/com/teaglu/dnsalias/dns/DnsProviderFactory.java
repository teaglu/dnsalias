package com.teaglu.dnsalias.dns;

import org.eclipse.jdt.annotation.NonNull;

import com.teaglu.composite.Composite;
import com.teaglu.composite.exception.SchemaException;
import com.teaglu.composite.exception.UndefinedOptionException;
import com.teaglu.configure.exception.ConfigException;
import com.teaglu.configure.secret.SecretProvider;
import com.teaglu.dnsalias.dns.cloudflare.CloudflareDnsProvider;
import com.teaglu.dnsalias.dns.option.OptionDnsProvider;
import com.teaglu.dnsalias.dns.route53.Route53DnsProvider;

public class DnsProviderFactory {
	public static @NonNull DnsProvider Create(
			@NonNull Composite config,
			@NonNull SecretProvider secretProvider) throws SchemaException, ConfigException
	{
		String type= config.getRequiredString("type");
		
		switch (type.toLowerCase()) {
		case "cloudflare":
			return CloudflareDnsProvider.Create(config, secretProvider);
			
		case "awsroute53":
		case "route53":
			return Route53DnsProvider.Create(config, secretProvider);
			
		case "option":
			return OptionDnsProvider.Create(config, secretProvider);
			
		default:
			throw new UndefinedOptionException(
					"DNS provider " + type + " is not implemented.");
		}
	}
}
